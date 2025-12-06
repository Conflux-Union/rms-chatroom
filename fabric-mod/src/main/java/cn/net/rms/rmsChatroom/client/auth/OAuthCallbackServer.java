package cn.net.rms.rmsChatroom.client.auth;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class OAuthCallbackServer {
    private HttpServer server;
    private int port;
    private CompletableFuture<String> tokenFuture;

    public OAuthCallbackServer() {
        this.tokenFuture = new CompletableFuture<>();
    }

    public int start() throws IOException {
        // Find available port
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/callback", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            String token = params.get("token");

            String response;
            int statusCode;

            if (token != null && !token.isEmpty()) {
                response = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <title>Login Successful</title>
                        <style>
                            body { font-family: Arial, sans-serif; text-align: center; padding: 50px; background: #36393f; color: #fff; }
                            .success { color: #43b581; font-size: 24px; }
                        </style>
                    </head>
                    <body>
                        <h1 class="success">Login Successful!</h1>
                        <p>You can close this window and return to Minecraft.</p>
                        <script>setTimeout(() => window.close(), 3000);</script>
                    </body>
                    </html>
                    """;
                statusCode = 200;
                tokenFuture.complete(token);
            } else {
                response = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <title>Login Failed</title>
                        <style>
                            body { font-family: Arial, sans-serif; text-align: center; padding: 50px; background: #36393f; color: #fff; }
                            .error { color: #f04747; font-size: 24px; }
                        </style>
                    </head>
                    <body>
                        <h1 class="error">Login Failed</h1>
                        <p>No token received. Please try again.</p>
                    </body>
                    </html>
                    """;
                statusCode = 400;
                tokenFuture.completeExceptionally(new RuntimeException("No token received"));
            }

            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }

            // Stop server after response
            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    Thread.sleep(1000);
                    stop();
                } catch (InterruptedException ignored) {}
            });
        });

        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        return port;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public int getPort() {
        return port;
    }

    public CompletableFuture<String> getTokenFuture() {
        return tokenFuture;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                params.put(
                        URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8)
                );
            }
        }
        return params;
    }
}
