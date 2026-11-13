package dev.nazhif.visionbrowser;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings panel that appears only while the chat is open (that is when the mouse cursor is free), so no extra keybinds are needed.
 * Sliders: floating window size, Dynamic Island size, lyric side position, lyric size. Buttons: floating window on/off + lock, lyric font, lyrics on/off.
 * The chat screen owns the input events, so the cursor position comes from MouseHandler.xpos()/ypos() and the button from MouseProbe.
 */
final class ChatPanel {
    private enum Id { NONE, FLOAT_TOGGLE, FLOAT_LOCK, FLOAT_SIZE, ISLAND_SIZE, FONT_PREV, FONT_NEXT, SIDE, LYRIC_SIZE, LYRICS_TOGGLE }

    private record Rect(Id id, int x, int y, int w, int h) {
        boolean has(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private static final int W = 208;
    private static Id dragging = Id.NONE;
    private static boolean prevDown;
    private static Rect dragRect;

    private ChatPanel() { }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("visionbrowser", "chat_panel"), ChatPanel::render);
    }

    static boolean chatOpen(Minecraft mc) {
        Object screen = mc.gui.screen();
        if (screen == null) return false;
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals("ChatScreen")) return true;
        }
        return false;
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !chatOpen(mc)) {
            if (dragging != Id.NONE) finishDrag();
            prevDown = false;
            return;
        }
        try {
            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            double mx = mc.mouseHandler.xpos() * gw / (double) Math.max(1, mc.getWindow().getScreenWidth());
            double my = mc.mouseHandler.ypos() * gh / (double) Math.max(1, mc.getWindow().getScreenHeight());
            boolean down = MouseProbe.leftDown(mc);
            List<Rect> rects = layout(gw, gh);
            if (down && !prevDown) press(rects, mx, my);
            if (down && dragging != Id.NONE && dragRect != null) drag(dragging, dragRect, mx);
            if (!down && prevDown && dragging != Id.NONE) finishDrag();
            prevDown = down;
            draw(g, mc, rects, gw, gh, mx, my);
        } catch (Throwable error) {
            // never let the settings overlay break the chat
        }
    }

    // ------------------------------------------------------------------ layout

    private static int panelX(int gw) { return gw - W - 8; }
    private static int panelY(int gh) { return Math.max(24, gh / 2 - 130); }

    private static List<Rect> layout(int gw, int gh) {
        int x = panelX(gw) + 10, y = panelY(gh), inner = W - 20;
        List<Rect> r = new ArrayList<>();
        int half = (inner - 6) / 2;
        r.add(new Rect(Id.FLOAT_TOGGLE, x, y + 36, half, 20));
        r.add(new Rect(Id.FLOAT_LOCK, x + half + 6, y + 36, half, 20));
        r.add(new Rect(Id.FLOAT_SIZE, x, y + 74, inner, 14));
        r.add(new Rect(Id.ISLAND_SIZE, x, y + 126, inner, 14));
        r.add(new Rect(Id.FONT_PREV, x, y + 158, 20, 18));
        r.add(new Rect(Id.FONT_NEXT, x + inner - 20, y + 158, 20, 18));
        r.add(new Rect(Id.SIDE, x, y + 198, inner, 14));
        r.add(new Rect(Id.LYRIC_SIZE, x, y + 232, inner, 14));
        r.add(new Rect(Id.LYRICS_TOGGLE, x, y + 254, inner, 18));
        return r;
    }

    // ------------------------------------------------------------------ input

    private static void press(List<Rect> rects, double mx, double my) {
        for (Rect r : rects) {
            if (!r.has(mx, my)) continue;
            switch (r.id()) {
                case FLOAT_TOGGLE -> FloatWindow.toggle();
                case FLOAT_LOCK -> FloatWindow.toggleLock();
                case LYRICS_TOGGLE -> FloatingLyrics.toggle();
                case FONT_PREV -> cycleFont(-1);
                case FONT_NEXT -> cycleFont(1);
                case FLOAT_SIZE, ISLAND_SIZE, SIDE, LYRIC_SIZE -> {
                    dragging = r.id();
                    dragRect = r;
                    drag(dragging, r, mx);
                }
                default -> { }
            }
            return;
        }
    }

    private static void drag(Id id, Rect track, double mx) {
        float t = VisionSettings.clamp((float) ((mx - track.x()) / track.w()), 0f, 1f);
        switch (id) {
            case FLOAT_SIZE -> VisionSettings.floatWidth = 0.6f + t * (7.0f - 0.6f);
            case ISLAND_SIZE -> VisionSettings.islandScale = 0.6f + t * (1.8f - 0.6f);
            case SIDE -> VisionSettings.lyricsSide = -1f + t * 2f;
            case LYRIC_SIZE -> VisionSettings.lyricsScale = 0.6f + t * (1.6f - 0.6f);
            default -> { }
        }
    }

    private static void finishDrag() {
        Id was = dragging;
        dragging = Id.NONE;
        dragRect = null;
        VisionSettings.save();
        if (was == Id.SIDE || was == Id.LYRIC_SIZE) Lyrics3D.clear(); // respawn the current line with the new placement
    }

    private static void cycleFont(int dir) {
        List<String> names = LyricFonts.names();
        int i = names.indexOf(VisionSettings.lyricsFont);
        if (i < 0) i = 0;
        VisionSettings.lyricsFont = names.get(Math.floorMod(i + dir, names.size()));
        VisionSettings.save();
        Lyrics3D.clear();
    }

    // ------------------------------------------------------------------ drawing (frameless glass, no outline)

    private static void draw(GuiGraphicsExtractor g, Minecraft mc, List<Rect> rects, int gw, int gh, double mx, double my) {
        Font font = mc.font;
        int px = panelX(gw), py = panelY(gh), ph = 296;
        int accent = IslandArt.accent() & 0xFFFFFF;
        DynamicIsland.roundedRect(g, px, py, W, ph, 14, 0xA80C0C14);
        DynamicIsland.roundedRect(g, px + 2, py + 2, W - 4, 30, 12, 0x12FFFFFF);
        int x = px + 10;
        g.text(font, "VISION BROWSER", x, py + 9, 0xFFFFFFFF, false);
        g.text(font, "FLOATING WINDOW", x, py + 25, 0xFF9AA0B4, false);
        g.text(font, "DYNAMIC ISLAND", x, py + 102, 0xFF9AA0B4, false);
        g.text(font, "3D LYRICS", x, py + 140, 0xFF9AA0B4, false);
        for (Rect r : rects) {
            boolean hot = r.has(mx, my) || dragging == r.id();
            switch (r.id()) {
                case FLOAT_TOGGLE -> button(g, font, r, FloatWindow.isEnabled() ? "Floating: On" : "Floating: Off", hot, FloatWindow.isEnabled(), accent);
                case FLOAT_LOCK -> button(g, font, r, FloatWindow.isLocked() ? "Locked" : "Free", hot, FloatWindow.isLocked(), accent);
                case LYRICS_TOGGLE -> button(g, font, r, FloatingLyrics.isEnabled() ? "Lyrics: On" : "Lyrics: Off", hot, FloatingLyrics.isEnabled(), accent);
                case FONT_PREV -> button(g, font, r, "<", hot, false, accent);
                case FONT_NEXT -> button(g, font, r, ">", hot, false, accent);
                case FLOAT_SIZE -> slider(g, font, r, "Size", String.format(Locale.ROOT, "%.1f blocks", VisionSettings.floatWidth),
                        (VisionSettings.floatWidth - 0.6f) / (7.0f - 0.6f), hot, accent);
                case ISLAND_SIZE -> slider(g, font, r, "Size", Math.round(VisionSettings.islandScale * 100) + "%",
                        (VisionSettings.islandScale - 0.6f) / (1.8f - 0.6f), hot, accent);
                case SIDE -> slider(g, font, r, "Side", sideLabel(VisionSettings.lyricsSide), (VisionSettings.lyricsSide + 1f) / 2f, hot, accent);
                case LYRIC_SIZE -> slider(g, font, r, "Size", Math.round(VisionSettings.lyricsScale * 100) + "%",
                        (VisionSettings.lyricsScale - 0.6f) / (1.6f - 0.6f), hot, accent);
                default -> { }
            }
        }
        Rect prev = rects.get(4), next = rects.get(5);
        String name = DynamicIsland.ellipsize(font, VisionSettings.lyricsFont, next.x() - (prev.x() + prev.w()) - 12);
        g.text(font, name, prev.x() + prev.w() + (next.x() - (prev.x() + prev.w()) - font.width(name)) / 2, prev.y() + 5, 0xFFFFFFFF, false);
        g.text(font, "Drag a slider, release to save", x, py + ph - 14, 0xFF6F7690, false);
    }

    private static String sideLabel(float v) {
        if (Math.abs(v) < 0.05f) return "Center";
        return (v > 0 ? "Right " : "Left ") + Math.round(Math.abs(v) * 100) + "%";
    }

    private static void button(GuiGraphicsExtractor g, Font font, Rect r, String label, boolean hot, boolean on, int accent) {
        int fill = on ? (0x70000000 | accent) : (hot ? 0x40FFFFFF : 0x22FFFFFF);
        DynamicIsland.roundedRect(g, r.x(), r.y(), r.w(), r.h(), Math.min(8, r.h() / 2), fill);
        g.text(font, label, r.x() + (r.w() - font.width(label)) / 2, r.y() + (r.h() - 8) / 2, 0xFFFFFFFF, false);
    }

    private static void slider(GuiGraphicsExtractor g, Font font, Rect r, String label, String value, float t, boolean hot, int accent) {
        t = VisionSettings.clamp(t, 0f, 1f);
        g.text(font, label, r.x(), r.y() - 11, 0xFFD6DAE8, false);
        g.text(font, value, r.x() + r.w() - font.width(value), r.y() - 11, 0xFFFFFFFF, false);
        int ty = r.y() + r.h() / 2 - 2;
        DynamicIsland.roundedRect(g, r.x(), ty, r.w(), 4, 2, 0x44FFFFFF);
        int fw = Math.round(r.w() * t);
        if (fw > 0) DynamicIsland.roundedRect(g, r.x(), ty, Math.max(4, fw), 4, 2, 0xE0000000 | accent | 0x404040);
        int kr = hot ? 5 : 4;
        DynamicIsland.roundedRect(g, r.x() + fw - kr, ty + 2 - kr, kr * 2, kr * 2, kr, 0xFFFFFFFF);
    }
}