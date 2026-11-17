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
    record Candidate(long id, String label, double duration, String synced) { }
    private static volatile List<Candidate> candidates = List.of();
    private static volatile int candidateIndex;
    private static volatile String trackKey = "";
    private static volatile String toastText = "";
    private static volatile long toastUntil;
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
        trackKey = key.toLowerCase(java.util.Locale.ROOT);
        candidates = List.of();
        candidateIndex = 0;
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
        trackKey = "";
        candidates = List.of();
        lines = List.of();
        song = "";
        artist = "";
        message = "Play music in Vision Browser";
    }

    static String trackKey() { return trackKey; }
    static int candidateCount() { return candidates.size(); }

    static String candidateLabel() {
        List<Candidate> c = candidates;
        if (c.isEmpty()) return "none";
        Candidate cur = c.get(Math.min(candidateIndex, c.size() - 1));
        double media = VisionMediaBridge.durationSeconds();
        String diff = cur.duration() > 0 && media > 0 ? String.format(java.util.Locale.ROOT, " %+.0fs", cur.duration() - media) : "";
        return (candidateIndex + 1) + "/" + c.size() + diff;
    }

    static void setCandidates(String title, String artistName, List<Candidate> list) {
        candidates = list;
        long saved = LyricSync.candidateId(trackKey);
        int idx = 0;
        for (int i = 0; i < list.size(); i++) if (saved != 0 && list.get(i).id() == saved) { idx = i; break; }
        candidateIndex = idx;
        if (list.isEmpty()) { setUnavailable("No synchronized lyrics found"); return; }
        setLyrics(title, artistName, list.get(idx).synced());
    }

    /** switch to another version of the lyrics (they differ in timing); the choice is remembered for this song */
    static void cycleCandidate(int dir) {
        List<Candidate> c = candidates;
        if (c.size() < 2) { toast("Only one lyric version found"); return; }
        candidateIndex = Math.floorMod(candidateIndex + dir, c.size());
        Candidate cur = c.get(candidateIndex);
        LyricSync.setCandidate(trackKey, cur.id());
        LyricSync.save();
        setLyrics(song, artist, cur.synced());
        modeChanged();
        toast("Lyric version " + candidateLabel());
    }

    static void toast(String text) {
        toastText = text;
        toastUntil = System.currentTimeMillis() + 1600;
    }

    /** delta in ms: + shows lyrics earlier, - later; saved for this song */
    static void nudge(int deltaMs) {
        if (trackKey.isEmpty()) return;
        int v = Math.max(-5000, Math.min(5000, LyricSync.offsetMs(trackKey) + deltaMs));
        LyricSync.setOffset(trackKey, v);
        LyricSync.save();
        toast(String.format(java.util.Locale.ROOT, "Lyric sync %+d ms (saved for this song)", v));
    }

    /** the lyric line closest to the current moment is declared to start right now; the song offset is set to match and saved */
    static void tapSync() {
        List<Line> cur = lines;
        if (cur.isEmpty() || trackKey.isEmpty()) return;
        double lp = VisionMediaBridge.lyricPosition(), raw = VisionMediaBridge.positionSeconds();
        Line best = cur.get(0);
        for (Line l : cur) if (Math.abs(l.time() - lp) < Math.abs(best.time() - lp)) best = l;
        int off = (int) Math.round((best.time() - raw) * 1000.0 - VisionSettings.lyricsGlobalOffsetMs);
        off = Math.max(-5000, Math.min(5000, off));
        LyricSync.setOffset(trackKey, off);
        LyricSync.save();
        toast(String.format(java.util.Locale.ROOT, "Synced: %+d ms (saved for this song)", off));
    }

    static void resetSongSync() {
        LyricSync.setOffset(trackKey, 0);
        LyricSync.setCandidate(trackKey, 0);
        LyricSync.save();
        toast("Song sync reset");
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

    private static void hideLines(String key) {
        NameTagLyrics3D.update(key, "", 0);
        Lyrics3D.show(key, "");
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (!enabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (System.currentTimeMillis() < toastUntil) {
            String t = toastText;
            graphics.text(client.font, t, (client.getWindow().getGuiScaledWidth() - client.font.width(t)) / 2, 62, 0xFFFFFFFF, true);
        }
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

        double position = VisionMediaBridge.lyricPosition();
        int active = -1;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).time() <= position) active = i;
            else break;
        }
        boolean glowPath = mode == VisionSettings.MODE_GLOW || (mode == VisionSettings.MODE_TEXT && !NameTagLyrics3D.usable());
        if (active < 0) {
            // before the first vocal line: show nothing (no early first line)
            if (mode == VisionSettings.MODE_HUD) { Lyrics3D.clear(); NameTagLyrics3D.clear(); return; }
            hideLines(song + "#pre");
            if (glowPath) Lyrics3D.render(graphics, client);
            return;
        }
        double sinceCur = position - current.get(active).time();
        double gapLen = active + 1 < current.size() ? current.get(active + 1).time() - current.get(active).time() : 1e9;
        if (mode != VisionSettings.MODE_HUD && sinceCur > 6.5 && gapLen > 12) {
            // long instrumental gap or after the last line: let the lyric leave instead of hanging around
            hideLines(song + "#gap" + active);
            if (glowPath) Lyrics3D.render(graphics, client);
            return;
        }
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