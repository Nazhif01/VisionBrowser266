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
 * Three tabs: Window (floating window), Island (Dynamic Island), Lyrics (style + the options of the chosen style).
 * The chat screen owns the input events, so the cursor position comes from MouseHandler.xpos()/ypos() and the button from MouseProbe.
 */
final class ChatPanel {
    private enum Id {
        NONE, TAB_WINDOW, TAB_ISLAND, TAB_LYRICS,
        FLOAT_TOGGLE, FLOAT_LOCK, FLOAT_SIZE, ISLAND_SIZE,
        LYR_TOGGLE, MODE_PREV, MODE_NEXT, L_DIST, L_SIZE, L_SPREAD, L_SIDE, COLOR_TOGGLE,
        FONT_PREV, FONT_NEXT, COLOR_PREV, COLOR_NEXT, POS_PREV, POS_NEXT, LINES_PREV, LINES_NEXT, PANEL_TOGGLE, ANIM_PREV, ANIM_NEXT, POWER_PREV, POWER_NEXT, RAINBOW_TOGGLE, GLOW_FACING_PREV, GLOW_FACING_NEXT, GLOW_PLACE_PREV, GLOW_PLACE_NEXT, GLOW_TOGGLE
    }

    private record Item(Id id, int x, int y, int w, int h) {
        boolean has(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private record Layout(List<Item> items, int x, int y, int height) { }

    private static final int W = 208;
    private static final String[] MODES = {"3D Words", "3D Glow", "HUD"};
    private static final String[] ANIMATIONS = {"Edit", "Pulse", "Fade In", "Wave", "Bounce", "Glitch", "Orbit", "Spotlight"};
    private static final String[] INTENSITIES = {"Chill", "Normal", "Hype"};
    private static int tab = 2;                 // start on Lyrics: that is where most options live
    private static Id dragging = Id.NONE;
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
            Layout layout = layout(gw, gh);
            if (down && !prevDown) press(layout, mx, my);
            if (down && dragging != Id.NONE && dragItem != null) drag(dragging, dragItem, mx);
            if (!down && prevDown && dragging != Id.NONE) finishDrag();
            prevDown = down;
            layout = layout(gw, gh);   // a click may have changed the tab or style
            draw(g, mc, layout, mx, my);
        } catch (Throwable error) {
            // never let the settings overlay break the chat
        }
    }

    // ------------------------------------------------------------------ layout

    private static Layout layout(int gw, int gh) {
        int px = gw - W - 8, x = px + 10, inner = W - 20;
        List<Item> r = new ArrayList<>();
        int tabW = (inner - 8) / 3, y0 = 60;
        // first pass to know the height, second pass is the real one (y0 is clamped so the panel stays on screen)
        int height = build(r, x, y0, inner, tabW);
        int py = Math.max(8, Math.min(y0, gh - height - 8));
        if (py != y0) { r.clear(); height = build(r, x, py, inner, tabW); }
        return new Layout(r, px, py, height);
    }

