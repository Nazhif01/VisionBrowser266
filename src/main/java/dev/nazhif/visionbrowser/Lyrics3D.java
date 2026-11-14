package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;

/**
 * Lyrics that float in the WORLD. Each line is a quad placed in world space when it becomes active; every frame its four
 * corners are projected with the game's own camera (position, xRot, yRot) and projection matrix, and the pre-rendered
 * glowing text texture is drawn perspective-correct as thin vertical strips through the verified GUI blit call.
 * No raw OpenGL is used, so it does not depend on the renderer backend. Limitation: no occlusion by terrain.
 */
final class Lyrics3D {
    private static final float BLOCKS_PER_PX = 0.012f;
    private static final float MIN_DISTANCE = 3.0f;
    private static final long LIFE_MS = 7500, FADE_IN_MS = 400, FADE_OUT_MS = 1500;
    private static final int MAX_LINES = 3, FONT_SIZE = 48, PAD = 28, MAX_TEX_W = 1100;

    private static final class Line {
        final long born = System.currentTimeMillis();
        volatile long animationBorn = born;
        String text;
        double cx, cy, cz;         // world centre; updated when a followed line is released
        final double rx, rz;       // horizontal "right" unit vector
        final double nx, nz;       // horizontal normal pointing at the player who saw it spawn
        final float scale = VisionSettings.lyricsScale; // size multiplier captured at spawn
        boolean followsPlayer;
        double localX, localY, localZ;
        double smoothX, smoothY, smoothZ;
        boolean smoothReady;
        float lift, liftTarget;
        volatile Identifier texture;
        volatile int texW, texH;
        volatile boolean released, failed;
        volatile int textureGeneration;

        Line(String text, double cx, double cy, double cz, double rx, double rz, double nx, double nz) {
            this.text = text; this.cx = cx; this.cy = cy; this.cz = cz;
            this.rx = rx; this.rz = rz; this.nx = nx; this.nz = nz;
        }
    }

    private record Rendered(NativeImage image, int w, int h) { }

    private static final List<Line> lines = new ArrayList<>();
    private static String lastKey = "";
    private static String lastText = "";
    private static Line activeLine;
    private static volatile String status = "3D lyrics: idle";
    private static long lastFrameNanos;
    private static int counter;

    private Lyrics3D() { }

    static String status() { return status; }

