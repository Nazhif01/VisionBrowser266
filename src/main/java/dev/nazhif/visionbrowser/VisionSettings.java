package dev.nazhif.visionbrowser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** All user-adjustable values, edited in the chat settings window. Saved to config/visionbrowser.json. */
final class VisionSettings {
    static final int MODE_TEXT = 0, MODE_GLOW = 1, MODE_HUD = 2;

    // ---- app mode: false = browser mode, true = music mode (hidden YouTube Music player + search screen)
    static volatile boolean musicMode = false;

    // ---- floating window
    static volatile float floatWidth = 1.8f;
    static volatile float floatDistance = 3.0f;
    static volatile float floatHeight = 0f;
    static volatile float floatSide = 0f;
    static volatile float floatFollowSpeed = 9f;
    static volatile float floatOpacity = 1f;
    static volatile float floatCorner = 0.14f;
    static volatile boolean floatLocked = true;
    static volatile boolean floatBillboard = true;
    static volatile boolean floatLockBar = true;
    static volatile boolean floatToast = true;

    // ---- dynamic island
    static volatile boolean islandShow = true;
    static volatile float islandScale = 1.0f;
    static volatile float islandOffsetX = 0f;
    static volatile float islandOffsetY = 0f;

    // ---- lyrics: general
    static volatile int lyricsMode = MODE_TEXT;
    static volatile boolean lyricsAlbumColor = true;
    static volatile float lyricsScale = 1.0f;
    static volatile boolean lyricsReveal = true;
    static volatile boolean lyricsWordByWord = false;
    static volatile float lyricsWordSpeed = 0.22f;
    static volatile int lyricsMaxLines = 3;
    static volatile float lyricsLifeSeconds = 7.5f;

    // ---- lyrics: world placement (classic)
    static volatile float lyricsDistance = 18f;
    static volatile float lyricsSpread = 1.0f;
    static volatile float lyricsSide = 0.6f;
    static volatile float lyricsVerticalOffset = 1.4f;
    static volatile float lyricsHorizontalOffset = 0f;
    static volatile float lyricsWorldDistance = 7f;
    static volatile boolean lyricsGlowRandomPosition = true;
    static volatile float lyricsGlowRandomRange = 3.5f;
    static volatile boolean lyricsPlayerRelative = false;
    static volatile boolean lyricsFollowRotation = true;
    static volatile float lyricsGlowSmoothing = 0.55f;

    // ---- lyrics: player view (random spot in the view, never on the crosshair, always fully on screen, always facing the player)
    static volatile boolean lyricsViewMode = true;
    static volatile int lyricsViewAnchor = 0;          // 0 random, 1 top, 2 bottom, 3 upper-left, 4 upper-right, 5 lower-left, 6 lower-right
    static volatile float lyricsViewDepth = 9f;
    static volatile float lyricsCrosshairExclude = 0.32f;
    static volatile float lyricsScreenMargin = 0.08f;
    static volatile float lyricsViewSmoothing = 0.35f;
    static volatile boolean lyricsFaceAlways = true;
    static volatile boolean lyricsFitScreen = true;
    static volatile boolean lyricsCornerAvoid = true;   // keep random lyrics out of the top-left / top-right corners
    static volatile float lyricsCornerSize = 0.42f;
    static volatile float lyricsBottomLimit = 0.55f;    // lowest allowed edge of a lyric, as a fraction below screen centre (1 = very bottom)

    // ---- lyrics: timing
    static volatile float lyricsGlobalOffsetMs = 0f;    // + = lyrics earlier, - = later (applies to every song)
    static volatile boolean lyricsLatencyComp = true;   // compensate the delay between the player and the mod

    // ---- lyrics: animation
    static volatile int lyricsAnimIn = 2;
    static volatile int lyricsAnimOut = 1;
    static volatile float lyricsAnimSpeed = 1f;
    static volatile float lyricsBob = 0f;
    static volatile float lyricsPulse = 0f;

