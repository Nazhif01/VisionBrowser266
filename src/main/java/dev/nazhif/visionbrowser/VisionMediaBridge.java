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
import java.util.Locale;

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
    private static final int SCRIPT_VERSION = 3;

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
            .connectTimeout(Duration.ofSeconds(8)).build();

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
            + "scan();setInterval(scan,500);"
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
        if (++tickCounter % 20 != 0) return;
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

    // ------------------------------------------------------------------ lyrics (unchanged behaviour, plus stale-result guard)

    static void fetchLyrics(String title, String artist, double duration) {
        final String requestKey = (title + "|" + artist).toLowerCase(Locale.ROOT);
        Thread worker = new Thread(() -> {
            try {
                String query = "track_name=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                        + "&artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8);
                if (duration > 0 && duration < 3600) query += "&duration=" + Math.round(duration);

                String synced = "";
                HttpRequest exact = HttpRequest.newBuilder()
                        .uri(URI.create("https://lrclib.net/api/get?" + query))
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "VisionBrowser/0.1.0 (Minecraft Fabric mod)")
                        .GET().build();
                HttpResponse<String> response = HTTP.send(exact, HttpResponse.BodyHandlers.ofString());
                if (!requestKey.equals(lastTrackKey)) return;
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    synced = json.has("syncedLyrics") && !json.get("syncedLyrics").isJsonNull()
                            ? json.get("syncedLyrics").getAsString() : "";
                }

                // Exact lookup can miss alternate punctuation, featured artists, and release metadata.
                // Search fallback is still restricted to the song currently playing in Vision Browser.
                if (synced.isBlank()) synced = searchLyrics(title, artist, duration, requestKey);
                if (!requestKey.equals(lastTrackKey)) return;

                if (synced.isBlank()) {
                    Minecraft.getInstance().execute(() ->
                            FloatingLyrics.setUnavailable("No synchronized lyrics found"));
                    return;
                }
                String result = synced;
                Minecraft.getInstance().execute(() -> {
                    if (requestKey.equals(lastTrackKey)) FloatingLyrics.setLyrics(title, artist, result);
                });
            } catch (Throwable error) {
                if (requestKey.equals(lastTrackKey)) {
                    Minecraft.getInstance().execute(() ->
                            FloatingLyrics.setUnavailable("Lyrics service unavailable"));
                }
            }
        }, "VisionBrowser-LyricsLookup");
        worker.setDaemon(true);
        worker.start();
    }

    private static String searchLyrics(String title, String artist, double duration, String requestKey) throws Exception {
        String query = "track_name=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8);
        HttpRequest search = HttpRequest.newBuilder()
                .uri(URI.create("https://lrclib.net/api/search?" + query))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "VisionBrowser/0.1.0 (Minecraft Fabric mod)")
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
            String candidateTitle = str(item, "trackName");
            String candidateArtist = str(item, "artistName");
            String lyrics = str(item, "syncedLyrics");
            if (lyrics.isBlank()) continue;

            String normalizedTitle = normalize(candidateTitle);
            String normalizedArtist = normalize(candidateArtist);
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
