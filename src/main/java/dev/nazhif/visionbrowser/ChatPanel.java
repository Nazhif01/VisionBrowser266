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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Settings window shown beside the chat: categories (tabs) -> pages -> toggles, sliders and cyclers. Every option of the mod lives here. */
final class ChatPanel {
    private interface FGet { float get(); }
    private interface FSet { void set(float v); }
    private sealed interface Entry permits Header, Toggle, Slider, Cycle, Action { }
    private record Header(String label) implements Entry { }
    private record Toggle(String label, BooleanSupplier get, Runnable flip) implements Entry { }
    private record Slider(String label, float min, float max, FGet get, FSet set, Function<Float, String> fmt, Runnable commit) implements Entry { }
    private record Cycle(String label, Supplier<String> value, IntConsumer step) implements Entry { }
    private record Action(String label, Runnable run) implements Entry { }
    private record Page(String name, List<Entry> entries) { }
    private record Tab(String name, List<Page> pages) { }

    private enum Role { TAB, PAGE, HEADER, ACTION, TOGGLE, SLIDER, PREV, NEXT, UP, DOWN, RESET }
    private record Item(Role role, int index, Entry entry, int x, int y, int w, int h) {
        boolean has(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private record Layout(List<Item> items, int x, int y, int height) { }

    private static final int W = 232, FOOT = 40;
    private static final String[] MODES = {"3D Text", "3D Glow", "HUD"};
    private static final String[] ANCHORS = {"Random", "Top", "Bottom", "Upper left", "Upper right", "Lower left", "Lower right"};
    private static final Runnable LYR = FloatingLyrics::modeChanged;
    private static final Runnable GLOW = Lyrics3D::refreshAppearance;
    private static final List<Tab> TABS = buildTabs();

    private static int tab = 2, scroll = 0;
    private static final int[] pageOf = new int[3];
    private static Item dragItem;
    private static boolean prevDown;
    private static long resetUntil;

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

    // ------------------------------------------------------------------ definition of every setting

    private static Entry tg(String label, BooleanSupplier get, Consumer<Boolean> set, Runnable after) {
        return new Toggle(label, get, () -> { set.accept(!get.getAsBoolean()); VisionSettings.save(); if (after != null) after.run(); });
    }

    private static Entry sl(String label, float min, float max, FGet get, FSet set, Function<Float, String> fmt, Runnable commit) {
        return new Slider(label, min, max, get, set, fmt, commit);
    }

    private static Entry cyc(String label, String[] names, IntSupplier get, IntConsumer set, Runnable after) {
        return new Cycle(label, () -> names[Math.floorMod(get.getAsInt(), names.length)], d -> {
            set.accept(Math.floorMod(get.getAsInt() + d, names.length));
            VisionSettings.save();
            if (after != null) after.run();
        });
    }

    private static String blocks(float v) { return String.format(Locale.ROOT, "%.1f blocks", v); }
    private static String pct(float v) { return Math.round(v * 100) + "%"; }
    private static String secs(float v) { return String.format(Locale.ROOT, "%.1f s", v); }
    private static String px(float v) { return Math.round(v) + " px"; }
    private static String whole(float v) { return String.valueOf(Math.round(v)); }
    private static String ms(float v) { return String.format(Locale.ROOT, "%+d ms", Math.round(v)); }
    private static String times(float v) { return String.format(Locale.ROOT, "x%.2f", v); }
    private static String ndc(float v) { return Math.round(v * 50) + "% of half screen"; }

    private static List<Tab> buildTabs() {
        List<Page> window = List.of(
                new Page("Main", List.of(
                        new Header("Mode and floating window"),
                        tg("Music mode (no browser)", () -> VisionSettings.musicMode, v -> VisionSettings.musicMode = v, null),
                        tg("System media (Sonora, Spotify)", () -> VisionSettings.systemMedia, v -> VisionSettings.systemMedia = v, SystemMedia::ensureState),
                        cyc("Browser to control", RemoteBrowser.NAMES, () -> VisionSettings.remoteBrowser, v -> VisionSettings.remoteBrowser = v, null),
                        new Action("Launch browser", RemoteBrowser::launch),
                        tg("Reset browser each session", () -> VisionSettings.remoteResetTabs, v -> VisionSettings.remoteResetTabs = v, null),
                        sl("Max tabs", 2f, 12f, () -> (float) VisionSettings.remoteMaxTabs, v -> VisionSettings.remoteMaxTabs = Math.round(v), ChatPanel::whole, null),
                        sl("Stream quality", 30f, 100f, () -> (float) VisionSettings.remoteQuality, v -> VisionSettings.remoteQuality = Math.round(v), ChatPanel::whole, null),
                        new Toggle("Floating window", FloatWindow::isEnabled, FloatWindow::toggle),
                        new Toggle("Follow player", FloatWindow::isLocked, FloatWindow::toggleLock),
                        new Toggle("Billboard", () -> VisionSettings.floatBillboard, FloatWindow::toggleBillboard),
                        tg("Lock bar", () -> VisionSettings.floatLockBar, v -> VisionSettings.floatLockBar = v, null),
                        tg("Toast msgs", () -> VisionSettings.floatToast, v -> VisionSettings.floatToast = v, null))),
                new Page("Layout", List.of(
                        new Header("Size and position"),
                        sl("Window width", 0.6f, 7f, () -> VisionSettings.floatWidth, v -> VisionSettings.floatWidth = v, ChatPanel::blocks, null),
                        sl("Distance from player", 1.2f, 10f, () -> VisionSettings.floatDistance, v -> VisionSettings.floatDistance = v, ChatPanel::blocks, null),
                        sl("Height offset", -2f, 2f, () -> VisionSettings.floatHeight, v -> VisionSettings.floatHeight = v, ChatPanel::blocks, null),
                        sl("Side offset", -3f, 3f, () -> VisionSettings.floatSide, v -> VisionSettings.floatSide = v, ChatPanel::blocks, null))),
                new Page("Look", List.of(
                        new Header("Appearance and motion"),
                        sl("Opacity", 0.15f, 1f, () -> VisionSettings.floatOpacity, v -> VisionSettings.floatOpacity = v, ChatPanel::pct, null),
                        sl("Corner roundness", 0f, 0.5f, () -> VisionSettings.floatCorner, v -> VisionSettings.floatCorner = v, v -> pct(v * 2f), null),
                        sl("Follow speed", 1f, 30f, () -> VisionSettings.floatFollowSpeed, v -> VisionSettings.floatFollowSpeed = v, v -> String.format(Locale.ROOT, "%.0f/s", v), null))));

        List<Page> island = List.of(
                new Page("Island", List.of(
                        new Header("Dynamic Island"),
                        tg("Show island", () -> VisionSettings.islandShow, v -> VisionSettings.islandShow = v, null),
                        sl("Island size", 0.6f, 1.8f, () -> VisionSettings.islandScale, v -> VisionSettings.islandScale = v, ChatPanel::pct, null),
                        sl("Offset X", -300f, 300f, () -> VisionSettings.islandOffsetX, v -> VisionSettings.islandOffsetX = v, ChatPanel::px, null),
                        sl("Offset Y", 0f, 120f, () -> VisionSettings.islandOffsetY, v -> VisionSettings.islandOffsetY = v, ChatPanel::px, null))));

        List<Page> lyrics = List.of(
                new Page("Main", List.of(
                        new Header("General"),
                        new Toggle("Lyrics", FloatingLyrics::isEnabled, FloatingLyrics::toggle),
                        cyc("Style", MODES, () -> VisionSettings.lyricsMode, v -> VisionSettings.lyricsMode = v, LYR),
                        tg("Album color", () -> VisionSettings.lyricsAlbumColor, v -> VisionSettings.lyricsAlbumColor = v, LYR),
                        tg("Typewriter", () -> VisionSettings.lyricsReveal, v -> VisionSettings.lyricsReveal = v, LYR),
                        tg("Word by word", () -> VisionSettings.lyricsWordByWord, v -> VisionSettings.lyricsWordByWord = v, LYR),
                        sl("Text size", 0.1f, 1.6f, () -> VisionSettings.lyricsScale, v -> VisionSettings.lyricsScale = v, ChatPanel::pct, LYR),
                        sl("Word reveal speed", 0.08f, 0.8f, () -> VisionSettings.lyricsWordSpeed, v -> VisionSettings.lyricsWordSpeed = v, v -> String.format(Locale.ROOT, "%.2f s/word", v), LYR),
                        sl("Max lines on screen", 1f, 8f, () -> VisionSettings.lyricsMaxLines, v -> VisionSettings.lyricsMaxLines = Math.round(v), ChatPanel::whole, LYR),
                        sl("Glow line lifetime", 2f, 20f, () -> VisionSettings.lyricsLifeSeconds, v -> VisionSettings.lyricsLifeSeconds = v, ChatPanel::secs, LYR))),
                new Page("Sync", List.of(
                        new Header("Lyric timing (saved per song)"),
                        new Action("Line starts NOW (tap sync)", FloatingLyrics::tapSync),
                        sl("This song offset", -3000f, 3000f, () -> LyricSync.offsetMs(FloatingLyrics.trackKey()),
                                v -> LyricSync.setOffset(FloatingLyrics.trackKey(), Math.round(v / 10f) * 10), ChatPanel::ms, LyricSync::save),
                        new Cycle("Lyric version", FloatingLyrics::candidateLabel, FloatingLyrics::cycleCandidate),
                        sl("All songs offset", -1500f, 1500f, () -> VisionSettings.lyricsGlobalOffsetMs, v -> VisionSettings.lyricsGlobalOffsetMs = Math.round(v / 10f) * 10, ChatPanel::ms, null),
                        tg("Latency comp.", () -> VisionSettings.lyricsLatencyComp, v -> VisionSettings.lyricsLatencyComp = v, null),
                        new Action("Reset this song", FloatingLyrics::resetSongSync))),
                new Page("View", List.of(
                        new Header("Player view (follows where you look)"),
                        tg("Player view", () -> VisionSettings.lyricsViewMode, v -> VisionSettings.lyricsViewMode = v, LYR),
                        tg("Face player", () -> VisionSettings.lyricsFaceAlways, v -> VisionSettings.lyricsFaceAlways = v, LYR),
                        tg("Fit on screen", () -> VisionSettings.lyricsFitScreen, v -> VisionSettings.lyricsFitScreen = v, LYR),
                        cyc("Spot", ANCHORS, () -> VisionSettings.lyricsViewAnchor, v -> VisionSettings.lyricsViewAnchor = v, LYR),
                        sl("Depth in front of you", 3f, 30f, () -> VisionSettings.lyricsViewDepth, v -> VisionSettings.lyricsViewDepth = v, ChatPanel::blocks, LYR),
                        sl("Spread from centre", 0.2f, 1f, () -> VisionSettings.lyricsViewSpread, v -> VisionSettings.lyricsViewSpread = v, ChatPanel::pct, LYR),
                        sl("Crosshair clear zone", 0.05f, 0.7f, () -> VisionSettings.lyricsCrosshairExclude, v -> VisionSettings.lyricsCrosshairExclude = v, ChatPanel::pct, LYR),
                        tg("Avoid top corners", () -> VisionSettings.lyricsCornerAvoid, v -> VisionSettings.lyricsCornerAvoid = v, LYR),
                        sl("Corner zone size", 0.1f, 0.8f, () -> VisionSettings.lyricsCornerSize, v -> VisionSettings.lyricsCornerSize = v, ChatPanel::pct, LYR),
                        sl("Lowest allowed edge", 0.15f, 1f, () -> VisionSettings.lyricsBottomLimit, v -> VisionSettings.lyricsBottomLimit = v, v -> pct(v) + " below center", LYR),
                        sl("Screen edge margin", 0.01f, 0.3f, () -> VisionSettings.lyricsScreenMargin, v -> VisionSettings.lyricsScreenMargin = v, ChatPanel::pct, LYR),
                        sl("Follow smoothing", 0f, 1f, () -> VisionSettings.lyricsViewSmoothing, v -> VisionSettings.lyricsViewSmoothing = v, ChatPanel::pct, null))),
                new Page("Anim", List.of(
                        new Header("Animations"),
                        cyc("Enter animation", LyricAnim.IN, () -> VisionSettings.lyricsAnimIn, v -> VisionSettings.lyricsAnimIn = v, null),
                        cyc("Exit animation", LyricAnim.OUT, () -> VisionSettings.lyricsAnimOut, v -> VisionSettings.lyricsAnimOut = v, null),
                        sl("Animation speed", 0.3f, 3f, () -> VisionSettings.lyricsAnimSpeed, v -> VisionSettings.lyricsAnimSpeed = v, ChatPanel::times, null),
                        sl("Floating bob", 0f, 1f, () -> VisionSettings.lyricsBob, v -> VisionSettings.lyricsBob = v, ChatPanel::pct, null),
                        sl("Beat pulse", 0f, 1f, () -> VisionSettings.lyricsPulse, v -> VisionSettings.lyricsPulse = v, ChatPanel::pct, null))),
                new Page("World", List.of(
                        new Header("Classic world placement"),
                        sl("Text distance", 6f, 40f, () -> VisionSettings.lyricsDistance, v -> VisionSettings.lyricsDistance = v, ChatPanel::blocks, LYR),
                        sl("Line spread", 0f, 1.5f, () -> VisionSettings.lyricsSpread, v -> VisionSettings.lyricsSpread = v, ChatPanel::pct, LYR),
                        sl("Horizontal position", -1f, 1f, () -> VisionSettings.lyricsSide, v -> VisionSettings.lyricsSide = v,
                                v -> Math.abs(v) < 0.05f ? "Center" : (v > 0 ? "Right " : "Left ") + Math.round(Math.abs(v) * 100) + "%", LYR),
                        sl("Vertical offset", -2f, 5f, () -> VisionSettings.lyricsVerticalOffset, v -> VisionSettings.lyricsVerticalOffset = v, ChatPanel::blocks, LYR),
                        sl("Horizontal offset", -5f, 5f, () -> VisionSettings.lyricsHorizontalOffset, v -> VisionSettings.lyricsHorizontalOffset = v, ChatPanel::blocks, LYR),
                        sl("Glow distance", 3f, 24f, () -> VisionSettings.lyricsWorldDistance, v -> VisionSettings.lyricsWorldDistance = v, ChatPanel::blocks, LYR),
                        tg("Random spawn", () -> VisionSettings.lyricsGlowRandomPosition, v -> VisionSettings.lyricsGlowRandomPosition = v, LYR),
                        sl("Random range", 0f, 8f, () -> VisionSettings.lyricsGlowRandomRange, v -> VisionSettings.lyricsGlowRandomRange = v, ChatPanel::blocks, LYR),
                        tg("Player relative", () -> VisionSettings.lyricsPlayerRelative, v -> VisionSettings.lyricsPlayerRelative = v, LYR),
                        tg("Follow rotation", () -> VisionSettings.lyricsFollowRotation, v -> VisionSettings.lyricsFollowRotation = v, LYR),
                        sl("Motion smoothing", 0f, 1f, () -> VisionSettings.lyricsGlowSmoothing, v -> VisionSettings.lyricsGlowSmoothing = v, ChatPanel::pct, null))),
                new Page("Glow", List.of(
                        new Header("Glow texture style"),
                        new Cycle("Font", () -> VisionSettings.lyricsFont, ChatPanel::cycleFont),
                        sl("Font size", 12f, 96f, () -> VisionSettings.lyricsFontSize, v -> VisionSettings.lyricsFontSize = Math.round(v), ChatPanel::px, GLOW),
                        sl("Glow intensity", 0f, 5f, () -> VisionSettings.glowIntensity, v -> VisionSettings.glowIntensity = v, ChatPanel::pct, GLOW),
                        sl("Glow radius", 2f, 18f, () -> VisionSettings.glowRadius, v -> VisionSettings.glowRadius = v, ChatPanel::px, GLOW))),
                new Page("HUD", List.of(
                        new Header("HUD panel style"),
                        cyc("Position", LyricsHud.POSITIONS, () -> VisionSettings.hudPosition, v -> VisionSettings.hudPosition = v, null),
                        sl("Visible lines", 2f, 6f, () -> VisionSettings.hudLines, v -> VisionSettings.hudLines = Math.round(v), ChatPanel::whole, null),
                        tg("Dark panel", () -> VisionSettings.hudPanel, v -> VisionSettings.hudPanel = v, null),
                        sl("Text size", 0.1f, 1.6f, () -> VisionSettings.lyricsScale, v -> VisionSettings.lyricsScale = v, ChatPanel::pct, LYR))));

        return List.of(new Tab("Window", window), new Tab("Island", island), new Tab("Lyrics", lyrics));
    }

    private static void cycleFont(int dir) {
        List<String> names = LyricFonts.names();
        if (names.isEmpty()) return;
        int i = names.indexOf(VisionSettings.lyricsFont);
        if (i < 0) i = 0;
        VisionSettings.lyricsFont = names.get(Math.floorMod(i + dir, names.size()));
        VisionSettings.save();
        FloatingLyrics.modeChanged();
    }

    // ------------------------------------------------------------------ frame

    private static void render(GuiGraphicsExtractor g, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !chatOpen(mc)) {
            if (dragItem != null) finishDrag();
            prevDown = false;
            return;
        }
        try {
            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            double mx = mc.mouseHandler.xpos() * gw / (double) Math.max(1, mc.getWindow().getScreenWidth());
            double my = mc.mouseHandler.ypos() * gh / (double) Math.max(1, mc.getWindow().getScreenHeight());
            boolean down = MouseProbe.leftDown(mc);
            Layout l = layout(gw, gh);
            if (down && !prevDown) press(l, mx, my);
            if (down && dragItem != null) drag(dragItem, mx);
            if (!down && prevDown && dragItem != null) finishDrag();
            prevDown = down;
            draw(g, mc, layout(gw, gh), mx, my);
        } catch (Throwable ignored) { }
    }

