package cn.net.rms.rmsChatroom.client.gui;

import cn.net.rms.rmsChatroom.client.api.ApiClient;
import cn.net.rms.rmsChatroom.client.api.Models;
import cn.net.rms.rmsChatroom.client.auth.AuthManager;
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
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 220;

    private List<Models.Server> servers = new ArrayList<>();
    private List<Models.Channel> channels = new ArrayList<>();
    private Map<Long, List<Models.VoiceUser>> voiceUsers = Map.of();

    private Models.Server selectedServer = null;
    private Models.Channel selectedChannel = null;

    private int scrollOffset = 0;
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
            // Check if we have a token to validate
            if (cn.net.rms.rmsChatroom.client.config.ModConfig.getInstance().isLoggedIn()) {
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

        // Close button
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

        // Load servers
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

        int panelX = (width - PANEL_WIDTH) / 2;
        int panelY = (height - PANEL_HEIGHT) / 2;

        VoiceManager voiceManager = VoiceManager.getInstance();
        Models.User currentUser = AuthManager.getInstance().getCurrentUser();

        // User info and logout button
        if (currentUser != null) {
            addDrawableChild(new ButtonWidget(
                    panelX + PANEL_WIDTH - 60, panelY + 5, 55, 15,
                    new LiteralText("Logout"),
                    button -> {
                        voiceManager.disconnect();
                        AuthManager.getInstance().logout();
                        initLoginUI();
                    }
            ));
        }

        // Server selector (simple buttons for now)
        int serverY = panelY + 25;
        int serverBtnWidth = 60;
        int serverX = panelX + 5;
        for (int i = 0; i < Math.min(servers.size(), 4); i++) {
            Models.Server server = servers.get(i);
            boolean selected = selectedServer != null && selectedServer.id() == server.id();
            addDrawableChild(new ButtonWidget(
                    serverX + i * (serverBtnWidth + 5), serverY, serverBtnWidth, 20,
                    new LiteralText(truncate(server.name(), 8)),
                    button -> {
                        selectedServer = server;
                        loadChannels(server.id());
                    }
            ));
        }

        // Channel list
        int channelY = panelY + 55;
        int channelHeight = 20;
        int maxChannels = 5;
        for (int i = 0; i < Math.min(channels.size(), maxChannels); i++) {
            Models.Channel channel = channels.get(i);
            List<Models.VoiceUser> users = voiceUsers.getOrDefault(channel.id(), List.of());
            int userCount = users.size();

            String label = channel.name() + (userCount > 0 ? " (" + userCount + ")" : "");
            boolean isCurrentChannel = voiceManager.getCurrentChannelId() == channel.id();

            ButtonWidget btn = new ButtonWidget(
                    panelX + 5, channelY + i * (channelHeight + 2), PANEL_WIDTH - 10, channelHeight,
                    new LiteralText((isCurrentChannel ? "> " : "") + label),
                    button -> {
                        selectedChannel = channel;
                    }
            );
            addDrawableChild(btn);
        }

        // Action buttons
        int actionY = panelY + PANEL_HEIGHT - 60;

        // Join/Leave button
        if (voiceManager.isConnected()) {
            addDrawableChild(new ButtonWidget(
                    panelX + 5, actionY, 90, 20,
                    new LiteralText("Disconnect"),
                    button -> {
                        voiceManager.disconnect();
                        rebuildUI();
                    }
            ));

            // Mute button
            boolean muted = voiceManager.isMuted();
            addDrawableChild(new ButtonWidget(
                    panelX + 100, actionY, 90, 20,
                    new LiteralText(muted ? "Unmute" : "Mute"),
                    button -> {
                        voiceManager.toggleMute();
                        rebuildUI();
                    }
            ));
        } else if (selectedChannel != null) {
            addDrawableChild(new ButtonWidget(
                    panelX + 5, actionY, 140, 20,
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

        // Refresh button
        addDrawableChild(new ButtonWidget(
                panelX + PANEL_WIDTH - 75, actionY, 70, 20,
                new LiteralText("Refresh"),
                button -> {
                    loadVoiceUsers();
                    if (selectedServer != null) {
                        loadChannels(selectedServer.id());
                    }
                }
        ));

        // Close button
        addDrawableChild(new ButtonWidget(
                panelX + (PANEL_WIDTH - 80) / 2, actionY + 25, 80, 20,
                new LiteralText("Close"),
                button -> onClose()
        ));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        int panelX = (width - PANEL_WIDTH) / 2;
        int panelY = (height - PANEL_HEIGHT) / 2;

        // Draw panel background
        fill(matrices, panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xDD2F3136);
        // Border
        fill(matrices, panelX, panelY, panelX + PANEL_WIDTH, panelY + 2, 0xFF5865F2);

        // Title
        drawCenteredText(matrices, textRenderer, title, width / 2, panelY + 8, 0xFFFFFF);

        // User info
        Models.User user = AuthManager.getInstance().getCurrentUser();
        if (user != null) {
            String displayName = user.nickname() != null ? user.nickname() : user.username();
            drawTextWithShadow(matrices, textRenderer, new LiteralText(displayName).formatted(Formatting.GREEN),
                    panelX + 5, panelY + 8, 0xFFFFFF);
        }

        // Status message
        if (!statusMessage.isEmpty()) {
            drawCenteredText(matrices, textRenderer, new LiteralText(statusMessage).formatted(Formatting.YELLOW),
                    width / 2, panelY + PANEL_HEIGHT - 85, 0xFFFFFF);
        }

        // Voice status
        VoiceManager voiceManager = VoiceManager.getInstance();
        if (voiceManager.isConnected()) {
            String voiceStatus = "Voice: " + voiceManager.getCurrentChannelName() +
                    (voiceManager.isMuted() ? " (Muted)" : "");
            drawTextWithShadow(matrices, textRenderer,
                    new LiteralText(voiceStatus).formatted(voiceManager.isMuted() ? Formatting.RED : Formatting.GREEN),
                    panelX + 5, panelY + PANEL_HEIGHT - 85, 0xFFFFFF);
        }

        // Selected channel users
        if (selectedChannel != null && !voiceManager.isConnected()) {
            List<Models.VoiceUser> users = voiceUsers.getOrDefault(selectedChannel.id(), List.of());
            if (!users.isEmpty()) {
                int userListY = panelY + 165;
                drawTextWithShadow(matrices, textRenderer,
                        new LiteralText("In channel:").formatted(Formatting.GRAY),
                        panelX + 5, userListY - 12, 0xFFFFFF);
                for (int i = 0; i < Math.min(users.size(), 3); i++) {
                    Models.VoiceUser u = users.get(i);
                    drawTextWithShadow(matrices, textRenderer,
                            new LiteralText("  " + u.name()),
                            panelX + 5, userListY + i * 10, 0xAAAAAA);
                }
            }
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
