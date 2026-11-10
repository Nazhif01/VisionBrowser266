package dev.nazhif.visionbrowser;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.awt.Robot;
import java.awt.AWTException;
import java.awt.event.KeyEvent;

public final class VisionMusicHud {
    private static boolean visible = true;
    private static final int ISLAND_WIDTH = 196;
    private static final int ISLAND_HEIGHT = 38;
    private static final int TOP = 8;

    private VisionMusicHud() { }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("visionbrowser", "music_island"),
                VisionMusicHud::render
        );
    }

    public static void toggle() {
        visible = !visible;
    }

    public static boolean isVisible() {
        return visible;
    }

    public static boolean handleClick(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;
        Minecraft minecraft = Minecraft.getInstance();
        int x = (minecraft.getWindow().getGuiScaledWidth() - ISLAND_WIDTH) / 2;
        int y = TOP;
        if (mouseY < y || mouseY >= y + ISLAND_HEIGHT || mouseX < x || mouseX >= x + ISLAND_WIDTH) return false;

        if (mouseX >= x + 118 && mouseX < x + 143) {
            sendMediaKey(KeyEvent.VK_MEDIA_PREVIOUS_TRACK);
        } else if (mouseX >= x + 145 && mouseX < x + 169) {
            sendMediaKey(KeyEvent.VK_MEDIA_PLAY_PAUSE);
        } else if (mouseX >= x + 171 && mouseX < x + 194) {
            sendMediaKey(KeyEvent.VK_MEDIA_NEXT_TRACK);
        }
        return true;
    }

    private static void sendMediaKey(int keyCode) {
        try {
            Robot robot = new Robot();
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
        } catch (AWTException | IllegalArgumentException | SecurityException ignored) {
            // Media-key support depends on the operating system and active media player.
        }
    }

    private static void roundedPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius, int color) {
        for (int row = 0; row < h; row++) {
            int inset = 0;
            if (row < radius) {
                double dy = radius - row - 0.5;
                inset = radius - (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            } else if (row >= h - radius) {
                double dy = row - (h - radius) + 0.5;
                inset = radius - (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            }
            graphics.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
        }
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        Minecraft minecraft = Minecraft.getInstance();
        // Keep the island visible over chat and other screens; it is part of the HUD.
        if (!visible || minecraft.player == null || minecraft.level == null) return;

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int x = (screenWidth - ISLAND_WIDTH) / 2;
        int y = TOP;
        int width = ISLAND_WIDTH;
        int height = ISLAND_HEIGHT;

        roundedPanel(graphics, x - 2, y - 2, width + 4, height + 4, 21, 0x553D9ACB);
        roundedPanel(graphics, x, y, width, height, 19, 0xE9151D2A);
        roundedPanel(graphics, x + 1, y + 1, width - 2, height - 2, 18, 0xD92A3545);
        roundedPanel(graphics, x + 7, y + 7, 24, 24, 8, 0xFF354D69);
        graphics.text(minecraft.font, "♫", x + 15, y + 12, 0xFFBCEBFF, false);
        graphics.text(minecraft.font, "MEDIA CONTROLS", x + 37, y + 7, 0xFFB8DDF7, false);
        graphics.text(minecraft.font, "System playback", x + 37, y + 20, 0xFFEAF2FA, false);
        graphics.text(minecraft.font, "◀", x + 124, y + 13, 0xFFDCEBFA, false);
        graphics.text(minecraft.font, "▶", x + 150, y + 13, 0xFFFFFFFF, false);
        graphics.text(minecraft.font, "▶|", x + 174, y + 13, 0xFFDCEBFA, false);
    }
}
