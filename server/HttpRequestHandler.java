import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

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
            socket.setSoTimeout(10000); // don't let idle clients hold a thread forever
            // -----------------------------------------------------------------
            // [A3]
            // -----------------------------------------------------------------
            
            // Read the first line (Request Line) just to verify incoming data works
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
                                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
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

            // --------- PRINT BELOW JUST FOR DEBUGGING ---------
            // System.out.println(requestLine);
            // for (String header : requestHeader) {
            //     System.out.println(header);
            // }
            // System.out.println(requestBody);

            // Simulate a 2-second processing delay so you can prove concurrency
            // Thread.sleep(2000);

            // -----------------------------------------------------------------
            // [A3]
            // -----------------------------------------------------------------

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