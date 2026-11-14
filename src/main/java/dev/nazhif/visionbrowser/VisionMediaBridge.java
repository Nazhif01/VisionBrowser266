package dev.nazhif.visionbrowser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Reads playback state from the browser page and sends control commands back.
 *
 * Read path : injected JS -> document.title -> CefDisplayHandler.onTitleChange (registered on Rinku.getClient()).
 *             Rinku 3.0.5 has NO getTitle() method, so a handler is the only title channel.
 * Write path: RinkuBrowser.executeJavaScript(code, url, line) (inherited from CefBrowser).
 * All Rinku calls are made on the Minecraft client thread (tick / UI events), the same thread the rest of the mod uses.
 */
final class VisionMediaBridge {
    private static final String PREFIX = "__VISION_MEDIA__";
    private static final int SCRIPT_VERSION = 4;

    private static volatile boolean handlerRegistered;
    private static volatile CefBrowser mediaBrowser;
    private static volatile int mediaBrowserId = -1;
    private static volatile String trackTitle = "";
    private static volatile String artist = "";
    private static volatile String album = "";
    private static volatile String artUrl = "";
    private static volatile boolean playing;
    private static volatile double positionSeconds;
    private static volatile double durationSeconds;
    private static volatile float volume = 1f;
    private static volatile boolean muted;
    private static volatile long positionUpdatedAt;
    private static volatile long lastPayloadAt;
    private static volatile long playLockUntil;
    private static volatile long volumeLockUntil;
    private static volatile String lastTrackKey = "";
    private static volatile String status = "Open a music page in Vision Browser";
    private static int tickCounter;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private record LyricsCacheEntry(String lyrics, long expiresAt) { }
    private static final Map<String, LyricsCacheEntry> LYRICS_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService LYRICS_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "VisionBrowser-LyricsProvider");
        thread.setDaemon(true);
        return thread;
    });
    private static final long POSITIVE_CACHE_MS = Duration.ofHours(12).toMillis();
    private static final long NEGATIVE_CACHE_MS = Duration.ofMinutes(2).toMillis();
    private static final int MAX_CACHE_ENTRIES = 128;

    private static final String CAPTURE_SCRIPT = ("(function(){try{"
            + "if(window.__vmV===@V@)return;window.__vmV=@V@;"
            + "var P='@PREFIX@',last='';"
            + "function el(){var c=[document.querySelector('video'),document.querySelector('audio')];"
            + "for(var i=0;i<c.length;i++){if(c[i]&&c[i].readyState>0)return c[i];}return c[0]||c[1];}"
            + "function art(m){try{var a=m&&m.artwork;if(a&&a.length){return a[a.length-1].src||'';}}catch(e){}return '';}"
            + "function scan(){try{var v=el();if(!v)return;"
            + "var m=navigator.mediaSession&&navigator.mediaSession.metadata;"
            + "var t=(m&&m.title)||'',a=(m&&m.artist)||'',al=(m&&m.album)||'';"
            + "if(!t){var x=document.querySelector('ytmusic-player-bar .title');if(x)t=(x.textContent||'').trim();}"
            + "if(!a){var y=document.querySelector('ytmusic-player-bar .byline');if(y)a=(y.textContent||'').trim();}"
            + "if(!t)return;"
            + "var o={t:t,a:a,al:al,art:art(m),on:!v.paused&&!v.ended,pos:v.currentTime||0,"
            + "dur:isFinite(v.duration)?v.duration:0,vol:v.volume,mut:!!v.muted};"
            + "var n=P+encodeURIComponent(JSON.stringify(o));"
            + "if(n!==last){last=n;document.title=n;}"
            + "}catch(e){}}"
            + "scan();setInterval(scan,100);"
            + "['play','pause','loadedmetadata','seeked','volumechange'].forEach(function(ev){document.addEventListener(ev,scan,true);});"
            + "}catch(e){}})();")
            .replace("@V@", Integer.toString(SCRIPT_VERSION)).replace("@PREFIX@", PREFIX);

    private static final String JS_PREFIX = "var v=document.querySelector('video,audio');";
    private static final String JS_MEDIA_HELPERS =
            "function deep(sel){var q=[document];while(q.length){var r=q.shift();try{var e=r.querySelector(sel);if(e)return e;"
            + "var all=r.querySelectorAll('*');for(var i=0;i<all.length;i++)if(all[i].shadowRoot)q.push(all[i].shadowRoot);}catch(e){}}return null;}"
            + "function media(){return document.querySelector('video,audio');}"
            + "function clickAny(ids){for(var i=0;i<ids.length;i++){var b=deep(ids[i]);if(b){b.click();return true;}}return false;}";
    private static final String JS_TOGGLE = "(function(){"+JS_MEDIA_HELPERS
            + "var v=media();if(v){if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else{v.pause();}return;}"
            + "clickAny(['ytmusic-player-bar #play-pause-button','#play-pause-button','button[aria-label*=Pause]','button[aria-label*=Play]']);})();";
    private static final String JS_MUTE = "(function(){"+JS_MEDIA_HELPERS+"var v=media();if(v)v.muted=!v.muted;})();";
    private static final String JS_NEXT = "(function(){"+JS_MEDIA_HELPERS
            + "if(clickAny(['ytmusic-player-bar #next-button','#next-button','.next-button','.ytp-next-button','button[aria-label*=Next]']))return;"
            + "var v=media();if(v&&v.duration>0)v.currentTime=Math.min(v.duration,v.currentTime+10);})();";
    private static final String JS_PREV = "(function(){"+JS_MEDIA_HELPERS
            + "if(clickAny(['ytmusic-player-bar #previous-button','#previous-button','.previous-button','.ytp-prev-button','button[aria-label*=Previous]']))return;"
            + "var v=media();if(v)v.currentTime=0;})();";

    private VisionMediaBridge() { }

    static void start() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    // ------------------------------------------------------------------ read path

    private static void ensureHandler() {
        if (handlerRegistered) return;
        try {
            if (!Rinku.isInitialized()) return;
            Rinku.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override
                public void onTitleChange(CefBrowser browser, String title) {
                    onTitle(browser, title);
                }
            });
            handlerRegistered = true;
            status = "Waiting for music playback";
        } catch (Throwable error) {
            status = "Media handler failed: " + error.getClass().getSimpleName();
        }
    }

    private static void tick() {
        ensureHandler();
        if (++tickCounter % 5 != 0) return;
        if (handlerRegistered) {
            for (RinkuBrowser browser : VisionBrowserClient.getTabs()) {
                try {
                    browser.executeJavaScript(CAPTURE_SCRIPT, "visionbrowser://media-capture", 0);
                } catch (Throwable error) {
                    status = "Cannot inject media script: " + error.getClass().getSimpleName();
                }
            }
        }
        if (!lastTrackKey.isEmpty() && System.currentTimeMillis() - lastPayloadAt > 5000) {
            clearTrack("Playback stopped");
        }
    }

    private static void onTitle(CefBrowser browser, String title) {
        if (title == null || !title.startsWith(PREFIX)) return;
        try {
            String raw = URLDecoder.decode(title.substring(PREFIX.length()), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            String t = str(o, "t");
            if (t.isBlank()) return;
            boolean on = o.has("on") && o.get("on").getAsBoolean();
            int id = browser.getIdentifier();
            if (!on && mediaBrowserId != -1 && mediaBrowserId != id) return; // another tab is the active source
            long now = System.currentTimeMillis();
            String a = str(o, "a");
            double dur = num(o, "dur");
            mediaBrowser = browser;
            mediaBrowserId = id;
            trackTitle = t;
            artist = a;
            album = str(o, "al");
            if (now >= playLockUntil) playing = on;
            positionSeconds = num(o, "pos");
            durationSeconds = dur;
            positionUpdatedAt = now;
            lastPayloadAt = now;
            if (now >= volumeLockUntil) {
                volume = (float) Math.max(0, Math.min(1, num(o, "vol")));
                muted = o.has("mut") && o.get("mut").getAsBoolean();
            }
            String key = (t + "|" + a).toLowerCase(Locale.ROOT);
            if (!key.equals(lastTrackKey)) {
                lastTrackKey = key;
                status = "Finding synchronized lyrics";
                FloatingLyrics.loadTrack(t, a, dur);
            } else if (FloatingLyrics.hasLyrics()) {
                status = "Lyrics synchronized";
            }
            String art = str(o, "art");
            if (!art.equals(artUrl)) {
                artUrl = art;
                IslandArt.request(art);
            }
        } catch (Throwable ignored) {
            status = "Cannot read current track";
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static double num(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void clearTrack(String message) {
        mediaBrowser = null;
        mediaBrowserId = -1;
        trackTitle = "";
        artist = "";
        album = "";
        artUrl = "";
        playing = false;
        positionSeconds = 0;
        durationSeconds = 0;
        lastTrackKey = "";
        status = message;
        IslandArt.request("");
        FloatingLyrics.clear();
    }

    // ------------------------------------------------------------------ write path (commands)

    private static void exec(String js) {
        CefBrowser target = mediaBrowser;
        if (target == null) target = VisionBrowserClient.getActiveBrowser();
        if (target == null) return;
        try {
            target.executeJavaScript(js, "visionbrowser://command", 0);
        } catch (Throwable error) {
            status = "Command failed: " + error.getClass().getSimpleName();
        }
    }

    static void togglePlay() {
        long now = System.currentTimeMillis();
        double p = positionSeconds();
        playing = !playing;
        positionSeconds = p;
        positionUpdatedAt = now;
        playLockUntil = now + 900;
        exec(JS_TOGGLE);
    }

    static void seek(double seconds) {
        double s = Math.max(0, durationSeconds > 0 ? Math.min(seconds, durationSeconds) : seconds);
        positionSeconds = s;
        positionUpdatedAt = System.currentTimeMillis();
        exec(String.format(Locale.ROOT,
                "(function(){" + JS_PREFIX + "if(v&&isFinite(%1$f))v.currentTime=%1$f;})();", s));
    }

    static void setVolume(float value) {
        float v = Math.max(0f, Math.min(1f, value));
        volume = v;
        muted = false;
        volumeLockUntil = System.currentTimeMillis() + 1500;
        exec(String.format(Locale.ROOT,
                "(function(){" + JS_PREFIX + "if(v){v.muted=false;v.volume=%1$f;}"
                        + "var p=document.getElementById('movie_player');"
                        + "if(p&&p.setVolume){try{p.setVolume(%2$d);}catch(e){}}})();", v, Math.round(v * 100)));
    }

    static void toggleMute() {
        muted = !muted;
        volumeLockUntil = System.currentTimeMillis() + 1500;
        exec(JS_MUTE);
    }

    static void next() { exec(JS_NEXT); }

    static void previous() { exec(JS_PREV); }

    // ------------------------------------------------------------------ state for the UI

    static boolean hasMedia() {
        return !lastTrackKey.isEmpty() && System.currentTimeMillis() - lastPayloadAt < 5000;
    }

    static String title() { return trackTitle; }
    static String artist() { return artist; }
    static String album() { return album; }
    static boolean isPlaying() { return playing; }
    static String status() { return status; }
    static double durationSeconds() { return durationSeconds; }
    static float volume() { return volume; }
    static boolean isMuted() { return muted; }

    static double positionSeconds() {
        double p = positionSeconds;
        if (playing) p += Math.max(0, System.currentTimeMillis() - positionUpdatedAt) / 1000.0;
        return durationSeconds > 0 ? Math.min(p, durationSeconds) : p;
    }

    // ------------------------------------------------------------------ lyrics

    private static String lyricsCacheKey(String title, String artist) {
        return normalize(title) + "|" + normalize(artist);
    }

    private static void cacheLyrics(String key, String lyrics, long ttl) {
        if (LYRICS_CACHE.size() >= MAX_CACHE_ENTRIES) {
            long now = System.currentTimeMillis();
            LYRICS_CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
            if (LYRICS_CACHE.size() >= MAX_CACHE_ENTRIES) {
                String oldest = LYRICS_CACHE.keySet().stream().findFirst().orElse(null);
                if (oldest != null) LYRICS_CACHE.remove(oldest);
            }
        }
        LYRICS_CACHE.put(key, new LyricsCacheEntry(lyrics, System.currentTimeMillis() + ttl));
    }

    static void fetchLyrics(String title, String artist, double duration) {
        final String requestKey = (title + "|" + artist).toLowerCase(Locale.ROOT);
        final String cacheKey = lyricsCacheKey(title, artist);
        LyricsCacheEntry cached = LYRICS_CACHE.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            Minecraft.getInstance().execute(() -> {
                if (!requestKey.equals(lastTrackKey)) return;
                if (cached.lyrics().isBlank()) {
                    FloatingLyrics.setUnavailable("Word-synced lyrics are not available for this track");
                } else {
                    FloatingLyrics.setLyrics(title, artist, cached.lyrics());
                }
            });
            return;
        }
        if (cached != null) LYRICS_CACHE.remove(cacheKey, cached);

        Thread coordinator = new Thread(() -> {
            CompletionService<String> completed = new ExecutorCompletionService<>(LYRICS_EXECUTOR);
            List<Future<String>> requests = new java.util.ArrayList<>(4);
            requests.add(completed.submit(() -> fetchLiriQo(title, artist, duration, requestKey)));
            requests.add(completed.submit(() -> fetchKaralyr(title, artist, duration, requestKey)));
            requests.add(completed.submit(() -> {
                return fetchLrclibExact(title, artist, duration, requestKey);
            }));
            requests.add(completed.submit(() -> searchLyrics(title, artist, duration, requestKey)));

            String result = "";
            String lineTimedFallback = "";
            try {
                for (int i = 0; i < requests.size(); i++) {
                    if (!requestKey.equals(lastTrackKey)) return;
                    try {
                        String candidate = completed.take().get();
                        if (LyricsEngine.hasWordTiming(candidate)) {
                            result = candidate;
                            break;
                        }
                        if (lineTimedFallback.isBlank() && hasTimedLines(candidate)) {
                            lineTimedFallback = candidate;
                        }
                    } catch (Exception ignored) {
                        // A failed provider must not delay or prevent the others.
                    }
                }
                if (result.isBlank()) result = lineTimedFallback;
            } finally {
                for (Future<String> request : requests) {
                    if (!request.isDone()) request.cancel(true);
                }
            }

            if (!requestKey.equals(lastTrackKey)) return;
            final String found = result;
            cacheLyrics(cacheKey, found, found.isBlank() ? NEGATIVE_CACHE_MS : POSITIVE_CACHE_MS);
            Minecraft.getInstance().execute(() -> {
                if (!requestKey.equals(lastTrackKey)) return;
                if (found.isBlank()) {
                    FloatingLyrics.setUnavailable("Word-synced lyrics are not available for this track");
                } else {
                    FloatingLyrics.setLyrics(title, artist, found);
                }
            });
        }, "VisionBrowser-LyricsLookup");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    private static String fetchLiriQo(String title, String artist, double duration, String requestKey) throws Exception {
        String query = "title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&artist=" + URLEncoder.encode(artist, StandardCharsets.UTF_8)
                + "&full=1";
        if (duration > 0 && duration < 3600) query += "&duration=" + Math.round(duration);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.liriqo-alfarrizi.my.id/v1/lyrics?" + query))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "VisionBrowser/0.1.0 (https://github.com/Nazhif01/VisionBrowser266)")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !requestKey.equals(lastTrackKey)) return "";

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("error") && !json.get("error").isJsonNull()) return "";
        JsonObject primary = json.has("primary") && json.get("primary").isJsonObject()
                ? json.getAsJsonObject("primary") : null;
        if (primary == null) return "";

        String syncLevel = str(primary, "syncLevel").toLowerCase(Locale.ROOT);
        if (!syncLevel.equals("word") && !syncLevel.equals("syllable")
                && !syncLevel.equals("character")) return "";

        com.google.gson.JsonArray timed = primary.has("timed") && primary.get("timed").isJsonArray()
                ? primary.getAsJsonArray("timed") : null;
        if (timed == null || timed.isEmpty()) return "";

        StringBuilder lrc = new StringBuilder();
        for (com.google.gson.JsonElement element : timed) {
            if (!element.isJsonObject()) continue;
            JsonObject line = element.getAsJsonObject();
            double lineStart = num(line, "start") / 1000.0;
            com.google.gson.JsonArray words = line.has("words") && line.get("words").isJsonArray()
                    ? line.getAsJsonArray("words") : null;
            if (words == null && line.has("id") && primary.has("words")
                    && primary.get("words").isJsonObject()) {
                String id = str(line, "id");
                JsonObject wordMap = primary.getAsJsonObject("words");
                if (wordMap.has(id) && wordMap.get(id).isJsonArray()) {
                    words = wordMap.getAsJsonArray(id);
                }
            }
            if (words == null || words.isEmpty()) continue;

            lrc.append('[').append(formatLrcTime(lineStart)).append(']');
            String canonicalLine = str(line, "text");
            int canonicalCursor = 0;
            boolean hasPreviousChunk = false;
            for (com.google.gson.JsonElement wordElement : words) {
                if (!wordElement.isJsonObject()) continue;
                JsonObject word = wordElement.getAsJsonObject();
                String chunk = str(word, "text").trim();
                if (chunk.isEmpty()) continue;

                int match = canonicalLine.isEmpty() ? -1 : canonicalLine.indexOf(chunk, canonicalCursor);
                if (hasPreviousChunk) {
                    if (match >= canonicalCursor) {
                        lrc.append(canonicalLine, canonicalCursor, match);
                    } else if (syncLevel.equals("word")) {
                        // Word-level provider chunks should be separated when canonical text
                        // is missing or inconsistent. Syllable/character chunks stay joined.
                        lrc.append(' ');
                    }
                }

                lrc.append('<').append(formatLrcTime(num(word, "start") / 1000.0)).append('>')
                        .append(chunk);
                if (match >= canonicalCursor) canonicalCursor = match + chunk.length();
                hasPreviousChunk = true;
            }
            if (!canonicalLine.isEmpty() && canonicalCursor > 0 && canonicalCursor < canonicalLine.length()) {
                lrc.append(canonicalLine.substring(canonicalCursor));
            }
            lrc.append('\n');
        }
        String result = lrc.toString();
        return LyricsEngine.hasWordTiming(result) ? result : "";
    }

    private static String formatLrcTime(double seconds) {
        long centiseconds = Math.max(0, Math.round(seconds * 100.0));
        long minutes = centiseconds / 6000;
        long remainder = centiseconds % 6000;
        return String.format(Locale.ROOT, "%02d:%02d.%02d",
                minutes, remainder / 100, remainder % 100);
    }

    private static String fetchKaralyr(String title, String artist, double duration, String requestKey) throws Exception {
        String query = "track_name=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8);
        if (duration > 0 && duration < 3600) query += "&duration=" + Math.round(duration);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://karalyr.com/api/get?" + query))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "VisionBrowser/0.1.0 (https://github.com/Nazhif01/VisionBrowser266)")
                .header("X-Karalyr-Client", "VisionBrowser/0.1.0")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !requestKey.equals(lastTrackKey)) return "";

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject metadata = json.has("karalyr") && json.get("karalyr").isJsonObject()
                ? json.getAsJsonObject("karalyr") : null;
        if (metadata == null || !metadata.has("has_word_timing")
                || !metadata.get("has_word_timing").getAsBoolean()) return "";

        String lyrics = str(json, "syncedLyrics");
        return LyricsEngine.hasWordTiming(lyrics) ? lyrics : "";
    }

    private static String fetchLrclibExact(String title, String artist, double duration, String requestKey) throws Exception {
        String query = "track_name=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8);
        if (duration > 0 && duration < 3600) query += "&duration=" + Math.round(duration);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://lrclib.net/api/get?" + query))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "VisionBrowser/0.1.0 (https://github.com/Nazhif01/VisionBrowser266)")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !requestKey.equals(lastTrackKey)) return "";
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return str(json, "syncedLyrics");
    }

    private static String searchLyrics(String title, String artist, double duration, String requestKey) throws Exception {
        String query = "track_name=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8);
        HttpRequest search = HttpRequest.newBuilder()
                .uri(URI.create("https://lrclib.net/api/search?" + query))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "VisionBrowser/0.1.0 (https://github.com/Nazhif01/VisionBrowser266)")
                .GET().build();
        HttpResponse<String> response = HTTP.send(search, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !requestKey.equals(lastTrackKey)) return "";

        com.google.gson.JsonArray results = JsonParser.parseString(response.body()).getAsJsonArray();
        String wantedTitle = normalize(title);
        String wantedArtist = normalize(artist);
        String bestLyrics = "";
        double bestScore = Double.NEGATIVE_INFINITY;
        for (com.google.gson.JsonElement element : results) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String lyrics = str(item, "syncedLyrics");
            if (!hasTimedLines(lyrics)) continue;

            String normalizedTitle = normalize(str(item, "trackName"));
            String normalizedArtist = normalize(str(item, "artistName"));
            double score = similarity(wantedTitle, normalizedTitle) * 5.0
                    + similarity(wantedArtist, normalizedArtist) * 3.0;
            double candidateDuration = num(item, "duration");
            if (duration > 0 && candidateDuration > 0) {
                score -= Math.min(2.5, Math.abs(duration - candidateDuration) / 12.0);
            }
            if (score > bestScore) {
                bestScore = score;
                bestLyrics = lyrics;
            }
        }
        return bestScore >= 4.0 ? bestLyrics : "";
    }

    private static boolean hasTimedLines(String lyrics) {
        return lyrics != null && !lyrics.isBlank()
                && !LyricsEngine.parse(lyrics).lines().isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (a.contains(b) || b.contains(a)) return 0.85;
        int common = 0;
        int limit = Math.min(a.length(), b.length());
        while (common < limit && a.charAt(common) == b.charAt(common)) common++;
        return common / (double) Math.max(a.length(), b.length());
    }

}
