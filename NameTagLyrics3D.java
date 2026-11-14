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
    private static final long EXIT_MS = 280;

    private static final class Inst {
        final List<String> lines;
        final int total;
        final double x, y, z;
        final long spawn, durationMs;
        final String prefix;
        boolean exiting;
        long exitTime;

        Inst(String text, double x, double y, double z, long now, long durationMs, String prefix) {
            this.lines = wrap(text);
            int n = 0;
            for (String l : lines) n += l.length() + 1;
            this.total = Math.max(1, n - 1);
            this.x = x; this.y = y; this.z = z; this.spawn = now; this.durationMs = durationMs; this.prefix = prefix;
        }

        void startExit(long now) { if (!exiting) { exiting = true; exitTime = now; } }

        boolean dead(long now) { return exiting && now - exitTime > EXIT_MS; }

        void render(Object collector, Method submit, PoseStack ps, CameraRenderState crs, long now) throws Exception {
            long age = now - spawn;
            long reveal = Math.min(durationMs / 2, Math.max(160, total * 28L));      // typewriter duration
            float progress = exiting ? 1f : Math.min(1f, age / (float) Math.max(1, reveal));
            int visible = Math.max(1, (int) (total * progress));
            float exit = exiting ? 1f - Math.min(1f, (now - exitTime) / (float) EXIT_MS) : 1f;
            if (exit <= 0.02f) return;
            double dx = x - crs.pos.x, dy = y - crs.pos.y, dz = z - crs.pos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 140) return;
            float distScale = (float) Math.pow(Math.max(4.0, dist) / 4.0, 0.85);    // keeps the apparent size roughly constant
            float base = (total <= 10 ? 2.5f : 1.9f) * VisionSettings.lyricsScale;
            float scale = base * distScale * exit;
            if (scale <= 0.05f) return;
            String color = exiting ? "\u00a77" : prefix;
            int used = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int shown = visible > used ? Math.min(line.length(), visible - used) : 0;
                used += line.length() + 1;
                if (shown <= 0) continue;
                double lineDrop = -i * 0.38 * scale;
                ps.pushPose();
                ps.scale(scale, scale, scale);
                Vec3 p = new Vec3(dx / scale, (dy + lineDrop) / scale, dz / scale);
                submit.invoke(collector, ps, p, 0, Component.literal(color + line.substring(0, shown)), true, FULL_BRIGHT, crs);
                ps.popPose();
            }
        }
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
            Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
            Vec3 cp = cam.position();
            double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
            double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
            double rx = Math.cos(yaw), rz = Math.sin(yaw);
            counter++;
            double dist = VisionSettings.lyricsDistance;
            double lateral = (counter % 2 == 1 ? -4.5 : 4.5) * VisionSettings.lyricsSpread;
            double height = counter % 3 == 0 ? 2.5 : (counter % 2 == 1 ? 0.8 : 1.8);
            String shown = text.trim();
            active = new Inst(shown, cp.x + fx * dist + rx * lateral, cp.y + fy * dist + height, cp.z + fz * dist + rz * lateral,
                    now, Math.max(800, durationMs), colorPrefix());
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