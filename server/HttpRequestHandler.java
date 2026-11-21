import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/*
 * Part A3
 * Request Parser for HTTP server
 * Parses start line, headers and body. 
 * Sends the start line information and body to ResourceHandler class to handle.
 * Sends the output of ResourceHandler.handle() to the client.
 * 
 * Note: Body read is a while loop that does not stop until content length is reached.
 */

public class HttpRequestHandler implements Runnable {

    private final Socket clientSocket;

    public HttpRequestHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        System.out.println("[" + threadName + "] Started handling client.");

        try (
            Socket socket = this.clientSocket;
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream()
        ) {
            socket.setSoTimeout(10000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String[] requestLine = reader.readLine().split(" ");
            ArrayList<String> requestHeader = new ArrayList<>();
            String method = requestLine[0];
            String path = requestLine[1];
            String version = requestLine[2];
            String host;
            String contentType;
            String connection;
            String[] supportedHeaders = {"host", "content-length", "content-type", "connection"};
            
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                requestHeader.add(line);
            }

            for (String header : requestHeader) {
                for (String supported : supportedHeaders) {
                    if (header.toLowerCase().startsWith(supported + ":")) {
                        switch (supported) {
                            case "host" -> host = header.substring(header.indexOf(':') + 1).trim();
                            case "content-length" -> {
                                try {
                                    contentLength = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                                } catch (NumberFormatException e) {
                                    contentLength = 0;
                                }
                            }
                            case "content-type" -> contentType = header.substring(header.indexOf(':') + 1).trim();
                            case "connection" -> connection = header.substring(header.indexOf(':') + 1).trim();
                        }
                    }
                }
            }

            char[] bodyBytes = new char[contentLength];
            String requestBody = "";
            if (!method.toUpperCase().equals("GET") && contentLength > 0) {
                int total = 0;
                while (total < contentLength) {
                    int n = reader.read(bodyBytes, total, contentLength - total);
                    if (n == -1) break;
                    total += n;
                }
                requestBody = new String(bodyBytes);
            }
            
            byte[] response = ResourceHandler.handle(method, path, version, requestBody.getBytes(StandardCharsets.UTF_8));
            out.write(response);
            out.flush();

        } catch (Exception e) {
            System.err.println("[" + threadName + "] Error processing request: " + e.getMessage());
        } finally {
            System.out.println("[" + threadName + "] Connection closed and thread finished.");
        }
    }
}