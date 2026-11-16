import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

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
            // [A1/A2 WORKING SKELETON - REPLACE THIS BLOCK WITH A3, A4, A5]
            // -----------------------------------------------------------------
            
            // Read the first line (Request Line) just to verify incoming data works
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            System.out.println("[" + threadName + "] Received: " + requestLine);

            // Simulate a 2-second processing delay so you can prove concurrency
            Thread.sleep(2000);

            // Build a valid minimal HTTP/1.1 response using CRLF (\r\n)
            String responseBody = "Handled by " + threadName + "\n";
            byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8); // UTF-8 byte length

            String responseHeaders = 
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n"; // Blank line separating headers and body

            // Send headers and body
            out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();

            // -----------------------------------------------------------------
            // [END OF TEMPORARY SKELETON]
            // -----------------------------------------------------------------

        } catch (Exception e) {
            System.err.println("[" + threadName + "] Error processing request: " + e.getMessage());
        } finally {
            System.out.println("[" + threadName + "] Connection closed and thread finished.");
        }
    }
}