package dev.nazhif.visionbrowser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * Lyrics as a HUD block: the active line highlighted, the next lines dimmer, with a short slide/fade when the line changes and a
 * countdown during long instrumental gaps. Layout ideas follow LyricsPlus by FRK (MIT licence); this is an independent implementation.
 * Positions: 0 above crosshair, 1 centre, 2 top, 3 bottom, 4 top-left, 5 top-right.
 */
final class LyricsHud {
    static final String[] POSITIONS = {"Above crosshair", "Center", "Top", "Bottom", "Top left", "Top right"};
    private static final int PANEL_W = 250, LINE_H = 13, PAD = 4, MARGIN = 10;

    private static String lastKey = "";
    private static long changedAt;

    private LyricsHud() { }

    static void render(GuiGraphicsExtractor g, Minecraft mc, List<FloatingLyrics.Line> lines, int active, double positionSeconds, String songKey) {
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();
        FloatingLyrics.Line cur = lines.get(active);
        FloatingLyrics.Line next = active + 1 < lines.size() ? lines.get(active + 1) : null;

        String key = songKey + "#" + active;
        if (!key.equals(lastKey)) { lastKey = key; changedAt = now; }
        float t = Math.min(1f, (now - changedAt) / 200f);              // 200 ms line-change transition
        float ease = 1f - (1f - t) * (1f - t);

        // active text, or an instrumental countdown when the singer is silent for a while
        String activeText = cur.text();
        double sinceCur = positionSeconds - cur.time();
        if (next != null && sinceCur > 6 && next.time() - positionSeconds > 3) {
            activeText = "\u266b " + Math.max(1, (long) (next.time() - positionSeconds));
        }

        int n = VisionSettings.hudLines;
        float scale = VisionSettings.lyricsScale;
        int shownLines = 1;
        for (int i = 1; i < n && active + i < lines.size(); i++) shownLines++;
        int panelH = shownLines * LINE_H + PAD * 2;
        int w = Math.round(PANEL_W * scale), h = Math.round(panelH * scale);
        int x, y;
        switch (VisionSettings.hudPosition) {
            case 1 -> { x = (sw - w) / 2; y = (sh - h) / 2; }
            case 2 -> { x = (sw - w) / 2; y = MARGIN; }
            case 3 -> { x = (sw - w) / 2; y = sh - h - 72; }
            case 4 -> { x = MARGIN; y = MARGIN + 40; }
            case 5 -> { x = sw - w - MARGIN; y = MARGIN + 40; }
            default -> { x = (sw - w) / 2; y = sh / 2 - 56 - h; }
        }

        int accent = IslandArt.accent() & 0xFFFFFF;
        int activeRgb = VisionSettings.lyricsAlbumColor ? brighten(accent) : 0xFFFFFF;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) x, (float) y);
            pose.scale(scale, scale);
            if (VisionSettings.hudPanel) DynamicIsland.roundedRect(g, 0, 0, PANEL_W, panelH, 8, 0x8C101018);
            int slide = Math.round((1f - ease) * 4f);
            for (int i = 0; i < shownLines; i++) {
                String raw = i == 0 ? activeText : lines.get(active + i).text();
                if (raw.isBlank()) raw = "\u266b";
                boolean isActive = i == 0;
                int alpha = isActive ? Math.round(80 + 175 * ease) : Math.round((200 - i * 38) * (0.55f + 0.45f * ease));
                alpha = Math.max(40, Math.min(255, alpha));
                String text = DynamicIsland.ellipsize(font, raw, PANEL_W - 12);
                if (isActive) text = "\u00a7l" + text;
                int color = (alpha << 24) | (isActive ? activeRgb : 0xD6DAE8);
                int tx = (PANEL_W - font.width(text)) / 2;
                int ty = PAD + i * LINE_H + (isActive ? 0 : slide - 0);
                g.text(font, text, tx, ty, color, true);
            }
        } finally {
            pose.popMatrix();
        }
    }

    private static int brighten(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int peak = Math.max(r, Math.max(g, b));
        if (peak < 190 && peak > 0) { float k = 190f / peak; r = Math.min(255, Math.round(r * k)); g = Math.min(255, Math.round(g * k)); b = Math.min(255, Math.round(b * k)); }
        return (r << 16) | (g << 8) | b;
    }
}