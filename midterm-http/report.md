# Report — HTTP Client and Server over TCP Sockets

## 1. Architecture

**Server** (`server/`)

- `HttpServer` — entry point. Creates the data directory, opens a `ServerSocket` on the configured port and loops on `accept()`. For every accepted connection it starts a new `Thread` running an `HttpRequestHandler`. The accept loop does no request work, so it keeps accepting while handlers run. A failed `accept()` is logged and does not stop the server.
- `HttpRequestHandler` (`Runnable`) — serves exactly one request on one socket: parse, validate, perform the file operation, write the response, close the socket (try-with-resources).

**Client** (`client/HttpClient.java`)

A single class: validate command-line arguments, open a `Socket` (5 s connect timeout, 15 s read timeout), write a hand-built request, read and parse the response, print it, close the socket. Connection errors (unknown host, refused, timeout, I/O error, truncated or malformed response) are caught and reported with a clear message and exit code 1.

Resources are plain files under the data directory (`server_data/` by default): `POST` creates, `GET` reads, `PUT` replaces, `DELETE` removes.

## 2. Concurrent connection handling

Thread-per-client, as required: one new thread per accepted connection, no pool and no shared mutable state between handlers except the file system.

- Each handler uses only local variables, so handlers cannot interfere with each other in memory.
- File-system races are handled explicitly. `POST` uses `StandardOpenOption.CREATE_NEW`, which makes "create if absent" atomic: if two clients POST the same path simultaneously exactly one gets `201` and the other gets `409`. `PUT` writes to a temporary file and does an atomic move over the target, so a concurrent `GET` never sees a half-written file.
- A 15 s socket read timeout on the server stops a client that connects and never sends anything from holding a thread forever.

## 3. How request headers and bodies are parsed

Parsing is done byte by byte on the socket `InputStream` (no `BufferedReader`), because mixing character readers with a binary body would mis-count `Content-Length`.

1. **Request line** — read up to `\n`, strip the trailing `\r`, split on spaces; it must have exactly three parts (method, target, version). Version must be `HTTP/1.1` or `1.0`, otherwise 505. Wrong shape gives 400.
2. **Headers** — read lines until an empty line. Each line is split at the first `:`; names are lower-cased for case-insensitive lookup, values trimmed. Limits: 8 KB per line, 100 headers (400 on violation).
3. **Body** — if `Content-Length` is present it is parsed (invalid or negative gives 400, over 10 MB gives 413) and exactly that many bytes are read in a loop (`read` may return fewer bytes than requested). If the stream ends early the request is rejected with 400. The body is kept as `byte[]` and written to disk unchanged, so the stored file is byte-identical to the request body. `Transfer-Encoding` is answered with 501 (chunked is out of scope). `POST`/`PUT` without `Content-Length` get 411.
4. **Path safety** — the target is stripped of query/fragment, percent-decoded, and rejected if it contains NUL or backslash, or any `..` segment (403). It is then resolved against the data root, normalized, and checked with `startsWith(root)`. Finally the deepest existing ancestor is resolved with `toRealPath()` to make sure a symlink cannot lead outside the root. This catches `/../x`, `/%2e%2e/x`, and `/a/../../x`.

Responses are built by hand: status line, `Content-Type` (for 200 and error bodies), `Content-Length` (computed from the byte array), `Connection: close`, blank line, body. All lines end in CRLF. `201`/`204` responses carry `Content-Length: 0`.

The **client** builds requests the same way: request line, `Host: host:port`, `Content-Type: application/json` and `Content-Length` (UTF-8 byte count) when there is a body, `Connection: close`, blank line, body. For the response it reads the status line, headers, then exactly `Content-Length` body bytes (or until EOF if the header is absent); empty bodies print `(empty)`.

## 4. Test results

All tests are in `run_tests.sh`. Output of the final run (JDK 21, Linux):

```
=== Functional tests (using our Java client) ===
PASS  1. POST creates file
PASS  1b. file on disk matches body
PASS  2. GET returns contents
PASS  3a. PUT replaces content
PASS  3b. GET returns new content
PASS  5. POST existing -> 409
PASS  4a. DELETE removes file
PASS  4b. GET after DELETE -> 404
PASS  6. PUT missing -> 404
PASS  6b. DELETE missing -> 404

=== Error handling (raw requests via bash /dev/tcp) ===
PASS  7a. unsupported method (PATCH) -> 405
PASS  7b. malformed request line -> 400
PASS  7c. POST without Content-Length -> 411
PASS  7d. bad HTTP version -> 505
PASS  8a. path traversal (../) -> 403
PASS  8b. encoded traversal (%2e%2e) -> 403
PASS  8c. traversal via POST -> 403
PASS  8d. nothing written outside data dir

=== curl cross-check (server tested independently of our client) ===
PASS  curl POST 201
PASS  curl GET body
PASS  curl PUT 204
PASS  curl DELETE 204
PASS  curl GET after DELETE 404

=== Concurrency test: 10 clients POST simultaneously ===
PASS  all 10 concurrent POSTs got 201 and created correct files
PASS  server still available afterwards
PASS  server process still running

=== Client error handling ===
PASS  connection refused handled
PASS  unknown host handled
PASS  POST without body rejected

RESULT: 29 passed, 0 failed
```

### Concurrency test explanation

Ten client processes were started at the same time, each sending `POST /concurrent/userN.json` with a different body. The test then verified that (a) every client received `201 Created`, (b) every file exists on disk with exactly the body that client sent, (c) a follow-up `GET` still worked, and (d) the server process was still running. All checks passed, so the server kept accepting connections while handlers were running and remained available afterwards.

### Sample client output

```
$ java HttpClient localhost 8080 POST /users/alice.json '{"name":"Alice","age":21,"major":"CS"}'
Status: 201 Created
Headers:
  Content-Length: 0
  Connection: close
Body: (empty)

$ java HttpClient localhost 8080 GET /users/alice.json
Status: 200 OK
Headers:
  Content-Type: application/json
  Content-Length: 38
  Connection: close
Body (38 bytes):
{"name":"Alice","age":21,"major":"CS"}
```

## 5. Known limitations

- One request per connection (`Connection: close`) by design; no keep-alive or pipelining.
- No chunked transfer encoding, HTTPS, authentication or `Expect: 100-continue`.
- `GET` always reports `Content-Type: application/json` regardless of file content.
- Request bodies are limited to 10 MB and held in memory.
- Thread-per-client has no upper bound on threads, so a flood of connections could exhaust resources (a thread pool was explicitly out of scope).
- Only regular files are served; `GET`/`DELETE` on a directory returns 404.