    // ---- lyrics: glow texture
    static volatile String lyricsFont = "Dialog";
    static volatile int lyricsFontSize = 48;
    static volatile float glowIntensity = 2.6f;
    static volatile float glowRadius = 8f;

    // ---- lyrics: HUD
    static volatile int hudPosition = 0;
    static volatile int hudLines = 3;
    static volatile boolean hudPanel = false;

    private static final Path FILE = Path.of("config", "visionbrowser.json");
    private static final JsonObject DEFAULTS = toJson();

    private VisionSettings() { }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampI(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static float f(JsonObject o, String k, float cur, float lo, float hi) { return o.has(k) ? clamp(o.get(k).getAsFloat(), lo, hi) : cur; }
    private static int i(JsonObject o, String k, int cur, int lo, int hi) { return o.has(k) ? clampI(o.get(k).getAsInt(), lo, hi) : cur; }
    private static boolean b(JsonObject o, String k, boolean cur) { return o.has(k) ? o.get(k).getAsBoolean() : cur; }

    private static void apply(JsonObject o) {
        musicMode = b(o, "musicMode", musicMode);
        floatWidth = f(o, "floatWidth", floatWidth, 0.6f, 7f);
        floatDistance = f(o, "floatDistance", floatDistance, 1.2f, 10f);
        floatHeight = f(o, "floatHeight", floatHeight, -2f, 2f);
        floatSide = f(o, "floatSide", floatSide, -3f, 3f);
        floatFollowSpeed = f(o, "floatFollowSpeed", floatFollowSpeed, 1f, 30f);
        floatOpacity = f(o, "floatOpacity", floatOpacity, 0.15f, 1f);
        floatCorner = f(o, "floatCorner", floatCorner, 0f, 0.5f);
        floatLocked = b(o, "floatLocked", floatLocked);
        floatBillboard = b(o, "floatBillboard", floatBillboard);
        floatLockBar = b(o, "floatLockBar", floatLockBar);
        floatToast = b(o, "floatToast", floatToast);

        islandShow = b(o, "islandShow", islandShow);
        islandScale = f(o, "islandScale", islandScale, 0.6f, 1.8f);
        islandOffsetX = f(o, "islandOffsetX", islandOffsetX, -300f, 300f);
        islandOffsetY = f(o, "islandOffsetY", islandOffsetY, 0f, 120f);

        lyricsMode = i(o, "lyricsMode", lyricsMode, 0, 2);
        lyricsAlbumColor = b(o, "lyricsAlbumColor", lyricsAlbumColor);
        lyricsScale = f(o, "lyricsScale", lyricsScale, 0.1f, 1.6f);
        lyricsReveal = b(o, "lyricsReveal", lyricsReveal);
        lyricsWordByWord = b(o, "lyricsWordByWord", lyricsWordByWord);
        lyricsWordSpeed = f(o, "lyricsWordSpeed", lyricsWordSpeed, 0.08f, 0.8f);
        lyricsMaxLines = i(o, "lyricsMaxLines", lyricsMaxLines, 1, 8);
        lyricsLifeSeconds = f(o, "lyricsLifeSeconds", lyricsLifeSeconds, 2f, 20f);

        lyricsDistance = f(o, "lyricsDistance", lyricsDistance, 6f, 40f);
        lyricsSpread = f(o, "lyricsSpread", lyricsSpread, 0f, 1.5f);
        lyricsSide = f(o, "lyricsSide", lyricsSide, -1f, 1f);
        lyricsVerticalOffset = f(o, "lyricsVerticalOffset", lyricsVerticalOffset, -2f, 5f);
        lyricsHorizontalOffset = f(o, "lyricsHorizontalOffset", lyricsHorizontalOffset, -5f, 5f);
        lyricsWorldDistance = f(o, "lyricsWorldDistance", lyricsWorldDistance, 3f, 24f);
        lyricsGlowRandomPosition = b(o, "lyricsGlowRandomPosition", lyricsGlowRandomPosition);
        lyricsGlowRandomRange = f(o, "lyricsGlowRandomRange", lyricsGlowRandomRange, 0f, 8f);
        lyricsPlayerRelative = b(o, "lyricsPlayerRelative", lyricsPlayerRelative);
        lyricsFollowRotation = b(o, "lyricsFollowRotation", lyricsFollowRotation);
        lyricsGlowSmoothing = f(o, "lyricsGlowSmoothing", lyricsGlowSmoothing, 0f, 1f);

        lyricsViewMode = b(o, "lyricsViewMode", lyricsViewMode);
        lyricsViewAnchor = i(o, "lyricsViewAnchor", lyricsViewAnchor, 0, 6);
        lyricsViewDepth = f(o, "lyricsViewDepth", lyricsViewDepth, 3f, 30f);
        lyricsCrosshairExclude = f(o, "lyricsCrosshairExclude", lyricsCrosshairExclude, 0.05f, 0.7f);
        lyricsScreenMargin = f(o, "lyricsScreenMargin", lyricsScreenMargin, 0.01f, 0.3f);
        lyricsViewSmoothing = f(o, "lyricsViewSmoothing", lyricsViewSmoothing, 0f, 1f);
        lyricsFaceAlways = b(o, "lyricsFaceAlways", lyricsFaceAlways);
        lyricsFitScreen = b(o, "lyricsFitScreen", lyricsFitScreen);
        lyricsCornerAvoid = b(o, "lyricsCornerAvoid", lyricsCornerAvoid);
        lyricsCornerSize = f(o, "lyricsCornerSize", lyricsCornerSize, 0.1f, 0.8f);
        lyricsBottomLimit = f(o, "lyricsBottomLimit", lyricsBottomLimit, 0.15f, 1f);

        lyricsGlobalOffsetMs = f(o, "lyricsGlobalOffsetMs", lyricsGlobalOffsetMs, -1500f, 1500f);
        lyricsLatencyComp = b(o, "lyricsLatencyComp", lyricsLatencyComp);
        lyricsAnimIn = i(o, "lyricsAnimIn", lyricsAnimIn, 0, LyricAnim.IN.length - 1);
        lyricsAnimOut = i(o, "lyricsAnimOut", lyricsAnimOut, 0, LyricAnim.OUT.length - 1);
        lyricsAnimSpeed = f(o, "lyricsAnimSpeed", lyricsAnimSpeed, 0.3f, 3f);
        lyricsBob = f(o, "lyricsBob", lyricsBob, 0f, 1f);
        lyricsPulse = f(o, "lyricsPulse", lyricsPulse, 0f, 1f);

        if (o.has("lyricsFont")) lyricsFont = o.get("lyricsFont").getAsString();
        lyricsFontSize = i(o, "lyricsFontSize", lyricsFontSize, 12, 96);
        glowIntensity = f(o, "glowIntensity", glowIntensity, 0f, 5f);
        glowRadius = f(o, "glowRadius", glowRadius, 2f, 18f);

        hudPosition = i(o, "hudPosition", hudPosition, 0, 5);
        hudLines = i(o, "hudLines", hudLines, 2, 6);
        hudPanel = b(o, "hudPanel", hudPanel);
    }

    private static JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("musicMode", musicMode);
        o.addProperty("floatWidth", floatWidth);
        o.addProperty("floatDistance", floatDistance);
        o.addProperty("floatHeight", floatHeight);
        o.addProperty("floatSide", floatSide);
        o.addProperty("floatFollowSpeed", floatFollowSpeed);
        o.addProperty("floatOpacity", floatOpacity);
        o.addProperty("floatCorner", floatCorner);
        o.addProperty("floatLocked", floatLocked);
        o.addProperty("floatBillboard", floatBillboard);
        o.addProperty("floatLockBar", floatLockBar);
        o.addProperty("floatToast", floatToast);
        o.addProperty("islandShow", islandShow);
        o.addProperty("islandScale", islandScale);
        o.addProperty("islandOffsetX", islandOffsetX);
        o.addProperty("islandOffsetY", islandOffsetY);
        o.addProperty("lyricsMode", lyricsMode);
        o.addProperty("lyricsAlbumColor", lyricsAlbumColor);
        o.addProperty("lyricsScale", lyricsScale);
        o.addProperty("lyricsReveal", lyricsReveal);
        o.addProperty("lyricsWordByWord", lyricsWordByWord);
        o.addProperty("lyricsWordSpeed", lyricsWordSpeed);
        o.addProperty("lyricsMaxLines", lyricsMaxLines);
        o.addProperty("lyricsLifeSeconds", lyricsLifeSeconds);
        o.addProperty("lyricsDistance", lyricsDistance);
        o.addProperty("lyricsSpread", lyricsSpread);
        o.addProperty("lyricsSide", lyricsSide);
        o.addProperty("lyricsVerticalOffset", lyricsVerticalOffset);
        o.addProperty("lyricsHorizontalOffset", lyricsHorizontalOffset);
        o.addProperty("lyricsWorldDistance", lyricsWorldDistance);
        o.addProperty("lyricsGlowRandomPosition", lyricsGlowRandomPosition);
        o.addProperty("lyricsGlowRandomRange", lyricsGlowRandomRange);
        o.addProperty("lyricsPlayerRelative", lyricsPlayerRelative);
        o.addProperty("lyricsFollowRotation", lyricsFollowRotation);
        o.addProperty("lyricsGlowSmoothing", lyricsGlowSmoothing);
        o.addProperty("lyricsViewMode", lyricsViewMode);
        o.addProperty("lyricsViewAnchor", lyricsViewAnchor);
        o.addProperty("lyricsViewDepth", lyricsViewDepth);
        o.addProperty("lyricsCrosshairExclude", lyricsCrosshairExclude);
        o.addProperty("lyricsScreenMargin", lyricsScreenMargin);
        o.addProperty("lyricsViewSmoothing", lyricsViewSmoothing);
        o.addProperty("lyricsFaceAlways", lyricsFaceAlways);
        o.addProperty("lyricsFitScreen", lyricsFitScreen);
        o.addProperty("lyricsCornerAvoid", lyricsCornerAvoid);
        o.addProperty("lyricsCornerSize", lyricsCornerSize);
        o.addProperty("lyricsBottomLimit", lyricsBottomLimit);
        o.addProperty("lyricsGlobalOffsetMs", lyricsGlobalOffsetMs);
        o.addProperty("lyricsLatencyComp", lyricsLatencyComp);
        o.addProperty("lyricsAnimIn", lyricsAnimIn);
        o.addProperty("lyricsAnimOut", lyricsAnimOut);
        o.addProperty("lyricsAnimSpeed", lyricsAnimSpeed);
        o.addProperty("lyricsBob", lyricsBob);
        o.addProperty("lyricsPulse", lyricsPulse);
        o.addProperty("lyricsFont", lyricsFont);
        o.addProperty("lyricsFontSize", lyricsFontSize);
        o.addProperty("glowIntensity", glowIntensity);
        o.addProperty("glowRadius", glowRadius);
        o.addProperty("hudPosition", hudPosition);
        o.addProperty("hudLines", hudLines);
        o.addProperty("hudPanel", hudPanel);
        return o;
    }

    static void load() {
        try {
            if (!Files.exists(FILE)) return;
            apply(JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Throwable ignored) {
            // a broken file simply leaves the defaults in place
        }
    }

    static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, toJson().toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // settings are a convenience; never break the game over them
        }
    }

    /** back to factory values for everything */
    static void reset() {
        apply(DEFAULTS);
        FloatWindow.syncLockFromSettings();
        save();
    }
}