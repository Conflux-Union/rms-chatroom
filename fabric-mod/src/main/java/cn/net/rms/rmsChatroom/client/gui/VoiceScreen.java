package cn.net.rms.rmsChatroom.client.gui;

import cn.net.rms.rmsChatroom.client.api.ApiClient;
import cn.net.rms.rmsChatroom.client.api.Models;
import cn.net.rms.rmsChatroom.client.auth.AuthManager;
import cn.net.rms.rmsChatroom.client.config.ModConfig;
import cn.net.rms.rmsChatroom.client.voice.VoiceManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VoiceScreen extends Screen {
    private List<Models.Server> servers = new ArrayList<>();
    private List<Models.Channel> channels = new ArrayList<>();
    private Map<Long, List<Models.VoiceUser>> voiceUsers = Map.of();

    private Models.Server selectedServer = null;
    private Models.Channel selectedChannel = null;

    private String statusMessage = "";
    private boolean isLoading = false;

    public VoiceScreen() {
        super(new LiteralText("RMS Voice Chat"));
    }

    @Override
    protected void init() {
        super.init();

        AuthManager authManager = AuthManager.getInstance();

        if (!authManager.isLoggedIn()) {
            if (ModConfig.getInstance().isLoggedIn()) {
                statusMessage = "Validating session...";
                isLoading = true;
                authManager.validateToken().thenAccept(valid -> {
                    isLoading = false;
                    if (valid) {
                        client.execute(this::initLoggedInUI);
                    } else {
                        client.execute(this::initLoginUI);
                    }
                });
            } else {
                initLoginUI();
            }
        } else {
            initLoggedInUI();
        }
    }

    private void initLoginUI() {
        clearChildren();
        statusMessage = "";

        int centerX = width / 2;
        int centerY = height / 2;

        if (AuthManager.getInstance().isLoggingIn()) {
            statusMessage = "Waiting for login in browser...";
            addDrawableChild(new ButtonWidget(
                    centerX - 75, centerY + 20, 150, 20,
                    new LiteralText("Cancel"),
                    button -> {
                        AuthManager.getInstance().cancelLogin();
                        initLoginUI();
                    }
            ));
        } else {
            addDrawableChild(new ButtonWidget(
                    centerX - 75, centerY - 10, 150, 20,
                    new LiteralText("Login with SSO"),
                    button -> {
                        statusMessage = "Opening browser...";
                        AuthManager.getInstance().startLogin(success -> {
                            client.execute(() -> {
                                if (success) {
                                    initLoggedInUI();
                                } else {
                                    statusMessage = "Login failed. Please try again.";
                                    initLoginUI();
                                }
                            });
                        });
                        initLoginUI();
                    }
            ));
        }

        addDrawableChild(new ButtonWidget(
                centerX - 75, centerY + 50, 150, 20,
                new LiteralText("Close"),
                button -> onClose()
        ));
    }

    private void initLoggedInUI() {
        clearChildren();
        statusMessage = "Loading...";
        isLoading = true;

        ApiClient.getInstance().getServers().thenAccept(serverList -> {
            servers = serverList;
            if (!servers.isEmpty()) {
                selectedServer = servers.get(0);
                loadChannels(selectedServer.id());
            }
            loadVoiceUsers();
            isLoading = false;
            client.execute(this::rebuildUI);
        });
    }

    private void loadChannels(long serverId) {
        ApiClient.getInstance().getChannels(serverId).thenAccept(channelList -> {
            channels = channelList.stream()
                    .filter(Models.Channel::isVoice)
                    .toList();
            client.execute(this::rebuildUI);
        });
    }

    private void loadVoiceUsers() {
        ApiClient.getInstance().getAllVoiceUsers().thenAccept(response -> {
            voiceUsers = response.users();
            client.execute(this::rebuildUI);
        });
    }

    private void rebuildUI() {
        clearChildren();

        VoiceManager voiceManager = VoiceManager.getInstance();
        Models.User currentUser = AuthManager.getInstance().getCurrentUser();

        int leftColumnX = 20;
        int contentWidth = 200;
        int startY = 50;

        // Title and logout
        if (currentUser != null) {
            addDrawableChild(new ButtonWidget(
                    width - 70, 10, 60, 20,
                    new LiteralText("Logout"),
                    button -> {
                        voiceManager.disconnect();
                        AuthManager.getInstance().logout();
                        initLoginUI();
                    }
            ));
        }

        // Server buttons
        int serverY = startY;
        int serverBtnWidth = 80;
        for (int i = 0; i < Math.min(servers.size(), 4); i++) {
            Models.Server server = servers.get(i);
            addDrawableChild(new ButtonWidget(
                    leftColumnX + i * (serverBtnWidth + 5), serverY, serverBtnWidth, 20,
                    new LiteralText(truncate(server.name(), 10)),
                    button -> {
                        selectedServer = server;
                        loadChannels(server.id());
                    }
            ));
        }

        int listY = serverY + 30;

        // Connected: show participants
        if (voiceManager.isConnected()) {
            List<VoiceManager.ParticipantInfo> participants = voiceManager.getParticipants();
            
            for (int i = 0; i < Math.min(participants.size(), 8); i++) {
                VoiceManager.ParticipantInfo participant = participants.get(i);
                int volPercent = (int)(participant.volume() * 100);
                String label = participant.name() + 
                        (participant.isLocal() ? " (You)" : " [" + volPercent + "%]") +
                        (participant.isMuted() ? " [M]" : "");
                
                if (!participant.isLocal()) {
                    final VoiceManager.ParticipantInfo p = participant;
                    addDrawableChild(new ButtonWidget(
                            leftColumnX, listY + i * 25, contentWidth, 20,
                            new LiteralText(label),
                            button -> client.setScreen(new VolumeScreen(this, p))
                    ));
                } else {
                    addDrawableChild(new ButtonWidget(
                            leftColumnX, listY + i * 25, contentWidth, 20,
                            new LiteralText(label),
                            button -> {}
                    ));
                }
            }
        } else {
            // Not connected: show channels
            for (int i = 0; i < Math.min(channels.size(), 8); i++) {
                Models.Channel channel = channels.get(i);
                List<Models.VoiceUser> users = voiceUsers.getOrDefault(channel.id(), List.of());
                int userCount = users.size();
                String label = channel.name() + (userCount > 0 ? " (" + userCount + ")" : "");
                boolean isCurrentChannel = voiceManager.getCurrentChannelId() == channel.id();

                addDrawableChild(new ButtonWidget(
                        leftColumnX, listY + i * 25, contentWidth, 20,
                        new LiteralText((isCurrentChannel ? "> " : "") + label),
                        button -> selectedChannel = channel
                ));
            }
        }

        // Bottom action buttons
        int actionY = height - 60;

        if (voiceManager.isConnected()) {
            addDrawableChild(new ButtonWidget(
                    leftColumnX, actionY, 90, 20,
                    new LiteralText("Disconnect"),
                    button -> {
                        voiceManager.disconnect();
                        rebuildUI();
                    }
            ));

            boolean muted = voiceManager.isMuted();
            addDrawableChild(new ButtonWidget(
                    leftColumnX + 100, actionY, 90, 20,
                    new LiteralText(muted ? "Unmute" : "Mute"),
                    button -> {
                        voiceManager.toggleMute();
                        rebuildUI();
                    }
            ));
        } else if (selectedChannel != null) {
            addDrawableChild(new ButtonWidget(
                    leftColumnX, actionY, 150, 20,
                    new LiteralText("Join " + truncate(selectedChannel.name(), 12)),
                    button -> {
                        statusMessage = "Connecting...";
                        voiceManager.joinChannel(selectedChannel.id(), selectedChannel.name())
                                .thenAccept(success -> client.execute(() -> {
                                    statusMessage = success ? "Connected!" : "Failed to connect";
                                    rebuildUI();
                                }));
                    }
            ));
        }

        addDrawableChild(new ButtonWidget(
                leftColumnX + 200, actionY, 70, 20,
                new LiteralText("Refresh"),
                button -> {
                    loadVoiceUsers();
                    if (selectedServer != null) {
                        loadChannels(selectedServer.id());
                    }
                }
        ));

        addDrawableChild(new ButtonWidget(
                width / 2 - 40, actionY + 25, 80, 20,
                new LiteralText("Close"),
                button -> onClose()
        ));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        // Title
        drawCenteredText(matrices, textRenderer, title, width / 2, 15, 0xFFFFFF);

        // User info
        Models.User user = AuthManager.getInstance().getCurrentUser();
        if (user != null) {
            String displayName = user.nickname() != null ? user.nickname() : user.username();
            drawTextWithShadow(matrices, textRenderer, 
                    new LiteralText("User: " + displayName).formatted(Formatting.GREEN),
                    20, 15, 0xFFFFFF);
        }

        // Status message
        if (!statusMessage.isEmpty()) {
            drawCenteredText(matrices, textRenderer, 
                    new LiteralText(statusMessage).formatted(Formatting.YELLOW),
                    width / 2, height - 90, 0xFFFFFF);
        }

        // Voice status
        VoiceManager voiceManager = VoiceManager.getInstance();
        if (voiceManager.isConnected()) {
            String voiceStatus = "Voice: " + voiceManager.getCurrentChannelName() +
                    (voiceManager.isMuted() ? " (Muted)" : "");
            drawTextWithShadow(matrices, textRenderer,
                    new LiteralText(voiceStatus).formatted(voiceManager.isMuted() ? Formatting.RED : Formatting.GREEN),
                    20, 35, 0xFFFFFF);
        }

        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String truncate(String str, int maxLen) {
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 2) + "..";
    }
}
