package dev.nazhif.visionbrowser;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal, independent floating synced-lyrics HUD. */
final class FloatingLyrics {
    record Line(double time, String text) { }
    private static volatile List<Line> lines = List.of();
    private static volatile String song = "";
    private static volatile String artist = "";
    private static volatile String message = "Play music in Vision Browser";
    private static volatile boolean enabled = true;
    private static volatile String requestedTrack = "";
    private static final AtomicLong requestId = new AtomicLong();

    private FloatingLyrics() { }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("visionbrowser", "floating_lyrics"),
                FloatingLyrics::render);
    }

    static void toggle() { enabled = !enabled; modeChanged(); }

    /** drop whatever the previous lyric style still shows (called when the style is switched) */
    static void modeChanged() {
        Lyrics3D.clear();
        NameTagLyrics3D.clear();
    }
    static boolean isEnabled() { return enabled; }
    static boolean hasLyrics() { return !lines.isEmpty(); }

    static void loadTrack(String title, String artist, double duration) {
        String key = title + "|" + artist;
        if (key.equals(requestedTrack)) return;
        requestedTrack = key;
        long id = requestId.incrementAndGet();
        lines = List.of();
        song = title;
        FloatingLyrics.artist = artist;
        message = "Loading synchronized lyrics...";
        VisionMediaBridge.fetchLyrics(title, artist, duration);
    }

    static void clear() {
        requestId.incrementAndGet();
        requestedTrack = "";
        lines = List.of();
        song = "";
        artist = "";
        message = "Play music in Vision Browser";
    }

    static void setUnavailable(String text) {
        message = text;
        lines = List.of();
    }

    static void setLyrics(String title, String artistName, String lrc) {
        List<Line> parsed = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]\\s*(.*)")
                    .matcher(raw);
            while (matcher.find()) {
                double minutes = Double.parseDouble(matcher.group(1));
                double seconds = Double.parseDouble(matcher.group(2));
                String fraction = matcher.group(3);
                double millis = fraction == null ? 0 : Double.parseDouble("0." + fraction);
                String text = matcher.group(4).trim();
                if (!text.isEmpty()) parsed.add(new Line(minutes * 60 + seconds + millis, text));
            }
        }
        parsed.sort(java.util.Comparator.comparingDouble(Line::time));
        if (parsed.isEmpty()) {
            setUnavailable("No timed lyrics available");
            return;
        }
        song = title;
        artist = artistName;
        lines = List.copyOf(parsed);
        message = "";
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (!enabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        List<Line> current = lines;
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int mode = VisionSettings.lyricsMode;

        if (current.isEmpty()) {
            Lyrics3D.clear();
            NameTagLyrics3D.clear();
            if (song.isBlank() && message.startsWith("Play music")) return;
            // frameless status text (searching / not found), no box
            String label = song.isBlank() ? message : song + "  \u2022  " + message;
            graphics.text(client.font, label, centerX - client.font.width(label) / 2, (int) (screenHeight * 0.62f), 0xCCD6DAE8, true);
            return;
        }

        double position = VisionMediaBridge.positionSeconds();
        int active = -1;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).time() <= position) active = i;
            else break;
        }
        if (active < 0) active = 0;
        String key = song + "#" + active;

        if (mode == VisionSettings.MODE_HUD) {
            Lyrics3D.clear();
            NameTagLyrics3D.clear();
            LyricsHud.render(graphics, client, current, active, position, song);
            return;
        }
        if (mode == VisionSettings.MODE_TEXT && NameTagLyrics3D.usable()) {
            Lyrics3D.clear();
            long durationMs = active + 1 < current.size() ? Math.round((current.get(active + 1).time() - current.get(active).time()) * 1000) : 3500;
            NameTagLyrics3D.update(key, current.get(active).text(), durationMs);
            return;
        }
        // glow-texture 3D (chosen, or the fallback when the vanilla-text path is unavailable)
        NameTagLyrics3D.clear();
        Lyrics3D.show(key, current.get(active).text());
        if (Lyrics3D.render(graphics, client)) return;
        LyricsHud.render(graphics, client, current, active, position, song);   // last resort: HUD block
        graphics.text(client.font, Lyrics3D.status(), 8, screenHeight - 40, 0xFF8899AA, false);
    }
}