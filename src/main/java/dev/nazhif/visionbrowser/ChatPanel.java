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

final class ChatPanel {
    private enum Id {
        TAB_WINDOW, TAB_ISLAND, TAB_LYRICS, PAGE_GENERAL, PAGE_TEXT, PAGE_GLOW, PAGE_MOTION, PAGE_HUD,
        FLOAT_TOGGLE, FLOAT_LOCK, FLOAT_SIZE, ISLAND_SIZE, LYR_TOGGLE, MODE_PREV, MODE_NEXT,
        WORD_TOGGLE, COLOR_TOGGLE, PANEL_TOGGLE, FONT_PREV, FONT_NEXT, POS_PREV, POS_NEXT,
        LINES_PREV, LINES_NEXT, MAXLINES_PREV, MAXLINES_NEXT, L_SIZE, L_DIST, L_SPREAD,
        L_SIDE, WORD_SPEED, G_INTENSITY, G_RADIUS, G_DISTANCE, G_LIFE, G_FONTSIZE,
        PLAYER_RELATIVE, FOLLOW_ROTATION, L_HEIGHT, L_HOFFSET, G_RANDOM_TOGGLE, G_RANDOM_RANGE, G_SMOOTH
    }
    private record Item(Id id, int x, int y, int w, int h) {
        boolean has(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private record Layout(List<Item> items, int x, int y, int height) { }
    private static final int W = 300;
    private static final String[] MODES = {"3D Text", "3D Glow", "HUD"};
    private static int tab = 2, page = 0;
    private static Id dragging;
    private static Item dragItem;
    private static boolean prevDown;
    private ChatPanel() { }
    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("visionbrowser", "chat_panel"), ChatPanel::render);
    }
    static boolean chatOpen(Minecraft mc) {
        Object screen = mc.gui.screen();
        if (screen == null) return false;
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass())
            if (c.getSimpleName().equals("ChatScreen")) return true;
        return false;
    }
    private static void render(GuiGraphicsExtractor g, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !chatOpen(mc)) {
            if (dragging != null) finishDrag();
            prevDown = false; return;
        }
        try {
            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            double mx = mc.mouseHandler.xpos() * gw / (double) Math.max(1, mc.getWindow().getScreenWidth());
            double my = mc.mouseHandler.ypos() * gh / (double) Math.max(1, mc.getWindow().getScreenHeight());
            boolean down = MouseProbe.leftDown(mc);
            Layout l = layout(gw, gh);
            if (down && !prevDown) press(l, mx, my);
            if (down && dragging != null && dragItem != null) drag(dragging, dragItem, mx);
            if (!down && prevDown && dragging != null) finishDrag();
            prevDown = down;
            draw(g, mc, layout(gw, gh), mx, my);
        } catch (Throwable ignored) { }
    }
    private static Layout layout(int gw, int gh) {
        int px = gw - W - 8, x = px + 10, inner = W - 20, tabW = (inner - 8) / 3;
        List<Item> a = new ArrayList<>();
        a.add(new Item(Id.TAB_WINDOW, x, 0, tabW, 18));
        a.add(new Item(Id.TAB_ISLAND, x + tabW + 4, 0, tabW, 18));
        a.add(new Item(Id.TAB_LYRICS, x + 2 * (tabW + 4), 0, tabW, 18));
        int y = 54, half = (inner - 6) / 2;
        if (tab == 0) {
            a.add(new Item(Id.FLOAT_TOGGLE, x, y, half, 20));
            a.add(new Item(Id.FLOAT_LOCK, x + half + 6, y, half, 20));
            y += 30; y = slider(a, Id.FLOAT_SIZE, x, y, inner);
        } else if (tab == 1) {
            y = slider(a, Id.ISLAND_SIZE, x, y, inner);
        } else {
            a.add(new Item(Id.LYR_TOGGLE, x, y, inner, 18)); y += 24;
            y = cycle(a, Id.MODE_PREV, Id.MODE_NEXT, x, y, inner);
            Id[] pageIds = {Id.PAGE_GENERAL, Id.PAGE_TEXT, Id.PAGE_GLOW, Id.PAGE_MOTION, Id.PAGE_HUD};
            int pw = (inner - 12) / 5;
            for (int i = 0; i < 5; i++) a.add(new Item(pageIds[i], x + i * (pw + 3), y, pw, 18));
            y += 26;
            switch (page) {
                case 0 -> {
                    a.add(new Item(Id.WORD_TOGGLE, x, y, half, 18));
                    a.add(new Item(Id.COLOR_TOGGLE, x + half + 6, y, half, 18)); y += 24;
                    y = slider(a, Id.L_SIZE, x, y, inner);
                    y = slider(a, Id.WORD_SPEED, x, y, inner);
                }
                case 1 -> {
                    y = slider(a, Id.L_DIST, x, y, inner);
                    y = slider(a, Id.L_SPREAD, x, y, inner);
                    y = slider(a, Id.L_SIDE, x, y, inner);
                    y = slider(a, Id.L_HEIGHT, x, y, inner);
                    y = slider(a, Id.L_HOFFSET, x, y, inner);
                }
                case 2 -> {
                    y = cycle(a, Id.FONT_PREV, Id.FONT_NEXT, x, y, inner);
                    y = slider(a, Id.G_DISTANCE, x, y, inner);
                    y = slider(a, Id.G_INTENSITY, x, y, inner);
                    y = slider(a, Id.G_RADIUS, x, y, inner);
                    y = slider(a, Id.G_LIFE, x, y, inner);
                    y = slider(a, Id.G_FONTSIZE, x, y, inner);
                    y = cycle(a, Id.MAXLINES_PREV, Id.MAXLINES_NEXT, x, y, inner);
                }
                case 3 -> {
                    a.add(new Item(Id.PLAYER_RELATIVE, x, y, half, 18));
                    a.add(new Item(Id.FOLLOW_ROTATION, x + half + 6, y, half, 18)); y += 24;
                    a.add(new Item(Id.G_RANDOM_TOGGLE, x, y, inner, 18)); y += 24;
                    y = slider(a, Id.G_RANDOM_RANGE, x, y, inner);
                    y = slider(a, Id.G_SMOOTH, x, y, inner);
                }
                default -> {
                    y = cycle(a, Id.POS_PREV, Id.POS_NEXT, x, y, inner);
                    y = cycle(a, Id.LINES_PREV, Id.LINES_NEXT, x, y, inner);
                    a.add(new Item(Id.PANEL_TOGGLE, x, y, half, 18));
                    a.add(new Item(Id.COLOR_TOGGLE, x + half + 6, y, half, 18)); y += 24;
                    y = slider(a, Id.L_SIZE, x, y, inner);
                }
            }
            y += 18;
        }
        int height = y + 12;
        int py = Math.max(8, Math.min(60, gh - height - 8));
        List<Item> shifted = new ArrayList<>(a.size());
        for (Item i : a) shifted.add(new Item(i.id(), i.x(), i.y() + py, i.w(), i.h()));
        return new Layout(shifted, px, py, height);
    }
    private static int slider(List<Item> a, Id id, int x, int y, int w) {
        a.add(new Item(id, x, y + 9, w, 12)); return y + 28;
    }
    private static int cycle(List<Item> a, Id prev, Id next, int x, int y, int w) {
        a.add(new Item(prev, x, y + 7, 20, 18));
        a.add(new Item(next, x + w - 20, y + 7, 20, 18)); return y + 28;
    }
    private static float min(Id id) {
        return switch (id) {
            case FLOAT_SIZE, ISLAND_SIZE -> 0.6f; case L_SIZE -> 0.1f;
            case L_DIST -> 6f; case L_SPREAD -> 0f; case L_SIDE -> -1f;
            case WORD_SPEED -> 0.08f; case G_INTENSITY -> 0f; case G_RADIUS -> 2f;
            case L_HEIGHT -> -2f; case L_HOFFSET -> -5f;
            case G_DISTANCE -> 3f; case G_LIFE -> 2f; case G_FONTSIZE -> 12f;
            case G_RANDOM_RANGE -> 0f; case G_SMOOTH -> 0f;
            default -> 0f;
        };
    }
    private static float max(Id id) {
        return switch (id) {
            case FLOAT_SIZE -> 7f; case ISLAND_SIZE -> 1.8f; case L_SIZE -> 1.6f;
            case L_DIST -> 40f; case L_SPREAD -> 1.5f; case L_SIDE -> 1f;
            case WORD_SPEED -> 0.8f; case G_INTENSITY -> 5f; case G_RADIUS -> 18f;
            case L_HEIGHT -> 5f; case L_HOFFSET -> 5f;
            case G_DISTANCE -> 24f; case G_LIFE -> 20f; case G_FONTSIZE -> 96f;
            case G_RANDOM_RANGE -> 8f; case G_SMOOTH -> 1f;
            default -> 1f;
        };
    }
    private static float get(Id id) {
        return switch (id) {
            case FLOAT_SIZE -> VisionSettings.floatWidth; case ISLAND_SIZE -> VisionSettings.islandScale;
            case L_SIZE -> VisionSettings.lyricsScale; case L_DIST -> VisionSettings.lyricsDistance;
            case L_SPREAD -> VisionSettings.lyricsSpread; case L_SIDE -> VisionSettings.lyricsSide;
            case WORD_SPEED -> VisionSettings.lyricsWordSpeed; case G_INTENSITY -> VisionSettings.glowIntensity;
            case G_RADIUS -> VisionSettings.glowRadius; case G_DISTANCE -> VisionSettings.lyricsWorldDistance;
            case G_LIFE -> VisionSettings.lyricsLifeSeconds; case G_FONTSIZE -> VisionSettings.lyricsFontSize;
            case L_HEIGHT -> VisionSettings.lyricsVerticalOffset; case L_HOFFSET -> VisionSettings.lyricsHorizontalOffset;
            case G_RANDOM_RANGE -> VisionSettings.lyricsGlowRandomRange; case G_SMOOTH -> VisionSettings.lyricsGlowSmoothing;
            default -> 0f;
        };
    }
    private static void set(Id id, float v) {
        switch (id) {
            case FLOAT_SIZE -> VisionSettings.floatWidth = v; case ISLAND_SIZE -> VisionSettings.islandScale = v;
            case L_SIZE -> VisionSettings.lyricsScale = v; case L_DIST -> VisionSettings.lyricsDistance = v;
            case L_SPREAD -> VisionSettings.lyricsSpread = v; case L_SIDE -> VisionSettings.lyricsSide = v;
            case WORD_SPEED -> VisionSettings.lyricsWordSpeed = v; case G_INTENSITY -> VisionSettings.glowIntensity = v;
            case G_RADIUS -> VisionSettings.glowRadius = v; case G_DISTANCE -> VisionSettings.lyricsWorldDistance = v;
            case G_LIFE -> VisionSettings.lyricsLifeSeconds = v; case G_FONTSIZE -> VisionSettings.lyricsFontSize = Math.round(v);
            case L_HEIGHT -> VisionSettings.lyricsVerticalOffset = v; case L_HOFFSET -> VisionSettings.lyricsHorizontalOffset = v;
            case G_RANDOM_RANGE -> VisionSettings.lyricsGlowRandomRange = v; case G_SMOOTH -> VisionSettings.lyricsGlowSmoothing = v;
            default -> { }
        }
    }
    private static String caption(Id id) {
        return switch (id) {
            case FLOAT_SIZE -> "Window width"; case ISLAND_SIZE -> "Island size"; case L_SIZE -> "Text size";
            case L_DIST -> "Text distance"; case L_SPREAD -> "Line spread"; case L_SIDE -> "Horizontal position";
            case WORD_SPEED -> "Word reveal speed"; case G_INTENSITY -> "Glow intensity"; case G_RADIUS -> "Glow radius";
            case G_DISTANCE -> "Glow distance"; case G_LIFE -> "Glow lifetime"; case G_FONTSIZE -> "Glow font size";
            case L_HEIGHT -> "Vertical offset"; case L_HOFFSET -> "Horizontal offset";
            case G_RANDOM_RANGE -> "Random position range"; case G_SMOOTH -> "Motion smoothing";
            default -> "";
        };
    }
    private static String valueText(Id id) {
        float v = get(id);
        return switch (id) {
            case FLOAT_SIZE, L_DIST, G_DISTANCE -> String.format(Locale.ROOT, "%.1f blocks", v);
            case WORD_SPEED -> String.format(Locale.ROOT, "%.2f s/word", v);
            case G_LIFE -> String.format(Locale.ROOT, "%.1f s", v);
            case G_FONTSIZE, G_RADIUS -> Math.round(v) + " px";
            case L_SPREAD -> Math.round(v * 100) + "%";
            case L_SIDE -> Math.abs(v) < 0.05f ? "Center" : (v > 0 ? "Right " : "Left ") + Math.round(Math.abs(v) * 100) + "%";
            case L_HEIGHT, L_HOFFSET, G_RANDOM_RANGE -> String.format(Locale.ROOT, "%.1f blocks", v);
            case G_SMOOTH -> Math.round(v * 100) + "%";
            default -> Math.round(v * 100) + "%";
        };
    }
    private static void press(Layout l, double mx, double my) {
        for (Item r : l.items()) {
            if (!r.has(mx, my)) continue;
            switch (r.id()) {
                case TAB_WINDOW -> tab = 0; case TAB_ISLAND -> tab = 1; case TAB_LYRICS -> tab = 2;
                case PAGE_GENERAL -> page = 0; case PAGE_TEXT -> page = 1; case PAGE_GLOW -> page = 2; case PAGE_MOTION -> page = 3; case PAGE_HUD -> page = 4;
                case FLOAT_TOGGLE -> FloatWindow.toggle(); case FLOAT_LOCK -> FloatWindow.toggleLock();
                case LYR_TOGGLE -> FloatingLyrics.toggle();
                case MODE_PREV -> cycleMode(-1); case MODE_NEXT -> cycleMode(1);
                case WORD_TOGGLE -> { VisionSettings.lyricsWordByWord = !VisionSettings.lyricsWordByWord; VisionSettings.save(); FloatingLyrics.modeChanged(); }
                case PLAYER_RELATIVE -> { VisionSettings.lyricsPlayerRelative = !VisionSettings.lyricsPlayerRelative; VisionSettings.save(); FloatingLyrics.modeChanged(); }
                case FOLLOW_ROTATION -> { VisionSettings.lyricsFollowRotation = !VisionSettings.lyricsFollowRotation; VisionSettings.save(); FloatingLyrics.modeChanged(); }
                case G_RANDOM_TOGGLE -> { VisionSettings.lyricsGlowRandomPosition = !VisionSettings.lyricsGlowRandomPosition; VisionSettings.save(); FloatingLyrics.modeChanged(); }
                case COLOR_TOGGLE -> { VisionSettings.lyricsAlbumColor = !VisionSettings.lyricsAlbumColor; VisionSettings.save(); FloatingLyrics.modeChanged(); }
                case PANEL_TOGGLE -> { VisionSettings.hudPanel = !VisionSettings.hudPanel; VisionSettings.save(); }
                case FONT_PREV -> cycleFont(-1); case FONT_NEXT -> cycleFont(1);
                case POS_PREV -> { VisionSettings.hudPosition = Math.floorMod(VisionSettings.hudPosition - 1, LyricsHud.POSITIONS.length); VisionSettings.save(); }
                case POS_NEXT -> { VisionSettings.hudPosition = Math.floorMod(VisionSettings.hudPosition + 1, LyricsHud.POSITIONS.length); VisionSettings.save(); }
                case LINES_PREV -> { VisionSettings.hudLines = Math.max(2, VisionSettings.hudLines - 1); VisionSettings.save(); }
                case LINES_NEXT -> { VisionSettings.hudLines = Math.min(6, VisionSettings.hudLines + 1); VisionSettings.save(); }
                case MAXLINES_PREV -> { VisionSettings.lyricsMaxLines = Math.max(1, VisionSettings.lyricsMaxLines - 1); VisionSettings.save(); }
                case MAXLINES_NEXT -> { VisionSettings.lyricsMaxLines = Math.min(8, VisionSettings.lyricsMaxLines + 1); VisionSettings.save(); }
                case FLOAT_SIZE, ISLAND_SIZE, L_SIZE, L_DIST, L_SPREAD, L_SIDE, WORD_SPEED, G_INTENSITY, G_RADIUS, G_DISTANCE, G_LIFE, G_FONTSIZE, L_HEIGHT, L_HOFFSET, G_RANDOM_RANGE, G_SMOOTH -> {
                    dragging = r.id(); dragItem = r; drag(dragging, dragItem, mx);
                }
                default -> { }
            }
            return;
        }
    }
    private static void drag(Id id, Item track, double mx) {
        float t = VisionSettings.clamp((float) ((mx - track.x()) / track.w()), 0f, 1f);
        set(id, min(id) + t * (max(id) - min(id)));
    }
    private static void finishDrag() {
        Id was = dragging; dragging = null; dragItem = null; VisionSettings.save();
        if (was == Id.G_INTENSITY || was == Id.G_RADIUS || was == Id.G_FONTSIZE) {
            Lyrics3D.refreshAppearance();
        } else if (was != Id.FLOAT_SIZE && was != Id.ISLAND_SIZE) {
            FloatingLyrics.modeChanged();
        }
    }
    private static void cycleMode(int dir) {
        VisionSettings.lyricsMode = Math.floorMod(VisionSettings.lyricsMode + dir, MODES.length);
        VisionSettings.save(); FloatingLyrics.modeChanged();
    }
    private static void cycleFont(int dir) {
        List<String> names = LyricFonts.names(); if (names.isEmpty()) return;
        int i = names.indexOf(VisionSettings.lyricsFont); if (i < 0) i = 0;
        VisionSettings.lyricsFont = names.get(Math.floorMod(i + dir, names.size()));
        VisionSettings.save(); FloatingLyrics.modeChanged();
    }
    private static void draw(GuiGraphicsExtractor g, Minecraft mc, Layout l, double mx, double my) {
        Font font = mc.font; int x = l.x() + 10, y = l.y(), inner = W - 20, accent = IslandArt.accent() & 0xFFFFFF;
        DynamicIsland.roundedRect(g, l.x(), y, W, l.height(), 14, 0xA80C0C14);
        DynamicIsland.roundedRect(g, l.x() + 2, y + 2, W - 4, 27, 12, 0x12FFFFFF);
        Item prev = null;
        for (Item r : l.items()) {
            boolean hot = r.has(mx, my) || (dragging != null && dragging == r.id());
            switch (r.id()) {
                case TAB_WINDOW -> button(g, font, r, "Window", hot, tab == 0, accent);
                case TAB_ISLAND -> button(g, font, r, "Island", hot, tab == 1, accent);
                case TAB_LYRICS -> button(g, font, r, "Lyrics", hot, tab == 2, accent);
                case PAGE_GENERAL -> button(g, font, r, "Basics", hot, page == 0, accent);
                case PAGE_TEXT -> button(g, font, r, "3D Text", hot, page == 1, accent);
                case PAGE_GLOW -> button(g, font, r, "3D Glow", hot, page == 2, accent);
                case PAGE_MOTION -> button(g, font, r, "Motion", hot, page == 3, accent);
                case PAGE_HUD -> button(g, font, r, "HUD", hot, page == 4, accent);
                case FLOAT_TOGGLE -> button(g, font, r, FloatWindow.isEnabled() ? "Floating: On" : "Floating: Off", hot, FloatWindow.isEnabled(), accent);
                case FLOAT_LOCK -> button(g, font, r, FloatWindow.isLocked() ? "Locked" : "Free", hot, FloatWindow.isLocked(), accent);
                case LYR_TOGGLE -> button(g, font, r, FloatingLyrics.isEnabled() ? "Lyrics: On" : "Lyrics: Off", hot, FloatingLyrics.isEnabled(), accent);
                case WORD_TOGGLE -> button(g, font, r, VisionSettings.lyricsWordByWord ? "Word: On" : "Word: Off", hot, VisionSettings.lyricsWordByWord, accent);
                case PLAYER_RELATIVE -> button(g, font, r, VisionSettings.lyricsPlayerRelative ? "Position: Follow" : "Position: Locked", hot, !VisionSettings.lyricsPlayerRelative, accent);
                case FOLLOW_ROTATION -> button(g, font, r, VisionSettings.lyricsFollowRotation ? "Billboard: On" : "Billboard: Off", hot, VisionSettings.lyricsFollowRotation, accent);
                case G_RANDOM_TOGGLE -> button(g, font, r, VisionSettings.lyricsGlowRandomPosition ? "Random spawn: On" : "Random spawn: Off", hot, VisionSettings.lyricsGlowRandomPosition, accent);
                case COLOR_TOGGLE -> button(g, font, r, VisionSettings.lyricsAlbumColor ? "Album color" : "White color", hot, VisionSettings.lyricsAlbumColor, accent);
                case PANEL_TOGGLE -> button(g, font, r, VisionSettings.hudPanel ? "Panel: On" : "Panel: Off", hot, VisionSettings.hudPanel, accent);
                case MODE_PREV, FONT_PREV, POS_PREV, LINES_PREV, MAXLINES_PREV -> { button(g, font, r, "<", hot, false, accent); prev = r; }
                case MODE_NEXT -> cycleLabel(g, font, prev, r, "Style", MODES[VisionSettings.lyricsMode], hot, accent);
                case FONT_NEXT -> cycleLabel(g, font, prev, r, "Font", VisionSettings.lyricsFont, hot, accent);
                case POS_NEXT -> cycleLabel(g, font, prev, r, "Position", LyricsHud.POSITIONS[VisionSettings.hudPosition], hot, accent);
                case LINES_NEXT -> cycleLabel(g, font, prev, r, "HUD lines", String.valueOf(VisionSettings.hudLines), hot, accent);
                case MAXLINES_NEXT -> cycleLabel(g, font, prev, r, "World lines", String.valueOf(VisionSettings.lyricsMaxLines), hot, accent);
                case FLOAT_SIZE, ISLAND_SIZE, L_SIZE, L_DIST, L_SPREAD, L_SIDE, WORD_SPEED, G_INTENSITY, G_RADIUS, G_DISTANCE, G_LIFE, G_FONTSIZE, L_HEIGHT, L_HOFFSET, G_RANDOM_RANGE, G_SMOOTH ->
                    slider(g, font, r, caption(r.id()), valueText(r.id()), (get(r.id()) - min(r.id())) / (max(r.id()) - min(r.id())), hot, accent);
                default -> { }
            }
        }
        if (tab == 2) {
            String section = switch (page) {
                case 0 -> "General lyric controls";
                case 1 -> "3D Text settings";
                case 2 -> "3D Glow appearance";
                case 3 -> "Billboard and position behavior";
                default -> "HUD screen layout";
            };
            String detail = switch (page) {
                case 0 -> "Enable lyrics, choose style, size, and reveal";
                case 1 -> "Control distance, spread, and text placement";
                case 2 -> "Configure font, glow strength, lifetime, and lines";
                case 3 -> "Billboard faces the camera; Locked stays at its world position";
                default -> "Choose screen position, visible lines, and panel";
            };
            g.text(font, section, x, y + 31, 0xFFFFFFFF, true);
            g.text(font, DynamicIsland.ellipsize(font, detail, inner), x, y + 42, 0xFF9AA4B8, false);
            String status = VisionSettings.lyricsMode == VisionSettings.MODE_TEXT ? NameTagLyrics3D.status() : "Mode: " + MODES[VisionSettings.lyricsMode];
            g.text(font, DynamicIsland.ellipsize(font, status, inner), x, y + l.height() - 14, 0xFF8992A8, false);
        }
    }
    private static void cycleLabel(GuiGraphicsExtractor g, Font font, Item prev, Item next, String caption, String value, boolean hot, int accent) {
        button(g, font, next, ">", hot, false, accent);
        if (prev == null) return;
        g.text(font, caption, prev.x(), prev.y() - 11, 0xFFD6DAE8, false);
        int gap = next.x() - (prev.x() + prev.w()) - 12;
        String shown = DynamicIsland.ellipsize(font, value, gap);
        g.text(font, shown, prev.x() + prev.w() + 6 + (gap - font.width(shown)) / 2, prev.y() + 5, 0xFFFFFFFF, false);
    }
    private static void button(GuiGraphicsExtractor g, Font font, Item r, String label, boolean hot, boolean on, int accent) {
        int fill = on ? (0x70000000 | accent) : (hot ? 0x40FFFFFF : 0x22FFFFFF);
        DynamicIsland.roundedRect(g, r.x(), r.y(), r.w(), r.h(), Math.min(8, r.h() / 2), fill);
        g.text(font, label, r.x() + (r.w() - font.width(label)) / 2, r.y() + (r.h() - 8) / 2, 0xFFFFFFFF, false);
    }
    private static void slider(GuiGraphicsExtractor g, Font font, Item r, String label, String value, float t, boolean hot, int accent) {
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
