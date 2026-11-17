package dev.nazhif.visionbrowser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Remote mode: drives a REAL Chromium-family browser (Brave, Chrome, Edge, Vivaldi, Opera, Chromium) through the
 * Chrome DevTools Protocol. The page is streamed into a texture and mouse / keyboard are sent back to the browser,
 * so the browser's own extensions, Web Store, logins and DRM all work.
 */
final class RemoteBrowser {
    static final String[] NAMES = {"Auto-detect", "Brave", "Chrome", "Edge", "Vivaldi", "Opera", "Custom path"};
    static final int PORT = 9222;
    private static final String BASE = "http://127.0.0.1:" + PORT;

    record Tab(String id, String title, String url, String wsUrl) { }
    private record Frame(NativeImage image, int w, int h) { }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final ExecutorService SEND = Executors.newSingleThreadExecutor(r -> daemon(r, "VisionRemote-send"));
    private static final ExecutorService ASYNC = Executors.newCachedThreadPool(r -> daemon(r, "VisionRemote-async"));
    private static final AtomicInteger MSG_ID = new AtomicInteger(1);
    private static final AtomicReference<Frame> PENDING = new AtomicReference<>();

    private static volatile WebSocket ws;
    private static volatile String activeId = "";
    private static volatile List<Tab> tabs = List.of();          // tabs shown to the player
    private static volatile List<Tab> allTabs = List.of();       // every page tab, including hidden helper tabs (music player)
    private static final Set<String> HIDDEN = ConcurrentHashMap.newKeySet();
    private static final Map<String, WebSocket> PROBES = new ConcurrentHashMap<>();
    private static final Map<Integer, CompletableFuture<JsonObject>> WAITING = new ConcurrentHashMap<>();
    private static volatile boolean autoLaunched;
    private static volatile String status = "Remote: idle";
    private static volatile boolean workerRunning;
    private static volatile int wantW = 1280, wantH = 720, appliedW = 0, appliedH = 0;
    private static volatile int appliedQuality = -1;
    private static volatile int buttonsDown = 0;

    // render-thread only
    private static Identifier texture;
    private static int counter;

    private RemoteBrowser() { }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ------------------------------------------------------------------ public state

    static String status() { return status; }
    static boolean connected() { return ws != null; }
    static List<Tab> tabs() { return tabs; }
    static List<Tab> allTabs() { return allTabs; }
    static boolean hasTab(String id) {
        if (id == null) return false;
        for (Tab t : allTabs) if (t.id().equals(id)) return true;
        return false;
    }
    private static List<Tab> visible(List<Tab> all) {
        List<Tab> out = new ArrayList<>();
        for (Tab t : all) if (!HIDDEN.contains(t.id())) out.add(t);
        return out;
    }
    static String activeId() { return activeId; }

    static Tab activeTab() {
        for (Tab t : tabs) if (t.id().equals(activeId)) return t;
        return tabs.isEmpty() ? null : tabs.get(0);
    }

    // ------------------------------------------------------------------ worker: connect + keep tab list fresh

    static synchronized void start() {
        if (workerRunning) return;
        workerRunning = true;
        Thread t = daemon(RemoteBrowser::workerLoop, "VisionRemote-worker");
        t.start();
    }

