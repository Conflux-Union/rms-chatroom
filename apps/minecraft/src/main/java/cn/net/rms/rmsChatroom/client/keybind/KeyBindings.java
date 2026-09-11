package cn.net.rms.rmsChatroom.client.keybind;

import cn.net.rms.rmsChatroom.client.config.ModConfig;
import cn.net.rms.rmsChatroom.client.gui.VoiceScreen;
import cn.net.rms.rmsChatroom.client.voice.VoiceManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public class KeyBindings {
    private static KeyBinding openGuiKey;
    private static KeyBinding toggleMicKey;

    private static boolean rKeyHeld = false;
    private static boolean mKeyPressed = false;
    private static boolean iKeyPressed = false;

    public static void register() {
        // Register key bindings (these are for display in controls menu)
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rms-chatroom.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.rms-chatroom"
        ));

        toggleMicKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rms-chatroom.toggle_mic",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                "category.rms-chatroom"
        ));

        // Register tick event for combo key detection
        ClientTickEvents.END_CLIENT_TICK.register(KeyBindings::onClientTick);
    }

    private static void onClientTick(MinecraftClient client) {
        if (client.player == null) return;

        long window = client.getWindow().getHandle();
        ModConfig config = ModConfig.getInstance();

        // Check R key state
        boolean rPressed = InputUtil.isKeyPressed(window, config.openGuiModifier);

        // Check M key state (for R+M combo)
        boolean mPressed = InputUtil.isKeyPressed(window, config.openGuiKey);

        // Check I key state (for R+I combo)
        boolean iPressed = InputUtil.isKeyPressed(window, config.toggleMicKey);

        // R+M: Open GUI
        if (rPressed && mPressed && !mKeyPressed && client.currentScreen == null) {
            mKeyPressed = true;
            client.setScreen(new VoiceScreen());
        } else if (!mPressed) {
            mKeyPressed = false;
        }

        // R+I: Toggle mic
        if (rPressed && iPressed && !iKeyPressed) {
            iKeyPressed = true;
            VoiceManager voiceManager = VoiceManager.getInstance();
            if (voiceManager.isConnected()) {
                voiceManager.toggleMute();
                boolean muted = voiceManager.isMuted();
                client.player.sendMessage(
                        new LiteralText("[RMS Voice] Microphone: " + (muted ? "Muted" : "Unmuted"))
                                .formatted(muted ? Formatting.RED : Formatting.GREEN),
                        true
                );
            } else {
                client.player.sendMessage(
                        new LiteralText("[RMS Voice] Not connected to voice channel")
                                .formatted(Formatting.YELLOW),
                        true
                );
            }
        } else if (!iPressed) {
            iKeyPressed = false;
        }

        rKeyHeld = rPressed;
    }

    public static KeyBinding getOpenGuiKey() {
        return openGuiKey;
    }

    public static KeyBinding getToggleMicKey() {
        return toggleMicKey;
    }
}
