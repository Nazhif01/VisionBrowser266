package dev.nazhif.visionbrowser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Music mode: YouTube Music runs as its normal web player inside a hidden browser (no browser window is shown), and the mod searches and
 * starts songs through the page itself. Audio is played by YouTube's own player; nothing is downloaded or extracted. Playback position
 * therefore still comes from VisionMediaBridge, so lyrics, island and sync keep working unchanged.
 */
final class MusicPlayer {
    static final String SEARCH_PREFIX = "__VISION_SEARCH__";
    private static final String HOME = "https://music.youtube.com";
    private static final int PX_W = 1280, PX_H = 720;

    record Result(String id, String title, String artist) { }

    private static volatile RinkuBrowser browser;
    private static volatile List<Result> results = List.of();
    private static volatile String query = "";
    private static volatile String status = "Type a song and press Enter";
    private static boolean searching;
    private static int searchTries;
    private static long searchSentAt;
    private static int activateTries;
    private static long nextActivateAt;

    private MusicPlayer() { }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    static List<Result> results() { return results; }
    static String status() { return status; }
    static String query() { return query; }
    static boolean active() { return browser != null; }

    static List<RinkuBrowser> browsers() {
        RinkuBrowser b = browser;
        return b == null ? List.of() : List.of(b);
    }

    /** creates the hidden YouTube Music player on first use */
    static synchronized RinkuBrowser ensure() {
        if (browser != null) return browser;
        if (!VisionBrowserClient.ensureBrowserRuntime()) {
            status = "Browser runtime is not ready yet";
            return null;
        }
        try {
            RinkuBrowser b = Rinku.createBrowser(HOME, true);
            if (b == null) { status = "Cannot start the music player"; return null; }
            try { b.resize(PX_W, PX_H); } catch (Throwable ignored) { }
            browser = b;
            return b;
        } catch (Throwable error) {
            status = "Cannot start the music player: " + error.getClass().getSimpleName();
            return null;
        }
    }

    // ------------------------------------------------------------------ search

    static void search(String q) {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) return;
        query = q;
        results = List.of();
        searching = true;
        searchTries = 0;
        status = "Searching...";
        if (ensure() == null) { searching = false; return; }
        sendSearch();
    }

    private static void sendSearch() {
        RinkuBrowser b = browser;
        if (b == null) return;
        searchTries++;
        searchSentAt = System.currentTimeMillis();
        String js = "(async function(q){try{"
                + "var c=window.ytcfg;var key=c.get('INNERTUBE_API_KEY'),ctx=c.get('INNERTUBE_CONTEXT');"
                + "var res=await fetch('/youtubei/v1/search?prettyPrint=false&key='+key,{method:'POST',credentials:'include',"
                + "headers:{'Content-Type':'application/json'},body:JSON.stringify({context:ctx,query:q,params:'EgWKAQIIAWoKEAkQBRAKEAMQBA=='})});"
                + "var j=await res.json();var out=[];"
                + "(function walk(o){if(!o||typeof o!=='object'||out.length>=14)return;"
                + "var r=o.musicResponsiveListItemRenderer;"
                + "if(r){var id=r.playlistItemData&&r.playlistItemData.videoId;"
                + "if(id){var cols=r.flexColumns||[];"
                + "var txt=function(i){try{return cols[i].musicResponsiveListItemFlexColumnRenderer.text.runs.map(function(x){return x.text;}).join('');}catch(e){return '';}};"
                + "out.push({id:id,t:txt(0),a:txt(1)});}return;}"
                + "for(var k in o)walk(o[k]);})(j);"
                + "window.__vmSP='" + SEARCH_PREFIX + "'+encodeURIComponent(JSON.stringify({q:q,r:out}));"
                + "}catch(e){window.__vmSP='" + SEARCH_PREFIX + "'+encodeURIComponent(JSON.stringify({q:q,r:[],err:String(e)}));}"
                + "})(" + new JsonPrimitive(query) + ");";
        try {
            b.executeJavaScript(js, "visionbrowser://music-search", 0);
        } catch (Throwable error) {
            status = "Search failed: " + error.getClass().getSimpleName();
        }
    }

    /** called by the media bridge when the page reports search results through the title channel */
    static void onSearchPayload(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("q") || !o.get("q").getAsString().equals(query)) return;
            if (o.has("err") && !o.get("err").getAsString().isEmpty()) return;   // page not ready yet: the tick retries
            List<Result> list = new ArrayList<>();
            JsonArray arr = o.getAsJsonArray("r");
            for (JsonElement el : arr) {
                JsonObject r = el.getAsJsonObject();
                String artist = r.get("a").getAsString();
                if (artist.startsWith("Song • ")) artist = artist.substring(7);
                list.add(new Result(r.get("id").getAsString(), r.get("t").getAsString(), artist));
            }
            results = list;
            searching = false;
            status = list.isEmpty() ? "No results" : "";
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ playback

    static void play(Result r) {
        RinkuBrowser b = ensure();
        if (b == null) return;
        try {
            b.loadURL(HOME + "/watch?v=" + r.id());
            status = "Starting: " + r.title();
            activateTries = 4;
            nextActivateAt = System.currentTimeMillis() + 3000;
        } catch (Throwable error) {
            status = "Cannot start song: " + error.getClass().getSimpleName();
        }
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        if (searching && now - searchSentAt > 1500) {
            if (searchTries >= 8) {
                searching = false;
                status = "Search failed (YouTube Music not reachable)";
            } else {
                sendSearch();
            }
        }
        RinkuBrowser b = browser;
        if (b != null && activateTries > 0 && now >= nextActivateAt) {
            if (VisionMediaBridge.isFreshPlaying()) {
                activateTries = 0;
                if (status.startsWith("Starting")) status = "";
            } else {
                activateTries--;
                nextActivateAt = now + 2500;
                try {
                    // a real mouse click gives the page the user activation browsers require before audio may start
                    b.sendMouseMove(PX_W / 2, PX_H / 2);
                    b.sendMousePress(PX_W / 2, PX_H / 2, 1);
                    b.sendMouseRelease(PX_W / 2, PX_H / 2, 1);
                    b.executeJavaScript("setTimeout(function(){var v=document.querySelector('video,audio');if(v&&v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}},600);",
                            "visionbrowser://music-activate", 0);
                } catch (Throwable ignored) { }
            }
        }
    }
}