package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 3D lyrics drawn as Minecraft's own world text (the "name tag" text vanilla draws above players): it sits in the world, scales with
 * distance, shows through blocks and uses the vanilla font. Behaviour is modelled on LyricsPlus by FRK (MIT licence); this is an
 * independent implementation.
 *
 * Layouts: SCATTER splits each lyric into short chunks that appear one after another at random places along an arc in the sky
 * (each a little tilted, at its own distance and size, never straight in front of the crosshair); SINGLE shows the whole line as one
 * block at a random spot, wrapped onto more rows when it is long.
 *
 * The submit call and the event are looked up by reflection and checked at runtime, because they could not be verified against
 * Minecraft 26.3 here. If anything does not match, this class marks itself broken, records what it found in status(), and the caller
 * falls back to the glow-texture lyrics.
 */
final class NameTagLyrics3D {
    private static final int FULL_BRIGHT = 15728880;
    private static final long EXIT_MS = 280;

    /** a short piece of the line (scatter) or the whole line (single block); typed out over its share of the line's singing time */
    private static final class Chunk {
        final List<String> rows;
        final int total;
        final double x, y, z;
        final float roll, size;
        final double startFrac, fracLen;

        Chunk(List<String> rows, double x, double y, double z, float roll, float size, double startFrac, double fracLen) {
            this.rows = rows;
            int n = 0;
            for (String r : rows) n += r.length() + 1;
            this.total = Math.max(1, n - 1);
            this.x = x; this.y = y; this.z = z; this.roll = roll; this.size = size; this.startFrac = startFrac; this.fracLen = fracLen;
        }
    }

    /** one whole word of a row (word-by-word layout); a word is never split */
    private static final class Word {
        final String text;
        final int row, start, end;
        final double startFrac;

        Word(String text, int row, int start, int end, double startFrac) {
            this.text = text; this.row = row; this.start = start; this.end = end; this.startFrac = startFrac;
        }
    }

    private static final class Group {
        final List<Chunk> chunks;                  // scatter / single block
        final List<String> rows;                   // word-by-word
        final List<Word> words;
        final double px, py, pz;
        final float proll;
        final double lineStart, singDur;           // seconds on the media clock
        final String prefix;
        boolean exiting;
        long exitTime;

        Group(List<Chunk> chunks, List<String> rows, List<Word> words, double[] p, float proll, double lineStart, double singDur, String prefix) {
            this.chunks = chunks; this.rows = rows; this.words = words;
            this.px = p == null ? 0 : p[0]; this.py = p == null ? 0 : p[1]; this.pz = p == null ? 0 : p[2];
            this.proll = proll; this.lineStart = lineStart; this.singDur = singDur; this.prefix = prefix;
        }

        void startExit(long now) { if (!exiting) { exiting = true; exitTime = now; } }

        boolean dead(long now) { return exiting && now - exitTime > EXIT_MS; }

        void render(Object collector, Method submit, PoseStack ps, CameraRenderState crs, long now, double pos) throws Exception {
            float exit = exiting ? 1f - Math.min(1f, (now - exitTime) / (float) EXIT_MS) : 1f;
            if (exit <= 0.02f) return;
            if (words != null) renderWords(collector, submit, ps, crs, pos, exit);
            else renderChunks(collector, submit, ps, crs, pos, exit);
        }

        private void renderChunks(Object collector, Method submit, PoseStack ps, CameraRenderState crs, double pos, float exit) throws Exception {
            String color = exiting ? EXIT_PREFIX : prefix;
            for (Chunk c : chunks) {
                double startSec = lineStart + c.startFrac * singDur;
                double durSec = Math.max(0.12, c.fracLen * singDur * 1.1);
                if (!exiting && pos < startSec) continue;                              // not sung yet
                float progress = exiting ? 1f : (float) Math.min(1.0, (pos - startSec) / durSec);   // follows the SONG clock, so pause/seek stay in sync
                int visible = Math.max(1, (int) (c.total * progress));
                double dx = c.x - crs.pos.x, dy = c.y - crs.pos.y, dz = c.z - crs.pos.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > 140 || dist < 0.5) continue;
                float scale = (c.total <= 10 ? 2.4f : 1.9f) * VisionSettings.lyricsScale * c.size * distScale(dist) * exit;
                if (scale <= 0.05f) continue;
                int used = 0;
                for (int i = 0; i < c.rows.size(); i++) {
                    String row = c.rows.get(i);
                    int shown = visible > used ? Math.min(row.length(), visible - used) : 0;
                    used += row.length() + 1;
                    if (shown <= 0) continue;
                    submitText(collector, submit, ps, crs, dx, dy, dz, dist, c.roll, scale, -i * 0.38 * scale, color + row.substring(0, shown));
                }
            }
        }

