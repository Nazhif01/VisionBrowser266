package dev.nazhif.visionbrowser;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class VisionMusicHud {
    private static boolean visible = true;
    private static final int ISLAND_WIDTH = 224;
    private static final int ISLAND_HEIGHT = 40;
    private static final int TOP = 8;

    private VisionMusicHud() { }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("visionbrowser", "music_island"),
                VisionMusicHud::render
        );
    }

    public static void toggle() { visible = !visible; }
    public static boolean isVisible() { return visible; }

    public static boolean handleClick(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;
        Minecraft minecraft = Minecraft.getInstance();
        int x = (minecraft.getWindow().getGuiScaledWidth() - ISLAND_WIDTH) / 2;
        int y = TOP;
        if (mouseY < y || mouseY >= y + ISLAND_HEIGHT || mouseX < x || mouseX >= x + ISLAND_WIDTH) return false;

        if (mouseX >= x + 151 && mouseX < x + 174) VisionMediaService.previous();
        else if (mouseX >= x + 177 && mouseX < x + 199) VisionMediaService.togglePlayback();
        else if (mouseX >= x + 201 && mouseX < x + 223) VisionMediaService.next();
        return true;
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

    private static String shorten(String value, int max) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!visible || minecraft.player == null || minecraft.level == null) return;

        int x = (minecraft.getWindow().getGuiScaledWidth() - ISLAND_WIDTH) / 2;
        int y = TOP;
        roundedPanel(graphics, x - 2, y - 2, ISLAND_WIDTH + 4, ISLAND_HEIGHT + 4, 22, 0x553D9ACB);
        roundedPanel(graphics, x, y, ISLAND_WIDTH, ISLAND_HEIGHT, 20, 0xE9151D2A);
        roundedPanel(graphics, x + 1, y + 1, ISLAND_WIDTH - 2, ISLAND_HEIGHT - 2, 19, 0xD92A3545);
        roundedPanel(graphics, x + 7, y + 8, 24, 24, 8, 0xFF354D69);
        graphics.text(minecraft.font, "♫", x + 15, y + 13, 0xFFBCEBFF, false);

        String title = VisionMediaService.title();
        String artist = VisionMediaService.artist();
        if (title.isBlank()) {
            graphics.text(minecraft.font, "VISION MUSIC", x + 38, y + 8, 0xFFB8DDF7, false);
            graphics.text(minecraft.font, VisionMediaService.isConnected() ? "No media playing" : "Media service unavailable",
                    x + 38, y + 22, 0xFFEAF2FA, false);
        } else {
            graphics.text(minecraft.font, shorten(title, 17), x + 38, y + 7, 0xFFFFFFFF, false);
            graphics.text(minecraft.font, shorten(artist, 18), x + 38, y + 21, 0xFFB8DDF7, false);
        }

        graphics.text(minecraft.font, "◀", x + 157, y + 14, 0xFFDCEBFA, false);
        graphics.text(minecraft.font, VisionMediaService.isPlaying() ? "Ⅱ" : "▶", x + 182, y + 14, 0xFFFFFFFF, false);
        graphics.text(minecraft.font, "▶|", x + 205, y + 14, 0xFFDCEBFA, false);
    }
}
