import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// [A4 + A5] Resource operations and server responses
//This class does the file actions (GET, POST, PUT, DELETE) and builds the server's response.

public class ResourceHandler {

    // Builds the reply with no Content-Type (used by all the other responses).
    static byte[] buildResponse(int code, String reason, String version, byte[] body)
    {
        return buildResponse(code, reason, version, body, null);
    }

    // Builds the reply: status line, headers, an empty line, then the body.
    // Adds a Content-Type line only if one is given.
    static byte[] buildResponse(int code, String reason, String version, byte[] body, String contentType)
    {
        String head = version + " " + code + " " + reason + "\r\n";
        if (contentType != null) head += "Content-Type: " + contentType + "\r\n";
        head += "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(body);
        return out.toByteArray();
    }

    // The folder where all files are stored.
    static final Path BASE = Paths.get("server_data").toAbsolutePath().normalize();

    // Turns the request path into a real file path.
    // Returns null if the path tries to go outside server_data or points to a folder.
    static Path safePath(String requestPath) {
        if (!requestPath.startsWith("/")) return null;

        Path p = BASE.resolve(requestPath.substring(1)).normalize();

        if (!p.startsWith(BASE)) return null;
        if (Files.isDirectory(p)) return null;

        return p;
    }

    // Main method. Checks the method, does the file action, and returns the reply.
    // synchronized: only one thread can change files at a time.
    static synchronized byte[] handle(String method, String path, String version, byte[] body) {
        try {
            Path file = safePath(path);
            if (file == null) return buildResponse(400, "Bad Request", version, new byte[0]);

            // GET: read the file and send its content
            if (method.equals("GET")) {
                if (!Files.exists(file)) return buildResponse(404, "Not Found", version, new byte[0]);
                byte[] content = Files.readAllBytes(file);
                String type = file.toString().endsWith(".json") ? "application/json" : "text/plain";
                return buildResponse(200, "OK", version, content, type);
            }

            // POST: create a new file (409 if it already exists)
            if (method.equals("POST")) {
                if (Files.exists(file)) return buildResponse(409, "Conflict", version, new byte[0]);
                Files.createDirectories(file.getParent());
                Files.write(file, body);
                return buildResponse(201, "Created", version, new byte[0]);
            }

            // PUT: replace the file's content (404 if it doesn't exist)
            if (method.equals("PUT")) {
                if (!Files.exists(file)) return buildResponse(404, "Not Found", version, new byte[0]);
                Files.write(file, body);
                return buildResponse(204, "No Content", version, new byte[0]);
            }

            // DELETE: remove the file
            if (method.equals("DELETE")) {
                if (!Files.exists(file)) return buildResponse(404, "Not Found", version, new byte[0]);
                Files.delete(file);
                return buildResponse(204, "No Content", version, new byte[0]);
            }

            return buildResponse(405, "Method Not Allowed", version, new byte[0]);

        } catch (Exception e) {
            return buildResponse(500, "Internal Server Error", version, new byte[0]);
        }
    }

    /*
     * Functional tests (8.1)
     *
     * 1. POST creates a new JSON file
     * $ curl -i -X POST --data-binary '{"name":"Alice"}' http://localhost:8080/users/alice.json
     * HTTP/1.1 201 Created
     * Content-Length: 0
     * Connection: close
     *
     * 2. GET retrieves the file and displays its contents
     * $ curl -i http://localhost:8080/users/alice.json
     * HTTP/1.1 200 OK
     * Content-Type: application/json
     * Content-Length: 16
     * Connection: close
     *
     * {"name":"Alice"}
     *
     * 3. PUT changes the content, then GET returns the new content
     * $ curl -i -X PUT --data-binary '{"name":"Alice","age":22}' http://localhost:8080/users/alice.json
     * HTTP/1.1 204 No Content
     * Content-Length: 0
     * Connection: close
     *
     * $ curl -i http://localhost:8080/users/alice.json
     * HTTP/1.1 200 OK
     * Content-Type: application/json
     * Content-Length: 25
     * Connection: close
     *
     * {"name":"Alice","age":22}
     *
     * 4. DELETE removes the file, then GET returns 404
     * $ curl -i -X DELETE http://localhost:8080/users/alice.json
     * HTTP/1.1 204 No Content
     * Content-Length: 0
     * Connection: close
     *
     * $ curl -i http://localhost:8080/users/alice.json
     * HTTP/1.1 404 Not Found
     * Content-Length: 0
     * Connection: close
     *
     * 5. POST to an existing file returns 409
     * $ curl -i -X POST --data-binary '{"name":"Alice","age":21}' http://localhost:8080/users/alice.json
     * HTTP/1.1 409 Conflict
     * Content-Length: 0
     * Connection: close
     *
     * 6. PUT to a missing file returns 404
     * $ curl -i -X PUT --data-binary 'hi' http://localhost:8080/nothere.txt
     * HTTP/1.1 404 Not Found
     * Content-Length: 0
     * Connection: close
     *
     * 7. Unsupported method returns 405
     * $ curl -i -X PATCH http://localhost:8080/users/alice.json
     * HTTP/1.1 405 Method Not Allowed
     * Content-Length: 0
     * Connection: close
     *
     * 8. Path escaping server_data/ is rejected
     * $ curl -i --path-as-is http://localhost:8080/../../secret.txt
     * HTTP/1.1 400 Bad Request
     * Content-Length: 0
     * Connection: close
     */
}