package dev.nazhif.visionbrowser;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Independent VisionBrowser lyrics state, synchronized only with Vision Browser media. */
final class FloatingLyrics {
    record Line(double time, double end, String text, List<LyricsEngine.Word> words) { }

    private static volatile LyricsEngine.Track track = new LyricsEngine.Track(List.of(), 0);
    private static volatile List<Line> lines = List.of();
    private static volatile String song = "";
    private static volatile String artist = "";
    private static volatile String message = "Play music in Vision Browser";
    private static volatile boolean enabled = true;
    private static volatile String requestedTrack = "";

    private FloatingLyrics() { }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("visionbrowser", "floating_lyrics"),
                FloatingLyrics::render);
    }

    static void toggle() {
        enabled = !enabled;
        modeChanged();
    }

    static void modeChanged() {
        Lyrics3D.clear();
        NameTagLyrics3D.clear();
    }

    static boolean isEnabled() { return enabled; }
    static boolean hasLyrics() { return !lines.isEmpty(); }
    static String song() { return song; }
    static String artist() { return artist; }
    static String message() { return message; }

    static void loadTrack(String title, String artistName, double duration) {
        String key = (title + "|" + artistName).trim().toLowerCase(java.util.Locale.ROOT);
        if (key.equals(requestedTrack)) return;
        requestedTrack = key;
        track = new LyricsEngine.Track(List.of(), 0);
        lines = List.of();
        song = title;
        artist = artistName;
        // Fetch in the background without showing a blocking loading banner.
        modeChanged();
        VisionMediaBridge.fetchLyrics(title, artistName, duration);
    }

    static void clear() {
        requestedTrack = "";
        track = new LyricsEngine.Track(List.of(), 0);
        lines = List.of();
        song = "";
        artist = "";
        message = "Play music in Vision Browser";
        modeChanged();
    }

    static void setUnavailable(String text) {
        track = new LyricsEngine.Track(List.of(), 0);
        lines = List.of();
        message = text;
        modeChanged();
    }

    static void setLyrics(String title, String artistName, String lrc) {
        LyricsEngine.Track parsed = LyricsEngine.parse(lrc);
        if (parsed.lines().isEmpty()) {
            setUnavailable("No timed lyrics available");
            return;
        }
        List<Line> converted = new ArrayList<>(parsed.lines().size());
        for (LyricsEngine.Line line : parsed.lines()) {
            converted.add(new Line(line.start(), line.end(), line.text(), line.words()));
        }
        song = title;
        artist = artistName;
        track = parsed;
        lines = List.copyOf(converted);
        message = "";
        modeChanged();
    }

    private static String progressiveGlowText(Line line, double position) {
        List<LyricsEngine.Word> words = line.words();
        if (words.isEmpty()) return line.text();
        StringBuilder visible = new StringBuilder();
        if (words.size() == 1) {
            // Standard LRC has only a line timestamp, not reliable per-word timing.
            // Show the complete line at its actual timestamp instead of inventing word delays.
            return position >= line.time() ? line.text() : "";
        } else {
            for (int i = 0; i < words.size(); i++) {
                LyricsEngine.Word word = words.get(i);
                if (position < word.start()) break;
                visible.append(LyricsEngine.displayWord(words, i));
            }
        }
        return visible.toString().trim();
    }

    static int activeLine(double positionSeconds) {
        return track.lines().isEmpty() ? -1 : track.indexAt(positionSeconds);
    }

    static int activeWord(int lineIndex, double positionSeconds) {
        return track.wordAt(lineIndex, positionSeconds);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (!enabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        List<Line> current = lines;
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();

        if (current.isEmpty()) {
            Lyrics3D.clear();
            NameTagLyrics3D.clear();
            if (song.isBlank() && message.startsWith("Play music")) return;
            String label = song.isBlank() ? message : message.isBlank() ? song : song + "  \\u2022  " + message;
            graphics.text(client.font, label, screenWidth / 2 - client.font.width(label) / 2,
                    (int) (screenHeight * 0.62f), 0xCCD6DAE8, true);
            return;
        }

        double position = VisionMediaBridge.positionSeconds();
        int active = activeLine(position);
        if (active < 0) active = 0;
        String key = song + "#" + active;
        int mode = VisionSettings.lyricsMode;

        if (mode == VisionSettings.MODE_HUD) {
            Lyrics3D.clear();
            NameTagLyrics3D.clear();
            LyricsHud.render(graphics, client, current, active, position, song);
            return;
        }
        if (mode == VisionSettings.MODE_TEXT && NameTagLyrics3D.usable()) {
            Lyrics3D.clear();
            NameTagLyrics3D.update(key, current.get(active), position);
            return;
        }

        NameTagLyrics3D.clear();
        Lyrics3D.show(key, progressiveGlowText(current.get(active), position));
        if (Lyrics3D.render(graphics, client)) return;
        LyricsHud.render(graphics, client, current, active, position, song);
        graphics.text(client.font, Lyrics3D.status(), 8, screenHeight - 40, 0xFF8899AA, false);
    }
}
