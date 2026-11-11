package dev.nazhif.visionbrowser;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/** Apple-style Dynamic Island: compact HUD pill that morphs into a full control panel. */
final class DynamicIsland {
    static final int COMPACT_W = 176, COMPACT_H = 30, EXP_W = 292, EXP_H = 150, TOP = 6;

    enum Hit { NONE, ISLAND, PREV, PLAY, NEXT, SEEK, VOLUME, MUTE }

    // animation (spring) state, shared by the HUD and the interactive screen
    private static float expand, velocity, target;
    private static long lastFrame;
    // drag state, written by IslandScreen
    static boolean draggingSeek, draggingVolume;
    static float seekFrac, volumeFrac;

    private DynamicIsland() { }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("visionbrowser", "dynamic_island"),
                DynamicIsland::renderHud);
    }

    static void setTarget(float value) { target = value; }

    static boolean isExpanded() { return target > 0.5f && expand > 0.85f && VisionMediaBridge.hasMedia(); }

    private static void renderHud(GuiGraphicsExtractor g, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.gui.screen() instanceof IslandScreen) return; // the screen draws it itself
        if (!VisionMediaBridge.hasMedia() && target == 0f && expand < 0.01f) return;
        draw(g, mc, -1, -1);
    }

    private static void step() {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        float k = 280f, c = 26f; // slightly under-damped: a small, Dynamic-Island-like bounce
        velocity += (k * (target - expand) - c * velocity) * dt;
        expand += velocity * dt;
        if (Math.abs(target - expand) < 0.0005f && Math.abs(velocity) < 0.001f) {
            expand = target;
            velocity = 0f;
        }
    }

    // ------------------------------------------------------------------ geometry (shared with hit testing)

    private static int islandX(int sw, int w) { return (sw - w) / 2; }

    static Hit hitTest(int sw, double mx, double my) {
        if (!VisionMediaBridge.hasMedia() || !isExpanded()) {
            int w = VisionMediaBridge.hasMedia() ? COMPACT_W : 200;
            int x = islandX(sw, w);
            return inside(mx, my, x, TOP, w, COMPACT_H) ? Hit.ISLAND : Hit.NONE;
        }
        int x = islandX(sw, EXP_W), y = TOP, w = EXP_W;
        if (!inside(mx, my, x, y, EXP_W, EXP_H)) return Hit.NONE;
        int cx = x + w / 2, cy = y + 114;
        if (inside(mx, my, x + 12, y + 76, w - 24, 16)) return Hit.SEEK;
        if (inside(mx, my, cx - 56 - 16, cy - 16, 32, 32)) return Hit.PREV;
        if (inside(mx, my, cx - 18, cy - 18, 36, 36)) return Hit.PLAY;
        if (inside(mx, my, cx + 56 - 16, cy - 16, 32, 32)) return Hit.NEXT;
        if (inside(mx, my, x + 12, y + 128, 24, 18)) return Hit.MUTE;
        if (inside(mx, my, x + 40 - 4, y + 128, w - 40 - 12 + 4, 18)) return Hit.VOLUME;
        return Hit.ISLAND;
    }

    static float seekFracAt(int sw, double mx) {
        int bx = islandX(sw, EXP_W) + 16, bw = EXP_W - 32;
        return clamp01((float) ((mx - bx) / bw));
    }

    static float volumeFracAt(int sw, double mx) {
        int vx = islandX(sw, EXP_W) + 40, vw = EXP_W - 40 - 16;
        return clamp01((float) ((mx - vx) / vw));
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ drawing

    static void draw(GuiGraphicsExtractor g, Minecraft mc, int mouseX, int mouseY) {
        step();
        boolean media = VisionMediaBridge.hasMedia();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        float e = media ? Math.max(0f, Math.min(1.05f, expand)) : 0f;
        int baseW = media ? COMPACT_W : 200;
        int w = Math.round(lerp(baseW, EXP_W, e));
        int h = Math.round(lerp(COMPACT_H, EXP_H, e));
        int r = Math.max(6, Math.min(h / 2, Math.round(lerp(COMPACT_H / 2f, 28f, e))));
        int x = islandX(sw, w), y = TOP;

        int accent = media ? IslandArt.accent() : 0xFF7C8CFF;
        roundedRect(g, x - 5, y - 3, w + 10, h + 11, r + 5, withAlpha(accent, 0x0E));
        roundedRect(g, x - 3, y - 2, w + 6, h + 7, r + 3, withAlpha(accent, 0x18));
        roundedRect(g, x - 2, y - 1, w + 4, h + 5, r + 2, withAlpha(accent, 0x26));
        roundedRect(g, x, y, w, h, r, 0x70FFFFFF);
        roundedRect(g, x + 1, y + 1, w - 2, h - 2, Math.max(2, r - 1), 0xAA0C0C14);
        roundedRect(g, x + 2, y + 2, w - 4, Math.max(4, (h - 4) / 2), Math.max(2, r - 2), 0x16FFFFFF);
        roundedRect(g, x + Math.max(6, r / 2), y + 3, Math.max(8, r), 2, 1, 0x66FFFFFF);

        float compactAlpha = clamp01(1f - e * 2.2f);
        float expandedAlpha = clamp01((e - 0.55f) / 0.45f);

        // cover art morphs between the compact and expanded position/size
        int size = Math.round(lerp(20f, 56f, e));
        int ax = x + Math.round(lerp(6f, 16f, e)), ay = y + Math.round(lerp(5f, 16f, e));
        Identifier art = media ? IslandArt.current() : null;
        if (art != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, art, ax, ay, 0.0F, 0.0F, size, size, size, size);
        } else {
            roundedRect(g, ax, ay, size, size, Math.max(3, size / 5), 0xFF2B2B33);
        }

        if (compactAlpha > 0.04f) {
            int tx = x + 6 + 20 + 8;
            int maxW = w - (tx - x) - 8 - 14 - 6;
            String label = media ? VisionMediaBridge.title() + "  \u2022  " + VisionMediaBridge.artist() : VisionMediaBridge.status(); // status doubles as a diagnostic line
            g.text(font, ellipsize(font, label, maxW), tx, y + (h - 9) / 2, argb(compactAlpha, 0xFFFFFF), false);
            if (media) equalizer(g, x + w - 8 - 10, y + h / 2, compactAlpha, VisionMediaBridge.isPlaying());
        }

        if (media && expandedAlpha > 0.04f) {
            drawExpanded(g, font, x, y, w, expandedAlpha, mouseX, mouseY);
        }
    }

    private static void drawExpanded(GuiGraphicsExtractor g, Font font, int x, int y, int w, float a, int mx, int my) {
        int tx = x + 16 + 56 + 12, maxW = w - (tx - x) - 16;
        g.text(font, ellipsize(font, VisionMediaBridge.title(), maxW), tx, y + 22, argb(a, 0xFFFFFF), false);
        g.text(font, ellipsize(font, VisionMediaBridge.artist(), maxW), tx, y + 37, argb(a * 0.7f, 0xB4B4BE), false);
        String album = VisionMediaBridge.album();
        if (!album.isBlank()) g.text(font, ellipsize(font, album, maxW), tx, y + 51, argb(a * 0.5f, 0xB4B4BE), false);

        // progress bar
        double dur = VisionMediaBridge.durationSeconds();
        double pos = VisionMediaBridge.positionSeconds();
        float frac = draggingSeek ? seekFrac : (dur > 0 ? clamp01((float) (pos / dur)) : 0f);
        int bx = x + 16, bw = w - 32, by = y + 82;
        boolean hotSeek = draggingSeek || (mx >= x + 12 && mx < x + w - 12 && my >= y + 76 && my < y + 92);
        roundedRect(g, bx, by, bw, 4, 2, argb(a * 0.30f, 0xFFFFFF));
        int fw = Math.round(bw * frac);
        if (fw > 0) roundedRect(g, bx, by, Math.max(4, fw), 4, 2, argb(a, 0xFFFFFF));
        if (hotSeek) roundedRect(g, bx + fw - 4, by - 2, 8, 8, 4, argb(a, 0xFFFFFF));
        double shown = draggingSeek ? seekFrac * dur : pos;
        g.text(font, fmt(shown), bx, by + 8, argb(a * 0.6f, 0xB4B4BE), false);
        String total = fmt(dur);
        g.text(font, total, bx + bw - font.width(total), by + 8, argb(a * 0.6f, 0xB4B4BE), false);

        // transport controls
        int cx = x + w / 2, cy = y + 114;
        if (over(mx, my, cx - 72, cy - 16, 32, 32)) roundedRect(g, cx - 72, cy - 16, 32, 32, 16, argb(a * 0.14f, 0xFFFFFF));
        if (over(mx, my, cx - 18, cy - 18, 36, 36)) roundedRect(g, cx - 18, cy - 18, 36, 36, 18, argb(a * 0.14f, 0xFFFFFF));
        if (over(mx, my, cx + 40, cy - 16, 32, 32)) roundedRect(g, cx + 40, cy - 16, 32, 32, 16, argb(a * 0.14f, 0xFFFFFF));
        int white = argb(a, 0xFFFFFF);
        iconPrev(g, cx - 56, cy, 14, white);
        if (VisionMediaBridge.isPlaying()) iconPause(g, cx, cy, 18, white); else iconPlay(g, cx, cy, 18, white);
        iconNext(g, cx + 56, cy, 14, white);

        // volume
        boolean muted = VisionMediaBridge.isMuted();
        float vol = draggingVolume ? volumeFrac : (muted ? 0f : VisionMediaBridge.volume());
        int vy = y + 137, vx = x + 40, vw = w - 40 - 16;
        iconSpeaker(g, x + 16, vy, argb(a * (muted ? 0.5f : 1f), 0xFFFFFF), muted);
        roundedRect(g, vx, vy - 2, vw, 4, 2, argb(a * 0.30f, 0xFFFFFF));
        int vf = Math.round(vw * vol);
        if (vf > 0) roundedRect(g, vx, vy - 2, Math.max(4, vf), 4, 2, argb(a, 0xFFFFFF));
        boolean hotVol = draggingVolume || (mx >= vx - 4 && mx < vx + vw + 4 && my >= y + 128 && my < y + 146);
        if (hotVol) roundedRect(g, vx + vf - 4, vy - 4, 8, 8, 4, argb(a, 0xFFFFFF));
    }

    private static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ small drawing helpers

    static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        for (int row = 0; row < h; row++) {
            int inset = 0;
            if (row < r) {
                double dy = r - row - 0.5;
                inset = r - (int) Math.floor(Math.sqrt(Math.max(0, r * r - dy * dy)));
            } else if (row >= h - r) {
                double dy = row - (h - r) + 0.5;
                inset = r - (int) Math.floor(Math.sqrt(Math.max(0, r * r - dy * dy)));
            }
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
        }
    }

    private static void equalizer(GuiGraphicsExtractor g, int bx, int cy, float alpha, boolean playing) {
        long t = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            float hh = playing ? 4f + 10f * (float) Math.abs(Math.sin(t / 170.0 + i * 1.7)) : 4f;
            int h = Math.round(hh);
            g.fill(bx + i * 5, cy - h / 2, bx + i * 5 + 3, cy + (h + 1) / 2, argb(alpha, 0xFFFFFF));
        }
    }

    private static void iconPlay(GuiGraphicsExtractor g, int cx, int cy, int s, int color) {
        int w = Math.round(s * 0.8f);
        int x0 = cx - w / 2 + 1;
        for (int i = 0; i < w; i++) {
            int half = Math.max(1, Math.round((s / 2f) * (1f - i / (float) w)));
            g.fill(x0 + i, cy - half, x0 + i + 1, cy + half, color);
        }
    }

    private static void iconPause(GuiGraphicsExtractor g, int cx, int cy, int s, int color) {
        int bw = Math.max(3, s / 4);
        g.fill(cx - bw - 2, cy - s / 2, cx - 2, cy + s / 2, color);
        g.fill(cx + 2, cy - s / 2, cx + 2 + bw, cy + s / 2, color);
    }

    private static void iconNext(GuiGraphicsExtractor g, int cx, int cy, int s, int color) {
        int w = Math.round(s * 0.7f);
        int x0 = cx - w / 2 - 1;
        for (int i = 0; i < w; i++) {
            int half = Math.max(1, Math.round((s / 2f) * (1f - i / (float) w)));
            g.fill(x0 + i, cy - half, x0 + i + 1, cy + half, color);
        }
        g.fill(x0 + w + 1, cy - s / 2, x0 + w + 3, cy + s / 2, color);
    }

    private static void iconPrev(GuiGraphicsExtractor g, int cx, int cy, int s, int color) {
        int w = Math.round(s * 0.7f);
        int x0 = cx - w / 2 + 2;
        g.fill(x0 - 3, cy - s / 2, x0 - 1, cy + s / 2, color);
        for (int i = 0; i < w; i++) {
            int half = Math.max(1, Math.round((s / 2f) * (i / (float) w)));
            g.fill(x0 + i, cy - half, x0 + i + 1, cy + half, color);
        }
    }

    private static void iconSpeaker(GuiGraphicsExtractor g, int x, int cy, int color, boolean muted) {
        g.fill(x, cy - 2, x + 3, cy + 2, color);
        for (int i = 0; i < 5; i++) {
            int half = 2 + i;
            g.fill(x + 3 + i, cy - half, x + 4 + i, cy + half, color);
        }
        if (!muted) {
            g.fill(x + 11, cy - 2, x + 12, cy + 2, color);
            g.fill(x + 13, cy - 4, x + 14, cy + 4, color);
        } else {
            for (int i = 0; i < 7; i++) g.fill(x + 10 + i, cy - 3 + i, x + 11 + i, cy - 2 + i, color);
        }
    }

    static String ellipsize(Font font, String s, int maxW) {
        if (maxW <= 0) return "";
        if (font.width(s) <= maxW) return s;
        String ell = "\u2026";
        int ew = font.width(ell);
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end)) + ew > maxW) end--;
        return s.substring(0, end) + ell;
    }

    static String fmt(double seconds) {
        int t = (int) Math.max(0, seconds);
        return (t / 60) + ":" + String.format(Locale.ROOT, "%02d", t % 60);
    }

    private static int withAlpha(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    private static int argb(float alpha, int rgb) {
        int a = Math.round(clamp01(alpha) * 255f);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
