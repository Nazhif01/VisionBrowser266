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
        if (client.player == null) return;
        List<Line> current = lines;
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int baseY = (int) (screenHeight * 0.72f);

        if (current.isEmpty()) {
            Lyrics3D.clear();
            if (song.isBlank() && message.startsWith("Play music")) return;
            String label = song.isBlank() ? message : song + "  •  " + message;
            int width = client.font.width(label) + 28;
            int x = centerX - width / 2;
            graphics.fill(x, baseY - 12, x + width, baseY + 16, 0xA8141D2B);
            graphics.fill(x + 1, baseY - 11, x + width - 1, baseY - 10, 0x669BC9F0);
            graphics.text(client.font, label, centerX - client.font.width(label) / 2,
                    baseY - 3, 0xFFEAF1FF, false);
            return;
        }

        double position = VisionMediaBridge.positionSeconds();
        int active = -1;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).time() <= position) active = i;
            else break;
        }
        if (active < 0) active = 0;
        Lyrics3D.show(song + "#" + active, current.get(active).text());
        if (Lyrics3D.render(graphics, client)) return; // lyrics are drawn floating in the world
        // 3D unavailable this frame: fall back to the 2D panel below and show why
        int first = Math.max(0, active - 1);
        int last = Math.min(current.size() - 1, active + 1);
        int maxWidth = 0;
        for (int i = first; i <= last; i++) maxWidth = Math.max(maxWidth, client.font.width(current.get(i).text()));
        int panelWidth = Math.min(screenWidth - 32, maxWidth + 48);
        int panelHeight = (last - first + 1) * 20 + 34;
        int panelX = centerX - panelWidth / 2;
        int panelY = baseY - panelHeight / 2;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xA8141D2B);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + 2, 0xAA9BC9F0);
        String label = song + "  •  " + artist;
        graphics.text(client.font, label, centerX - client.font.width(label) / 2,
                panelY + 7, 0xFFB7CBE2, false);
        for (int i = first; i <= last; i++) {
            String text = current.get(i).text();
            int color = i == active ? 0xFFFFFFFF : 0xFF9BAFC8;
            int x = centerX - client.font.width(text) / 2;
            graphics.text(client.font, text, x, panelY + 22 + (i - first) * 20, color, false);
        }
        graphics.text(client.font, Lyrics3D.status(), panelX + 6, panelY + panelHeight + 4, 0xFF8899AA, false);
    }
}
