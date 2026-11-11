package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Transparent screen that frees the cursor so the Dynamic Island can be clicked and dragged without opening the browser. */
public final class IslandScreen extends Screen {
    /** Minecraft 26.3 uses SDL button numbering: left = 1. */
    private static final int MOUSE_LEFT = 1;
    private final Screen parentScreen;
    private long lastVolumeSend;

    public IslandScreen(Screen parentScreen) {
        super(Component.literal("Vision Island"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        minecraft.mouseHandler.releaseMouse();
        DynamicIsland.setTarget(1f);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately not calling super: no dimmed background, no widgets; the world stays visible.
        DynamicIsland.draw(graphics, minecraft, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != MOUSE_LEFT) return false;
        double x = event.x(), y = event.y();
        DynamicIsland.Hit hit = DynamicIsland.hitTest(width, x, y);
        switch (hit) {
            case PLAY -> VisionMediaBridge.togglePlay();
            case PREV -> VisionMediaBridge.previous();
            case NEXT -> VisionMediaBridge.next();
            case MUTE -> VisionMediaBridge.toggleMute();
            case SEEK -> {
                DynamicIsland.draggingSeek = true;
                DynamicIsland.seekFrac = DynamicIsland.seekFracAt(width, x);
            }
            case VOLUME -> {
                DynamicIsland.draggingVolume = true;
                DynamicIsland.volumeFrac = DynamicIsland.volumeFracAt(width, x);
                VisionMediaBridge.setVolume(DynamicIsland.volumeFrac);
                lastVolumeSend = System.currentTimeMillis();
            }
            case ISLAND -> { }
            case NONE -> onClose();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (DynamicIsland.draggingSeek) {
            DynamicIsland.seekFrac = DynamicIsland.seekFracAt(width, event.x());
            return true;
        }
        if (DynamicIsland.draggingVolume) {
            DynamicIsland.volumeFrac = DynamicIsland.volumeFracAt(width, event.x());
            long now = System.currentTimeMillis();
            if (now - lastVolumeSend >= 60) {
                VisionMediaBridge.setVolume(DynamicIsland.volumeFrac);
                lastVolumeSend = now;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (DynamicIsland.draggingSeek) {
            DynamicIsland.draggingSeek = false;
            VisionMediaBridge.seek(DynamicIsland.seekFrac * VisionMediaBridge.durationSeconds());
            return true;
        }
        if (DynamicIsland.draggingVolume) {
            DynamicIsland.draggingVolume = false;
            VisionMediaBridge.setVolume(DynamicIsland.volumeFrac);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_I) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        DynamicIsland.draggingSeek = false;
        DynamicIsland.draggingVolume = false;
        DynamicIsland.setTarget(0f);
    }
}
