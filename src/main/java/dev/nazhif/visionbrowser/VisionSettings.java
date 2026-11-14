package dev.nazhif.visionbrowser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** User-adjustable look-and-feel values, edited with the sliders that appear while the chat is open. Saved to config/visionbrowser.json. */
final class VisionSettings {
    static volatile float floatWidth = 1.8f;     // floating window width in blocks
    static volatile float islandScale = 1.0f;    // Dynamic Island size multiplier
    static volatile float lyricsSide = 0.6f;     // -1 (left) .. +1 (right): where new lyric lines appear, to the side of the view
    static volatile float lyricsScale = 1.0f;    // lyric size multiplier
    static volatile String lyricsFont = "Dialog";
    static volatile boolean floatLocked = true;  // floating window follows the player
    static final int MODE_TEXT = 0, MODE_GLOW = 1, MODE_HUD = 2;
    static volatile int lyricsMode = MODE_TEXT;       // default to spatial glow lyrics; 0 = vanilla 3D text, 1 = 3D glow texture, 2 = karaoke HUD
    static volatile float lyricsDistance = 18f;       // 3D text: blocks in front of the player (6..40)
    static volatile float lyricsSpread = 1.0f;        // 3D text: how far left/right lines alternate (0..1.5)
    static volatile boolean lyricsAlbumColor = true;  // active lyric takes the album-cover colour
    static volatile int lyricsAnimation = 0;          // 0 Edit, 1 Karaoke pulse, 2 Typewriter reveal, 3 Wave
    static volatile int lyricsIntensity = 1;          // 0 Chill, 1 Normal, 2 Hype
    static volatile boolean lyricsRainbow = false;    // animated spectrum tint
    static volatile int glowFacing = 1;              // 0 = fixed world plane, 1 = always face camera (billboard)
    static volatile int glowPlacement = 0;           // 0 = near crosshair, 1 = random within the forward view
    static volatile int hudPosition = 0;              // 0 above crosshair, 1 centre, 2 top, 3 bottom, 4 top-left, 5 top-right
    static volatile int hudLines = 3;                 // 2..6 lines in the HUD panel
    static volatile boolean hudPanel = false;         // dark panel behind the HUD lyrics

    private static final Path FILE = Path.of("config", "visionbrowser.json");

    private VisionSettings() { }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    static void load() {
        try {
            if (!Files.exists(FILE)) return;
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("floatWidth")) floatWidth = clamp(o.get("floatWidth").getAsFloat(), 0.6f, 7f);
            if (o.has("islandScale")) islandScale = clamp(o.get("islandScale").getAsFloat(), 0.6f, 1.8f);
            if (o.has("lyricsSide")) lyricsSide = clamp(o.get("lyricsSide").getAsFloat(), -1f, 1f);
            if (o.has("lyricsScale")) lyricsScale = clamp(o.get("lyricsScale").getAsFloat(), 0.6f, 1.6f);
            if (o.has("lyricsFont")) lyricsFont = o.get("lyricsFont").getAsString();
            if (o.has("floatLocked")) floatLocked = o.get("floatLocked").getAsBoolean();
            if (o.has("lyricsMode")) lyricsMode = Math.max(0, Math.min(2, o.get("lyricsMode").getAsInt()));
            if (o.has("lyricsDistance")) lyricsDistance = clamp(o.get("lyricsDistance").getAsFloat(), 6f, 40f);
            if (o.has("lyricsSpread")) lyricsSpread = clamp(o.get("lyricsSpread").getAsFloat(), 0f, 1.5f);
            if (o.has("lyricsAlbumColor")) lyricsAlbumColor = o.get("lyricsAlbumColor").getAsBoolean();
            if (o.has("lyricsAnimation")) lyricsAnimation = Math.max(0, Math.min(3, o.get("lyricsAnimation").getAsInt()));
            if (o.has("lyricsIntensity")) lyricsIntensity = Math.max(0, Math.min(2, o.get("lyricsIntensity").getAsInt()));
            if (o.has("lyricsRainbow")) lyricsRainbow = o.get("lyricsRainbow").getAsBoolean();
            if (o.has("glowFacing")) glowFacing = Math.max(0, Math.min(1, o.get("glowFacing").getAsInt()));
            if (o.has("glowPlacement")) glowPlacement = Math.max(0, Math.min(1, o.get("glowPlacement").getAsInt()));
            if (o.has("hudPosition")) hudPosition = Math.max(0, Math.min(5, o.get("hudPosition").getAsInt()));
            if (o.has("hudLines")) hudLines = Math.max(2, Math.min(6, o.get("hudLines").getAsInt()));
            if (o.has("hudPanel")) hudPanel = o.get("hudPanel").getAsBoolean();
        } catch (Throwable ignored) {
            // a broken file simply leaves the defaults in place
        }
    }

    static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("floatWidth", floatWidth);
            o.addProperty("islandScale", islandScale);
            o.addProperty("lyricsSide", lyricsSide);
            o.addProperty("lyricsScale", lyricsScale);
            o.addProperty("lyricsFont", lyricsFont);
            o.addProperty("floatLocked", floatLocked);
            o.addProperty("lyricsMode", lyricsMode);
            o.addProperty("lyricsDistance", lyricsDistance);
            o.addProperty("lyricsSpread", lyricsSpread);
            o.addProperty("lyricsAlbumColor", lyricsAlbumColor);
            o.addProperty("lyricsAnimation", lyricsAnimation);
            o.addProperty("lyricsIntensity", lyricsIntensity);
            o.addProperty("lyricsRainbow", lyricsRainbow);
            o.addProperty("glowFacing", glowFacing);
            o.addProperty("glowPlacement", glowPlacement);
            o.addProperty("hudPosition", hudPosition);
            o.addProperty("hudLines", hudLines);
            o.addProperty("hudPanel", hudPanel);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, o.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // settings are a convenience; never break the game over them
        }
    }
}