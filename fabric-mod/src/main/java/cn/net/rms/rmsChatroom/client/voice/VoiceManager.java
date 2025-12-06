package cn.net.rms.rmsChatroom.client.voice;

import cn.net.rms.rmsChatroom.client.api.ApiClient;
import cn.net.rms.rmsChatroom.client.api.Models;
import io.livekit.sdk.Room;
import io.livekit.sdk.RoomListener;
import io.livekit.sdk.RoomOptions;
import io.livekit.sdk.Participant;
import io.livekit.sdk.RemoteParticipant;
import io.livekit.sdk.rtc.RtcClient;
import io.livekit.sdk.rtc.LocalAudioTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public class VoiceManager {
    private static VoiceManager INSTANCE;

    private RtcClient client;
    private Room room;
    private LocalAudioTrack micTrack;

    private long currentChannelId = -1;
    private String currentChannelName = "";
    private boolean isMuted = false;
    private boolean isConnecting = false;
    private boolean isConnected = false;

    private final List<VoiceStateListener> listeners = new CopyOnWriteArrayList<>();

    public interface VoiceStateListener {
        default void onConnecting() {}
        default void onConnected(String channelName) {}
        default void onDisconnected() {}
        default void onError(String message) {}
        default void onMuteChanged(boolean muted) {}
        default void onParticipantJoined(String name) {}
        default void onParticipantLeft(String name) {}
    }

    public static VoiceManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new VoiceManager();
        }
        return INSTANCE;
    }

    public void addListener(VoiceStateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(VoiceStateListener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isConnecting() {
        return isConnecting;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public long getCurrentChannelId() {
        return currentChannelId;
    }

    public String getCurrentChannelName() {
        return currentChannelName;
    }

    public CompletableFuture<Boolean> joinChannel(long channelId, String channelName) {
        if (isConnecting || isConnected) {
            if (currentChannelId == channelId) {
                return CompletableFuture.completedFuture(true);
            }
            disconnect();
        }

        isConnecting = true;
        currentChannelId = channelId;
        currentChannelName = channelName;
        listeners.forEach(VoiceStateListener::onConnecting);

        return ApiClient.getInstance().getVoiceToken(channelId)
                .thenCompose(tokenResponse -> {
                    if (tokenResponse == null) {
                        throw new RuntimeException("Failed to get voice token");
                    }
                    return connect(tokenResponse.url(), tokenResponse.token());
                })
                .thenApply(success -> {
                    isConnecting = false;
                    if (success) {
                        isConnected = true;
                        listeners.forEach(l -> l.onConnected(channelName));
                    }
                    return success;
                })
                .exceptionally(e -> {
                    isConnecting = false;
                    isConnected = false;
                    currentChannelId = -1;
                    currentChannelName = "";
                    String errorMsg = e.getMessage() != null ? e.getMessage() : "Connection failed";
                    listeners.forEach(l -> l.onError(errorMsg));
                    return false;
                });
    }

    private CompletableFuture<Boolean> connect(String url, String token) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RoomOptions options = new RoomOptions();
                client = new RtcClient(options);
                room = client.getRoom();

                room.addListener(new RoomListener() {
                    @Override
                    public void onConnected(Room r) {
                        // Already handled in joinChannel
                    }

                    @Override
                    public void onDisconnected(Room r, io.livekit.sdk.DisconnectReason reason) {
                        handleDisconnect();
                    }

                    @Override
                    public void onParticipantConnected(Room r, RemoteParticipant participant) {
                        listeners.forEach(l -> l.onParticipantJoined(participant.getName()));
                    }

                    @Override
                    public void onParticipantDisconnected(Room r, RemoteParticipant participant) {
                        listeners.forEach(l -> l.onParticipantLeft(participant.getName()));
                    }
                });

                client.connect(url, token);

                // Publish microphone
                publishMicrophone();

                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        });
    }

    private void publishMicrophone() {
        if (client == null) return;

        try {
            // Create and publish audio track using RtcClient
            micTrack = client.createAudioTrack();
            if (micTrack != null) {
                client.publishAudioTrack(micTrack);
                // Apply mute state if needed
                if (isMuted && room != null && room.getLocalParticipant() != null) {
                    room.getLocalParticipant().setMicrophoneEnabled(false);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void disconnect() {
        if (client != null) {
            try {
                micTrack = null;
                client.disconnect();
                client.shutdown();
            } catch (Exception e) {
                e.printStackTrace();
            }
            client = null;
            room = null;
        }
        handleDisconnect();
    }

    private void handleDisconnect() {
        isConnected = false;
        isConnecting = false;
        currentChannelId = -1;
        currentChannelName = "";
        listeners.forEach(VoiceStateListener::onDisconnected);
    }

    public void toggleMute() {
        setMuted(!isMuted);
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
        if (room != null && room.getLocalParticipant() != null) {
            room.getLocalParticipant().setMicrophoneEnabled(!muted);
        }
        listeners.forEach(l -> l.onMuteChanged(muted));
    }

    public List<String> getParticipantNames() {
        List<String> names = new ArrayList<>();
        if (room != null) {
            Participant local = room.getLocalParticipant();
            if (local != null) {
                names.add(local.getName() + " (You)");
            }
            for (RemoteParticipant participant : room.getRemoteParticipants().values()) {
                names.add(participant.getName());
            }
        }
        return names;
    }
}