    private static void workerLoop() {
        long lastTabs = 0;
        while (true) {
            try {
                long now = System.currentTimeMillis();
                if (ws == null) {
                    List<Tab> list = fetchTabs();
                    if (list.isEmpty()) {
                        status = "Remote: no browser on port " + PORT + " (press Launch)";
                    } else {
                        publish(list);
                        Tab pick = null;
                        for (Tab t : tabs) if (t.id().equals(activeId)) pick = t;
                        if (pick == null && !tabs.isEmpty()) pick = tabs.get(0);
                        if (pick != null) attach(pick);
                    }
                } else {
                    if (now - lastTabs > 1500) {
                        lastTabs = now;
                        List<Tab> list = fetchTabs();
                        if (!list.isEmpty()) {
                            publish(list);
                            boolean found = false;
                            for (Tab t : tabs) if (t.id().equals(activeId)) found = true;
                            if (!found && !tabs.isEmpty()) attach(tabs.get(0));
                        }
                    }
                    applyViewport();
                }
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                status = "Remote: " + e.getClass().getSimpleName();
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** store a fresh tab list and drop probe connections of tabs that are gone */
    private static void publish(List<Tab> list) {
        allTabs = list;
        tabs = visible(list);
        for (String id : new ArrayList<>(PROBES.keySet())) {
            boolean alive = false;
            for (Tab t : list) if (t.id().equals(id)) alive = true;
            if (!alive) {
                WebSocket w = PROBES.remove(id);
                HIDDEN.remove(id);
                if (w != null) { try { w.sendClose(WebSocket.NORMAL_CLOSURE, "gone"); } catch (Throwable ignored) { } }
            }
        }
    }

    private static List<Tab> fetchTabs() {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/json/list"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return List.of();
            JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
            List<Tab> out = new ArrayList<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if (!"page".equals(str(o, "type"))) continue;
                String url = str(o, "url");
                if (url.startsWith("devtools://") || url.startsWith("chrome-extension://")) continue;
                String wsUrl = str(o, "webSocketDebuggerUrl");
                if (wsUrl.isEmpty()) continue;
                out.add(new Tab(str(o, "id"), str(o, "title"), url, wsUrl));
            }
            return out;
        } catch (Throwable e) {
            return List.of();
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    // ------------------------------------------------------------------ websocket / screencast

    private static synchronized void attach(Tab t) {
        WebSocket old = ws;
        ws = null;
        if (old != null) {
            try { old.sendClose(WebSocket.NORMAL_CLOSURE, "switch"); } catch (Throwable ignored) { }
        }
        try {
            WebSocket w = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create(t.wsUrl()), new Listener(null)).get(4, TimeUnit.SECONDS);
            ws = w;
            activeId = t.id();
            appliedW = 0;
            appliedH = 0;
            appliedQuality = -1;
            status = "Remote: connected";
            send("Page.enable", null);
            send("Page.bringToFront", null);
        } catch (Throwable e) {
            ws = null;
            status = "Remote: cannot attach (" + e.getClass().getSimpleName()
                    + "). If the browser was started by you, relaunch it with --remote-allow-origins=*";
        }
    }

    private static void applyViewport() {
        int w = wantW, h = wantH, q = VisionSettings.remoteQuality;
        if (w == appliedW && h == appliedH && q == appliedQuality) return;
        appliedW = w;
        appliedH = h;
        appliedQuality = q;
        JsonObject m = new JsonObject();
        m.addProperty("width", w);
        m.addProperty("height", h);
        m.addProperty("deviceScaleFactor", 1);
        m.addProperty("mobile", false);
        send("Emulation.setDeviceMetricsOverride", m);
        send("Page.stopScreencast", null);
        JsonObject s = new JsonObject();
        s.addProperty("format", "jpeg");
        s.addProperty("quality", q);
        s.addProperty("maxWidth", w);
        s.addProperty("maxHeight", h);
        s.addProperty("everyNthFrame", 1);
        send("Page.startScreencast", s);
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        private final String probeTab;

        Listener(String probeTab) { this.probeTab = probeTab; }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try { handle(msg, probeTab); } catch (Throwable ignored) { }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (probeTab != null) { PROBES.remove(probeTab, webSocket); return null; }
            if (ws == webSocket) { ws = null; status = "Remote: browser closed"; }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (probeTab != null) { PROBES.remove(probeTab, webSocket); return; }
            if (ws == webSocket) { ws = null; status = "Remote: connection lost"; }
        }
    }

    private static void handle(String msg, String probeTab) throws Exception {
        JsonObject o = JsonParser.parseString(msg).getAsJsonObject();
        if (o.has("id") && !o.has("method")) {
            CompletableFuture<JsonObject> f = WAITING.remove(o.get("id").getAsInt());
            if (f != null) f.complete(o);
            return;
        }
        if (!o.has("method")) return;
        if (probeTab != null && "Page.javascriptDialogOpening".equals(o.get("method").getAsString())) {
            // helper tabs (music) must never stop on a "Leave site?" prompt
            JsonObject a = new JsonObject();
            a.addProperty("accept", true);
            call(probeTab, "Page.handleJavaScriptDialog", a);
            return;
        }
        if (!"Page.screencastFrame".equals(o.get("method").getAsString())) return;
        JsonObject p = o.getAsJsonObject("params");
        JsonObject ack = new JsonObject();
        ack.addProperty("sessionId", p.get("sessionId").getAsInt());
        send("Page.screencastFrameAck", ack);

        byte[] jpg = Base64.getDecoder().decode(p.get("data").getAsString());
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(jpg));
        if (bi == null) return;
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = bi.getRGB(0, 0, w, h, null, 0, w);
        NativeImage ni = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                ni.setPixelABGR(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }
        Frame old = PENDING.getAndSet(new Frame(ni, w, h));
        if (old != null) old.image().close();
    }

