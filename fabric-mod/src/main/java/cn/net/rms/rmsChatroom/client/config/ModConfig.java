package cn.net.rms.rmsChatroom.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("rms-chatroom.json");

    private static ModConfig INSTANCE;

    // Server settings
    public String serverUrl = "https://preview-chatroom.rms.net.cn";

    // Auth
    public String token = "";

    // Voice settings
    public long defaultChannelId = -1;
    public boolean autoJoinDefaultChannel = false;

    // Keybinds (stored as key codes, -1 means not set)
    public int openGuiModifier = 82;  // R key
    public int openGuiKey = 77;       // M key
    public int toggleMicModifier = 82; // R key
    public int toggleMicKey = 73;      // I key

    // Audio settings
    public boolean pushToTalk = false;
    public int pushToTalkKey = -1;

    // Per-participant volume settings (participantId -> volume 0.0-2.0)
    public Map<String, Float> participantVolumes = new HashMap<>();

    public static ModConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static ModConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                ModConfig config = GSON.fromJson(json, ModConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        ModConfig config = new ModConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isLoggedIn() {
        return token != null && !token.isEmpty();
    }

    public void setToken(String token) {
        this.token = token;
        save();
    }

    public void logout() {
        this.token = "";
        save();
    }

    public void setParticipantVolume(String participantId, float volume) {
        participantVolumes.put(participantId, volume);
        save();
    }

    public float getParticipantVolume(String participantId) {
        return participantVolumes.getOrDefault(participantId, 1.0f);
    }
}
