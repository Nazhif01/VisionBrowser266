package dev.nazhif.visionbrowser;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class VisionMusicHud {
    private static boolean visible = true;

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
        if (!visible || minecraft.player == null || minecraft.level == null || minecraft.gui.screen() != null) return;

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int width = 260;
        int height = 48;
        int x = (screenWidth - width) / 2;
        int y = 12;

        // Compact Dynamic-Island-inspired media capsule. Playback integration is a later milestone.
        roundedPanel(graphics, x - 2, y - 2, width + 4, height + 4, 25, 0x553D9ACB);
        roundedPanel(graphics, x, y, width, height, 23, 0xE9151D2A);
        roundedPanel(graphics, x + 1, y + 1, width - 2, height - 2, 22, 0xD92A3545);
        roundedPanel(graphics, x + 10, y + 8, 32, 32, 9, 0xFF354D69);
        graphics.text(minecraft.font, "♫", x + 21, y + 18, 0xFFBCEBFF, false);
        graphics.text(minecraft.font, "VISION MUSIC", x + 51, y + 8, 0xFFB8DDF7, false);
        graphics.text(minecraft.font, "Waiting for media session", x + 51, y + 22, 0xFFEAF2FA, false);
        graphics.text(minecraft.font, "◀", x + 177, y + 17, 0xFFDCEBFA, false);
        graphics.text(minecraft.font, "▶", x + 203, y + 17, 0xFFFFFFFF, false);
        graphics.text(minecraft.font, "▶|", x + 228, y + 17, 0xFFDCEBFA, false);
        graphics.fill(x + 51, y + 39, x + width - 12, y + 40, 0x668CAAC8);
    }
}
