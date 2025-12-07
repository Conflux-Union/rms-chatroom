package cn.net.rms.rmsChatroom.client.gui;

import cn.net.rms.rmsChatroom.client.config.ModConfig;
import cn.net.rms.rmsChatroom.client.voice.VoiceManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;

public class VolumeScreen extends Screen {
    private final Screen parent;
    private final String participantId;
    private final String participantName;
    
    private int currentVolumePercent;
    private VolumeSlider volumeSlider;

    public VolumeScreen(Screen parent, VoiceManager.ParticipantInfo participant) {
        super(new LiteralText("Volume: " + participant.name()));
        this.parent = parent;
        this.participantId = participant.identity();
        this.participantName = participant.name();
        this.currentVolumePercent = (int) (VoiceManager.getInstance().getParticipantVolume(participantId) * 100);
    }

    @Override
    protected void init() {
        super.init();
        
        int centerX = width / 2;
        int centerY = height / 2;

        // Volume slider
        volumeSlider = new VolumeSlider(centerX - 100, centerY - 30, 200, 20, currentVolumePercent);
        addDrawableChild(volumeSlider);

        // Preset buttons
        int btnWidth = 45;
        int btnSpacing = 5;
        int totalWidth = btnWidth * 4 + btnSpacing * 3;
        int startX = centerX - totalWidth / 2;
        int presetY = centerY + 10;

        addDrawableChild(new ButtonWidget(startX, presetY, btnWidth, 20, new LiteralText("0%"), 
                button -> setVolume(0)));
        addDrawableChild(new ButtonWidget(startX + btnWidth + btnSpacing, presetY, btnWidth, 20, new LiteralText("50%"), 
                button -> setVolume(50)));
        addDrawableChild(new ButtonWidget(startX + (btnWidth + btnSpacing) * 2, presetY, btnWidth, 20, new LiteralText("100%"), 
                button -> setVolume(100)));
        addDrawableChild(new ButtonWidget(startX + (btnWidth + btnSpacing) * 3, presetY, btnWidth, 20, new LiteralText("200%"), 
                button -> setVolume(200)));

        // Done button
        addDrawableChild(new ButtonWidget(centerX - 50, centerY + 50, 100, 20, new LiteralText("Done"),
                button -> onClose()));
    }

    private void setVolume(int percent) {
        currentVolumePercent = percent;
        float volume = percent / 100f;
        VoiceManager.getInstance().setParticipantVolume(participantId, volume);
        ModConfig.getInstance().setParticipantVolume(participantId, volume);
        // Update slider without rebuilding UI
        if (volumeSlider != null) {
            volumeSlider.setValueFromPercent(percent);
        }
    }

    private class VolumeSlider extends SliderWidget {
        public VolumeSlider(int x, int y, int width, int height, int initialPercent) {
            super(x, y, width, height, new LiteralText("Volume: " + initialPercent + "%"), initialPercent / 200.0);
        }

        @Override
        protected void updateMessage() {
            int percent = (int) (this.value * 200);
            this.setMessage(new LiteralText("Volume: " + percent + "%"));
        }

        @Override
        protected void applyValue() {
            currentVolumePercent = (int) (this.value * 200);
            float volume = currentVolumePercent / 100f;
            VoiceManager.getInstance().setParticipantVolume(participantId, volume);
            ModConfig.getInstance().setParticipantVolume(participantId, volume);
        }

        public void setValueFromPercent(int percent) {
            this.value = percent / 200.0;
            this.updateMessage();
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        // Draw solid background
        renderBackground(matrices);
        
        // Title
        drawCenteredText(matrices, textRenderer, title, width / 2, height / 2 - 60, 0xFFFFFF);

        // Warning for >100%
        if (currentVolumePercent > 100) {
            drawCenteredText(matrices, textRenderer, 
                    new LiteralText("Warning: Volume boost may cause distortion").formatted(Formatting.YELLOW),
                    width / 2, height / 2 - 45, 0xFFFF00);
        }

        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        client.setScreen(parent);
    }
}
