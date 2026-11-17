package dev.nazhif.visionbrowser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
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
import java.util.ArrayList;
import java.util.List;
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
    private static final int SCRIPT_VERSION = 6;

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
    private static volatile double playbackRate = 1.0;
    private static volatile long lastPayloadAt;
    private static volatile long playLockUntil;
    private static volatile long volumeLockUntil;
    private static volatile String lastTrackKey = "";
    private static volatile String status = "Open a music page in Vision Browser";
    private static int tickCounter;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private static final String CAPTURE_SCRIPT = ("(function(){try{"
            + "if(window.__vmV===@V@)return;window.__vmV=@V@;if(window.__vmT)clearInterval(window.__vmT);"
            + "var EV=['play','pause','loadedmetadata','seeked','volumechange','ratechange'];"
            + "if(window.__vmH)EV.forEach(function(ev){document.removeEventListener(ev,window.__vmH,true);});"
            + "var P='@PREFIX@',last='';"
            + "function el(){var c=[document.querySelector('video'),document.querySelector('audio')];"
            + "for(var i=0;i<c.length;i++){if(c[i]&&c[i].readyState>0)return c[i];}return c[0]||c[1];}"
            + "function art(m){try{var a=m&&m.artwork;if(a&&a.length){return a[a.length-1].src||'';}}catch(e){}return '';}"
            + "function ads(){try{var p=document.getElementById('movie_player');"
            + "var ad=!!((p&&p.classList.contains('ad-showing'))||document.querySelector('.ad-showing'));"
            + "var v=document.querySelector('video');"
            + "if(ad){var b=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-container button');if(b)b.click();"
            + "if(v&&!v.muted){v.muted=true;window.__vmAdMute=true;}}"
            + "else if(window.__vmAdMute){window.__vmAdMute=false;if(v)v.muted=false;}}catch(e){}}"
            + "function scan(){try{ads();if(window.__vmSP){var sp=window.__vmSP;window.__vmSP=null;document.title=sp;return;}var v=el();if(!v)return;"
            + "var m=navigator.mediaSession&&navigator.mediaSession.metadata;"
            + "var t=(m&&m.title)||'',a=(m&&m.artist)||'',al=(m&&m.album)||'';"
            + "if(!t){var x=document.querySelector('ytmusic-player-bar .title');if(x)t=(x.textContent||'').trim();}"
            + "if(!a){var y=document.querySelector('ytmusic-player-bar .byline');if(y)a=(y.textContent||'').trim();}"
            + "if(!t)return;"
            + "var o={t:t,a:a,al:al,art:art(m),on:!v.paused&&!v.ended,pos:v.currentTime||0,"
            + "dur:isFinite(v.duration)?v.duration:0,vol:v.volume,mut:!!v.muted,rate:v.playbackRate||1,ts:Date.now()};"
            + "var n=P+encodeURIComponent(JSON.stringify(o));"
            + "if(n!==last){last=n;document.title=n;}"
            + "}catch(e){}}"
            + "scan();window.__vmT=setInterval(scan,200);window.__vmH=scan;"
            + "EV.forEach(function(ev){document.addEventListener(ev,scan,true);});"
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
            List<RinkuBrowser> all = new ArrayList<>(VisionBrowserClient.getTabs());
            all.addAll(MusicPlayer.browsers());
            for (RinkuBrowser browser : all) {
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
        if (title != null && title.startsWith(MusicPlayer.SEARCH_PREFIX)) {
            try {
                MusicPlayer.onSearchPayload(URLDecoder.decode(title.substring(MusicPlayer.SEARCH_PREFIX.length()), StandardCharsets.UTF_8));
            } catch (Throwable ignored) { }
            return;
        }
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
            double rate = num(o, "rate");
            playbackRate = rate > 0.1 && rate < 4 ? rate : 1.0;
            // the page stamped the moment it read the position: the real position is that old, so extrapolate from the stamp
            long sent = (long) num(o, "ts");
            long age = sent > 0 ? now - sent : 0;
            positionUpdatedAt = VisionSettings.lyricsLatencyComp && age >= 0 && age < 1500 ? sent : now;
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
    static boolean isFreshPlaying() { return playing && System.currentTimeMillis() - lastPayloadAt < 700; }
    static String status() { return status; }
    static double durationSeconds() { return durationSeconds; }
    static float volume() { return volume; }
    static boolean isMuted() { return muted; }

    static double positionSeconds() {
        double p = positionSeconds;
        if (playing) p += Math.max(0, System.currentTimeMillis() - positionUpdatedAt) / 1000.0 * playbackRate;
        return durationSeconds > 0 ? Math.min(p, durationSeconds) : p;
    }

    /** playback position shifted by the global and per-song lyric offsets (what the lyric lines are compared against) */
    static double lyricPosition() {
        double ms = VisionSettings.lyricsGlobalOffsetMs + LyricSync.offsetMs(FloatingLyrics.trackKey());
        return positionSeconds() + ms / 1000.0;
    }

    // ------------------------------------------------------------------ lyrics (unchanged behaviour, plus stale-result guard)

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static String clean(String v) {
        String r = v.replaceAll("\\(.*?\\)|\\[.*?\\]", " ").replaceAll("(?i)\\s+-\\s+.*$", " ")
                .replaceAll("(?i)\\s(feat|ft)\\.?\\s.*$", " ").replaceAll("\\s+\u2022.*$", " ");
        int comma = r.indexOf(',');
        if (comma > 0) r = r.substring(0, comma);
        return r.replaceAll("\\s+", " ").trim();
    }

    private static List<FloatingLyrics.Candidate> search(String query, String artistHint) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://lrclib.net/api/search?" + query))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "VisionBrowser/0.1.0 (Minecraft Fabric mod)")
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return List.of();
        if (response.statusCode() != 200) throw new java.io.IOException("lrclib " + response.statusCode());
        List<FloatingLyrics.Candidate> out = new ArrayList<>();
        String ah = artistHint == null ? "" : artistHint.toLowerCase(Locale.ROOT);
        for (JsonElement el : JsonParser.parseString(response.body()).getAsJsonArray()) {
            JsonObject j = el.getAsJsonObject();
            String synced = j.has("syncedLyrics") && !j.get("syncedLyrics").isJsonNull() ? j.get("syncedLyrics").getAsString() : "";
            if (synced.isBlank()) continue;
            if (!ah.isEmpty() && !str(j, "artistName").toLowerCase(Locale.ROOT).contains(ah)) continue;
            out.add(new FloatingLyrics.Candidate(j.has("id") ? j.get("id").getAsLong() : 0L,
                    str(j, "trackName") + " - " + str(j, "artistName"), num(j, "duration"), synced));
            if (out.size() >= 10) break;
        }
        return out;
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                .header("Referer", "https://music.163.com/").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new java.io.IOException("http " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /** second lyric source for songs LRCLIB does not have (timed lyrics, picked by title/artist match) */
    private static List<FloatingLyrics.Candidate> netease(String title, String artist) {
        List<FloatingLyrics.Candidate> out = new ArrayList<>();
        try {
            String t = clean(title).toLowerCase(Locale.ROOT), a = clean(artist).toLowerCase(Locale.ROOT);
            JsonObject s = getJson("https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hltag=&s=" + enc(clean(title) + " " + clean(artist))
                    + "&type=1&offset=0&total=true&limit=6");
            if (!s.has("result") || !s.getAsJsonObject("result").has("songs")) return out;
            for (JsonElement el : s.getAsJsonObject("result").getAsJsonArray("songs")) {
                JsonObject song = el.getAsJsonObject();
                String name = str(song, "name");
                String by = song.has("artists") && song.getAsJsonArray("artists").size() > 0
                        ? str(song.getAsJsonArray("artists").get(0).getAsJsonObject(), "name") : "";
                String nl = name.toLowerCase(Locale.ROOT), bl = by.toLowerCase(Locale.ROOT);
                boolean titleOk = !t.isEmpty() && (nl.contains(t) || t.contains(nl));
                boolean artistOk = a.isEmpty() || bl.contains(a) || a.contains(bl);
                if (!titleOk || !artistOk) continue;
                long id = song.get("id").getAsLong();
                JsonObject l = getJson("https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1");
                String lrc = l.has("lrc") && l.getAsJsonObject("lrc").has("lyric") ? l.getAsJsonObject("lrc").get("lyric").getAsString() : "";
                if (!lrc.contains("[0")) continue;
                lrc = lrc.replaceAll("(?m)^\\[[^\\]]*\\]\\s*(\u4f5c\u8bcd|\u4f5c\u66f2|\u7f16\u66f2|\u5236\u4f5c\u4eba|\u6df7\u97f3|\u6bcd\u5e26|\u5f55\u97f3|\u76d1\u5236|\u8bcd|\u66f2)\\s*[:\uff1a].*$", "")
                        .replaceAll("(?m)^\\{.*$", "");
                double dur = song.has("duration") ? song.get("duration").getAsDouble() / 1000.0 : 0;
                out.add(new FloatingLyrics.Candidate(-id, "NetEase: " + name + " - " + by, dur, lrc));
                if (out.size() >= 3) break;
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** looks up every synced version of the song, so the one whose length matches the playing track (best timing) can be used */
    static void fetchLyrics(String title, String artist, double duration) {
        final String requestKey = (title + "|" + artist).toLowerCase(Locale.ROOT);
        final String hintT = MusicPlayer.hintTitle(), hintA = MusicPlayer.hintArtist();
        Thread worker = new Thread(() -> {
            try {
                String ct = clean(title), ca = clean(artist);
                List<FloatingLyrics.Candidate> found = new ArrayList<>();
                java.util.Set<Long> seen = new java.util.HashSet<>();
                List<String[]> attempts = new ArrayList<>();
                attempts.add(new String[]{"track_name=" + enc(title) + "&artist_name=" + enc(artist), null});
                attempts.add(new String[]{"track_name=" + enc(ct) + "&artist_name=" + enc(ca), null});
                if (!hintT.isEmpty()) attempts.add(new String[]{"track_name=" + enc(clean(hintT)) + "&artist_name=" + enc(clean(hintA)), null});
                attempts.add(new String[]{"q=" + enc(ct + " " + ca), null});
                attempts.add(new String[]{"q=" + enc(ct), ca.toLowerCase(Locale.ROOT)});
                attempts.add(new String[]{"track_name=" + enc(ct), ca.toLowerCase(Locale.ROOT)});
                for (String[] at : attempts) {
                    for (FloatingLyrics.Candidate c : search(at[0], at[1])) if (seen.add(c.id())) found.add(c);
                    if (!found.isEmpty()) break;
                }
                if (!requestKey.equals(lastTrackKey)) return; // track changed while the request was running
                if (found.size() < 2) found.addAll(netease(ct.isEmpty() ? title : ct, ca.isEmpty() ? artist : ca));
                if (!requestKey.equals(lastTrackKey)) return;
                if (found.isEmpty()) {
                    FloatingLyrics.setUnavailable("No synchronized lyrics found");
                    return;
                }
                if (duration > 0) {
                    List<FloatingLyrics.Candidate> sorted = new ArrayList<>(found);
                    sorted.sort(java.util.Comparator.comparingDouble(c -> c.duration() > 0 ? Math.abs(c.duration() - duration) : 999));
                    found = sorted;
                }
                FloatingLyrics.setCandidates(title, artist, found);
            } catch (Throwable error) {
                if (requestKey.equals(lastTrackKey)) FloatingLyrics.setUnavailable("Could not load lyrics");
            }
        }, "VisionBrowser-Lyrics");
        worker.setDaemon(true);
        worker.start();
    }
}