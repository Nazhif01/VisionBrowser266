package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 3D lyrics drawn as Minecraft's own world text (the same "name tag" text vanilla uses above players), so they sit in the world,
 * scale with distance, show through blocks and use the vanilla font. Behaviour is modelled on LyricsPlus by FRK (MIT licence):
 * a new line appears about 18 blocks ahead, alternating left/right and at varying heights, is typed out letter by letter, and fades
 * when the next line arrives. This is an independent implementation.
 *
 * The submit call and the event are looked up by reflection and checked at runtime, because they could not be verified against
 * Minecraft 26.3 here. If anything does not match, this class marks itself broken, records exactly what it found in status(),
 * and the caller falls back to the glow-texture lyrics.
 */
final class NameTagLyrics3D {
    private static final int FULL_BRIGHT = 15728880;

    private static final class Inst {
        final List<String> lines;
        final int total;
        final double x, y, z;
        final double forward, side, height, spawnYaw, spawnPitch;
        final long spawn, durationMs;
        final String prefix;
        // player-view placement
        final boolean view;
        final double ndcX, ndcY, depth;
        final float fit, hwUnits;      // fit: shrink factor so it fits the screen; hwUnits: half width in px of the widest line
        double sx, sy, sz;
        boolean smoothInit;
        long lastRender;
        boolean exiting;
        long exitTime;

        Inst(String text, double x, double y, double z, double forward, double side, double height, double yaw, double pitch, long now,
             long durationMs, String prefix, boolean view, double ndcX, double ndcY, double depth, float fit) {
            this.lines = wrap(text);
            int n = 0;
            for (String l : lines) n += l.length() + 1;
            this.total = VisionSettings.lyricsWordByWord ? countWords(this.lines) : Math.max(1, n - 1);
            this.x = x; this.y = y; this.z = z;
            this.forward = forward; this.side = side; this.height = height; this.spawnYaw = yaw; this.spawnPitch = pitch;
            this.spawn = now; this.durationMs = durationMs; this.prefix = prefix;
            this.view = view; this.ndcX = ndcX; this.ndcY = ndcY; this.depth = depth; this.fit = fit;
            this.hwUnits = (float) widestPx(this.lines) / 2f;
        }

        void startExit(long now) { if (!exiting) { exiting = true; exitTime = now; } }

        boolean dead(long now) { return exiting && now - exitTime > LyricAnim.outMs(); }

        float scaleAt(double dist) {
            float distScale = (float) Math.pow(Math.max(4.0, dist) / 4.0, 0.85);    // keeps the apparent size roughly constant
            float base = (total <= 10 ? 2.5f : 1.9f) * VisionSettings.lyricsScale;
            return base * distScale * fit;
        }

