import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class HttpClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;

    public static void main(String[] args) {
        if (args.length < 4 || args.length > 5) usage();

        String host = args[0];
        int port = 0;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + args[1]);
            usage();
        }
        if (port < 1 || port > 65535) {
            System.err.println("Port out of range: " + port);
            usage();
        }
        String method = args[2].toUpperCase();
        String path = args[3];
        String body = args.length == 5 ? args[4] : null;

        if (!(method.equals("GET") || method.equals("POST")
                || method.equals("PUT") || method.equals("DELETE"))) {
            System.err.println("Unsupported method: " + args[2]);
            usage();
        }
        if (!path.startsWith("/")) {
            System.err.println("Path must start with '/': " + path);
            usage();
        }
        if ((method.equals("POST") || method.equals("PUT")) && body == null) {
            System.err.println(method + " requires a body argument.");
            usage();
        }
        if ((method.equals("GET") || method.equals("DELETE")) && body != null) {
            System.err.println(method + " does not take a body; ignoring it.");
            body = null;
        }

        byte[] bodyBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        byte[] request = buildRequest(method, host, port, path, bodyBytes);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();

            readAndPrintResponse(socket.getInputStream());
        } catch (UnknownHostException e) {
            fail("Unknown host: " + host);
        } catch (ConnectException e) {
            fail("Connection refused to " + host + ":" + port + " (is the server running?)");
        } catch (SocketTimeoutException e) {
            fail("Timed out talking to " + host + ":" + port);
        } catch (IOException e) {
            fail("I/O error: " + e.getMessage());
        }
    }

   
    static byte[] buildRequest(String method, String host, int port, String path, byte[] body) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
        if (body != null) {
            sb.append("Content-Type: application/json\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n"); // bytes, not chars
        }
        sb.append("Connection: close\r\n");
        sb.append("\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (body == null) return head;
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    

    private static void readAndPrintResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || statusLine.isEmpty()) {
            fail("Server closed the connection without sending a response");
        }
        String[] sp = statusLine.split(" ", 3);
        if (sp.length < 2 || !sp[0].startsWith("HTTP/")) {
            fail("Malformed status line: " + statusLine);
        }
        String code = sp[1];
        String reason = sp.length == 3 ? sp[2] : "";

        List<String> headerLines = new ArrayList<>();
        long contentLength = -1;
        while (true) {
            String line = readLine(in);
            if (line == null) fail("Connection closed while reading headers");
            if (line.isEmpty()) break;
            headerLines.add(line);
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Long.parseLong(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    fail("Invalid Content-Length in response: " + line);
                }
            }
        }

        
        byte[] body;
        if (contentLength >= 0) {
            body = readExactly(in, contentLength);
        } else {
            body = readToEof(in);
        }

        System.out.println("Status: " + code + " " + reason);
        System.out.println("Headers:");
        for (String h : headerLines) System.out.println("  " + h);
        if (body.length > 0) {
            System.out.println("Body (" + body.length + " bytes):");
            System.out.println(new String(body, StandardCharsets.UTF_8));
        } else {
            System.out.println("Body: (empty)");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                byte[] b = buf.toByteArray();
                int n = b.length;
                if (n > 0 && b[n - 1] == '\r') n--;
                return new String(b, 0, n, StandardCharsets.ISO_8859_1);
            }
            buf.write(c);
        }
        return any ? new String(buf.toByteArray(), StandardCharsets.ISO_8859_1) : null;
    }

    private static byte[] readExactly(InputStream in, long len) throws IOException {
        if (len > Integer.MAX_VALUE) fail("Response too large");
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, (int) len - off);
            if (r == -1) {
                fail("Connection closed after " + off + " of " + len + " body bytes");
            }
            off += r;
        }
        return buf;
    }

    private static byte[] readToEof(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int r;
        while ((r = in.read(tmp)) != -1) bos.write(tmp, 0, r);
        return bos.toByteArray();
    }


    private static void usage() {
        System.err.println("Usage: java HttpClient <host> <port> <GET|POST|PUT|DELETE> <path> [body]");
        System.err.println("  e.g. java HttpClient localhost 8080 POST /users/alice.json '{\"name\":\"Alice\"}'");
        System.exit(2);
    }

    private static void fail(String msg) {
        System.err.println("Error: " + msg);
        System.exit(1);
    }
}
