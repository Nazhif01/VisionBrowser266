package dev.nazhif.visionbrowser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers, per song, the lyric timing offset (ms) and which lyric version was chosen, so a song only has to be tuned once.
 * Only a few bytes per song go to config/visionbrowser-sync.json; no audio is ever stored.
 */
final class LyricSync {
    private record Entry(int offsetMs, long candidateId) { }

    private static final Path FILE = Path.of("config", "visionbrowser-sync.json");
    private static final Map<String, Entry> map = new HashMap<>();
    private static boolean loaded;

    private LyricSync() { }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String k : o.keySet()) {
                JsonObject e = o.getAsJsonObject(k);
                map.put(k, new Entry(e.has("o") ? e.get("o").getAsInt() : 0, e.has("c") ? e.get("c").getAsLong() : 0L));
            }
        } catch (Throwable ignored) { }
    }

    static synchronized int offsetMs(String key) {
        if (key == null || key.isEmpty()) return 0;
        ensureLoaded();
        Entry e = map.get(key);
        return e == null ? 0 : e.offsetMs();
    }

    static synchronized long candidateId(String key) {
        if (key == null || key.isEmpty()) return 0;
        ensureLoaded();
        Entry e = map.get(key);
        return e == null ? 0 : e.candidateId();
    }

    static synchronized void setOffset(String key, int ms) {
        if (key == null || key.isEmpty()) return;
        ensureLoaded();
        Entry e = map.get(key);
        put(key, new Entry(Math.max(-5000, Math.min(5000, ms)), e == null ? 0 : e.candidateId()));
    }

    static synchronized void setCandidate(String key, long id) {
        if (key == null || key.isEmpty()) return;
        ensureLoaded();
        Entry e = map.get(key);
        put(key, new Entry(e == null ? 0 : e.offsetMs(), id));
    }

    private static void put(String key, Entry e) {
        if (e.offsetMs() == 0 && e.candidateId() == 0) map.remove(key);
        else map.put(key, e);
    }

    static synchronized void save() {
        try {
            ensureLoaded();
            JsonObject o = new JsonObject();
            int n = 0;
            for (Map.Entry<String, Entry> en : map.entrySet()) {
                if (++n > 3000) break;
                JsonObject e = new JsonObject();
                e.addProperty("o", en.getValue().offsetMs());
                e.addProperty("c", en.getValue().candidateId());
                o.add(en.getKey(), e);
            }
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, o.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) { }
    }
}