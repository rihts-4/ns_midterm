import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class HttpServer {

    public static void main(String[] args) {
        int port = 8080;
        String dir = "server_data";
        try {
            if (args.length >= 1) port = Integer.parseInt(args[0]);
            if (args.length >= 2) dir = args[1];
        } catch (NumberFormatException e) {
            System.err.println("Usage: java HttpServer [port] [dataDir]");
            System.exit(2);
        }

        Path root;
        try {
            root = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            root = root.toRealPath();
        } catch (IOException e) {
            System.err.println("Cannot prepare data directory '" + dir + "': " + e.getMessage());
            System.exit(1);
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("HTTP server listening on port " + port
                    + ", data directory: " + root);
            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    Thread t = new Thread(new HttpRequestHandler(client, root));
                    t.start();
                } catch (IOException e) {
                    // A failed accept must not take the server down.
                    System.err.println("Accept failed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not start server on port " + port + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
