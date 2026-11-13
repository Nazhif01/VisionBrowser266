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
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, o.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // settings are a convenience; never break the game over them
        }
    }
}