package cn.net.rms.rmsChatroom.client.voice;

import cn.net.rms.rmsChatroom.client.api.ApiClient;
import cn.net.rms.rmsChatroom.client.api.Models;
import cn.net.rms.rmsChatroom.client.config.ModConfig;
import io.livekit.sdk.Room;
import io.livekit.sdk.RoomListener;
import io.livekit.sdk.RoomOptions;
import io.livekit.sdk.Participant;
import io.livekit.sdk.RemoteParticipant;
import io.livekit.sdk.rtc.RtcClient;
import io.livekit.sdk.rtc.LocalAudioTrack;
import io.livekit.sdk.rtc.RemoteAudioTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    // Per-participant volume storage (identity -> volume 0.0-2.0, where 1.0=100%)
    private final Map<String, Float> participantVolumes = new ConcurrentHashMap<>();

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
        CompletableFuture<Boolean> result = new CompletableFuture<>();

        try {
            RoomOptions options = new RoomOptions();
            client = new RtcClient(options);
            room = client.getRoom();

            room.addListener(new RoomListener() {
                @Override
                public void onConnected(Room r) {
                    // Publish microphone after connection is established
                    publishMicrophone();
                    result.complete(true);
                }

                @Override
                public void onDisconnected(Room r, io.livekit.sdk.DisconnectReason reason) {
                    handleDisconnect();
                }

                @Override
                public void onParticipantConnected(Room r, RemoteParticipant participant) {
                    // Load saved volume for this participant
                    String identity = participant.getSid();
                    float savedVolume = ModConfig.getInstance().getParticipantVolume(identity);
                    if (savedVolume != 1.0f) {
                        participantVolumes.put(identity, savedVolume);
                        applyVolumeToParticipant(identity, savedVolume);
                    }
                    listeners.forEach(l -> l.onParticipantJoined(participant.getName()));
                }

                @Override
                public void onParticipantDisconnected(Room r, RemoteParticipant participant) {
                    listeners.forEach(l -> l.onParticipantLeft(participant.getName()));
                }
            });

            client.connect(url, token);

        } catch (Exception e) {
            e.printStackTrace();
            result.complete(false);
        }

        return result;
    }

    private void publishMicrophone() {
        if (client == null) {
            System.out.println("[RMS Voice] publishMicrophone - client is NULL!");
            return;
        }

        try {
            // Create and publish audio track using RtcClient
            micTrack = client.createAudioTrack();
            System.out.println("[RMS Voice] publishMicrophone - createAudioTrack returned: " + (micTrack != null));
            if (micTrack != null) {
                System.out.println("[RMS Voice] publishMicrophone - micTrack.id: " + micTrack.getId() + ", nativeTrack: " + (micTrack.getNativeTrack() != null));
                client.publishAudioTrack(micTrack);
                // Apply current mute state
                micTrack.setMuted(isMuted);
                System.out.println("[RMS Voice] publishMicrophone - published, isMuted: " + isMuted + ", sid: " + micTrack.getSid());
            }
        } catch (Exception e) {
            System.out.println("[RMS Voice] publishMicrophone - Exception: " + e.getMessage());
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
        // Use RtcClient to set mute state - it sends signal to server
        if (client != null) {
            String sid = micTrack != null ? micTrack.getSid() : null;
            System.out.println("[RMS Voice] setMuted(" + muted + ") - calling client.setMicrophoneEnabled, micTrack.sid: " + sid);
            client.setMicrophoneEnabled(!muted);
        } else {
            System.out.println("[RMS Voice] setMuted(" + muted + ") - client is NULL!");
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

    /**
     * Get list of remote participants with their volume info
     */
    public List<ParticipantInfo> getParticipants() {
        List<ParticipantInfo> list = new ArrayList<>();
        if (room != null) {
            // Add local participant
            Participant local = room.getLocalParticipant();
            if (local != null) {
                list.add(new ParticipantInfo(
                        local.getSid(),
                        local.getName(),
                        isMuted,
                        1.0f,
                        true
                ));
            }
            // Add remote participants
            for (RemoteParticipant participant : room.getRemoteParticipants().values()) {
                String identity = participant.getSid();
                float volume = participantVolumes.getOrDefault(identity, 1.0f);
                list.add(new ParticipantInfo(
                        identity,
                        participant.getName(),
                        false, // muted state - SDK doesn't expose this directly
                        volume,
                        false
                ));
            }
        }
        return list;
    }

    /**
     * Set volume for a specific participant (0.0-2.0, where 1.0=100%, 2.0=200%)
     */
    public void setParticipantVolume(String participantId, float volume) {
        float clampedVolume = Math.max(0f, Math.min(2f, volume));
        participantVolumes.put(participantId, clampedVolume);
        applyVolumeToParticipant(participantId, clampedVolume);
        System.out.println("[RMS Voice] Set volume for " + participantId + " to " + (int)(clampedVolume * 100) + "%");
    }

    /**
     * Get volume for a specific participant (checks local cache first, then config)
     */
    public float getParticipantVolume(String participantId) {
        if (participantVolumes.containsKey(participantId)) {
            return participantVolumes.get(participantId);
        }
        // Load from config if not in local cache
        float savedVolume = ModConfig.getInstance().getParticipantVolume(participantId);
        participantVolumes.put(participantId, savedVolume);
        return savedVolume;
    }

    private void applyVolumeToParticipant(String participantId, float volume) {
        if (room == null) return;
        
        RemoteParticipant participant = room.getRemoteParticipants().get(participantId);
        if (participant != null) {
            try {
                var tracks = participant.getTrackPublications();
                if (tracks != null) {
                    tracks.forEach((sid, trackPub) -> {
                        var track = trackPub.getTrack();
                        if (track instanceof RemoteAudioTrack audioTrack) {
                            audioTrack.setVolume(volume);
                            System.out.println("[RMS Voice] Applied volume " + (int)(volume * 100) + "% to track " + sid);
                        }
                    });
                }
            } catch (Exception e) {
                System.out.println("[RMS Voice] Failed to set volume for " + participantId + ": " + e.getMessage());
            }
        }
    }

    /**
     * Data class for participant info
     */
    public record ParticipantInfo(
            String identity,
            String name,
            boolean isMuted,
            float volume,
            boolean isLocal
    ) {}
}
