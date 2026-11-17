package dev.nazhif.visionbrowser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Music mode: YouTube Music runs as its normal web player in a helper tab of the remote browser (the tab is not shown in the mod's tab bar),
 * and the mod searches and starts songs through the page itself. Audio is played by YouTube's own player; nothing is downloaded or extracted.
 * Playback position still comes from VisionMediaBridge, so lyrics, island and sync keep working unchanged.
 */
final class MusicPlayer {
    private static final String HOME = "https://music.youtube.com";

    record Result(String id, String title, String artist) { }

    private static volatile String tabId = "";
    private static volatile boolean creating;
    private static volatile List<Result> results = List.of();
    private static volatile String query = "";
    private static volatile String status = "Type a song and press Enter";
    private static volatile String hintTitle = "", hintArtist = "";
    private static volatile Result pendingPlay;
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
    /** title/artist of the song last started from the search list (cleaner than the page metadata), used as an extra lyric lookup */
    static String hintTitle() { return hintTitle; }
    static String hintArtist() { return hintArtist; }
    static boolean active() { return !tabId.isEmpty() && RemoteBrowser.hasTab(tabId); }

    /** makes sure the remote browser runs and the hidden YouTube Music tab exists (created in the background) */
    static synchronized void ensure() {
        RemoteBrowser.ensureRunning();
        if (active() || creating) return;
        if (!RemoteBrowser.connected() && RemoteBrowser.allTabs().isEmpty()) {
            status = "Starting the browser...";
            return;
        }
        creating = true;
        status = "Opening YouTube Music...";
        RemoteBrowser.openHidden(HOME).thenAccept(id -> {
            creating = false;
            if (id == null) {
                status = "Cannot open the music tab";
                return;
            }
            tabId = id;
            status = "";
            Result p = pendingPlay;
            if (p != null) { pendingPlay = null; play(p); }
            if (searching) sendSearch();
        });
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
        ensure();
        if (active()) sendSearch();
    }

    private static void sendSearch() {
        if (!active()) return;
        searchTries++;
        searchSentAt = System.currentTimeMillis();
        final String asked = query;
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
                + "return JSON.stringify({q:q,r:out});"
                + "}catch(e){return JSON.stringify({q:q,r:[],err:String(e)});}"
                + "})(" + new JsonPrimitive(asked) + ")";
        RemoteBrowser.eval(tabId, js, true, false).thenAccept(text -> {
            if (text != null && !text.isEmpty()) onSearchPayload(text);
        });
    }

    private static void onSearchPayload(String json) {
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
        hintTitle = r.title();
        hintArtist = r.artist();
        ensure();
        if (!active()) {
            pendingPlay = r;
            status = "Starting: " + r.title();
            return;
        }
        try {
            RemoteBrowser.eval(tabId, "location.href=" + new JsonPrimitive(HOME + "/watch?v=" + r.id()) + ";0", false, true);
            status = "Starting: " + r.title();
            activateTries = 4;
            nextActivateAt = System.currentTimeMillis() + 3500;
        } catch (Throwable error) {
            status = "Cannot start song: " + error.getClass().getSimpleName();
        }
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        if (!active() && (searching || pendingPlay != null) && !creating) ensure();
        if (searching && active() && now - searchSentAt > 1500) {
            if (searchTries >= 8) {
                searching = false;
                status = "Search failed (YouTube Music not reachable)";
            } else {
                sendSearch();
            }
        }
        if (active() && activateTries > 0 && now >= nextActivateAt) {
            if (VisionMediaBridge.isFreshPlaying()) {
                activateTries = 0;
                if (status.startsWith("Starting")) status = "";
            } else {
                activateTries--;
                nextActivateAt = now + 2500;
                // an evaluation flagged as a user gesture lets the page start audio the browser would otherwise block
                RemoteBrowser.eval(tabId, "(function(){var v=document.querySelector('video,audio');"
                        + "if(v&&v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}return 0;})()", false, true);
            }
        }
    }
}