        private void renderWords(Object collector, Method submit, PoseStack ps, CameraRenderState crs, double pos, float exit) throws Exception {
            double dx = px - crs.pos.x, dy = py - crs.pos.y, dz = pz - crs.pos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 140 || dist < 0.5) return;
            float scale = 2.0f * VisionSettings.lyricsScale * distScale(dist) * exit;
            if (scale <= 0.05f) return;
            for (Word w : words) {
                double startSec = lineStart + w.startFrac * singDur;
                if (!exiting && pos < startSec) continue;                              // this word is not sung yet
                float p = exiting ? 1f : (float) Math.min(1.0, (pos - startSec) / WORD_ANIM_SECONDS);
                float eased = easeOutBack(p);
                int letters = Math.max(1, (int) Math.ceil(w.text.length() * Math.min(1f, p / 0.6f)));
                String color = exiting ? EXIT_PREFIX : (p < 0.25f ? "\u00a78\u00a7l" : p < 0.5f ? "\u00a77\u00a7l" : prefix);
                String row = rows.get(w.row);
                // The word keeps its exact place inside the row: spaces stand in for the text before and after it, so the sentence is
                // spaced like normal text and a word is never split ("wondered" stays "wondered").
                String text = pad(widthPx(row.substring(0, w.start))) + w.text.substring(0, letters)
                        + pad(widthPx(w.text.substring(letters))) + pad(widthPx(row.substring(w.end)));
                double rise = -(1f - eased) * 0.12 * scale;                           // each word rises into place with a small bounce
                submitText(collector, submit, ps, crs, dx, dy, dz, dist, proll, scale, -w.row * 0.38 * scale + rise, color + text);
            }
        }
    }

    private static final String EXIT_PREFIX = "\u00a77\u00a7l";
    private static final double WORD_ANIM_SECONDS = 0.32;

    private static float distScale(double dist) { return (float) Math.pow(Math.max(4.0, dist) / 4.0, 0.85); }   // keeps the apparent size steady

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static void submitText(Object collector, Method submit, PoseStack ps, CameraRenderState crs,
                                   double dx, double dy, double dz, double dist, float roll, float scale, double drop, String text) throws Exception {
        ps.pushPose();
        if (roll != 0f && rollMethod() != null) {
            // tilt the text around the line of sight: the centre lies on that axis and does not move
            rollMethod().invoke(ps, new Quaternionf().rotationAxis(roll, (float) (dx / dist), (float) (dy / dist), (float) (dz / dist)));
        }
        ps.scale(scale, scale, scale);
        Vec3 p = new Vec3(dx / scale, (dy + drop) / scale, dz / scale);
        submit.invoke(collector, ps, p, 0, Component.literal(text), true, FULL_BRIGHT, crs);
        ps.popPose();
    }

    /** width in font pixels of s in bold (the colour code itself has no width) */
    private static int widthPx(String s) {
        if (s.isEmpty()) return 0;
        net.minecraft.client.gui.Font f = Minecraft.getInstance().font;
        return Math.max(0, f.width("\u00a7l" + s) - f.width("\u00a7l"));
    }

    private static String pad(int px) {
        if (px <= 0) return "";
        int space = Math.max(1, widthPx(" "));
        return " ".repeat(Math.max(0, Math.round(px / (float) space)));
    }

    private static Group active, previous;
    private static int counter;
    private static String lastKey = "";
    private static boolean registered, broken;
    private static volatile String status = "3D text: not started";
    private static Method collectorMethod, submitMethod, mulPose;
    private static boolean mulPoseResolved;
    static Random rng = new Random();

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

    /** PoseStack.mulPose(Quaternion...) if this version has it; without it the chunks simply are not tilted */
    private static Method rollMethod() {
        if (!mulPoseResolved) {
            mulPoseResolved = true;
            for (Method m : PoseStack.class.getMethods()) {
                if (m.getName().equals("mulPose") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(Quaternionf.class)) { mulPose = m; break; }
            }
        }
        return mulPose;
    }

    // ------------------------------------------------------------------ world callback

    private static void onCollect(LevelRenderContext ctx) {
        if (broken) return;
        Group a = active, p = previous;
        if (a == null && p == null) return;
        try {
            if (collectorMethod == null) collectorMethod = LevelRenderContext.class.getMethod("submitNodeCollector");
            if (submitMethod == null) submitMethod = findSubmit();
            PoseStack ps = ctx.poseStack();
            CameraRenderState crs = ctx.levelState().cameraRenderState;
            Object collector = collectorMethod.invoke(ctx);
            if (collector == null || ps == null || crs == null || crs.pos == null) return;
            long now = System.currentTimeMillis();
            double pos = FloatingLyrics.lyricPosition();
            if (p != null) {
                if (p.dead(now)) previous = null; else p.render(collector, submitMethod, ps, crs, now, pos);
            }
            if (a != null) a.render(collector, submitMethod, ps, crs, now, pos);
            status = "3D text: ok";
        } catch (Throwable error) {
            broken = true;
            status = "3D text failed: " + describe(error);
        }
    }

    // ------------------------------------------------------------------ line spawning (called every frame from the lyrics HUD element)

    /**
     * @param lineStart start of this lyric line on the song clock (seconds)
     * @param nextStart start of the next line, or a negative number if there is none
     */
    static void update(String key, String text, double lineStart, double nextStart) {
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
            counter++;
            String shown = VisionSettings.lyricsUppercase ? text.trim().toUpperCase(Locale.ROOT) : text.trim();
            double singDur = singingSeconds(shown, nextStart > lineStart ? nextStart - lineStart : 4.0);
            int layout = VisionSettings.lyricsLayout;
            Group g;
            if (layout == 2) g = wordByWord(shown, cp, yaw, pitch, lineStart, singDur);
            else g = new Group(layout == 0 ? scatter(shown, cp, yaw, pitch) : single(shown, cp, yaw, pitch), null, null, null, 0f, lineStart, singDur, colorPrefix());
            active = g;
        } catch (Throwable error) {
            status = "3D text: cannot place line (" + describe(error) + ")";
        }
    }

    static void clear() {
        active = null;
        previous = null;
        lastKey = "";
    }

    /**
     * How long the line is being sung, estimated from the gap to the next line: most of the gap, but a short line followed by a long
     * instrumental break is not stretched over the break. Words are spread over this time, so they track the vocal.
     */
    static double singingSeconds(String text, double gapSeconds) {
        double byChars = Math.max(1.0, text.length() * 0.13);
        return Math.max(0.6, Math.min(gapSeconds * 0.9, byChars));
    }

    // ------------------------------------------------------------------ layouts

    /** world position `dist` blocks from the camera, `yawOff` degrees to the side and `pitchOff` degrees above the gaze direction */
    private static double[] place(Vec3 cp, double yaw, double pitch, double yawOffDeg, double pitchOffDeg, double dist) {
        double yw = yaw + Math.toRadians(yawOffDeg), pt = pitch - Math.toRadians(pitchOffDeg);   // Minecraft: positive pitch looks down
        pt = Math.max(-Math.PI / 2 + 0.05, Math.min(Math.PI / 2 - 0.05, pt));
        double fx = -Math.sin(yw) * Math.cos(pt), fy = -Math.sin(pt), fz = Math.cos(yw) * Math.cos(pt);
        return new double[]{cp.x + fx * dist, cp.y + fy * dist, cp.z + fz * dist};
    }

    private static List<Chunk> scatter(String text, Vec3 cp, double yaw, double pitch) {
        List<String> parts = chunkWords(text, 14, 3, 6);
        int n = parts.size();
        double totalW = 0;
        for (String p : parts) totalW += p.length() + 2;
        double arc = Math.max(12.0, 36.0 * VisionSettings.lyricsSpread);          // half-width of the arc, in degrees
        double base = VisionSettings.lyricsDistance;
        boolean leftToRight = rng.nextBoolean();
        List<Chunk> out = new ArrayList<>();
        double cum = 0;
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0.5 : i / (double) (n - 1);
            double side = (leftToRight ? t : 1 - t) * 2 - 1;                         // -1 .. +1 across the arc, reading direction random
            double yawOff = side * arc + (rng.nextDouble() * 12 - 6);
            if (n == 1 || Math.abs(yawOff) < 10) yawOff = (yawOff < 0 ? -1 : 1) * (10 + rng.nextDouble() * 12);   // never straight ahead
            double pitchOff = 14 + (i % 2 == 0 ? 6 : -3) + (rng.nextDouble() * 10 - 5);
            double dist = base * (0.85 + rng.nextDouble() * 0.3);
            double[] w = place(cp, yaw, pitch, yawOff, pitchOff, dist);
            float roll = (float) Math.toRadians(rng.nextDouble() * 22 - 11);
            float size = 0.9f + rng.nextFloat() * 0.35f;
            double len = parts.get(i).length() + 2;
            out.add(new Chunk(List.of(parts.get(i)), w[0], w[1], w[2], roll, size, cum / totalW, len / totalW));
            cum += len;
        }
        return out;
    }

    private static List<Chunk> single(String text, Vec3 cp, double yaw, double pitch) {
        int maxChars = Math.max(14, Math.round(28f / Math.max(0.6f, VisionSettings.lyricsScale)));
        List<String> rows = wrapBalanced(text, maxChars);
        double yawOff = (rng.nextBoolean() ? 1 : -1) * (12 + rng.nextDouble() * 22) * Math.max(0.4, VisionSettings.lyricsSpread);   // never straight ahead
        double pitchOff = 6 + rng.nextDouble() * 14;
        double dist = VisionSettings.lyricsDistance * (0.9 + rng.nextDouble() * 0.2);
        double[] w = place(cp, yaw, pitch, yawOff, pitchOff, dist);
        float roll = (float) Math.toRadians(rng.nextDouble() * 8 - 4);
        return List.of(new Chunk(rows, w[0], w[1], w[2], roll, 1f, 0, 1));
    }

    /** the whole sentence appears at one random spot (rows if long) and each whole word pops in when it is sung */
    private static Group wordByWord(String text, Vec3 cp, double yaw, double pitch, double lineStart, double singDur) {
        int maxChars = Math.max(14, Math.round(28f / Math.max(0.6f, VisionSettings.lyricsScale)));
        List<String> rows = wrapBalanced(text, maxChars);
        double yawOff = (rng.nextBoolean() ? 1 : -1) * (12 + rng.nextDouble() * 22) * Math.max(0.4, VisionSettings.lyricsSpread);
        double pitchOff = 6 + rng.nextDouble() * 14;
        double dist = VisionSettings.lyricsDistance * (0.9 + rng.nextDouble() * 0.2);
        double[] centre = place(cp, yaw, pitch, yawOff, pitchOff, dist);
        float roll = (float) Math.toRadians(rng.nextDouble() * 6 - 3);
        List<Word> words = new ArrayList<>();
        double totalW = 0;
        for (String row : rows) for (String w : row.split(" ")) if (!w.isEmpty()) totalW += w.length() + 2;
        double cum = 0;
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            int i = 0;
            while (i < row.length()) {
                if (row.charAt(i) == ' ') { i++; continue; }
                int j = i;
                while (j < row.length() && row.charAt(j) != ' ') j++;     // a word ends only at a space, so it is never cut in two
                words.add(new Word(row.substring(i, j), r, i, j, cum / totalW));
                cum += (j - i) + 2;
                i = j;
            }
        }
        return new Group(null, rows, words, centre, roll, lineStart, singDur, colorPrefix());
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

    /** word-wraps into rows of at most maxChars characters (a single longer word keeps its own row) */
    static List<String> wrapChars(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > maxChars) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add(text.trim());
        return out;
    }

    /** fewest rows that fit (a lyric that does not fit on one row goes onto two), with the rows about equally long */
    static List<String> wrapBalanced(String text, int maxChars) {
        String t = text.trim();
        int n = Math.max(1, (int) Math.ceil(t.length() / (double) maxChars));
        if (n == 1) return List.of(t);
        int limit = (int) Math.ceil(t.length() / (double) n) + 3;
        List<String> rows = wrapChars(t, limit);
        while (rows.size() > n && limit < t.length()) { limit += 2; rows = wrapChars(t, limit); }
        return rows;
    }

    /** groups words into short chunks (at most maxWords words and about maxChars characters); extra chunks beyond maxChunks join the last one */
    static List<String> chunkWords(String text, int maxChars, int maxWords, int maxChunks) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int words = 0;
        for (String word : text.trim().split("\\s+")) {
            if (cur.length() > 0 && (words >= maxWords || cur.length() + 1 + word.length() > maxChars)) { chunks.add(cur.toString()); cur.setLength(0); words = 0; }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
            words++;
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        while (chunks.size() > maxChunks) { int last = chunks.size() - 1; chunks.set(last - 1, chunks.get(last - 1) + " " + chunks.remove(last)); }
        if (chunks.isEmpty()) chunks.add(text.trim());
        return chunks;
    }

    static String format(String f, Object... a) { return String.format(Locale.ROOT, f, a); }
}