    // ------------------------------------------------------------------ render thread

    /** Upload the newest frame (if any) and return the page texture, or null before the first frame. */
    static Identifier pump() {
        Frame f = PENDING.getAndSet(null);
        if (f != null) {
            Minecraft mc = Minecraft.getInstance();
            Identifier id = Identifier.fromNamespaceAndPath("visionbrowser", "remote_" + (++counter));
            mc.getTextureManager().register(id, new DynamicTexture(() -> "Vision remote page", f.image()));
            Identifier old = texture;
            texture = id;
            if (old != null) mc.getTextureManager().release(old);
        }
        return texture;
    }

    // ------------------------------------------------------------------ sending

    private static void send(String method, JsonObject params) {
        WebSocket w = ws;
        if (w == null) return;
        JsonObject m = new JsonObject();
        m.addProperty("id", MSG_ID.getAndIncrement());
        m.addProperty("method", method);
        if (params != null) m.add("params", params);
        String text = m.toString();
        SEND.execute(() -> {
            try {
                w.sendText(text, true).get(3, TimeUnit.SECONDS);
            } catch (Throwable ignored) { }
        });
    }

    // ------------------------------------------------------------------ viewport

    static void resize(int w, int h) {
        wantW = Math.max(320, Math.min(3840, w));
        wantH = Math.max(200, Math.min(2160, h));
    }

    // ------------------------------------------------------------------ per-tab calls (probe connections: media polling, music tab)

