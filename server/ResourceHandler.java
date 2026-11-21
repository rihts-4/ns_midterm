import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// [A4 + A5] Resource operations and server responses
//This class does the file actions (GET, POST, PUT, DELETE) and builds the server's response.

public class ResourceHandler {

    // Builds the reply: status line, headers, an empty line, then the body.
    static byte[] buildResponse(int code, String reason, String version, byte[] body) {
        String head = version + " " + code + " " + reason + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close" + "\r\n"
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
            if (file == null) return buildResponse(400, "Bad Request", version, body);

            // GET: read the file and send its content
            if (method.equals("GET")) {
                if (!Files.exists(file)) return buildResponse(404, "Not Found", version, new byte[0]);
                byte[] content = Files.readAllBytes(file);
                return buildResponse(200, "OK", version, content);
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

//This is just a temporary testing main method to see whether it works.
//    public static void main(String[] args) {
//        System.out.println(new String(handle("POST", "/test.txt", "hello".getBytes())));
//        System.out.println(new String(handle("GET", "/test.txt", new byte[0])));
//        System.out.println(new String(handle("POST", "/test.txt", "again".getBytes())));
//        System.out.println(new String(handle("PUT", "/test.txt", "bye".getBytes())));
//        System.out.println(new String(handle("GET", "/test.txt", new byte[0])));
//        System.out.println(new String(handle("DELETE", "/test.txt", new byte[0])));
//        System.out.println(new String(handle("GET", "/test.txt", new byte[0])));
//        System.out.println(new String(handle("GET", "/../secret.txt", new byte[0])));
//        System.out.println(new String(handle("JUMP", "/test.txt", new byte[0])));
//    }
}