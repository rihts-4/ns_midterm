# midterm-http — HTTP/1.1 Server and Client over Raw TCP Sockets

A file-backed HTTP server and a command-line HTTP client written in Java using only
`Socket` / `ServerSocket`. HTTP messages are built and parsed by hand. No
`HttpURLConnection`, `java.net.http`, `com.sun.net.httpserver`, servlets or third-party libraries are used.

Requires: JDK 11+ (developed and tested on JDK 21). `curl` is only needed for the optional curl tests.

## Layout

```
midterm-http/
+-- README.md
+-- report.md
+-- run_tests.sh              automated functional + concurrency tests
+-- client/HttpClient.java
+-- server/HttpServer.java
+-- server/HttpRequestHandler.java
```

## Compile

```bash
mkdir -p build
javac -d build server/*.java client/*.java
```

## Run the server

```bash
java -cp build HttpServer [port] [dataDir]
# defaults: port 8080, dataDir ./server_data (created if missing)
java -cp build HttpServer                 # port 8080, ./server_data
java -cp build HttpServer 9000 /tmp/data  # custom port and directory
```

A request to `/users/alice.json` maps to `<dataDir>/users/alice.json`. Parent directories are created automatically on POST.

## Run the client

```bash
java -cp build HttpClient <host> <port> <GET|POST|PUT|DELETE> <path> [body]
```

Examples:

```bash
java -cp build HttpClient localhost 8080 POST /users/alice.json '{"name":"Alice","age":21,"major":"CS"}'
java -cp build HttpClient localhost 8080 GET /users/alice.json
java -cp build HttpClient localhost 8080 PUT /users/alice.json '{"name":"Alice","age":22,"major":"CS"}'
java -cp build HttpClient localhost 8080 DELETE /users/alice.json
```

`POST` and `PUT` require the body as the last argument (sent with `Content-Type: application/json` and a byte-accurate `Content-Length`).
The client prints the status code and reason phrase, the response headers, then the body (or `(empty)`).
Exit codes: `0` response received, `1` network/protocol error, `2` bad usage.

## Behaviour summary

| Request | Result |
|---|---|
| `GET` existing file | `200 OK`, file contents, `Content-Type: application/json` |
| `GET` missing file | `404 Not Found` |
| `POST` new path | `201 Created` (body written exactly as received) |
| `POST` existing path | `409 Conflict` |
| `PUT` existing file | `204 No Content` (content replaced) |
| `PUT` missing file | `404 Not Found` |
| `DELETE` existing file | `204 No Content` |
| `DELETE` missing file | `404 Not Found` |
| other method | `405 Method Not Allowed` (with `Allow` header) |
| malformed request / header / Content-Length | `400 Bad Request` |
| `POST`/`PUT` without `Content-Length` | `411 Length Required` |
| path escaping the data directory (`..`, `%2e%2e`, symlink) | `403 Forbidden` |
| body > 10 MB | `413 Payload Too Large` |
| `Transfer-Encoding` present | `501 Not Implemented` |
| HTTP version other than 1.0/1.1 | `505 HTTP Version Not Supported` |

Every response carries `Content-Length` and `Connection: close`; one request is served per connection.

## Testing

Automated (compiles, starts a server on a temporary data directory, runs everything, cleans up):

```bash
./run_tests.sh          # optional argument: port (default 8080)
```

It covers all eight required functional cases, extra error cases, a curl cross-check, a 10-client concurrency test and client error handling.

Manual curl examples:

```bash
curl -v http://localhost:8080/users/alice.json
curl -v -X POST -H 'Content-Type: application/json' --data-binary '{"name":"Alice","age":21}' http://localhost:8080/users/alice.json
curl -v -X PUT  -H 'Content-Type: application/json' --data-binary '{"name":"Alice","age":22}' http://localhost:8080/users/alice.json
curl -v -X DELETE http://localhost:8080/users/alice.json
```

Manual concurrency test (5+ clients at once):

```bash
for i in 1 2 3 4 5 6; do
  java -cp build HttpClient localhost 8080 POST /concurrent/user$i.json "{\"id\":$i}" &
done; wait
ls server_data/concurrent
```