    private static WebSocket probe(String tabId) throws Exception {
        WebSocket w = PROBES.get(tabId);
        if (w != null) return w;
        String url = null;
        for (Tab t : allTabs) if (t.id().equals(tabId)) url = t.wsUrl();
        if (url == null) return null;
        synchronized (PROBES) {
            w = PROBES.get(tabId);
            if (w != null) return w;
            w = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create(url), new Listener(tabId)).get(4, TimeUnit.SECONDS);
            PROBES.put(tabId, w);
            if (HIDDEN.contains(tabId)) {
                JsonObject en = new JsonObject();
                en.addProperty("id", MSG_ID.getAndIncrement());
                en.addProperty("method", "Page.enable");
                synchronized (w) { w.sendText(en.toString(), true).get(3, TimeUnit.SECONDS); }
            }
            return w;
        }
    }

    /** sends one DevTools command to any tab and completes with the whole response (null on failure) */
    static CompletableFuture<JsonObject> call(String tabId, String method, JsonObject params) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        f.completeOnTimeout(null, 8, TimeUnit.SECONDS);
        ASYNC.execute(() -> {
            int id = MSG_ID.getAndIncrement();
            try {
                WebSocket w = probe(tabId);
                if (w == null) { f.complete(null); return; }
                JsonObject m = new JsonObject();
                m.addProperty("id", id);
                m.addProperty("method", method);
                if (params != null) m.add("params", params);
                WAITING.put(id, f);
                f.whenComplete((r, e) -> WAITING.remove(id));
                synchronized (w) {
                    w.sendText(m.toString(), true).get(3, TimeUnit.SECONDS);
                }
            } catch (Throwable e) {
                f.complete(null);
            }
        });
        return f;
    }

    /** evaluates JavaScript in a tab; completes with the value as text ("" for undefined, null on failure) */
    static CompletableFuture<String> eval(String tabId, String expression, boolean awaitPromise, boolean userGesture) {
        JsonObject p = new JsonObject();
        p.addProperty("expression", expression);
        p.addProperty("returnByValue", true);
        if (awaitPromise) p.addProperty("awaitPromise", true);
        if (userGesture) p.addProperty("userGesture", true);
        return call(tabId, "Runtime.evaluate", p).thenApply(r -> {
            try {
                JsonObject res = r.getAsJsonObject("result").getAsJsonObject("result");
                JsonElement v = res.get("value");
                return v == null || v.isJsonNull() ? "" : v.getAsString();
            } catch (Throwable e) {
                return null;
            }
        });
    }

    private static String createTab(String url) throws Exception {
        String enc = java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/json/new?" + enc))
                .timeout(Duration.ofSeconds(3)).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        return str(JsonParser.parseString(r.body()).getAsJsonObject(), "id");
    }

    /** opens a helper tab that is not shown in the player's tab bar (used by the music player); completes with its id or null */
    static CompletableFuture<String> openHidden(String url) {
        CompletableFuture<String> f = new CompletableFuture<>();
        ASYNC.execute(() -> {
            try {
                String id = createTab(url);
                if (id.isEmpty()) { f.complete(null); return; }
                HIDDEN.add(id);
                Thread.sleep(300);
                publish(fetchTabs());
                f.complete(id);
            } catch (Throwable e) {
                status = "Remote: cannot open helper tab (" + e.getClass().getSimpleName() + ")";
                f.complete(null);
            }
        });
        return f;
    }

    /** starts the worker and, once, launches the selected browser if none answers on the debugging port */
    static void ensureRunning() {
        start();
        if (autoLaunched) return;
        autoLaunched = true;
        ASYNC.execute(() -> {
            try {
                for (int i = 0; i < 8; i++) {
                    if (!fetchTabs().isEmpty()) return;
                    Thread.sleep(250);
                }
            } catch (InterruptedException e) { return; }
            launch();
        });
    }

    // ------------------------------------------------------------------ navigation

    static void navigate(String url) {
        JsonObject p = new JsonObject();
        p.addProperty("url", url);
        send("Page.navigate", p);
    }

    private static void eval(String js) {
        JsonObject p = new JsonObject();
        p.addProperty("expression", js);
        send("Runtime.evaluate", p);
    }

    /** browser-level navigation of any tab (no page script, so no "Leave site?" prompt) */
    static void navigateTab(String tabId, String url) {
        JsonObject p = new JsonObject();
        p.addProperty("url", url);
        call(tabId, "Page.navigate", p);
    }

    static void back() { eval("history.back()"); }
    static void forward() { eval("history.forward()"); }
    static void reload() { send("Page.reload", new JsonObject()); }

    static void selectTab(String id) {
        ASYNC.execute(() -> {
            for (Tab t : fetchTabs()) if (t.id().equals(id)) { attach(t); return; }
        });
    }

    static void newTab(String url) {
        ASYNC.execute(() -> {
            try {
                String id = createTab(url);
                Thread.sleep(250);
                List<Tab> list = fetchTabs();
                publish(list);
                for (Tab t : list) if (t.id().equals(id)) { attach(t); return; }
            } catch (Throwable e) {
                status = "Remote: cannot open tab (" + e.getClass().getSimpleName() + ")";
            }
        });
    }

    static void closeTab(String id) {
        ASYNC.execute(() -> {
            try {
                HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/json/close/" + id))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                Thread.sleep(250);
                publish(fetchTabs());
            } catch (Throwable ignored) { }
        });
    }

    // ------------------------------------------------------------------ input

    /** Minecraft/SDL buttons: 1 left, 2 middle, 3 right. */
    private static String buttonName(int mc) {
        return mc == 2 ? "middle" : mc == 3 ? "right" : "left";
    }

    private static int buttonMask(int mc) {
        return mc == 2 ? 4 : mc == 3 ? 2 : 1;
    }

    static void mousePress(int x, int y, int mcButton, int clickCount) {
        buttonsDown |= buttonMask(mcButton);
        mouseEvent("mousePressed", x, y, buttonName(mcButton), Math.max(1, clickCount), 0, 0);
    }

    static void mouseRelease(int x, int y, int mcButton) {
        buttonsDown &= ~buttonMask(mcButton);
        mouseEvent("mouseReleased", x, y, buttonName(mcButton), 1, 0, 0);
    }

    static void mouseMove(int x, int y) {
        mouseEvent("mouseMoved", x, y, "none", 0, 0, 0);
    }

    static void mouseWheel(int x, int y, double vertical, double horizontal) {
        mouseEvent("mouseWheel", x, y, "none", 0, -horizontal * 100.0, -vertical * 100.0);
    }

    private static void mouseEvent(String type, int x, int y, String button, int clicks, double dx, double dy) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("x", x);
        p.addProperty("y", y);
        p.addProperty("button", button);
        p.addProperty("buttons", buttonsDown);
        p.addProperty("clickCount", clicks);
        if (type.equals("mouseWheel")) {
            p.addProperty("deltaX", dx);
            p.addProperty("deltaY", dy);
        }
        send("Input.dispatchMouseEvent", p);
    }

    private static int cdpMods(int glfwMods) {
        int m = 0;
        if ((glfwMods & 1) != 0) m |= 8;   // shift
        if ((glfwMods & 2) != 0) m |= 2;   // ctrl
        if ((glfwMods & 4) != 0) m |= 1;   // alt
        if ((glfwMods & 8) != 0) m |= 4;   // super
        return m;
    }

    private record Special(int vk, String key, String code) { }

    private static Special special(int glfwKey) {
        return switch (glfwKey) {
            case 259 -> new Special(8, "Backspace", "Backspace");
            case 258 -> new Special(9, "Tab", "Tab");
            case 257, 335 -> new Special(13, "Enter", "Enter");
            case 261 -> new Special(46, "Delete", "Delete");
            case 262 -> new Special(39, "ArrowRight", "ArrowRight");
            case 263 -> new Special(37, "ArrowLeft", "ArrowLeft");
            case 264 -> new Special(40, "ArrowDown", "ArrowDown");
            case 265 -> new Special(38, "ArrowUp", "ArrowUp");
            case 266 -> new Special(33, "PageUp", "PageUp");
            case 267 -> new Special(34, "PageDown", "PageDown");
            case 268 -> new Special(36, "Home", "Home");
            case 269 -> new Special(35, "End", "End");
            default -> null;
        };
    }

    static void keyDown(int glfwKey, int glfwMods) {
        int mods = cdpMods(glfwMods);
        boolean ctrl = (mods & 2) != 0;
        if (ctrl && (glfwKey == 65 || glfwKey == 67 || glfwKey == 86 || glfwKey == 88)) {
            String cmd = glfwKey == 65 ? "selectAll" : glfwKey == 67 ? "copy" : glfwKey == 86 ? "paste" : "cut";
            JsonObject p = new JsonObject();
            p.addProperty("type", "rawKeyDown");
            p.addProperty("modifiers", mods);
            p.addProperty("windowsVirtualKeyCode", glfwKey);
            JsonArray cmds = new JsonArray();
            cmds.add(cmd);
            p.add("commands", cmds);
            send("Input.dispatchKeyEvent", p);
            return;
        }
        Special s = special(glfwKey);
        if (s == null) return;
        JsonObject p = new JsonObject();
        p.addProperty("type", s.vk() == 13 ? "keyDown" : "rawKeyDown");
        p.addProperty("modifiers", mods);
        p.addProperty("windowsVirtualKeyCode", s.vk());
        p.addProperty("key", s.key());
        p.addProperty("code", s.code());
        if (s.vk() == 13) p.addProperty("text", "\r");
        send("Input.dispatchKeyEvent", p);
    }

    static void keyUp(int glfwKey, int glfwMods) {
        Special s = special(glfwKey);
        if (s == null) return;
        JsonObject p = new JsonObject();
        p.addProperty("type", "keyUp");
        p.addProperty("modifiers", cdpMods(glfwMods));
        p.addProperty("windowsVirtualKeyCode", s.vk());
        p.addProperty("key", s.key());
        p.addProperty("code", s.code());
        send("Input.dispatchKeyEvent", p);
    }

    static void typed(int codepoint) {
        if (codepoint < 32 || codepoint == 127) return;
        String text = new String(Character.toChars(codepoint));
        JsonObject p = new JsonObject();
        p.addProperty("type", "char");
        p.addProperty("text", text);
        p.addProperty("unmodifiedText", text);
        send("Input.dispatchKeyEvent", p);
    }

    // ------------------------------------------------------------------ launching the browser

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v;
    }

    private static Path firstExisting(String... paths) {
        for (String s : paths) {
            if (s == null || s.isEmpty()) continue;
            Path p = Path.of(s);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static Path onPath(String... names) {
        String path = env("PATH");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            for (String n : names) {
                Path p = Path.of(dir, n);
                if (Files.isRegularFile(p)) return p;
            }
        }
        return null;
    }

    private static Path find(int which) {
        String pf = env("ProgramFiles"), pf86 = env("ProgramFiles(x86)"), local = env("LOCALAPPDATA");
        switch (which) {
            case 1: {
                Path p = firstExisting(pf + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                        pf86 + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                        local + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                        "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser");
                return p != null ? p : onPath("brave-browser", "brave");
            }
            case 2: {
                Path p = firstExisting(pf + "\\Google\\Chrome\\Application\\chrome.exe",
                        pf86 + "\\Google\\Chrome\\Application\\chrome.exe",
                        local + "\\Google\\Chrome\\Application\\chrome.exe",
                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
                return p != null ? p : onPath("google-chrome", "google-chrome-stable", "chromium", "chromium-browser");
            }
            case 3: {
                Path p = firstExisting(pf86 + "\\Microsoft\\Edge\\Application\\msedge.exe",
                        pf + "\\Microsoft\\Edge\\Application\\msedge.exe",
                        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
                return p != null ? p : onPath("microsoft-edge", "microsoft-edge-stable");
            }
            case 4: {
                Path p = firstExisting(local + "\\Vivaldi\\Application\\vivaldi.exe",
                        pf + "\\Vivaldi\\Application\\vivaldi.exe",
                        "/Applications/Vivaldi.app/Contents/MacOS/Vivaldi");
                return p != null ? p : onPath("vivaldi", "vivaldi-stable");
            }
            case 5: {
                Path p = firstExisting(local + "\\Programs\\Opera\\opera.exe",
                        local + "\\Programs\\Opera GX\\opera.exe",
                        pf + "\\Opera\\opera.exe",
                        "/Applications/Opera.app/Contents/MacOS/Opera");
                return p != null ? p : onPath("opera");
            }
            case 6: {
                String custom = VisionSettings.remotePath == null ? "" : VisionSettings.remotePath.trim();
                return custom.isEmpty() ? null : firstExisting(custom);
            }
            default: {
                for (int i = 1; i <= 5; i++) {
                    Path p = find(i);
                    if (p != null) return p;
                }
                return null;
            }
        }
    }

    /** Start the selected browser with a debugging port and its own profile (needed by current Chromium versions). */
    static void launch() {
        start();
        ASYNC.execute(() -> {
            if (ws != null) { status = "Remote: already connected"; return; }
            Path exe = find(VisionSettings.remoteBrowser);
            if (exe == null) {
                status = "Remote: " + NAMES[Math.floorMod(VisionSettings.remoteBrowser, NAMES.length)]
                        + " not found. Set Custom path (remotePath in config/visionbrowser.json)";
                return;
            }
            try {
                Path profile = Path.of("config", "visionbrowser-remote-profile").toAbsolutePath();
                Files.createDirectories(profile);
                new ProcessBuilder(exe.toString(),
                        "--remote-debugging-port=" + PORT,
                        "--remote-allow-origins=*",
                        "--user-data-dir=" + profile,
                        "--autoplay-policy=no-user-gesture-required",
                        "--disable-background-timer-throttling",
                        "--disable-renderer-backgrounding",
                        "--disable-backgrounding-occluded-windows",
                        "--no-first-run",
                        "--no-default-browser-check",
                        "https://www.google.com")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                status = "Remote: launching " + exe.getFileName() + "...";
            } catch (Throwable e) {
                status = "Remote: launch failed (" + e.getClass().getSimpleName() + ")";
            }
        });
    }
}
