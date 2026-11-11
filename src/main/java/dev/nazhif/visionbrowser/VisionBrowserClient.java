package dev.nazhif.visionbrowser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.keksuccino.rinku.RinkuBrowser;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/** Reads playback metadata from the active Rinku page for the floating lyrics HUD. */
final class VisionMediaBridge {
    private static final String PREFIX = "__VISION_LYRICS__";
    private static volatile RinkuBrowser hookedBrowser;
    private static volatile Method scriptMethod;
    private static volatile Method titleMethod;
    private static volatile String trackTitle = "";
    private static volatile String artist = "";
    private static volatile boolean playing;
    private static volatile double positionSeconds;
    private static volatile double durationSeconds;
    private static volatile long positionUpdatedAt;
    private static volatile String lastTrackKey = "";
    private static volatile String status = "Open a music page in Vision Browser";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private VisionMediaBridge() { }

    static void start() {
        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    RinkuBrowser browser = VisionBrowserClient.getActiveBrowser();
                    if (browser == null) {
                        hookedBrowser = null;
                        scriptMethod = null;
                        titleMethod = null;
                        clearTrack("Open Vision Browser");
                    } else {
                        if (browser != hookedBrowser) attach(browser);
                        if (scriptMethod != null && System.currentTimeMillis() - positionUpdatedAt > 500) {
                            inject(browser, captureScript());
                        }
                        read(browser);
                    }
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Throwable error) {
                    status = "Media bridge: " + error.getClass().getSimpleName();
                }
            }
        }, "VisionBrowser-Lyrics-MediaBridge");
        worker.setDaemon(true);
        worker.start();
    }

    private static void attach(RinkuBrowser browser) {
        hookedBrowser = browser;
        scriptMethod = findScriptMethod(browser.getClass());
        titleMethod = findTitleMethod(browser.getClass());
        positionUpdatedAt = 0;
        status = scriptMethod == null || titleMethod == null
                ? "Rinku page bridge unavailable" : "Waiting for music playback";
        if (scriptMethod != null) inject(browser, captureScript());
    }

    private static Method findScriptMethod(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                Class<?>[] p = method.getParameterTypes();
                if ((name.equals("runjs") || name.equals("executejavascript"))
                        && p.length >= 1 && p[0] == String.class
                        && (p.length == 1 || (p.length == 2 && p[1] == String.class)
                        || (p.length == 3 && p[1] == String.class && (p[2] == int.class || p[2] == Integer.class)))) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static Method findTitleMethod(Class<?> type) {
        for (String candidate : new String[]{"getTitle", "getPageTitle", "getDocumentTitle"}) {
            try {
                Method method = type.getMethod(candidate);
                if (method.getReturnType() == String.class) return method;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void inject(RinkuBrowser browser, String script) {
        try {
            int count = scriptMethod.getParameterCount();
            if (count == 1) scriptMethod.invoke(browser, script);
            else if (count == 2) scriptMethod.invoke(browser, script, "");
            else scriptMethod.invoke(browser, script, "", 0);
        } catch (Throwable error) {
            status = "Cannot read browser playback";
        }
    }

    private static void read(RinkuBrowser browser) {
        if (titleMethod == null) return;
        try {
            Object value = titleMethod.invoke(browser);
            if (!(value instanceof String pageTitle) || !pageTitle.startsWith(PREFIX)) return;
            String[] p = pageTitle.substring(PREFIX.length()).split("\\|", -1);
            if (p.length < 5) return;
            String title = decode(p[0]);
            String newArtist = decode(p[1]);
            boolean isPlaying = "1".equals(p[2]);
            double position = parse(p[3]);
            double duration = parse(p[4]);
            if (title.isBlank() || newArtist.isBlank()) return;
            String key = title.toLowerCase(Locale.ROOT) + "|" + newArtist.toLowerCase(Locale.ROOT);
            if (!key.equals(lastTrackKey)) {
                lastTrackKey = key;
                trackTitle = title;
                artist = newArtist;
                positionSeconds = position;
                durationSeconds = duration;
                positionUpdatedAt = System.currentTimeMillis();
                playing = isPlaying;
                status = "Finding synchronized lyrics";
                FloatingLyrics.loadTrack(title, newArtist, duration);
            } else {
                trackTitle = title;
                artist = newArtist;
                playing = isPlaying;
                if (isPlaying) positionSeconds = position;
                else positionSeconds = position;
                durationSeconds = duration;
                positionUpdatedAt = System.currentTimeMillis();
                if (FloatingLyrics.hasLyrics()) status = "Lyrics synchronized";
            }
        } catch (Throwable error) {
            status = "Cannot read current track";
        }
    }

    private static String captureScript() {
        return "(function(){try{if(window.__visionLyricsBridge)return;window.__visionLyricsBridge=true;"
                + "const P='" + PREFIX + "';let last='';"
                + "function enc(v){return encodeURIComponent(v||'');}"
                + "function scan(){try{let m=navigator.mediaSession&&navigator.mediaSession.metadata;"
                + "let t=m&&m.title||'',a=m&&m.artist||'';"
                + "let v=document.querySelector('video,audio');"
                + "if(!t&&v){let x=document.querySelector('ytmusic-player-bar .title, .ytp-title-link, [data-testid=nowplaying-track-title]');if(x)t=x.textContent.trim();}"
                + "if(!a){let x=document.querySelector('ytmusic-player-bar .subtitle, .ytp-title-channel');if(x)a=x.textContent.trim();}"
                + "let pos=v&&Number.isFinite(v.currentTime)?v.currentTime:0;"
                + "let dur=v&&Number.isFinite(v.duration)?v.duration:0;"
                + "let on=!!(v&&!v.paused&&!v.ended);"
                + "if(t&&a){let n=P+enc(t)+'|'+enc(a)+'|'+(on?'1':'0')+'|'+pos+'|'+dur;"
                + "if(n!==last){document.title=n;last=n;}}"
                + "}catch(e){}}scan();setInterval(scan,500);"
                + "document.addEventListener('play',scan,true);document.addEventListener('pause',scan,true);"
                + "document.addEventListener('loadedmetadata',scan,true);}catch(e){}})();";
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static double parse(String value) {
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static void clearTrack(String message) {
        trackTitle = "";
        artist = "";
        playing = false;
        positionSeconds = 0;
        durationSeconds = 0;
        lastTrackKey = "";
        status = message;
        FloatingLyrics.clear();
    }

    static String title() { return trackTitle; }
    static String artist() { return artist; }
    static boolean isPlaying() { return playing; }
    static String status() { return status; }

    static double positionSeconds() {
        if (playing) return positionSeconds + Math.max(0, System.currentTimeMillis() - positionUpdatedAt) / 1000.0;
        return positionSeconds;
    }

    static void fetchLyrics(String title, String artist, double duration) {
        Thread worker = new Thread(() -> {
            try {
                String query = "track_name=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                        + "&artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8);
                if (duration > 0 && duration < 3600) query += "&duration=" + Math.round(duration);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://lrclib.net/api/get?" + query))
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "VisionBrowser/0.1.0 (Minecraft Fabric mod)")
                        .GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    FloatingLyrics.setUnavailable(response.statusCode() == 404
                            ? "No synchronized lyrics found" : "Lyrics service unavailable");
                    return;
                }
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String synced = json.has("syncedLyrics") && !json.get("syncedLyrics").isJsonNull()
                        ? json.get("syncedLyrics").getAsString() : "";
                if (synced.isBlank()) {
                    FloatingLyrics.setUnavailable("This track has no timed lyrics");
                    return;
                }
                FloatingLyrics.setLyrics(title, artist, synced);
            } catch (Throwable error) {
                FloatingLyrics.setUnavailable("Could not load lyrics");
            }
        }, "VisionBrowser-LRCLIB");
        worker.setDaemon(true);
        worker.start();
    }
}
