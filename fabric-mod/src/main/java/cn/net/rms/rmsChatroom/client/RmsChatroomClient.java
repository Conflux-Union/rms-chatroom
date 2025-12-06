package cn.net.rms.rmsChatroom.client;

import cn.net.rms.rmsChatroom.client.auth.AuthManager;
import cn.net.rms.rmsChatroom.client.config.ModConfig;
import cn.net.rms.rmsChatroom.client.keybind.KeyBindings;
import cn.net.rms.rmsChatroom.client.voice.VoiceManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RmsChatroomClient implements ClientModInitializer {
    public static final String MOD_ID = "rms-chatroom";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing RMS Chatroom Mod");

        // Load config
        ModConfig.getInstance();

        // Register keybindings
        KeyBindings.register();

        // Validate token on startup if present
        if (ModConfig.getInstance().isLoggedIn()) {
            AuthManager.getInstance().validateToken().thenAccept(valid -> {
                if (valid) {
                    LOGGER.info("Session validated successfully");
                } else {
                    LOGGER.warn("Session invalid, user needs to re-login");
                }
            });
        }

        // Cleanup on client stop
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            VoiceManager.getInstance().disconnect();
        });

        LOGGER.info("RMS Chatroom Mod initialized. Press R+M to open voice panel.");
    }
}