    private static int build(List<Item> r, int x, int py, int inner, int tabW) {
        r.add(new Item(Id.TAB_WINDOW, x, py + 8, tabW, 18));
        r.add(new Item(Id.TAB_ISLAND, x + tabW + 4, py + 8, tabW, 18));
        r.add(new Item(Id.TAB_LYRICS, x + 2 * (tabW + 4), py + 8, tabW, 18));
        int y = py + 34;
        int half = (inner - 6) / 2;
        switch (tab) {
            case 0 -> {
                r.add(new Item(Id.FLOAT_TOGGLE, x, y, half, 20));
                r.add(new Item(Id.FLOAT_LOCK, x + half + 6, y, half, 20));
                y += 26;
                y = slider(r, Id.FLOAT_SIZE, x, y, inner);
            }
            case 1 -> y = slider(r, Id.ISLAND_SIZE, x, y, inner);
            default -> {
                r.add(new Item(Id.LYR_TOGGLE, x, y, inner, 18));
                y += 24;
                y = cycle(r, Id.MODE_PREV, Id.MODE_NEXT, x, y, inner);
                switch (VisionSettings.lyricsMode) {
                    case VisionSettings.MODE_TEXT -> {
                        y = slider(r, Id.L_DIST, x, y, inner);
                        y = slider(r, Id.L_SIZE, x, y, inner);
                        y = slider(r, Id.L_SPREAD, x, y, inner);
                        y = cycle(r, Id.COLOR_PREV, Id.COLOR_NEXT, x, y, inner);
                        y += 2;
                    }
                    case VisionSettings.MODE_GLOW -> {
                        y = slider(r, Id.L_DIST, x, y, inner);
                        y = cycle(r, Id.ANIM_PREV, Id.ANIM_NEXT, x, y, inner);
                        y = cycle(r, Id.POWER_PREV, Id.POWER_NEXT, x, y, inner);
                        y = cycle(r, Id.COLOR_PREV, Id.COLOR_NEXT, x, y, inner);
                        y = cycle(r, Id.FONT_PREV, Id.FONT_NEXT, x, y, inner);
                        y = cycle(r, Id.GLOW_FACING_PREV, Id.GLOW_FACING_NEXT, x, y, inner);
                        y = cycle(r, Id.GLOW_PLACE_PREV, Id.GLOW_PLACE_NEXT, x, y, inner);
                        r.add(new Item(Id.RAINBOW_TOGGLE, x, y, inner, 18));
                        y += 24;
                        r.add(new Item(Id.GLOW_TOGGLE, x, y, inner, 18));
                        y += 24;
                        y = slider(r, Id.L_SIZE, x, y, inner);
                        y = slider(r, Id.L_SIDE, x, y, inner);
                    }
                    default -> {
                        y = cycle(r, Id.POS_PREV, Id.POS_NEXT, x, y, inner);
                        y = cycle(r, Id.LINES_PREV, Id.LINES_NEXT, x, y, inner);
                        y = slider(r, Id.L_SIZE, x, y, inner);
                        y = cycle(r, Id.COLOR_PREV, Id.COLOR_NEXT, x, y, inner);
                        r.add(new Item(Id.PANEL_TOGGLE, x, y, inner, 18));
                        y += 24;
                    }
                }
                y += 12; // status line
            }
        }
        return (y - py) + 14;
    }

    private static int slider(List<Item> r, Id id, int x, int y, int inner) {
        r.add(new Item(id, x, y + 11, inner, 14));
        return y + 11 + 14 + 9;
    }

    private static int cycle(List<Item> r, Id prev, Id next, int x, int y, int inner) {
        r.add(new Item(prev, x, y + 11, 20, 18));
        r.add(new Item(next, x + inner - 20, y + 11, 20, 18));
        return y + 11 + 18 + 8;
    }

    // ------------------------------------------------------------------ values

    private static float min(Id id) {
        return switch (id) { case FLOAT_SIZE -> 0.6f; case ISLAND_SIZE, L_SIZE -> 0.6f; case L_DIST -> 3f; case L_SPREAD -> 0f; case L_SIDE -> -1f; default -> 0f; };
    }

    private static float max(Id id) {
        return switch (id) { case FLOAT_SIZE -> 7f; case ISLAND_SIZE -> 1.8f; case L_SIZE -> 1.6f; case L_DIST -> 34f; case L_SPREAD -> 1.5f; case L_SIDE -> 1f; default -> 1f; };
    }

    private static float get(Id id) {
        return switch (id) {
            case FLOAT_SIZE -> VisionSettings.floatWidth; case ISLAND_SIZE -> VisionSettings.islandScale; case L_SIZE -> VisionSettings.lyricsScale;
            case L_DIST -> VisionSettings.lyricsDistance; case L_SPREAD -> VisionSettings.lyricsSpread; case L_SIDE -> VisionSettings.lyricsSide;
            default -> 0f;
        };
    }

