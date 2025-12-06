package cn.net.rms.rmsChatroom.client.api;

import cn.net.rms.rmsChatroom.client.config.ModConfig;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ApiClient {
    private static ApiClient INSTANCE;

    private final HttpClient httpClient;
    private final Gson gson;

    private ApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

    public static ApiClient getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ApiClient();
        }
        return INSTANCE;
    }

    private String getBaseUrl() {
        return ModConfig.getInstance().serverUrl;
    }

    private String getToken() {
        return ModConfig.getInstance().token;
    }

    private HttpRequest.Builder authorizedRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + path))
                .header("Authorization", "Bearer " + getToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
    }

    public CompletableFuture<Models.AuthMeResponse> getMe() {
        HttpRequest request = authorizedRequest("/api/auth/me")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return gson.fromJson(response.body(), Models.AuthMeResponse.class);
                    }
                    return new Models.AuthMeResponse(false, null);
                });
    }

    public CompletableFuture<List<Models.Server>> getServers() {
        HttpRequest request = authorizedRequest("/api/servers")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return gson.fromJson(response.body(),
                                new TypeToken<List<Models.Server>>() {}.getType());
                    }
                    return List.of();
                });
    }

    public CompletableFuture<List<Models.Channel>> getChannels(long serverId) {
        HttpRequest request = authorizedRequest("/api/servers/" + serverId + "/channels")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return gson.fromJson(response.body(),
                                new TypeToken<List<Models.Channel>>() {}.getType());
                    }
                    return List.of();
                });
    }

    public CompletableFuture<Models.VoiceTokenResponse> getVoiceToken(long channelId) {
        HttpRequest request = authorizedRequest("/api/voice/" + channelId + "/token")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return gson.fromJson(response.body(), Models.VoiceTokenResponse.class);
                    }
                    return null;
                });
    }

    public CompletableFuture<List<Models.VoiceUser>> getVoiceUsers(long channelId) {
        HttpRequest request = authorizedRequest("/api/voice/" + channelId + "/users")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return gson.fromJson(response.body(),
                                new TypeToken<List<Models.VoiceUser>>() {}.getType());
                    }
                    return List.of();
                });
    }

    public CompletableFuture<Models.AllVoiceUsersResponse> getAllVoiceUsers() {
        HttpRequest request = authorizedRequest("/api/voice/user/all")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return gson.fromJson(response.body(), Models.AllVoiceUsersResponse.class);
                    }
                    return new Models.AllVoiceUsersResponse(java.util.Map.of());
                });
    }
}
