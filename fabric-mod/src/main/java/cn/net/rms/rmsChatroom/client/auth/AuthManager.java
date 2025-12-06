package cn.net.rms.rmsChatroom.client.auth;

import cn.net.rms.rmsChatroom.client.api.ApiClient;
import cn.net.rms.rmsChatroom.client.api.Models;
import cn.net.rms.rmsChatroom.client.config.ModConfig;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class AuthManager {
    private static AuthManager INSTANCE;

    private OAuthCallbackServer callbackServer;
    private Models.User currentUser;
    private boolean loggingIn = false;

    public static AuthManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AuthManager();
        }
        return INSTANCE;
    }

    public boolean isLoggedIn() {
        return ModConfig.getInstance().isLoggedIn() && currentUser != null;
    }

    public boolean isLoggingIn() {
        return loggingIn;
    }

    public Models.User getCurrentUser() {
        return currentUser;
    }

    public CompletableFuture<Boolean> validateToken() {
        if (!ModConfig.getInstance().isLoggedIn()) {
            return CompletableFuture.completedFuture(false);
        }

        return ApiClient.getInstance().getMe()
                .thenApply(response -> {
                    if (response.success() && response.user() != null) {
                        currentUser = response.user();
                        return true;
                    }
                    currentUser = null;
                    return false;
                })
                .exceptionally(e -> {
                    currentUser = null;
                    return false;
                });
    }

    public void startLogin(Consumer<Boolean> callback) {
        if (loggingIn) {
            return;
        }
        loggingIn = true;

        try {
            callbackServer = new OAuthCallbackServer();
            int port = callbackServer.start();

            String redirectUri = "http://localhost:" + port + "/callback";
            String ssoUrl = ModConfig.getInstance().ssoUrl;
            String loginUrl = ssoUrl + "/oauth/authorize?redirect_uri=" + redirectUri + "&client_id=rms-chatroom-mc";

            // Open browser
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(loginUrl));
            } else {
                // Fallback: try xdg-open on Linux
                Runtime.getRuntime().exec(new String[]{"xdg-open", loginUrl});
            }

            // Wait for token
            callbackServer.getTokenFuture()
                    .thenAccept(token -> {
                        ModConfig.getInstance().setToken(token);
                        validateToken().thenAccept(valid -> {
                            loggingIn = false;
                            callback.accept(valid);
                        });
                    })
                    .exceptionally(e -> {
                        loggingIn = false;
                        callback.accept(false);
                        return null;
                    });

        } catch (Exception e) {
            e.printStackTrace();
            loggingIn = false;
            if (callbackServer != null) {
                callbackServer.stop();
            }
            callback.accept(false);
        }
    }

    public void cancelLogin() {
        loggingIn = false;
        if (callbackServer != null) {
            callbackServer.stop();
            callbackServer = null;
        }
    }

    public void logout() {
        currentUser = null;
        ModConfig.getInstance().logout();
    }
}