    private static void set(Id id, float v) {
        switch (id) {
            case FLOAT_SIZE -> VisionSettings.floatWidth = v; case ISLAND_SIZE -> VisionSettings.islandScale = v; case L_SIZE -> VisionSettings.lyricsScale = v;
            case L_DIST -> VisionSettings.lyricsDistance = v; case L_SPREAD -> VisionSettings.lyricsSpread = v; case L_SIDE -> VisionSettings.lyricsSide = v;
            default -> { }
        }
    }

    private static String caption(Id id) {
        return switch (id) {
            case FLOAT_SIZE -> "Window size"; case ISLAND_SIZE -> "Island size"; case L_SIZE -> "Text size";
            case L_DIST -> "Distance"; case L_SPREAD -> "Spread left/right"; case L_SIDE -> "Side";
            default -> "";
        };
    }

    private static String valueText(Id id) {
        float v = get(id);
        return switch (id) {
            case FLOAT_SIZE -> String.format(Locale.ROOT, "%.1f blocks", v);
            case L_DIST -> String.format(Locale.ROOT, "%.0f blocks", v);
            case L_SPREAD -> Math.round(v * 100) + "%";
            case L_SIDE -> Math.abs(v) < 0.05f ? "Center" : (v > 0 ? "Right " : "Left ") + Math.round(Math.abs(v) * 100) + "%";
            default -> Math.round(v * 100) + "%";
        };
    }

    // ------------------------------------------------------------------ input

