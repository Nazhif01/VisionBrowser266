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

/**
 * Independent world-space word renderer for VisionBrowser.
 * Each lyric word is a separate vanilla name-tag submit positioned in the scene.
 * Enhanced LRC timestamps are used when available; plain LRC gets a proportional timing estimate.
 */
final class NameTagLyrics3D {
    private static final int FULL_BRIGHT = 15728880;
    private static final long LINE_FADE_MS = 520;

    private static final class Word {
        final String text;
        final double x, y, z;
        final double start, end;
        final int index;

        Word(String text, double x, double y, double z, double start, double end, int index) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.z = z;
            this.start = start;
            this.end = end;
            this.index = index;
        }
    }

    private static final class Phrase {
        final List<Word> words;
        final double lineEnd;
        final long born = System.currentTimeMillis();

        Phrase(List<Word> words, double lineEnd) {
            this.words = List.copyOf(words);
            this.lineEnd = lineEnd;
        }

        void render(Object collector, Method submit, PoseStack ps, CameraRenderState crs,
                    double playback, long now) throws Exception {
            double dx0 = 0;
            for (Word word : words) {
                if (playback < word.start) continue;
                double wordAge = playback - word.start;
                double fade = Math.min(1.0, Math.max(0.0, (lineEnd - playback) * 3.0));
                if (fade <= 0.015) continue;

                double dx = word.x - crs.pos.x;
                double dy = word.y - crs.pos.y;
                double dz = word.z - crs.pos.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > 100 || dist < 1.5) continue;

                float distScale = (float) Math.pow(Math.max(4.0, dist) / 4.0, 0.82);
                float intensity = VisionSettings.lyricsIntensity == 0 ? 0.82f
                        : VisionSettings.lyricsIntensity == 2 ? 1.22f : 1.0f;
                float entrance = (float) Math.min(1.0, wordAge / (0.28 / intensity));
                int animation = VisionSettings.lyricsAnimation;
                float bounce = animation == 4 ? (float) Math.abs(Math.sin(wordAge * 10.5)) * (1f - entrance) * 0.34f : 0f;
                float pulse = animation == 1 ? 1f + (float) Math.sin(now / 1000.0 * 8.5 + word.index) * 0.09f
                        : animation == 7 ? 1f + (float) Math.sin(now / 1000.0 * 3.2 + word.index) * 0.08f : 1f;
                float pop = (0.68f + 0.32f * entrance + bounce) * pulse;
                float scale = (word.text.length() > 8 ? 1.65f : 1.95f)
                        * VisionSettings.lyricsScale * distScale * pop;
                if (scale <= 0.05f) continue;

                // Active word gets the bright cyan accent. Earlier words remain softly visible,
                // while upcoming words stay hidden until their own timestamps.
                boolean active = playback >= word.start && playback < word.end;
                String[] palette = {"§b", "§f", "§9", "§1", "§5", "§d", "§c", "§e", "§6", "§4", "§a", "§2", "§3", "§f", "§a", "§f"};
                String color = VisionSettings.lyricsRainbow
                        ? palette[Math.floorMod((int) (now / 180L) + word.index, palette.length)]
                        : palette[Math.max(0, Math.min(palette.length - 1, VisionSettings.lyricsColor))];
                if (!active && !VisionSettings.lyricsRainbow) color = "§f";
                if (fade < 0.55) color = "§8";
                String formatted = color + "§l" + word.text;

                // Tiny floating motion gives each word its own life without making the scene noisy.
                double bob = Math.sin((now / 1000.0) * (animation == 6 ? 5.0 : 2.4) + word.index * 1.31) * (animation == 6 ? 0.19 : 0.09);
                double glitch = animation == 5 ? Math.sin(now / 24.0 + word.index * 3.1) * (1.0 - entrance) * 0.14 : 0.0;
                double punch = (1.0 - entrance) * (animation == 4 ? 0.34 : 0.22) + glitch;
                ps.pushPose();
                ps.scale(scale, scale, scale);
                Vec3 relative = new Vec3(dx / scale, (dy + bob - punch) / scale, dz / scale);
                submit.invoke(collector, ps, relative, 0, Component.literal(formatted),
                        true, FULL_BRIGHT, crs);
                ps.popPose();
            }
        }
    }

    private static volatile Phrase active;
    private static volatile double playbackPosition;
    private static String lastKey = "";
    private static boolean registered, broken;
    private static volatile String status = "3D words: not started";
    private static Method collectorMethod, submitMethod;

    private NameTagLyrics3D() { }

    static String status() { return status; }
    static boolean usable() { return registered && !broken; }

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
                                default -> "VisionBrowserWorldLyrics";
                            };
                        }
                        if (args != null && args.length > 0 && args[0] instanceof LevelRenderContext ctx) onCollect(ctx);
                        return null;
                    });
            Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, proxy);
            registered = true;
            status = "3D words: ready";
        } catch (Throwable error) {
            broken = true;
            status = "3D words unavailable: " + describe(error);
        }
    }

    private static Class<?> callbackType(Field f) throws NoSuchMethodException {
        Type g = f.getGenericType();
        if (g instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) return c;
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
        Class<?> type = Class.forName("net.minecraft.client.renderer.SubmitNodeCollector");
        List<String> seen = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (!m.getName().equals("submitNameTag")) continue;
            Class<?>[] p = m.getParameterTypes();
            StringBuilder sig = new StringBuilder();
            for (Class<?> c : p) sig.append(c.getSimpleName()).append(' ');
            seen.add(sig.toString().trim());
            if (p.length == 7 && p[0].getSimpleName().equals("PoseStack") && p[1] == Vec3.class
                    && p[2] == int.class && p[3].getSimpleName().equals("Component")
                    && p[4] == boolean.class && p[5] == int.class
                    && p[6].getSimpleName().equals("CameraRenderState")) return m;
        }
        throw new NoSuchMethodException("submitNameTag signature not found; found: " + seen);
    }

    private static void onCollect(LevelRenderContext ctx) {
        if (broken) return;
        Phrase phrase = active;
        if (phrase == null) return;
        try {
            if (collectorMethod == null) collectorMethod = LevelRenderContext.class.getMethod("submitNodeCollector");
            if (submitMethod == null) submitMethod = findSubmit();
            PoseStack ps = ctx.poseStack();
            CameraRenderState crs = ctx.levelState().cameraRenderState;
            Object collector = collectorMethod.invoke(ctx);
            if (collector == null || ps == null || crs == null || crs.pos == null) return;
            phrase.render(collector, submitMethod, ps, crs, playbackPosition, System.currentTimeMillis());
            status = "3D words: rendering " + phrase.words.size() + " words";
        } catch (Throwable error) {
            broken = true;
            status = "3D words failed: " + describe(error);
        }
    }

    /** Called from the lyrics HUD update each frame. */
    static void update(String key, FloatingLyrics.Line line, double positionSeconds) {
        if (!usable() || line == null) return;
        playbackPosition = positionSeconds;
        if (key.equals(lastKey)) return;
        lastKey = key;
        try {
            Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
            Vec3 cp = cam.position();
            double yaw = Math.toRadians(cam.yRot());
            double pitch = Math.toRadians(cam.xRot());
            Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                    Math.cos(yaw) * Math.cos(pitch)).normalize();
            Vec3 right = new Vec3(Math.cos(yaw), 0, Math.sin(yaw)).normalize();
            Vec3 up = new Vec3(0, 1, 0);

            List<String> tokens = new ArrayList<>();
            List<Double> starts = new ArrayList<>();
            List<Double> ends = new ArrayList<>();
            List<LyricsEngine.Word> sourceWords = line.words();
            if (sourceWords.size() > 1) {
                for (LyricsEngine.Word source : sourceWords) {
                    String[] parts = source.text().trim().split("\\s+");
                    int chars = 0;
                    for (String part : parts) chars += part.length();
                    double cursor = source.start();
                    for (String part : parts) {
                        double span = Math.max(0.06, source.end() - source.start());
                        double partSpan = span * part.length() / Math.max(1.0, chars);
                        tokens.add(part);
                        starts.add(cursor);
                        cursor += partSpan;
                        ends.add(cursor);
                    }
                }
            } else {
                String[] parts = line.text().trim().split("\\s+");
                int chars = 0;
                for (String part : parts) chars += part.length();
                double cursor = line.time();
                double span = Math.max(0.25, line.end() - line.time());
                for (String part : parts) {
                    double partSpan = span * part.length() / Math.max(1.0, chars);
                    tokens.add(part);
                    starts.add(cursor);
                    cursor += partSpan;
                    ends.add(cursor);
                }
            }

            List<Word> positioned = new ArrayList<>();
            int count = tokens.size();
            double distance = VisionSettings.clamp(VisionSettings.lyricsDistance, 3f, 34f);
            double spread = Math.max(0.75, VisionSettings.lyricsSpread) * 5.2;
            for (int i = 0; i < count; i++) {
                double centered = i - (count - 1) / 2.0;
                double xOffset = centered * Math.min(2.1, spread / Math.max(1, count / 2.0));
                double yPattern = switch (i % 5) {
                    case 0 -> 1.15;
                    case 1 -> -0.45;
                    case 2 -> 0.55;
                    case 3 -> -1.05;
                    default -> 0.05;
                };
                double depth = (i % 3 - 1) * 0.34;
                Vec3 pos = cp.add(forward.scale(distance + depth))
                        .add(right.scale(xOffset))
                        .add(up.scale(yPattern + Math.sin(i * 1.7) * 0.18));
                positioned.add(new Word(tokens.get(i), pos.x, pos.y, pos.z,
                        starts.get(i), Math.max(starts.get(i) + 0.05, ends.get(i)), i));
            }
            active = new Phrase(positioned, line.end());
            status = "3D words: placed " + positioned.size() + " words";
        } catch (Throwable error) {
            status = "3D words: placement failed (" + describe(error) + ")";
        }
    }

    static void clear() {
        active = null;
        lastKey = "";
        playbackPosition = 0;
    }
}
