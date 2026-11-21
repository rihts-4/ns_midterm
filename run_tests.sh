#!/usr/bin/env bash
# Functional + concurrency tests for the HTTP server and client.
# Usage: ./run_tests.sh [port]      (default 8080)
set -u
PORT="${1:-8080}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$(mktemp -d)"
DATA="$(mktemp -d)"
PASS=0; FAIL=0

javac -d "$BUILD" "$HERE"/server/*.java "$HERE"/client/*.java || exit 1
java -cp "$BUILD" HttpServer "$PORT" "$DATA" > "$BUILD/server.log" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null; rm -rf "$BUILD" "$DATA"' EXIT
for i in $(seq 1 50); do
  (exec 3<>/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null && break
  sleep 0.1
done

client() { java -cp "$BUILD" HttpClient localhost "$PORT" "$@" 2> >(grep -v JAVA_TOOL_OPTIONS >&2); }

check() { # check <name> <output> <expected substring>
  if grep -qF -- "$3" <<<"$2"; then echo "PASS  $1"; PASS=$((PASS+1))
  else echo "FAIL  $1  (expected: $3)"; echo "$2" | sed 's/^/      /'; FAIL=$((FAIL+1)); fi
}

echo "=== Functional tests (using our Java client) ==="
J21='{"name":"Alice","age":21,"major":"CS"}'
J22='{"name":"Alice","age":22,"major":"CS"}'

out=$(client POST /users/alice.json "$J21");  check "1. POST creates file"          "$out" "Status: 201 Created"
[ "$(cat "$DATA/users/alice.json")" = "$J21" ] && { echo "PASS  1b. file on disk matches body"; PASS=$((PASS+1)); } \
  || { echo "FAIL  1b. file on disk matches body"; FAIL=$((FAIL+1)); }
out=$(client GET /users/alice.json);          check "2. GET returns contents"       "$out" "$J21"
out=$(client PUT /users/alice.json "$J22");   check "3a. PUT replaces content"      "$out" "Status: 204 No Content"
out=$(client GET /users/alice.json);          check "3b. GET returns new content"   "$out" "$J22"
out=$(client POST /users/alice.json "$J21");  check "5. POST existing -> 409"       "$out" "Status: 409 Conflict"
out=$(client DELETE /users/alice.json);       check "4a. DELETE removes file"       "$out" "Status: 204 No Content"
out=$(client GET /users/alice.json);          check "4b. GET after DELETE -> 404"   "$out" "Status: 404 Not Found"
out=$(client PUT /users/missing.json "$J22"); check "6. PUT missing -> 404"         "$out" "Status: 404 Not Found"
out=$(client DELETE /users/missing.json);     check "6b. DELETE missing -> 404"     "$out" "Status: 404 Not Found"

echo; echo "=== Error handling (raw requests via bash /dev/tcp) ==="
raw() { exec 3<>/dev/tcp/127.0.0.1/"$PORT"; printf '%b' "$1" >&3; cat <&3 | head -1; exec 3<&-; }
check "7a. unsupported method (PATCH) -> 405" "$(raw 'PATCH /x.json HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n')" "405"
check "7b. malformed request line -> 400"     "$(raw 'GARBAGE\r\n\r\n')" "400"
check "7c. POST without Content-Length -> 411" "$(raw 'POST /n.json HTTP/1.1\r\nHost: x\r\n\r\n')" "411"
check "7d. bad HTTP version -> 505"           "$(raw 'GET /x.json HTTP/9.9\r\nHost: x\r\n\r\n')" "505"
check "8a. path traversal (../) -> 403"       "$(raw 'GET /../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n')" "403"
check "8b. encoded traversal (%2e%2e) -> 403" "$(raw 'GET /%2e%2e/%2e%2e/etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n')" "403"
check "8c. traversal via POST -> 403"         "$(raw 'POST /a/../../evil.json HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}')" "403"
[ ! -e "$DATA/../evil.json" ] && { echo "PASS  8d. nothing written outside data dir"; PASS=$((PASS+1)); } \
  || { echo "FAIL  8d. file escaped data dir"; FAIL=$((FAIL+1)); }

echo; echo "=== curl cross-check (server tested independently of our client) ==="
code() { curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "$@"; }
U="http://localhost:$PORT/users/curl.json"
[ "$(code -X POST -H 'Content-Type: application/json' --data-binary '{"name":"Alice","age":21}' "$U")" = 201 ] && { echo "PASS  curl POST 201"; PASS=$((PASS+1)); } || { echo "FAIL  curl POST"; FAIL=$((FAIL+1)); }
[ "$(curl -s --noproxy '*' "$U")" = '{"name":"Alice","age":21}' ] && { echo "PASS  curl GET body"; PASS=$((PASS+1)); } || { echo "FAIL  curl GET"; FAIL=$((FAIL+1)); }
[ "$(code -X PUT --data-binary '{"name":"Alice","age":22}' "$U")" = 204 ] && { echo "PASS  curl PUT 204"; PASS=$((PASS+1)); } || { echo "FAIL  curl PUT"; FAIL=$((FAIL+1)); }
[ "$(code -X DELETE "$U")" = 204 ] && { echo "PASS  curl DELETE 204"; PASS=$((PASS+1)); } || { echo "FAIL  curl DELETE"; FAIL=$((FAIL+1)); }
[ "$(code "$U")" = 404 ] && { echo "PASS  curl GET after DELETE 404"; PASS=$((PASS+1)); } || { echo "FAIL  curl GET after DELETE"; FAIL=$((FAIL+1)); }

echo; echo "=== Concurrency test: 10 clients POST simultaneously ==="
N=10
PIDS=()
for i in $(seq 1 $N); do
  ( client POST "/concurrent/user$i.json" "{\"id\":$i}" | grep "^Status" > "$BUILD/c$i.out" ) &
  PIDS+=($!)
done
wait "${PIDS[@]}"
ok=0
for i in $(seq 1 $N); do
  grep -q "201 Created" "$BUILD/c$i.out" && [ "$(cat "$DATA/concurrent/user$i.json" 2>/dev/null)" = "{\"id\":$i}" ] && ok=$((ok+1))
done
[ $ok -eq $N ] && { echo "PASS  all $N concurrent POSTs got 201 and created correct files"; PASS=$((PASS+1)); } \
  || { echo "FAIL  only $ok/$N concurrent POSTs succeeded"; FAIL=$((FAIL+1)); }
check "server still available afterwards" "$(client GET /concurrent/user1.json)" '{"id":1}'
kill -0 $SERVER_PID 2>/dev/null && { echo "PASS  server process still running"; PASS=$((PASS+1)); } || { echo "FAIL  server died"; FAIL=$((FAIL+1)); }

echo; echo "=== Client error handling ==="
out=$(java -cp "$BUILD" HttpClient localhost 59999 GET /x.json 2>&1); check "connection refused handled" "$out" "Connection refused"
out=$(java -cp "$BUILD" HttpClient nosuchhost.invalid "$PORT" GET /x.json 2>&1); check "unknown host handled" "$out" "Unknown host"
out=$(java -cp "$BUILD" HttpClient localhost "$PORT" POST /x.json 2>&1); check "POST without body rejected" "$out" "requires a body"

echo; echo "RESULT: $PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