    /** Call every frame with a key that changes whenever the active lyric line changes. */
    static void show(String key, String text) {
        if (text == null) text = "";
        if (key.equals(lastKey) && activeLine == null && !text.isBlank()) {
            // A new lyric line can begin with an empty progressive reveal. Spawn it when its first word arrives.
            lastKey = "";
        }
        if (key.equals(lastKey)) {
            if (!text.equals(lastText) && activeLine != null && !text.isBlank()) {
                activeLine.text = text;
                // Restart only the entrance animation whenever the next lyric word is revealed.
                // The lyric lifetime and fade timing remain tied to the original sentence.
                activeLine.animationBorn = System.currentTimeMillis();
                Identifier previous = activeLine.texture;
                activeLine.texture = null;
                if (previous != null) Minecraft.getInstance().getTextureManager().release(previous);
                activeLine.texW = 0;
                activeLine.texH = 0;
                activeLine.failed = false;
                rasterize(activeLine);
                lastText = text;
            }
            return;
        }
        lastKey = key;
        lastText = text;
        if (text.isBlank()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            Camera cam = mc.gameRenderer.mainCamera();
            Vec3 cp = cam.position();
            double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
            Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                    Math.cos(yaw) * Math.cos(pitch)).normalize();
            Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)).normalize();
            double rxs = -Math.cos(yaw), rzs = -Math.sin(yaw);
            Line line;
            if (VisionSettings.glowPlacement == 2) {
                // Player Relative: keep the lyric at a fixed local offset from the player.
                // Randomize its position around the forward view, but store that offset in player space.
                line = createRandomLine(text, cp, forward, right, rxs, rzs);
                line.followsPlayer = true;
                double dx = line.cx - cp.x, dz = line.cz - cp.z;
                line.localX = dx * right.x + dz * right.z;
                line.localY = line.cy - cp.y;
                line.localZ = dx * forward.x + dz * forward.z;
            } else if (VisionSettings.glowPlacement == 1) {
                line = createRandomLine(text, cp, forward, right, rxs, rzs);
            } else {
                double angle = Math.toRadians(VisionSettings.lyricsSide * 24.0);
                Vec3 direction = forward.scale(Math.cos(angle)).add(right.scale(Math.sin(angle))).normalize();
                line = new Line(text,
                        cp.x + direction.x * VisionSettings.lyricsDistance, cp.y + direction.y * VisionSettings.lyricsDistance + 0.6,
                        cp.z + direction.z * VisionSettings.lyricsDistance,
                        rxs, rzs, -direction.x, -direction.z);
            }
            // Never stack old and new lyric sentences on top of each other.
            for (Line previous : lines) release(previous);
            lines.clear();
            activeLine = line;
            lines.add(line);
            rasterize(line);
        } catch (Throwable error) {
            status = "3D lyrics: cannot place line (" + error.getClass().getSimpleName() + ")";
        }
    }

    private static Line createRandomLine(String text, Vec3 cp, Vec3 forward, Vec3 right, double rxs, double rzs) {
        // Random positions stay inside a 84-degree forward cone, never behind the player.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = Math.toRadians(random.nextDouble(-42.0, 42.0));
        double configured = VisionSettings.clamp(VisionSettings.lyricsDistance, MIN_DISTANCE, 34f);
        double distance = VisionSettings.clamp((float) (configured + random.nextDouble(-0.8, 0.8)), MIN_DISTANCE, 34f);
        double vertical = random.nextDouble(-0.15, 1.65);
        Vec3 direction = forward.scale(Math.cos(angle)).add(right.scale(Math.sin(angle))).normalize();
        return new Line(text,
                cp.x + direction.x * distance, cp.y + direction.y * distance + vertical,
                cp.z + direction.z * distance,
                rxs, rzs, -direction.x, -direction.z);
    }

    static void clear() {
        for (Line l : lines) release(l);
        lines.clear();
        lastKey = "";
        lastText = "";
        activeLine = null;
    }

    private static void release(Line l) {
        l.released = true;
        Identifier t = l.texture;
        l.texture = null;
        if (t != null) Minecraft.getInstance().getTextureManager().release(t);
    }

    // ------------------------------------------------------------------ text -> glowing texture

    private static void rasterize(Line line) {
        final int accent = VisionSettings.lyricColorRgb();
        final int generation = ++line.textureGeneration;
        final String family = VisionSettings.lyricsFont;
        final boolean glow = VisionSettings.lyricsGlow;
        Thread worker = new Thread(() -> {
            try {
                Rendered r = renderText(line.text, accent, family, glow);
                Minecraft.getInstance().execute(() -> install(line, r, generation));
            } catch (Throwable error) {
                line.failed = true;
                status = "3D lyrics: text render failed (" + error.getClass().getSimpleName() + ")";
            }
        }, "VisionBrowser-Lyrics3D");
        worker.setDaemon(true);
        worker.start();
    }

    private static void install(Line line, Rendered r, int generation) {
        if (line.released || generation != line.textureGeneration) {
            r.image().close();
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath("visionbrowser", "lyric_" + (++counter));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Vision lyric line", r.image()));
        line.texW = r.w();
        line.texH = r.h();
        line.texture = id;
    }

    private static Rendered renderText(String text, int accentArgb, String family, boolean glow) {
        int size = FONT_SIZE;
        int maxLineWidth = 900;
        java.awt.Font font = LyricFonts.make(family, size, text);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        java.awt.FontMetrics fm = pg.getFontMetrics(font);
        while (fm.stringWidth(text) > maxLineWidth * 2 && size > 20) {
            size -= 3;
            font = font.deriveFont((float) size);
            fm = pg.getFontMetrics(font);
        }
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = row.isEmpty() ? word : row + " " + word;
            if (!row.isEmpty() && fm.stringWidth(candidate) > maxLineWidth) {
                rows.add(row.toString());
                row.setLength(0);
                row.append(word);
            } else {
                if (!row.isEmpty()) row.append(' ');
                row.append(word);
            }
        }
        if (!row.isEmpty()) rows.add(row.toString());
        if (rows.isEmpty()) rows.add(text);
        int tw = 1;
        for (String line : rows) tw = Math.max(tw, fm.stringWidth(line));
        int th = fm.getHeight(), ascent = fm.getAscent();
        pg.dispose();
        int w = tw + 2 * PAD, h = rows.size() * th + 2 * PAD;
        BufferedImage base = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = base.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        for (int i = 0; i < rows.size(); i++) g.drawString(rows.get(i), PAD, PAD + ascent + i * th);
        g.dispose();

        float[] a = new float[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) a[y * w + x] = ((base.getRGB(x, y) >>> 24) & 0xFF) / 255f;
        float[] blur = glow ? boxBlur(boxBlur(boxBlur(a, w, h, 8), w, h, 8), w, h, 8) : new float[w * h];

        int ar = Math.round(((accentArgb >> 16) & 0xFF) * 0.65f + 255 * 0.35f);
        int ag = Math.round(((accentArgb >> 8) & 0xFF) * 0.65f + 255 * 0.35f);
        int ab = Math.round((accentArgb & 0xFF) * 0.65f + 255 * 0.35f);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float ta = a[i];
                float ga = glow ? Math.min(1f, blur[i] * 2.6f) : 0f;
                float oa = ta + ga * (1f - ta);
                if (oa <= 0.003f) { out.setPixelABGR(x, y, 0); continue; }
                float k = ga * (1f - ta);
                int rr = Math.min(255, Math.round((255f * ta + ar * k) / oa));
                int gr = Math.min(255, Math.round((255f * ta + ag * k) / oa));
                int b = Math.min(255, Math.round((255f * ta + ab * k) / oa));
                int al = Math.min(255, Math.round(oa * 255f));
                out.setPixelABGR(x, y, (al << 24) | (b << 16) | (gr << 8) | rr);
            }
        }
        return new Rendered(out, w, h);
    }

    private static float[] boxBlur(float[] src, int w, int h, int r) {
        float inv = 1f / (2 * r + 1);
        float[] tmp = new float[w * h], dst = new float[w * h];
        for (int y = 0; y < h; y++) {
            float sum = 0;
            for (int x = -r; x <= r; x++) sum += src[y * w + Math.max(0, Math.min(w - 1, x))];
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = sum * inv;
                sum += src[y * w + Math.min(w - 1, x + r + 1)] - src[y * w + Math.max(0, x - r)];
            }
        }
        for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int y = -r; y <= r; y++) sum += tmp[Math.max(0, Math.min(h - 1, y)) * w + x];
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = sum * inv;
                sum += tmp[Math.min(h - 1, y + r + 1) * w + x] - tmp[Math.max(0, y - r) * w + x];
            }
        }
        return dst;
    }

    // ------------------------------------------------------------------ per-frame projection + drawing

    /** @return false when 3D could not be used this frame (caller falls back to the 2D panel). */
    static boolean render(GuiGraphicsExtractor g, Minecraft mc) {
        try {
            long now = System.currentTimeMillis();
            long nano = System.nanoTime();
            float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (nano - lastFrameNanos) / 1_000_000_000f);
            lastFrameNanos = nano;
            prune(now);

            Camera cam = mc.gameRenderer.mainCamera();
            CameraRenderState crs = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            Matrix4f proj = crs.projectionMatrix;
            if (proj == null || !proj.isFinite()) {
                status = "3D lyrics: no projection matrix";
                return false;
            }
            double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
            Matrix4f vp = new Matrix4f(proj).mul(new Matrix4f().rotateX((float) pitch).rotateY((float) yaw + (float) Math.PI));
            Vec3 cp = cam.position();

            // self-check against the camera's own look direction; also detects flipped clip-space axes
            double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
            double rxv = -Math.cos(yaw), rzv = -Math.sin(yaw);
            float[] mid = ndc(vp, lx * 5, ly * 5, lz * 5);
            float[] up = ndc(vp, lx * 5, ly * 5 + 0.5, lz * 5);
            float[] rt = ndc(vp, lx * 5 + rxv * 0.5, ly * 5, lz * 5 + rzv * 0.5);
            if (mid[2] <= 0 || Math.abs(mid[0]) > 0.1f || Math.abs(mid[1]) > 0.1f) {
                status = String.format(java.util.Locale.ROOT, "3D lyrics: camera mismatch (look ndc %.2f, %.2f)", mid[0], mid[1]);
                return false;
            }
            float signY = up[1] >= mid[1] ? 1f : -1f;
            float signX = rt[0] >= mid[0] ? 1f : -1f;
            status = "3D lyrics: ok" + (signY < 0 ? " (y flipped)" : "") + (signX < 0 ? " (x flipped)" : "");

            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            for (Line l : lines) {
                Identifier tex = l.texture;
                if (tex == null || l.released) continue;
                l.lift += (l.liftTarget - l.lift) * (1f - (float) Math.exp(-dt * 5f));
                long age = now - l.born;
                long wordAge = Math.max(0L, now - l.animationBorn);
                float intensity = switch (VisionSettings.lyricsIntensity) { case 0 -> 0.55f; case 2 -> 1.65f; default -> 1.0f; };
                int animation = VisionSettings.lyricsAnimation;
                float ageSeconds = age / 1000f;
                float wordAgeSeconds = wordAge / 1000f;
                float impact = (float) Math.exp(-wordAge / (240f / intensity));
                float pulse = animation == 1 ? 1f + (float) Math.sin(wordAgeSeconds * 8.5f) * 0.09f * intensity
                        : animation == 7 ? 0.94f + 0.12f * (0.5f + 0.5f * (float) Math.sin(wordAgeSeconds * 3.2f)) : 1f;
                float entrance = animation == 2 ? Math.min(1f, wordAge / (620f / intensity)) : 1f;
                if (animation == 4) pulse *= 1f + (float) Math.abs(Math.sin(wordAgeSeconds * 10f)) * impact * 0.16f * intensity;
                float alpha = Math.min(1f, age / (float) FADE_IN_MS) * Math.max(0f, Math.min(1f, (LIFE_MS - age) / (float) FADE_OUT_MS));
                alpha *= Math.max(0.3f, 1f - 0.35f * l.liftTarget) * entrance;
                if (animation == 0) alpha *= 0.88f + 0.12f * Math.min(1f, wordAge / 220f);
                double wave = animation == 3 ? Math.sin(ageSeconds * 4.8f) * 0.22f * intensity
                        : animation == 4 ? Math.abs(Math.sin(wordAgeSeconds * 8f)) * impact * 0.42f * intensity
                        : animation == 6 ? Math.sin(wordAgeSeconds * 5f) * 0.24f * impact * intensity : 0.0;
                double glitch = animation == 5 ? Math.sin(wordAgeSeconds * 55f) * impact * 0.12f * intensity : 0.0;
                double impactOffset = (animation == 0 ? Math.sin(ageSeconds * 24f) * impact * 0.045f * intensity : 0.0) + glitch;
                double cx = l.cx + impactOffset, cy = l.cy + l.lift + wave, cz = l.cz;
                boolean billboard = VisionSettings.glowFacing == 1;
                if (l.followsPlayer) {
                    // Rebuild world position from the player's current position and yaw.
                    // The stored local offset stays constant while walking and turning.
                    double currentYaw = Math.toRadians(cam.yRot());
                    double forwardX = -Math.sin(currentYaw), forwardZ = Math.cos(currentYaw);
                    double rightX = -Math.cos(currentYaw), rightZ = -Math.sin(currentYaw);
                    double targetX = cp.x + rightX * l.localX + forwardX * l.localZ + impactOffset;
                    double targetY = cp.y + l.localY + l.lift + wave;
                    double targetZ = cp.z + rightZ * l.localX + forwardZ * l.localZ;
                    if (!l.smoothReady) {
                        l.smoothX = targetX; l.smoothY = targetY; l.smoothZ = targetZ;
                        l.smoothReady = true;
                    }
                    double smooth = 1.0 - Math.exp(-dt * 7.5);
                    l.smoothX += (targetX - l.smoothX) * smooth;
                    l.smoothY += (targetY - l.smoothY) * smooth;
                    l.smoothZ += (targetZ - l.smoothZ) * smooth;
                    cx = l.smoothX; cy = l.smoothY; cz = l.smoothZ;
                }
                if (!billboard && (cp.x - cx) * l.nx + (cp.z - cz) * l.nz <= 0) continue; // fixed world plane: do not show its back
                double dist = Math.sqrt((cp.x - cx) * (cp.x - cx) + (cp.y - cy) * (cp.y - cy) + (cp.z - cz) * (cp.z - cz));
                // Do not fade lyrics when the player approaches. In billboard mode keep a small
                // minimum camera distance so the text cannot pass through the near clipping plane.
                if (billboard && dist < 1.25) {
                    double yawToLook = Math.toRadians(cam.yRot()), pitchToLook = Math.toRadians(cam.xRot());
                    double lookX = -Math.sin(yawToLook) * Math.cos(pitchToLook);
                    double lookY = -Math.sin(pitchToLook);
                    double lookZ = Math.cos(yawToLook) * Math.cos(pitchToLook);
                    cx = cp.x + lookX * 1.25;
                    cy = cp.y + lookY * 1.25;
                    cz = cp.z + lookZ * 1.25;
                }
                alpha *= (float) Math.max(0, Math.min(1, (64 - dist) / 24));
                int a255 = Math.round(alpha * 255f);
                if (a255 < 4) continue;

                float editScale = switch (animation) {
                    case 0 -> 1f + impact * 0.24f * intensity;
                    case 4 -> 0.94f + Math.abs((float) Math.sin(wordAgeSeconds * 8f)) * 0.18f * intensity;
                    case 5 -> 1f + (float) Math.sin(wordAgeSeconds * 48f) * impact * 0.08f * intensity;
                    case 6 -> 1f + impact * 0.20f * intensity;
                    default -> 1f + impact * 0.16f * intensity;
                };
                float hw = l.texW * BLOCKS_PER_PX * l.scale * pulse * editScale / 2f;
                float hh = l.texH * BLOCKS_PER_PX * l.scale * pulse * editScale / 2f;
                double normalX = cp.x - cx, normalY = cp.y - cy, normalZ = cp.z - cz;
                double normalLength = Math.max(0.0001, Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ));
                normalX /= normalLength;
                normalY /= normalLength;
                normalZ /= normalLength;
                // A true camera-facing basis needs both right and up vectors. Keeping up vertical
                // makes the quad edge-on when the camera looks toward the zenith or nadir.
                float planeRightX = billboard ? (float) (normalZ / Math.max(0.0001,
                        Math.hypot(normalX, normalZ))) : (float) l.rx;
                float planeRightZ = billboard ? (float) (-normalX / Math.max(0.0001,
                        Math.hypot(normalX, normalZ))) : (float) l.rz;
                float planeUpX = billboard ? (float) (normalY * planeRightZ) : 0f;
                float planeUpY = billboard ? (float) (normalZ * planeRightX - normalX * planeRightZ) : 1f;
                float planeUpZ = billboard ? (float) (-normalY * planeRightX) : 0f;
                float rotation = (float) Math.sin(wordAgeSeconds * (animation == 5 ? 62f : animation == 6 ? 5f : 18f))
                        * impact * (animation == 5 ? 0.12f : animation == 6 ? 0.18f : 0.075f) * intensity;
                float cs = (float) Math.cos(rotation), sn = (float) Math.sin(rotation);
                float[][] corner = {{-hw, hh}, {hw, hh}, {hw, -hh}, {-hw, -hh}}; // TL, TR, BR, BL
                float[] sx = new float[4], sy = new float[4], sw = new float[4];
                boolean visible = true;
                for (int i = 0; i < 4; i++) {
                    float localX = corner[i][0] * cs - corner[i][1] * sn;
                    float localY = corner[i][0] * sn + corner[i][1] * cs;
                    double wx = cx + planeRightX * localX + planeUpX * localY;
                    double wy = cy + planeUpY * localY;
                    double wz = cz + planeRightZ * localX + planeUpZ * localY;
                    float[] n = ndc(vp, wx - cp.x, wy - cp.y, wz - cp.z);
                    if (n[2] < 0.1f) { visible = false; break; }
                    sx[i] = (n[0] * signX * 0.5f + 0.5f) * gw;
                    sy[i] = (0.5f - n[1] * signY * 0.5f) * gh;
                    sw[i] = n[2];
                }
                if (visible) {
                    // Keep the complete lyric quad inside a safe viewport margin.
                    // Long wrapped lines are scaled down only as much as required, then centred/clamped.
                    float minX = Math.min(Math.min(sx[0], sx[1]), Math.min(sx[2], sx[3]));
                    float maxX = Math.max(Math.max(sx[0], sx[1]), Math.max(sx[2], sx[3]));
                    float minY = Math.min(Math.min(sy[0], sy[1]), Math.min(sy[2], sy[3]));
                    float maxY = Math.max(Math.max(sy[0], sy[1]), Math.max(sy[2], sy[3]));
                    float safe = 28f;
                    float availableW = Math.max(1f, gw - safe * 2f);
                    float availableH = Math.max(1f, gh - safe * 2f);
                    float fit = Math.min(1f, Math.min(availableW / Math.max(1f, maxX - minX),
                            availableH / Math.max(1f, maxY - minY)));
                    float centerX = (minX + maxX) * 0.5f;
                    float centerY = (minY + maxY) * 0.5f;
                    for (int i = 0; i < 4; i++) {
                        sx[i] = centerX + (sx[i] - centerX) * fit;
                        sy[i] = centerY + (sy[i] - centerY) * fit;
                    }
                    float fittedW = (maxX - minX) * fit;
                    float fittedH = (maxY - minY) * fit;
                    minX = centerX - fittedW * 0.5f;
                    maxX = centerX + fittedW * 0.5f;
                    minY = centerY - fittedH * 0.5f;
                    maxY = centerY + fittedH * 0.5f;
                    float shiftX = minX < safe ? safe - minX : maxX > gw - safe ? (gw - safe) - maxX : 0f;
                    float shiftY = minY < safe ? safe - minY : maxY > gh - safe ? (gh - safe) - maxY : 0f;
                    for (int i = 0; i < 4; i++) { sx[i] += shiftX; sy[i] += shiftY; }

                    int tint = VisionSettings.lyricsRainbow
                            ? (java.awt.Color.HSBtoRGB((now % 5200L) / 5200f, 0.82f, 1f) & 0xFFFFFF)
                            : VisionSettings.lyricColorRgb();
                    if (VisionSettings.lyricsGlow && intensity > 0.8f) {
                        float shift = 1.1f * intensity;
                        float[] redX = sx.clone(), blueX = sx.clone();
                        for (int i = 0; i < 4; i++) { redX[i] -= shift; blueX[i] += shift; }
                        drawStrips(g, tex, l.texW, l.texH, redX, sy, sw, gw, Math.round(a255 * 0.34f), 0xFFFF667F);
                        drawStrips(g, tex, l.texW, l.texH, blueX, sy, sw, gw, Math.round(a255 * 0.34f), 0xFF55DFFF);
                    }
                    drawStrips(g, tex, l.texW, l.texH, sx, sy, sw, gw, a255, tint);
                }
            }
            return true;
        } catch (Throwable error) {
            status = "3D lyrics: error (" + error.getClass().getSimpleName() + ")";
            return false;
        }
    }

    private static void prune(long now) {
        Iterator<Line> it = lines.iterator();
        while (it.hasNext()) {
            Line l = it.next();
            if (now - l.born > LIFE_MS || l.failed) {
                release(l);
                it.remove();
            }
        }
    }

    /** returns {ndcX, ndcY, clipW} for a point given relative to the camera */
    private static float[] ndc(Matrix4f vp, double rx, double ry, double rz) {
        Vector4f c = vp.transform(new Vector4f((float) rx, (float) ry, (float) rz, 1f));
        return new float[]{c.x / c.w, c.y / c.w, c.w};
    }

    private static float persp(float t, float wa, float wb) {
        float c = Math.max(0f, Math.min(1f, t));
        float den = (1f - c) / wa + c / wb;
        return den == 0f ? c : (c / wb) / den;
    }

    private static float uAt(float x, float[] sx, float[] sw) {
        float tT = (x - sx[0]) / (sx[1] - sx[0]);
        float tB = (x - sx[3]) / (sx[2] - sx[3]);
        return 0.5f * (persp(tT, sw[0], sw[1]) + persp(tB, sw[3], sw[2]));
    }

    private static void drawStrips(GuiGraphicsExtractor g, Identifier tex, int tw, int th,
                                   float[] sx, float[] sy, float[] sw, int gw, int alpha, int tint) {
        if (Math.abs(sx[1] - sx[0]) < 1f || Math.abs(sx[2] - sx[3]) < 1f) return;
        if (sx[1] < sx[0] || sx[2] < sx[3]) return;
        float left = Math.max(sx[0], sx[3]), right = Math.min(sx[1], sx[2]);
        int x0 = Math.max(0, (int) Math.ceil(left)), x1 = Math.min(gw, (int) Math.floor(right));
        int color = (alpha << 24) | (tint & 0xFFFFFF);
        for (int x = x0; x < x1; x++) {
            float xc = x + 0.5f;
            float tT = Math.max(0f, Math.min(1f, (xc - sx[0]) / (sx[1] - sx[0])));
            float tB = Math.max(0f, Math.min(1f, (xc - sx[3]) / (sx[2] - sx[3])));
            float yTop = sy[0] + (sy[1] - sy[0]) * tT;
            float yBot = sy[3] + (sy[2] - sy[3]) * tB;
            int top = Math.round(yTop), height = Math.round(yBot) - top;
            if (height < 1) continue;
            float u0 = uAt(x, sx, sw), u1 = uAt(x + 1, sx, sw);
            int uWidth = Math.max(1, Math.round((u1 - u0) * tw));
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x, top, u0 * tw, 0f, 1, height, uWidth, th, tw, th, color);
        }
    }
}