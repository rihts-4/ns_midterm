import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class HttpRequestHandler implements Runnable {

    private final Socket clientSocket;

    // Constructor receives the accepted Socket from HttpServer
    public HttpRequestHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        System.out.println("[" + threadName + "] Started handling client.");

        // Try-with-resources guarantees the Socket and streams close automatically
        // after handling one request, even if an exception occurs.
        //Note[not to wrap InputStream in in a BufferedReader when they implement real body 
        //     reading, because BufferedReader buffers ahead in characters and makes reading exact 
        //     Content-Length bytes[cite: 3] from the raw InputStream tricky.]
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
            
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                requestHeader.add(line);

                // Check for Content-Length header (case-insensitive check is safer)
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(line.length()-2).trim());
                    } catch (NumberFormatException e) {
                        contentLength = 0;
                    }
                }
            }

            // 2. Read the body if Content-Length is specified
            String requestBody = "";
            if (!method.toUpperCase().equals("GET")) {
                char[] bodyChars = new char[contentLength];
                int bytesRead = reader.read(bodyChars, 0, contentLength);
                requestBody = new String(bodyChars, 0, bytesRead);
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

            byte[] response = ResourceHandler.handle(method, path, requestBody.getBytes());
            out.write(response);
            out.flush();

        } catch (Exception e) {
            System.err.println("[" + threadName + "] Error processing request: " + e.getMessage());
        } finally {
            System.out.println("[" + threadName + "] Connection closed and thread finished.");
        }
    }
}