    private static Layout layout(int gw, int gh) {
        Tab t = TABS.get(tab);
        Page pg = t.pages().get(Math.min(pageOf[tab], t.pages().size() - 1));
        int px = gw - W - 8, x = px + 10, inner = W - 20, half = (inner - 6) / 2;
        List<Item> a = new ArrayList<>();
        int tabW = (inner - 4 * (TABS.size() - 1)) / TABS.size();
        for (int i = 0; i < TABS.size(); i++) a.add(new Item(Role.TAB, i, null, x + i * (tabW + 4), 0, tabW, 18));
        int y = 26, n = t.pages().size();
        if (n > 1) {
            int perRow = 4, rows = (n + perRow - 1) / perRow;
            for (int i = 0; i < n; i++) {
                int r = i / perRow, c = i % perRow, cnt = Math.min(perRow, n - r * perRow), cw = (inner - 3 * (cnt - 1)) / cnt;
                a.add(new Item(Role.PAGE, i, null, x + c * (cw + 3), y + r * 19, cw, 16));
            }
            y += rows * 19 + 6;
        } else {
            y += 4;
        }
        int limit = gh - 16 - FOOT;
        List<Entry> es = pg.entries();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, es.size() - 1)));
        int i = scroll;
        while (i < es.size()) {
            Entry e = es.get(i);
            if (e instanceof Header) {
                if (y + 14 > limit) break;
                a.add(new Item(Role.HEADER, i, e, x, y, inner, 12));
                y += 15;
                i++;
            } else if (e instanceof Action) {
                if (y + 18 > limit) break;
                a.add(new Item(Role.ACTION, i, e, x, y, inner, 18));
                y += 23;
                i++;
            } else if (e instanceof Toggle) {
                if (y + 18 > limit) break;
                if (i + 1 < es.size() && es.get(i + 1) instanceof Toggle) {
                    a.add(new Item(Role.TOGGLE, i, e, x, y, half, 18));
                    a.add(new Item(Role.TOGGLE, i + 1, es.get(i + 1), x + half + 6, y, half, 18));
                    i += 2;
                } else {
                    a.add(new Item(Role.TOGGLE, i, e, x, y, inner, 18));
                    i++;
                }
                y += 23;
            } else if (e instanceof Slider) {
                if (y + 28 > limit) break;
                a.add(new Item(Role.SLIDER, i, e, x, y + 12, inner, 12));
                y += 30;
                i++;
            } else {
                if (y + 28 > limit) break;
                a.add(new Item(Role.PREV, i, e, x, y + 11, 20, 16));
                a.add(new Item(Role.NEXT, i, e, x + inner - 20, y + 11, 20, 16));
                y += 30;
                i++;
            }
        }
        boolean more = i < es.size();
        int footY = y + 4;
        a.add(new Item(Role.RESET, 0, null, x, footY, 74, 16));
        if (scroll > 0) a.add(new Item(Role.UP, 0, null, x + inner - 42, footY, 20, 16));
        if (more) a.add(new Item(Role.DOWN, 0, null, x + inner - 20, footY, 20, 16));
        int height = footY + 38;
        int py = Math.max(8, Math.min(60, gh - height - 8));
        List<Item> shifted = new ArrayList<>(a.size());
        for (Item it : a) shifted.add(new Item(it.role(), it.index(), it.entry(), it.x(), it.y() + py, it.w(), it.h()));
        return new Layout(shifted, px, py, height);
    }

    // ------------------------------------------------------------------ input

    private static void press(Layout l, double mx, double my) {
        for (Item r : l.items()) {
            if (!r.has(mx, my)) continue;
            switch (r.role()) {
                case TAB -> { tab = r.index(); scroll = 0; }
                case PAGE -> { pageOf[tab] = r.index(); scroll = 0; }
                case TOGGLE -> ((Toggle) r.entry()).flip().run();
                case ACTION -> ((Action) r.entry()).run().run();
                case PREV -> ((Cycle) r.entry()).step().accept(-1);
                case NEXT -> ((Cycle) r.entry()).step().accept(1);
                case UP -> scroll = Math.max(0, scroll - 1);
                case DOWN -> scroll++;
                case SLIDER -> { dragItem = r; drag(r, mx); }
                case RESET -> {
                    long now = System.currentTimeMillis();
                    if (now < resetUntil) {
                        resetUntil = 0;
                        VisionSettings.reset();
                        FloatingLyrics.modeChanged();
                        Lyrics3D.refreshAppearance();
                    } else {
                        resetUntil = now + 3000;
                    }
                }
                default -> { }
            }
            return;
        }
    }

    private static void drag(Item r, double mx) {
        Slider s = (Slider) r.entry();
        float t = VisionSettings.clamp((float) ((mx - r.x()) / r.w()), 0f, 1f);
        s.set().set(s.min() + t * (s.max() - s.min()));
    }

    private static void finishDrag() {
        Item it = dragItem;
        dragItem = null;
        VisionSettings.save();
        if (it != null && it.entry() instanceof Slider s && s.commit() != null) s.commit().run();
    }

    // ------------------------------------------------------------------ drawing

    private static void draw(GuiGraphicsExtractor g, Minecraft mc, Layout l, double mx, double my) {
        Font font = mc.font;
        int x = l.x() + 10, y = l.y(), inner = W - 20, accent = IslandArt.accent() & 0xFFFFFF;
        DynamicIsland.roundedRect(g, l.x(), y, W, l.height(), 14, 0xB00C0C14);
        DynamicIsland.roundedRect(g, l.x() + 2, y + 2, W - 4, 27, 12, 0x12FFFFFF);
        for (Item r : l.items()) {
            boolean hot = r.has(mx, my) || (dragItem != null && dragItem.index() == r.index() && dragItem.role() == r.role() && r.role() == Role.SLIDER);
            switch (r.role()) {
                case TAB -> button(g, font, r, TABS.get(r.index()).name(), hot, tab == r.index(), accent);
                case PAGE -> button(g, font, r, TABS.get(tab).pages().get(r.index()).name(), hot, pageOf[tab] == r.index(), accent);
                case HEADER -> g.text(font, DynamicIsland.ellipsize(font, ((Header) r.entry()).label(), r.w()), r.x(), r.y(), 0xFF9AA6C4, false);
                case TOGGLE -> {
                    Toggle t = (Toggle) r.entry();
                    boolean on = t.get().getAsBoolean();
                    button(g, font, r, DynamicIsland.ellipsize(font, t.label() + (on ? ": On" : ": Off"), r.w() - 6), hot, on, accent);
                }
                case ACTION -> button(g, font, r, ((Action) r.entry()).label(), hot, false, accent);
                case SLIDER -> {
                    Slider s = (Slider) r.entry();
                    float v = s.get().get();
                    slider(g, font, r, s.label(), s.fmt().apply(v), (v - s.min()) / (s.max() - s.min()), hot, accent);
                }
                case PREV -> {
                    Cycle c = (Cycle) r.entry();
                    button(g, font, r, "<", hot, false, accent);
                    g.text(font, c.label(), r.x(), r.y() - 11, 0xFFD6DAE8, false);
                }
                case NEXT -> {
                    Cycle c = (Cycle) r.entry();
                    button(g, font, r, ">", hot, false, accent);
                    int gap = inner - 40 - 12;
                    String shown = DynamicIsland.ellipsize(font, c.value().get(), gap);
                    g.text(font, shown, x + 26 + (gap - font.width(shown)) / 2, r.y() + 4, 0xFFFFFFFF, false);
                }
                case UP -> button(g, font, r, "^", hot, false, accent);
                case DOWN -> button(g, font, r, "v", hot, false, accent);
                case RESET -> button(g, font, r, System.currentTimeMillis() < resetUntil ? "Sure? Click" : "Reset all", hot, System.currentTimeMillis() < resetUntil, 0xE05050);
            }
        }
        String status = switch (tab) {
            case 0 -> FloatWindow.status();
            case 2 -> VisionSettings.lyricsMode == VisionSettings.MODE_TEXT ? NameTagLyrics3D.status()
                    : VisionSettings.lyricsMode == VisionSettings.MODE_GLOW ? Lyrics3D.status() : "HUD lyrics";
            default -> "";
        };
        if (!status.isEmpty()) g.text(font, DynamicIsland.ellipsize(font, status, inner), x, y + l.height() - 16, 0xFF8992A8, false);
    }

    private static void button(GuiGraphicsExtractor g, Font font, Item r, String label, boolean hot, boolean on, int accent) {
        int fill = on ? (0x70000000 | accent) : (hot ? 0x40FFFFFF : 0x22FFFFFF);
        DynamicIsland.roundedRect(g, r.x(), r.y(), r.w(), r.h(), Math.min(8, r.h() / 2), fill);
        g.text(font, label, r.x() + (r.w() - font.width(label)) / 2, r.y() + (r.h() - 8) / 2, 0xFFFFFFFF, false);
    }

    private static void slider(GuiGraphicsExtractor g, Font font, Item r, String label, String value, float t, boolean hot, int accent) {
        t = VisionSettings.clamp(t, 0f, 1f);
        g.text(font, label, r.x(), r.y() - 12, 0xFFD6DAE8, false);
        g.text(font, value, r.x() + r.w() - font.width(value), r.y() - 12, 0xFFFFFFFF, false);
        int ty = r.y() + r.h() / 2 - 2;
        DynamicIsland.roundedRect(g, r.x(), ty, r.w(), 4, 2, 0x44FFFFFF);
        int fw = Math.round(r.w() * t);
        if (fw > 0) DynamicIsland.roundedRect(g, r.x(), ty, Math.max(4, fw), 4, 2, 0xE0000000 | accent | 0x404040);
        int kr = hot ? 5 : 4;
        DynamicIsland.roundedRect(g, r.x() + fw - kr, ty + 2 - kr, kr * 2, kr * 2, kr, 0xFFFFFFFF);
    }
}
