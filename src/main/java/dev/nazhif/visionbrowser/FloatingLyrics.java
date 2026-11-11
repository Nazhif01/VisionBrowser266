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
    private record Line(double time, String text) { }
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

    static void toggle() { enabled = !enabled; }
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
        if (client.player == null || client.options.hideGui) return;

        List<Line> current = lines;
        int sw = client.getWindow().getGuiScaledWidth();
        int sh = client.getWindow().getGuiScaledHeight();
        int cx = sw / 2;
        // Head-locked lyric placement: stays in the player's view, above the crosshair.
        int anchorY = Math.max(42, (int) (sh * 0.30f));

        if (current.isEmpty()) {
            if (song.isBlank() && message.startsWith("Play music")) return;
            String label = song.isBlank() ? message : song + "  •  " + message;
            label = DynamicIsland.ellipsize(client.font, label, Math.max(80, sw - 80));
            drawGlowText(graphics, client, label, cx, anchorY, 0xFFDDEBFF, 0x557DBBFF, false);
            return;
        }

        double position = VisionMediaBridge.positionSeconds();
        int active = -1;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).time() <= position) active = i;
            else break;
        }
        if (active < 0) active = 0;

        String heading = song + (artist.isBlank() ? "" : "  ·  " + artist);
        heading = DynamicIsland.ellipsize(client.font, heading, Math.max(80, sw - 100));
        int headingY = anchorY - 39;
        graphics.text(client.font, heading, cx - client.font.width(heading) / 2,
                headingY, 0x99D5E4F5, false);

        if (active > 0) {
            String previous = DynamicIsland.ellipsize(client.font, current.get(active - 1).text(), sw - 100);
            drawGlowText(graphics, client, previous, cx, anchorY - 19, 0x99B9C9DF, 0x223D78B8, false);
        }
        String lyric = DynamicIsland.ellipsize(client.font, current.get(active).text(), sw - 80);
        drawGlowText(graphics, client, lyric, cx, anchorY, 0xFFFFFFFF, 0xAA8BCBFF, true);

        if (active + 1 < current.size()) {
            String next = DynamicIsland.ellipsize(client.font, current.get(active + 1).text(), sw - 100);
            drawGlowText(graphics, client, next, cx, anchorY + 21, 0x99B9C9DF, 0x223D78B8, false);
        }
    }

    private static void drawGlowText(GuiGraphicsExtractor graphics, Minecraft client, String text,
                                     int centerX, int y, int color, int glow, boolean strong) {
        int x = centerX - client.font.width(text) / 2;
        if (strong) {
            graphics.text(client.font, text, x - 1, y, glow, false);
            graphics.text(client.font, text, x + 1, y, glow, false);
            graphics.text(client.font, text, x, y - 1, glow, false);
            graphics.text(client.font, text, x, y + 1, glow, false);
        } else {
            graphics.text(client.font, text, x, y + 1, glow, false);
        }
        graphics.text(client.font, text, x, y, color, false);
    }
}