        void render(Object collector, Method submit, PoseStack ps, CameraRenderState crs, long now) throws Exception {
            long age = now - spawn;
            long reveal = !VisionSettings.lyricsReveal ? 0
                    : VisionSettings.lyricsWordByWord ? Math.min(durationMs / 2, Math.max(160, Math.round(total * VisionSettings.lyricsWordSpeed * 1000f)))
                    : Math.min(durationMs / 2, Math.max(160, total * 28L));
            float progress = exiting || reveal == 0 ? 1f : Math.min(1f, age / (float) Math.max(1, reveal));
            int visible = Math.max(1, (int) (total * progress));

            LyricAnim.Pose pose = LyricAnim.idle(now, spawn);
            pose = LyricAnim.combine(pose, LyricAnim.enter(VisionSettings.lyricsAnimIn, age / (float) LyricAnim.inMs()));
            if (exiting) pose = LyricAnim.combine(pose, LyricAnim.exit(VisionSettings.lyricsAnimOut, (now - exitTime) / (float) LyricAnim.outMs()));
            if (pose.alpha() <= 0.03f) return;

            Minecraft mc = Minecraft.getInstance();
            Camera camera = mc.gameRenderer.mainCamera();
            Vec3 cp = camera.position();
            double yaw = Math.toRadians(camera.yRot()), pitch = Math.toRadians(camera.xRot());
            double crx = -Math.cos(yaw), crz = -Math.sin(yaw);                       // camera right
            double cux = -Math.sin(yaw) * Math.sin(pitch), cuy = Math.cos(pitch), cuz = Math.cos(yaw) * Math.sin(pitch);   // camera up

            double tx = x, ty = y, tz = z, lift;
            float scale;
            int rows = lines.size();
            if (view) {
                ViewMath.Frame fr = ViewMath.frame(mc);
                if (fr == null) return;
                double[] target = ViewMath.toWorld(fr, ndcX, ndcY, depth);
                scale = scaleAt(depth) * pose.scale() * (float) Math.pow(pose.alpha(), 0.6);
                if (scale <= 0.05f) return;
                float dt = lastRender == 0 ? 0.016f : Math.min(0.1f, (now - lastRender) / 1000f);
                lastRender = now;
                double k = VisionSettings.lyricsViewSmoothing <= 0.001f ? 1.0 : 1.0 - Math.exp(-dt / (0.02 + VisionSettings.lyricsViewSmoothing * 0.45));
                if (!smoothInit) { sx = target[0]; sy = target[1]; sz = target[2]; smoothInit = true; }
                else { sx += (target[0] - sx) * k; sy += (target[1] - sy) * k; sz += (target[2] - sz) * k; }
                double unit = 0.4 * scale;
                double ox = pose.dx() * unit, oy = pose.dy() * unit;
                double[] p = {sx + crx * ox + cux * oy, sy + cuy * oy, sz + crz * ox + cuz * oy};
                if (VisionSettings.lyricsFitScreen) {
                    double hw = hwUnits * 0.025 * scale, hh = ((rows - 1) * 0.38 + 0.42) * 0.5 * scale;
                    p = ViewMath.clampInside(fr, p, hw, hh, VisionSettings.lyricsScreenMargin);
                }
                tx = p[0]; ty = p[1]; tz = p[2];
                lift = (rows - 1) * 0.19 * scale;     // the centre of the block is the anchor; text hangs down from the first line
                ty += lift;
            } else {
                if (VisionSettings.lyricsPlayerRelative) {
                    double yw = Math.toRadians(VisionSettings.lyricsFollowRotation ? camera.yRot() : Math.toDegrees(spawnYaw));
                    double pt = Math.toRadians(VisionSettings.lyricsFollowRotation ? camera.xRot() : Math.toDegrees(spawnPitch));
                    double fx = -Math.sin(yw) * Math.cos(pt), fy = -Math.sin(pt), fz = Math.cos(yw) * Math.cos(pt);
                    double rx = Math.cos(yw), rz = Math.sin(yw);
                    tx = cp.x + fx * forward + rx * side;
                    ty = cp.y + fy * forward + height;
                    tz = cp.z + fz * forward + rz * side;
                }
                double dx0 = tx - cp.x, dy0 = ty - cp.y, dz0 = tz - cp.z;
                double dist0 = Math.sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0);
                scale = scaleAt(dist0) * pose.scale() * (float) Math.pow(pose.alpha(), 0.6);
                if (scale <= 0.05f) return;
                double unit = 0.4 * scale, ox = pose.dx() * unit, oy = pose.dy() * unit;
                tx += crx * ox + cux * oy; ty += cuy * oy; tz += crz * ox + cuz * oy;
            }
            double dx = tx - crs.pos.x, dy = ty - crs.pos.y, dz = tz - crs.pos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 140) return;
            String color = exiting && pose.alpha() < 0.6f ? "\u00a77" : prefix;
            int used = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int units = VisionSettings.lyricsWordByWord ? line.split("\\s+").length : line.length();
                int shown = visible > used ? Math.min(units, visible - used) : 0;
                used += units + 1;
                if (shown <= 0) continue;
                String partial = VisionSettings.lyricsWordByWord ? firstWords(line, shown) : line.substring(0, shown);
                double lineDrop = -i * 0.38 * scale;
                ps.pushPose();
                ps.scale(scale, scale, scale);
                Vec3 p = new Vec3(dx / scale, (dy + lineDrop) / scale, dz / scale);
                submit.invoke(collector, ps, p, 0, Component.literal(color + partial), true, FULL_BRIGHT, crs);
                ps.popPose();
            }
        }
    }

    private static double widestPx(List<String> lines) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        double w = 1;
        for (String l : lines) w = Math.max(w, font.width(l) + l.length());   // +1 px per glyph for the bold style
        return w;
    }

    private static Inst active, previous;
    private static int counter;
    private static String lastKey = "";
    private static boolean registered, broken;
    private static volatile String status = "3D text: not started";
    private static Method collectorMethod, submitMethod;

    private NameTagLyrics3D() { }

    static String status() { return status; }

    /** true while the vanilla-text path is registered and has not reported a mismatch */
    static boolean usable() { return registered && !broken; }

    // ------------------------------------------------------------------ registration (reflection)

    static void register() {
        try {
            Field f = LevelRenderEvents.class.getField("COLLECT_SUBMITS");
            Object event = f.get(null);
            Class<?> callback = callbackType(f);
            Object proxy = Proxy.newProxyInstance(NameTagLyrics3D.class.getClassLoader(), new Class<?>[]{callback},
                    (self, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> self == args[0];
                                case "hashCode" -> System.identityHashCode(self);
                                default -> "NameTagLyrics3D";
                            };
                        }
                        if (args != null && args.length > 0 && args[0] instanceof LevelRenderContext ctx) onCollect(ctx);
                        return null;
                    });
            Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, proxy);
            registered = true;
            status = "3D text: ready";
        } catch (Throwable error) {
            broken = true;
            status = "3D text unavailable: " + describe(error);
        }
    }

    private static Class<?> callbackType(Field f) throws NoSuchMethodException {
        Type g = f.getGenericType();
        if (g instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1 && pt.getActualTypeArguments()[0] instanceof Class<?> c) return c;
        for (Class<?> c : LevelRenderEvents.class.getDeclaredClasses()) {
            if (c.isInterface() && c.getSimpleName().contains("CollectSubmits")) return c;
        }
        throw new NoSuchMethodException("COLLECT_SUBMITS callback interface not found");
    }

    private static String describe(Throwable t) {
        Throwable c = t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }

    private static Method findSubmit() throws Exception {
        Class<?> collectorType = Class.forName("net.minecraft.client.renderer.SubmitNodeCollector");
        List<String> seen = new ArrayList<>();
        for (Method m : collectorType.getMethods()) {
            if (!m.getName().equals("submitNameTag")) continue;
            Class<?>[] p = m.getParameterTypes();
            StringBuilder sb = new StringBuilder();
            for (Class<?> c : p) sb.append(c.getSimpleName()).append(' ');
            seen.add(sb.toString().trim());
            if (p.length == 7 && p[0].getSimpleName().equals("PoseStack") && p[1] == Vec3.class && p[2] == int.class
                    && p[3].getSimpleName().equals("Component") && p[4] == boolean.class && p[5] == int.class
                    && p[6].getSimpleName().equals("CameraRenderState")) return m;
        }
        throw new NoSuchMethodException("submitNameTag(PoseStack,Vec3,int,Component,boolean,int,CameraRenderState) not found; found: " + seen);
    }

    // ------------------------------------------------------------------ world callback

    private static void onCollect(LevelRenderContext ctx) {
        if (broken) return;
        Inst a = active, p = previous;
        if (a == null && p == null) return;
        try {
            if (collectorMethod == null) collectorMethod = LevelRenderContext.class.getMethod("submitNodeCollector");
            if (submitMethod == null) submitMethod = findSubmit();
            PoseStack ps = ctx.poseStack();
            CameraRenderState crs = ctx.levelState().cameraRenderState;
            Object collector = collectorMethod.invoke(ctx);
            if (collector == null || ps == null || crs == null || crs.pos == null) return;
            long now = System.currentTimeMillis();
            if (p != null) {
                if (p.dead(now)) previous = null; else p.render(collector, submitMethod, ps, crs, now);
            }
            if (a != null) a.render(collector, submitMethod, ps, crs, now);
            status = "3D text: ok";
        } catch (Throwable error) {
            broken = true;
            status = "3D text failed: " + describe(error);
        }
    }

    // ------------------------------------------------------------------ line spawning (called every frame from the lyrics HUD element)

    static void update(String key, String text, long durationMs) {
        if (!usable() || key.equals(lastKey)) return;
        lastKey = key;
        long now = System.currentTimeMillis();
        if (active != null) {
            active.startExit(now);
            previous = active;
            active = null;
        }
        if (text == null || text.isBlank()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            Camera cam = mc.gameRenderer.mainCamera();
            Vec3 cp = cam.position();
            double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
            double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
            double rx = Math.cos(yaw), rz = Math.sin(yaw);
            counter++;
            String shown = text.trim();
            String prefix = colorPrefix();
            ViewMath.Frame fr = VisionSettings.lyricsViewMode ? ViewMath.frame(mc) : null;
            if (fr != null) {
                // player-view mode: pick a random spot in the view that is off the crosshair and fully on screen
                double depth = VisionSettings.lyricsViewDepth, margin = VisionSettings.lyricsScreenMargin;
                List<String> wrapped = wrap(shown);
                int totalUnits = 0;
                for (String l : wrapped) totalUnits += l.length() + 1;
                float base = (VisionSettings.lyricsWordByWord ? 2.5f : (totalUnits - 1 <= 10 ? 2.5f : 1.9f)) * VisionSettings.lyricsScale;
                float scale0 = base * (float) Math.pow(Math.max(4.0, depth) / 4.0, 0.85);
                double hwB = widestPx(wrapped) * 0.5 * 0.025 * scale0;
                double hhB = ((wrapped.size() - 1) * 0.38 + 0.42) * 0.5 * scale0;
                double hwN = hwB / (depth * fr.tanH()), hhN = hhB / (depth * fr.tanV());
                float fit = 1f;
                if (VisionSettings.lyricsFitScreen) {
                    double room = 1.0 - margin;
                    fit = (float) Math.min(1.0, Math.min(room / Math.max(0.001, hwN), room / Math.max(0.001, hhN)));
                    hwN *= fit; hhN *= fit;
                }
                List<double[]> avoid = new ArrayList<>();
                if (previous != null && previous.view) avoid.add(new double[]{previous.ndcX, previous.ndcY, previous.hwUnits * 0.025 * previous.scaleAt(previous.depth) / (previous.depth * fr.tanH()), 0.1});
                double[] spot = ViewMath.pick(hwN, hhN, VisionSettings.lyricsViewAnchor, VisionSettings.lyricsCrosshairExclude, margin, avoid);
                double[] w = ViewMath.toWorld(fr, spot[0], spot[1], depth);
                active = new Inst(shown, w[0], w[1], w[2], depth, 0, 0, yaw, pitch, now, Math.max(800, durationMs), prefix, true, spot[0], spot[1], depth, fit);
                return;
            }
            double dist = VisionSettings.lyricsDistance;
            double lateral = VisionSettings.lyricsSide * 4.5 + (counter % 2 == 1 ? -4.5 : 4.5) * VisionSettings.lyricsSpread
                    + VisionSettings.lyricsHorizontalOffset;
            double height = (counter % 3 == 0 ? 2.5 : (counter % 2 == 1 ? 0.8 : 1.8)) + VisionSettings.lyricsVerticalOffset;
            active = new Inst(shown, cp.x + fx * dist + rx * lateral, cp.y + fy * dist + height, cp.z + fz * dist + rz * lateral,
                    dist, lateral, height, yaw, pitch, now, Math.max(800, durationMs), prefix, false, 0, 0, dist, 1f);
        } catch (Throwable error) {
            status = "3D text: cannot place line (" + describe(error) + ")";
        }
    }

    static void clear() {
        active = null;
        previous = null;
        lastKey = "";
    }

    // ------------------------------------------------------------------ helpers

    /** nearest bright legacy chat colour to the album accent (or white), plus bold */
    static String colorPrefix() {
        String code = "f";
        if (VisionSettings.lyricsAlbumColor) {
            int rgb = IslandArt.accent() & 0xFFFFFF;
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            if (spread > 40) {
                String codes = "6" + "9" + "a" + "b" + "c" + "d" + "e";
                int[] pal = {0xFFAA00, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55};
                long best = Long.MAX_VALUE;
                for (int i = 0; i < pal.length; i++) {
                    int pr = (pal[i] >> 16) & 255, pg = (pal[i] >> 8) & 255, pb = pal[i] & 255;
                    long d = (long) (r - pr) * (r - pr) + (long) (g - pg) * (g - pg) + (long) (b - pb) * (b - pb);
                    if (d < best) { best = d; code = String.valueOf(codes.charAt(i)); }
                }
            }
        }
        return "\u00a7" + code + "\u00a7l";
    }

    private static int countWords(List<String> lines) {
        int count = 0;
        for (String line : lines) count += (line.isBlank() ? 0 : line.trim().split("\\s+").length) + 1;
        return Math.max(1, count);
    }

    private static String firstWords(String line, int count) {
        String[] words = line.trim().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(count, words.length)));
    }

    /** word-wraps a lyric into lines of about 28 characters */
    static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > 28) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add(text.trim());
        return out;
    }

    static String format(String f, Object... a) { return String.format(Locale.ROOT, f, a); }
}