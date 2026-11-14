package dev.nazhif.visionbrowser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.List;

/** Animated karaoke-style HUD renderer for the independent VisionBrowser lyrics engine. */
final class LyricsHud {
    static final String[] POSITIONS = {"Above crosshair", "Center", "Top", "Bottom", "Top left", "Top right"};
    private static final int PANEL_W = 340;
    private static final int PANEL_H = 94;
    private static final int PAD = 14;
    private static String lastKey = "";
    private static long changedAt;

    private LyricsHud() { }

    static void render(GuiGraphicsExtractor g, Minecraft mc, List<FloatingLyrics.Line> lines,
                       int active, double positionSeconds, String songKey) {
        if (lines.isEmpty() || active < 0 || active >= lines.size()) return;
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();

        String key = songKey + "#" + active;
        if (!key.equals(lastKey)) {
            lastKey = key;
            changedAt = now;
        }
        float transition = Math.min(1f, (now - changedAt) / 260f);
        float eased = 1f - (1f - transition) * (1f - transition);
        int x = (sw - PANEL_W) / 2;
        int y = Math.max(28, Math.min(sh - PANEL_H - 42, (int) (sh * 0.66f) - PANEL_H / 2));
        int accent = IslandArt.accent() & 0xFFFFFF;

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate(x, y + Math.round((1f - eased) * 7f));
            DynamicIsland.roundedRect(g, 0, 0, PANEL_W, PANEL_H, 13, 0xA90A0C14);
            DynamicIsland.roundedRect(g, 1, 1, PANEL_W - 2, 1, 1, 0x24FFFFFF);

            String title = FloatingLyrics.song();
            String artist = FloatingLyrics.artist();
            String heading = artist.isBlank() ? title : title + "  •  " + artist;
            g.text(font, DynamicIsland.ellipsize(font, heading, PANEL_W - PAD * 2),
                    PAD, 9, 0xC8DCE3F4, false);
            g.text(font, "LYRICS  /  LIVE SYNC", PANEL_W - PAD - font.width("LYRICS  /  LIVE SYNC"),
                    9, 0x807F91AA, false);

            FloatingLyrics.Line current = lines.get(active);
            int activeWord = FloatingLyrics.activeWord(active, positionSeconds);
            drawKaraokeLine(g, font, current, activeWord, 28, PANEL_W - PAD * 2, 1.22f, accent, 0xFFFFFFFF);

            int nextY = 59;
            for (int row = 1; row <= 2 && active + row < lines.size(); row++) {
                FloatingLyrics.Line upcoming = lines.get(active + row);
                String text = DynamicIsland.ellipsize(font, upcoming.text(), PANEL_W - PAD * 2);
                int alpha = row == 1 ? 0xB7 : 0x78;
                g.text(font, text, PAD, nextY + (row - 1) * 13, (alpha << 24) | 0xC7D0E0, false);
            }

            double duration = Math.max(0.08, current.end() - current.time());
            float progress = (float) Math.max(0, Math.min(1, (positionSeconds - current.time()) / duration));
            DynamicIsland.roundedRect(g, PAD, PANEL_H - 8, PANEL_W - PAD * 2, 2, 1, 0x35FFFFFF);
            DynamicIsland.roundedRect(g, PAD, PANEL_H - 8,
                    Math.max(2, Math.round((PANEL_W - PAD * 2) * progress)), 2, 1, 0xE0FFFFFF);
        } finally {
            pose.popMatrix();
        }
    }

    private static void drawKaraokeLine(GuiGraphicsExtractor g, Font font, FloatingLyrics.Line line,
                                         int activeWord, int y, int maxWidth, float scale,
                                         int accent, int fallbackColor) {
        List<LyricsEngine.Word> words = line.words();
        if (words.isEmpty()) {
            String text = DynamicIsland.ellipsize(font, line.text(), maxWidth);
            g.text(font, text, (PANEL_W - font.width(text)) / 2, y, fallbackColor, true);
            return;
        }

        int totalWidth = 0;
        for (LyricsEngine.Word word : words) totalWidth += font.width(word.text());
        float fit = totalWidth <= 0 ? 1f : Math.min(1f, maxWidth / (float) totalWidth);
        float finalScale = scale * fit;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            int renderedWidth = Math.round(totalWidth * finalScale);
            pose.translate((PANEL_W - renderedWidth) / 2f, y);
            pose.scale(finalScale, 1f);
            int cursor = 0;
            for (int i = 0; i < words.size(); i++) {
                LyricsEngine.Word word = words.get(i);
                int color;
                if (activeWord < 0) color = 0xFFB7C0D0;
                else if (i < activeWord) color = 0xFF000000 | accent;
                else if (i == activeWord) color = 0xFFFFFFFF;
                else color = 0xFF858D9E;
                if (i == activeWord) {
                    String value = "\u00a7l" + word.text();
                    g.text(font, value, cursor, 0, color, true);
                } else {
                    g.text(font, word.text(), cursor, 0, color, i < activeWord);
                }
                cursor += font.width(word.text());
            }
        } finally {
            pose.popMatrix();
        }
    }
}
