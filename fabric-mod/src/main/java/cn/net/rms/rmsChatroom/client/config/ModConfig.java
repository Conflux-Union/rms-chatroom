package cn.net.rms.rmsChatroom.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("rms-chatroom.json");

    private static ModConfig INSTANCE;

    // Server settings
    public String serverUrl = "https://preview-chatroom.rms.net.cn";
    public String ssoUrl = "https://sso.rms.net.cn";

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
}
