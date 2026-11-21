import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;


public class HttpRequestHandler implements Runnable {

    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 100;
    private static final long MAX_BODY = 10L * 1024 * 1024; // 10 MB
    private static final int READ_TIMEOUT_MS = 15000;

    private final Socket socket;
    private final Path root;

    public HttpRequestHandler(Socket socket, Path root) {
        this.socket = socket;
        this.root = root;
    }

    
    private static class HttpException extends Exception {
        final int status;
        HttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    @Override
    public void run() {
        String who = socket.getRemoteSocketAddress() + "";
        try (Socket s = socket) {
            s.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            try {
                handle(in, out, who);
            } catch (HttpException e) {
                log(who, "-", "-", e.status);
                sendResponse(out, e.status, e.getMessage());
            } catch (java.net.SocketTimeoutException e) {
                try { sendResponse(out, 408, "Request timed out"); } catch (IOException ignored) { }
            } catch (Exception e) {
                System.err.println("[" + who + "] internal error: " + e);
                try { sendResponse(out, 500, "Internal server error"); } catch (IOException ignored) { }
            }
        } catch (IOException e) {
            System.err.println("[" + who + "] connection error: " + e.getMessage());
        }
    }

   

    private void handle(InputStream in, OutputStream out, String who) throws Exception {
        // --- request line
        String requestLine = readLine(in);
        if (requestLine == null) return; 
        if (requestLine.isEmpty()) {
            requestLine = readLine(in);
            if (requestLine == null) return;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length != 3) throw new HttpException(400, "Malformed request line");
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new HttpException(505, "HTTP version not supported");
        }

        
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in);
            if (line == null) throw new HttpException(400, "Unexpected end of headers");
            if (line.isEmpty()) break;
            if (headers.size() >= MAX_HEADERS) throw new HttpException(400, "Too many headers");
            int colon = line.indexOf(':');
            if (colon <= 0) throw new HttpException(400, "Malformed header line");
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }

        if (headers.containsKey("transfer-encoding")) {
            throw new HttpException(501, "Transfer-Encoding is not supported");
        }

        
        byte[] body = new byte[0];
        String cl = headers.get("content-length");
        if (cl != null) {
            long len;
            try {
                len = Long.parseLong(cl);
            } catch (NumberFormatException e) {
                throw new HttpException(400, "Invalid Content-Length");
            }
            if (len < 0) throw new HttpException(400, "Invalid Content-Length");
            if (len > MAX_BODY) throw new HttpException(413, "Request body too large");
            body = readFully(in, (int) len);
        }

        
        switch (method) {
            case "GET":
            case "POST":
            case "PUT":
            case "DELETE":
                break;
            default:
                throw new HttpException(405, "Method not allowed: " + method);
        }
        if ((method.equals("POST") || method.equals("PUT")) && cl == null) {
            throw new HttpException(411, "Content-Length required");
        }

        Path file = resolve(target);
        log(who, method, target, 0);

        switch (method) {
            case "GET":    doGet(out, file);          break;
            case "POST":   doPost(out, file, body);   break;
            case "PUT":    doPut(out, file, body);    break;
            case "DELETE": doDelete(out, file);       break;
        }
    }


    private String readLine(InputStream in) throws IOException, HttpException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > MAX_LINE) throw new HttpException(400, "Line too long");
        }
        return any ? sb.toString() : null;
    }

    private byte[] readFully(InputStream in, int len) throws IOException, HttpException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r == -1) throw new HttpException(400, "Body shorter than Content-Length");
            off += r;
        }
        return buf;
    }

    
    private Path resolve(String target) throws HttpException {
        int q = target.indexOf('?');
        if (q >= 0) target = target.substring(0, q);
        int h = target.indexOf('#');
        if (h >= 0) target = target.substring(0, h);

        String decoded;
        try {
            decoded = URLDecoder.decode(target.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            throw new HttpException(400, "Bad percent-encoding in path");
        }
        if (!decoded.startsWith("/")) throw new HttpException(400, "Path must start with /");
        if (decoded.indexOf('\0') >= 0 || decoded.indexOf('\\') >= 0) {
            throw new HttpException(400, "Illegal character in path");
        }
        for (String seg : decoded.split("/")) {
            if (seg.equals("..")) throw new HttpException(403, "Path escapes server_data/");
        }

        String rel = decoded.substring(1);
        if (rel.isEmpty()) throw new HttpException(400, "No resource specified");

        Path p;
        try {
            p = root.resolve(rel).normalize();
        } catch (Exception e) {
            throw new HttpException(400, "Invalid path");
        }
        if (!p.startsWith(root) || p.equals(root)) {
            throw new HttpException(403, "Path escapes server_data/");
        }
        try {
            Path existing = p;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing != null && !existing.toRealPath().startsWith(root)) {
                throw new HttpException(403, "Path escapes server_data/");
            }
        } catch (IOException e) {
            throw new HttpException(400, "Invalid path");
        }
        return p;
    }


    private void doGet(OutputStream out, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            sendResponse(out, 404, "Not Found");
            return;
        }
        byte[] data = Files.readAllBytes(file);
        sendResponse(out, 200, "application/json", data);
    }

    private void doPost(OutputStream out, Path file, byte[] body) throws IOException {
        if (Files.exists(file)) {
            sendResponse(out, 409, "Conflict: resource already exists");
            return;
        }
        Path parent = file.getParent();
        if (parent != null) {
            if (Files.exists(parent) && !Files.isDirectory(parent)) {
                sendResponse(out, 409, "Conflict: parent path is a file");
                return;
            }
            Files.createDirectories(parent);
        }
        try {
            Files.write(file, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            sendResponse(out, 409, "Conflict: resource already exists");
            return;
        }
        sendEmpty(out, 201);
    }

    private void doPut(OutputStream out, Path file, byte[] body) throws IOException {
        if (!Files.isRegularFile(file)) {
            sendResponse(out, 404, "Not Found");
            return;
        }
        Path tmp = Files.createTempFile(file.getParent(), ".put-", ".tmp");
        try {
            Files.write(tmp, body);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        sendEmpty(out, 204);
    }

    private void doDelete(OutputStream out, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            sendResponse(out, 404, "Not Found");
            return;
        }
        try {
            Files.delete(file);
        } catch (NoSuchFileException e) {
            sendResponse(out, 404, "Not Found");
            return;
        }
        sendEmpty(out, 204);
    }


    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 505: return "HTTP Version Not Supported";
            default:  return "Unknown";
        }
    }


    private void sendEmpty(OutputStream out, int status) throws IOException {
        write(out, status, null, new byte[0], null);
    }


    private void sendResponse(OutputStream out, int status, String message) throws IOException {
        byte[] b = (message + "\n").getBytes(StandardCharsets.UTF_8);
        String extra = status == 405 ? "Allow: GET, POST, PUT, DELETE\r\n" : null;
        write(out, status, "text/plain; charset=utf-8", b, extra);
    }

    private void sendResponse(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        write(out, status, contentType, body, null);
    }

    private void write(OutputStream out, int status, String contentType, byte[] body, String extra)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (extra != null) sb.append(extra);
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private void log(String who, String method, String target, int status) {
        if (status == 0) {
            System.out.println("[" + Thread.currentThread().getName() + "] "
                    + who + " " + method + " " + target);
        } else {
            System.out.println("[" + Thread.currentThread().getName() + "] "
                    + who + " rejected -> " + status);
        }
    }
}
