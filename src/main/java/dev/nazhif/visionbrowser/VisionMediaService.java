package dev.nazhif.visionbrowser;

import de.keksuccino.rinku.RinkuBrowser;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class VisionMediaService {
    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile boolean playing;
    private static volatile String status = "Waiting for Vision Browser";
    private static volatile RinkuBrowser hookedBrowser;
    private static volatile Method scriptMethod;
    private static volatile Method titleMethod;
    private static volatile long lastInjection;

    private static final String PREFIX = "__VISION_MEDIA__";

    private VisionMediaService() { }

    public static void start() {
        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    RinkuBrowser browser = VisionBrowserClient.getActiveBrowser();
                    if (browser == null) {
                        hookedBrowser = null;
                        title = "";
                        artist = "";
                        status = "Open Vision Browser";
                    } else {
                        if (browser != hookedBrowser) attach(browser);
                        readBrowserTitle(browser);
                        if (System.currentTimeMillis() - lastInjection > 1200 && scriptMethod != null) {
                            inject(browser, buildCaptureScript());
                            lastInjection = System.currentTimeMillis();
                        }
                    }
                    Thread.sleep(350);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Throwable error) {
                    status = "Browser media bridge error: " + error.getClass().getSimpleName();
                    title = "";
                    artist = "";
                }
            }
        }, "VisionBrowser-PageMediaBridge");
        worker.setDaemon(true);
        worker.start();
    }

    private static void attach(RinkuBrowser browser) {
        hookedBrowser = browser;
        scriptMethod = findScriptMethod(browser.getClass());
        titleMethod = findTitleMethod(browser.getClass());
        lastInjection = 0;
        status = scriptMethod == null
                ? "Rinku JS API not found"
                : "Connected to browser, waiting for media";
        if (scriptMethod != null) inject(browser, buildCaptureScript());
    }

    private static Method findScriptMethod(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                Class<?>[] p = method.getParameterTypes();
                if ((name.equals("runjs") || name.equals("executejavascript") || name.equals("executejavascript"))
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
            if (scriptMethod.getParameterCount() == 1) scriptMethod.invoke(browser, script);
            else if (scriptMethod.getParameterCount() == 2) scriptMethod.invoke(browser, script, "");
            else scriptMethod.invoke(browser, script, "", 0);
        } catch (Throwable error) {
            status = "JS injection failed: " + error.getClass().getSimpleName();
        }
    }

    private static void readBrowserTitle(RinkuBrowser browser) {
        if (titleMethod == null) {
            status = scriptMethod == null ? "Rinku JS API not found" : "Waiting for browser title bridge";
            return;
        }
        try {
            Object value = titleMethod.invoke(browser);
            if (!(value instanceof String pageTitle) || !pageTitle.startsWith(PREFIX)) return;
            String[] parts = pageTitle.substring(PREFIX.length()).split("\\|", -1);
            if (parts.length < 3) return;
            title = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            artist = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            playing = "1".equals(parts[2]);
            status = title.isBlank() ? "No media in this browser tab" : "Playing from Vision Browser";
        } catch (Throwable error) {
            status = "Cannot read browser title: " + error.getClass().getSimpleName();
        }
    }

    private static String buildCaptureScript() {
        return "(function(){try{if(window.__visionMediaBridge)return;window.__visionMediaBridge=true;"
            + "const P='" + PREFIX + "';let last='';"
            + "function enc(v){return encodeURIComponent(v||'');}"
            + "function scan(){try{let m=navigator.mediaSession&&navigator.mediaSession.metadata;"
            + "let t=m&&m.title||'';let a=m&&m.artist||'';"
            + "let v=document.querySelector('video');"
            + "if(!t&&v){let el=document.querySelector('ytmusic-player-bar .title, ytmusic-player-bar .subtitle, [data-testid=nowplaying-track-title]');if(el)t=el.textContent.trim();}"
            + "let isPlaying=!!(v&&!v.paused);"
            + "if(!t&&v&&!v.paused){let q=document.querySelector('ytmusic-player-bar');"
            + "if(q){let x=q.querySelector('.title');if(x)t=x.textContent.trim();}}"
            + "let next=P+enc(t)+'|'+enc(a)+'|'+(isPlaying?'1':'0');"
            + "if(next!==last){document.title=next;last=next;}"
            + "}catch(e){}}"
            + "scan();setInterval(scan,700);"
            + "document.addEventListener('play',scan,true);document.addEventListener('pause',scan,true);"
            + "}catch(e){}})();";
    }

    private static void runPageAction(String action) {
        RinkuBrowser browser = hookedBrowser;
        if (browser == null || scriptMethod == null) return;
        String js;
        if ("toggle".equals(action)) {
            js = "(()=>{let v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause();}"
                + "else{let b=document.querySelector('ytmusic-player-bar #play-pause-button, button[aria-label*=Play], button[aria-label*=Pause]');if(b)b.click();}})();";
        } else {
            boolean next = "next".equals(action);
            js = "(()=>{let sels=" + (next
                ? "['ytmusic-player-bar #next-button','button[aria-label*=Next]']"
                : "['ytmusic-player-bar #previous-button','button[aria-label*=Previous]']") + ";"
                + "for(let s of sels){let b=document.querySelector(s);if(b){b.click();return;}}})();";
        }
        inject(browser, js);
    }

    public static String title() { return title; }
    public static String artist() { return artist; }
    public static String application() { return "Vision Browser"; }
    public static boolean isPlaying() { return playing; }
    public static boolean isConnected() { return hookedBrowser != null && scriptMethod != null; }
    public static String status() { return status; }
    public static void previous() { runPageAction("previous"); }
    public static void togglePlayback() { runPageAction("toggle"); }
    public static void next() { runPageAction("next"); }
}
