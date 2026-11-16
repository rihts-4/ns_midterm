import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class HttpServer {

    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        // 1. Allow configurable port via command-line argument (default: 8080)
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 0 || port > 65535) {
                    System.err.println("Port out of range (0-65535). Using default port " + DEFAULT_PORT);
                    port = DEFAULT_PORT;
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number. Using default port " + DEFAULT_PORT);
            }
        }

        // 2. Part A1: Create ServerSocket listening on the TCP port
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("=== HTTP Server started on port " + port + " ===");

            // 3. Part A1: Continuously accept client connections
            while (true) {
                try {
                    // Blocks here until a client connects
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("[Main Thread] Accepted connection from: " 
                        + clientSocket.getRemoteSocketAddress());

                    // 4. Part A2: Hand off to a separate Java thread immediately
                    // Do NOT use a thread pool (explicitly forbidden by spec)
                    HttpRequestHandler handler = new HttpRequestHandler(clientSocket);
                    Thread clientThread = new Thread(handler);
                    clientThread.start();

                    // Main thread immediately loops back to accept() without waiting

                } catch (IOException e) {
                    // If accepting one client fails, log it and keep the server running
                    System.err.println("[Main Thread] Failed to accept client connection: " + e.getMessage());
                    if (serverSocket.isClosed()) break;
                }
            }
        } catch (IOException e) {
            System.err.println("Fatal: Could not start server on port " + port + ": " + e.getMessage());
        }
    }
}