    private static void press(Layout layout, double mx, double my) {
        for (Item r : layout.items()) {
            if (!r.has(mx, my)) continue;
            switch (r.id()) {
                case TAB_WINDOW -> tab = 0;
                case TAB_ISLAND -> tab = 1;
                case TAB_LYRICS -> tab = 2;
                case FLOAT_TOGGLE -> FloatWindow.toggle();
                case FLOAT_LOCK -> FloatWindow.toggleLock();
                case LYR_TOGGLE -> FloatingLyrics.toggle();
                case MODE_PREV -> cycleMode(-1);
                case MODE_NEXT -> cycleMode(1);
                case FONT_PREV -> cycleFont(-1);
                case FONT_NEXT -> cycleFont(1);
                case COLOR_PREV -> cycleColor(-1);
                case COLOR_NEXT -> cycleColor(1);
                case GLOW_FACING_PREV -> setGlowFacing(-1);
                case GLOW_FACING_NEXT -> setGlowFacing(1);
                case GLOW_PLACE_PREV -> setGlowPlacement(-1);
                case GLOW_PLACE_NEXT -> setGlowPlacement(1);
                case ANIM_PREV -> cycleAnimation(-1);
                case ANIM_NEXT -> cycleAnimation(1);
                case POWER_PREV -> cycleIntensity(-1);
                case POWER_NEXT -> cycleIntensity(1);
                case RAINBOW_TOGGLE -> { VisionSettings.lyricsRainbow = !VisionSettings.lyricsRainbow; VisionSettings.save(); }
                case GLOW_TOGGLE -> { VisionSettings.lyricsGlow = !VisionSettings.lyricsGlow; VisionSettings.save(); FloatingLyrics.modeChanged(); }
                case POS_PREV -> { VisionSettings.hudPosition = Math.floorMod(VisionSettings.hudPosition - 1, LyricsHud.POSITIONS.length); VisionSettings.save(); }
                case POS_NEXT -> { VisionSettings.hudPosition = Math.floorMod(VisionSettings.hudPosition + 1, LyricsHud.POSITIONS.length); VisionSettings.save(); }
                case LINES_PREV -> { VisionSettings.hudLines = Math.max(2, VisionSettings.hudLines - 1); VisionSettings.save(); }
                case LINES_NEXT -> { VisionSettings.hudLines = Math.min(6, VisionSettings.hudLines + 1); VisionSettings.save(); }
                case PANEL_TOGGLE -> { VisionSettings.hudPanel = !VisionSettings.hudPanel; VisionSettings.save(); }
                case COLOR_TOGGLE -> { VisionSettings.lyricsAlbumColor = !VisionSettings.lyricsAlbumColor; VisionSettings.save(); FloatingLyrics.modeChanged(); }
                case FLOAT_SIZE, ISLAND_SIZE, L_SIZE, L_DIST, L_SPREAD, L_SIDE -> {
                    dragging = r.id();
                    dragItem = r;
                    drag(dragging, r, mx);
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
        Id was = dragging;
        dragging = Id.NONE;
        dragItem = null;
        VisionSettings.save();
        if (was == Id.L_SIZE || was == Id.L_DIST || was == Id.L_SPREAD || was == Id.L_SIDE) FloatingLyrics.modeChanged();
    }

    private static void setGlowFacing(int dir) { VisionSettings.glowFacing = Math.floorMod(VisionSettings.glowFacing + dir, 2); VisionSettings.save(); FloatingLyrics.modeChanged(); }

    private static void setGlowPlacement(int dir) { VisionSettings.glowPlacement = Math.floorMod(VisionSettings.glowPlacement + dir, 3); VisionSettings.save(); FloatingLyrics.modeChanged(); }

    private static void cycleMode(int dir) {
        VisionSettings.lyricsMode = Math.floorMod(VisionSettings.lyricsMode + dir, MODES.length);
        VisionSettings.save();
        FloatingLyrics.modeChanged();
    }

    private static void cycleAnimation(int dir) {
        VisionSettings.lyricsAnimation = Math.floorMod(VisionSettings.lyricsAnimation + dir, ANIMATIONS.length);
        VisionSettings.save();
    }

    private static void cycleIntensity(int dir) {
        VisionSettings.lyricsIntensity = Math.floorMod(VisionSettings.lyricsIntensity + dir, INTENSITIES.length);
        VisionSettings.save();
    }

    private static void cycleColor(int dir) { VisionSettings.lyricsColor = Math.floorMod(VisionSettings.lyricsColor + dir, VisionSettings.LYRIC_COLORS.length); VisionSettings.save(); FloatingLyrics.modeChanged(); }

    private static void cycleFont(int dir) {
        List<String> names = LyricFonts.names();
        int i = names.indexOf(VisionSettings.lyricsFont);
        if (i < 0) i = 0;
        VisionSettings.lyricsFont = names.get(Math.floorMod(i + dir, names.size()));
        VisionSettings.save();
        FloatingLyrics.modeChanged();
    }

    // ------------------------------------------------------------------ drawing (frameless glass, no outline)

    private static void draw(GuiGraphicsExtractor g, Minecraft mc, Layout layout, double mx, double my) {
        Font font = mc.font;
        int px = layout.x(), py = layout.y(), x = px + 10, inner = W - 20;
        int accent = IslandArt.accent() & 0xFFFFFF;
        DynamicIsland.roundedRect(g, px, py, W, layout.height(), 14, 0xA80C0C14);
        DynamicIsland.roundedRect(g, px + 2, py + 2, W - 4, 30, 12, 0x12FFFFFF);
        Item prevItem = null;
        for (Item r : layout.items()) {
            boolean hot = r.has(mx, my) || dragging == r.id();
            switch (r.id()) {
                case TAB_WINDOW -> button(g, font, r, "Window", hot, tab == 0, accent);
                case TAB_ISLAND -> button(g, font, r, "Island", hot, tab == 1, accent);
                case TAB_LYRICS -> button(g, font, r, "Lyrics", hot, tab == 2, accent);
                case FLOAT_TOGGLE -> button(g, font, r, FloatWindow.isEnabled() ? "Floating: On" : "Floating: Off", hot, FloatWindow.isEnabled(), accent);
                case FLOAT_LOCK -> button(g, font, r, FloatWindow.isLocked() ? "Locked" : "Free", hot, FloatWindow.isLocked(), accent);
                case LYR_TOGGLE -> button(g, font, r, FloatingLyrics.isEnabled() ? "Lyrics: On" : "Lyrics: Off", hot, FloatingLyrics.isEnabled(), accent);
                case COLOR_TOGGLE -> button(g, font, r, "Use selected palette", hot, true, accent);
                case RAINBOW_TOGGLE -> button(g, font, r, VisionSettings.lyricsRainbow ? "Rainbow: On" : "Rainbow: Off", hot, VisionSettings.lyricsRainbow, accent);
                case GLOW_TOGGLE -> button(g, font, r, VisionSettings.lyricsGlow ? "Glow: On" : "Glow: Off", hot, VisionSettings.lyricsGlow, accent);
                case PANEL_TOGGLE -> button(g, font, r, VisionSettings.hudPanel ? "Panel: On" : "Panel: Off", hot, VisionSettings.hudPanel, accent);
                case MODE_PREV, FONT_PREV, COLOR_PREV, POS_PREV, LINES_PREV, ANIM_PREV, POWER_PREV -> { button(g, font, r, "<", hot, false, accent); prevItem = r; }
                case MODE_NEXT -> cycleLabel(g, font, prevItem, r, "Style", MODES[VisionSettings.lyricsMode], hot, accent);
                case ANIM_NEXT -> cycleLabel(g, font, prevItem, r, "Animation", ANIMATIONS[VisionSettings.lyricsAnimation], hot, accent);
                case POWER_NEXT -> cycleLabel(g, font, prevItem, r, "Intensity", INTENSITIES[VisionSettings.lyricsIntensity], hot, accent);
                case FONT_NEXT -> cycleLabel(g, font, prevItem, r, "Font", VisionSettings.lyricsFont, hot, accent);
                case COLOR_NEXT -> cycleLabel(g, font, prevItem, r, "Color", VisionSettings.LYRIC_COLORS[VisionSettings.lyricsColor], hot, accent);
                case GLOW_FACING_PREV, GLOW_PLACE_PREV -> { button(g, font, r, "<", hot, false, accent); prevItem = r; }
                case GLOW_FACING_NEXT -> cycleLabel(g, font, prevItem, r, "Facing", VisionSettings.glowFacing == 1 ? "Billboard" : "World", hot, accent);
                case GLOW_PLACE_NEXT -> cycleLabel(g, font, prevItem, r, "Spawn", VisionSettings.glowPlacement == 2 ? "Player Relative" : VisionSettings.glowPlacement == 1 ? "Random View" : "Crosshair", hot, accent);
                case POS_NEXT -> cycleLabel(g, font, prevItem, r, "Position", LyricsHud.POSITIONS[VisionSettings.hudPosition], hot, accent);
                case LINES_NEXT -> cycleLabel(g, font, prevItem, r, "Lines", String.valueOf(VisionSettings.hudLines), hot, accent);
                case FLOAT_SIZE, ISLAND_SIZE, L_SIZE, L_DIST, L_SPREAD, L_SIDE -> slider(g, font, r, caption(r.id()), valueText(r.id()),
                        (get(r.id()) - min(r.id())) / (max(r.id()) - min(r.id())), hot, accent);
                default -> { }
            }
        }
        if (tab == 2) {
            String status = VisionSettings.lyricsMode == VisionSettings.MODE_TEXT ? NameTagLyrics3D.status() : "Style: " + MODES[VisionSettings.lyricsMode];
            boolean bad = VisionSettings.lyricsMode == VisionSettings.MODE_TEXT && !NameTagLyrics3D.usable();
            String text = DynamicIsland.ellipsize(font, bad ? status + " (using 3D Glow)" : status, inner);
            g.text(font, text, x, py + layout.height() - 24, bad ? 0xFFFFAA66 : 0xFF6F7690, false);
        }
    }

    private static void cycleLabel(GuiGraphicsExtractor g, Font font, Item prev, Item next, String caption, String value, boolean hot, int accent) {
        button(g, font, next, ">", hot, false, accent);
        if (prev == null) return;
        g.text(font, caption, prev.x(), prev.y() - 11, 0xFFD6DAE8, false);
        int gap = next.x() - (prev.x() + prev.w()) - 12;
        String name = DynamicIsland.ellipsize(font, value, gap);
        g.text(font, name, prev.x() + prev.w() + 6 + (gap - font.width(name)) / 2, prev.y() + 5, 0xFFFFFFFF, false);
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