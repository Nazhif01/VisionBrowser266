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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lyrics that float in the WORLD. Each line is a quad placed in world space when it becomes active; every frame its four
 * corners are projected with the game's own camera (position, xRot, yRot) and projection matrix, and the pre-rendered
 * glowing text texture is drawn perspective-correct as thin vertical strips through the verified GUI blit call.
 * No raw OpenGL is used, so it does not depend on the renderer backend. Limitation: no occlusion by terrain.
 */
final class Lyrics3D {
    private static final float BLOCKS_PER_PX = 0.012f;
    private static final long FADE_IN_MS = 400, FADE_OUT_MS = 1500;
    private static final int PAD = 28, MAX_TEX_W = 1100;
    private static long lifeMs() { return Math.round(VisionSettings.lyricsLifeSeconds * 1000f); }

    private static final class Line {
        final long born = System.currentTimeMillis();
        final String text;
        final double cx, cy, cz;   // world centre at spawn
        final double rx, rz;       // initial horizontal right vector
        final double nx, nz;       // initial horizontal normal
        final double forward, side, up, spawnYaw, spawnPitch;
        float scale = VisionSettings.lyricsScale;
        double smoothX, smoothY, smoothZ, smoothYaw, smoothPitch;
        boolean smoothInitialized;
        long retireAt = -1L;
        int renderRevision;
        float lift, liftTarget;
        volatile Identifier texture;
        volatile int texW, texH;
        volatile boolean released, failed;

        Line(String text, double cx, double cy, double cz, double rx, double rz, double nx, double nz,
             double forward, double side, double up, double yaw, double pitch) {
            this.text = text; this.cx = cx; this.cy = cy; this.cz = cz;
            this.rx = rx; this.rz = rz; this.nx = nx; this.nz = nz;
            this.forward = forward; this.side = side; this.up = up; this.spawnYaw = yaw; this.spawnPitch = pitch;
        }
    }

    private record Rendered(NativeImage image, int w, int h) { }

    private static final List<Line> lines = new ArrayList<>();
    private static String lastKey = "";
    private static volatile String status = "3D lyrics: idle";
    private static long lastFrameNanos;
    private static int counter;

    private Lyrics3D() { }

    static String status() { return status; }

    /** Call every frame with a key that changes whenever the active lyric line changes. */
    static void show(String key, String text) {
        if (key.equals(lastKey)) return;
        lastKey = key;
        if (text == null || text.isBlank()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            Camera cam = mc.gameRenderer.mainCamera();
            Vec3 cp = cam.position();
            double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
            double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
            long now = System.currentTimeMillis();
            for (Line old : lines) { old.liftTarget += 1.0f; old.retireAt = now + 2400L; }
            // Offset from the player's view. In player-relative mode the render loop recalculates this anchor each frame.
            double rxs = -Math.cos(yaw), rzs = -Math.sin(yaw);
            double forward = VisionSettings.lyricsWorldDistance;
            double lat = VisionSettings.lyricsSide * 4.5 + VisionSettings.lyricsHorizontalOffset;
            double up = 0.6 + VisionSettings.lyricsVerticalOffset;
            if (VisionSettings.lyricsGlowRandomPosition) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double range = VisionSettings.lyricsGlowRandomRange;
                if (range > 0.001) {
                    lat += random.nextDouble(-range, range);
                    forward += random.nextDouble(-range * 0.35, range * 0.35);
                    up += random.nextDouble(-range * 0.22, range * 0.22);
                }
            }
            Line line = new Line(text, cp.x + lx * forward + rxs * lat, cp.y + ly * forward + up, cp.z + lz * forward + rzs * lat,
                    rxs, rzs, Math.sin(yaw), -Math.cos(yaw), forward, lat, up, yaw, pitch);
            lines.add(line);
            while (lines.size() > VisionSettings.lyricsMaxLines) release(lines.remove(0));
            rasterize(line);
        } catch (Throwable error) {
            status = "3D lyrics: cannot place line (" + error.getClass().getSimpleName() + ")";
        }
    }

    static void clear() {
        for (Line l : lines) release(l);
        lines.clear();
        lastKey = "";
    }

    private static void release(Line l) {
        l.released = true;
        releaseTexture(l);
    }

    private static void releaseTexture(Line l) {
        Identifier t = l.texture;
        l.texture = null;
        if (t != null) Minecraft.getInstance().getTextureManager().release(t);
    }

    /** Rebuild visible glow textures after a live appearance setting changes. */
    static void refreshAppearance() {
        for (Line line : lines) {
            if (line.released) continue;
            releaseTexture(line);
            rasterize(line);
        }
    }

    // ------------------------------------------------------------------ text -> glowing texture

    private static void rasterize(Line line) {
        final int accent = IslandArt.accent();
        final String family = VisionSettings.lyricsFont;
        final int revision = ++line.renderRevision;
        Thread worker = new Thread(() -> {
            try {
                Rendered r = renderText(line.text, accent, family);
                Minecraft.getInstance().execute(() -> install(line, r, revision));
            } catch (Throwable error) {
                line.failed = true;
                status = "3D lyrics: text render failed (" + error.getClass().getSimpleName() + ")";
            }
        }, "VisionBrowser-Lyrics3D");
        worker.setDaemon(true);
        worker.start();
    }

    private static void install(Line line, Rendered r, int revision) {
        if (line.released || revision != line.renderRevision) {
            r.image().close();
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath("visionbrowser", "lyric_" + (++counter));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Vision lyric line", r.image()));
        line.texW = r.w();
        line.texH = r.h();
        line.texture = id;
    }

    private static Rendered renderText(String text, int accentArgb, String family) {
        int size = VisionSettings.lyricsFontSize;
        java.awt.Font font = LyricFonts.make(family, size, text);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        java.awt.FontMetrics fm = pg.getFontMetrics(font);
        while (fm.stringWidth(text) > MAX_TEX_W - 2 * PAD && size > 8) {
            size -= 2;
            font = font.deriveFont((float) size);
            fm = pg.getFontMetrics(font);
        }
        int tw = fm.stringWidth(text), th = fm.getHeight(), ascent = fm.getAscent();
        pg.dispose();
        int w = tw + 2 * PAD, h = th + 2 * PAD;
        BufferedImage base = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = base.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, PAD, PAD + ascent);
        g.dispose();

        float[] a = new float[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) a[y * w + x] = ((base.getRGB(x, y) >>> 24) & 0xFF) / 255f;
        float[] blur = boxBlur(boxBlur(a, w, h, Math.max(1, Math.round(VisionSettings.glowRadius))), w, h, Math.max(1, Math.round(VisionSettings.glowRadius)));

        // glow colour: the cover accent mixed 35% towards white so it stays luminous on dark skies
        int ar = Math.round(((accentArgb >> 16) & 0xFF) * 0.65f + 255 * 0.35f);
        int ag = Math.round(((accentArgb >> 8) & 0xFF) * 0.65f + 255 * 0.35f);
        int ab = Math.round((accentArgb & 0xFF) * 0.65f + 255 * 0.35f);
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float ta = a[i];
                float ga = Math.min(1f, blur[i] * VisionSettings.glowIntensity);
                float oa = ta + ga * (1f - ta);
                if (oa <= 0.003f) { out.setPixelABGR(x, y, 0); continue; }
                float k = ga * (1f - ta);
                int r = Math.min(255, Math.round((255f * ta + ar * k) / oa));
                int gr = Math.min(255, Math.round((255f * ta + ag * k) / oa));
                int b = Math.min(255, Math.round((255f * ta + ab * k) / oa));
                int al = Math.min(255, Math.round(oa * 255f));
                out.setPixelABGR(x, y, (al << 24) | (b << 16) | (gr << 8) | r);
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
                float alpha = Math.min(1f, age / (float) FADE_IN_MS) * Math.max(0f, Math.min(1f, (lifeMs() - age) / (float) FADE_OUT_MS));
                alpha *= Math.max(0f, 1f - 0.35f * l.liftTarget);
                if (l.retireAt >= 0L) alpha *= Math.max(0f, Math.min(1f, (l.retireAt - now) / 2400f));
                double useYaw = VisionSettings.lyricsFollowRotation ? yaw : l.spawnYaw;
                double usePitch = VisionSettings.lyricsFollowRotation ? pitch : l.spawnPitch;
                double fx = -Math.sin(useYaw) * Math.cos(usePitch), fy = -Math.sin(usePitch), fz = Math.cos(useYaw) * Math.cos(usePitch);
                double rightX = -Math.cos(useYaw), rightZ = -Math.sin(useYaw);
                double cx = l.cx, cy = l.cy, cz = l.cz;
                double orientYaw = useYaw, orientPitch = usePitch;
                if (VisionSettings.lyricsPlayerRelative) {
                    double targetX = cp.x + fx * l.forward + rightX * l.side;
                    double targetY = cp.y + fy * l.forward + l.up;
                    double targetZ = cp.z + fz * l.forward + rightZ * l.side;
                    if (!l.smoothInitialized) {
                        l.smoothX = targetX; l.smoothY = targetY; l.smoothZ = targetZ;
                        l.smoothYaw = useYaw; l.smoothPitch = usePitch;
                        l.smoothInitialized = true;
                    } else {
                        double smooth = VisionSettings.lyricsGlowSmoothing;
                        double tau = 0.015 + smooth * 0.42;
                        double blend = smooth <= 0.001 ? 1.0 : 1.0 - Math.exp(-dt / tau);
                        l.smoothX += (targetX - l.smoothX) * blend;
                        l.smoothY += (targetY - l.smoothY) * blend;
                        l.smoothZ += (targetZ - l.smoothZ) * blend;
                        l.smoothYaw += wrapRadians(useYaw - l.smoothYaw) * blend;
                        l.smoothPitch += (usePitch - l.smoothPitch) * blend;
                    }
                    cx = l.smoothX; cy = l.smoothY; cz = l.smoothZ;
                    orientYaw = l.smoothYaw;
                    orientPitch = l.smoothPitch;
                }
                // Build the complete camera basis, including pitch. The previous quad used
                // world-up for its vertical axis, which made it feel like a flat 2D label
                // when looking up or down. This basis keeps the lyric plane truly 3D.
                double orientFx = -Math.sin(orientYaw) * Math.cos(orientPitch);
                double orientFy = -Math.sin(orientPitch);
                double orientFz = Math.cos(orientYaw) * Math.cos(orientPitch);
                double lineRightX = -Math.cos(orientYaw), lineRightZ = -Math.sin(orientYaw);
                double lineUpX = -Math.sin(orientYaw) * Math.sin(orientPitch);
                double lineUpY = Math.cos(orientPitch);
                double lineUpZ = Math.cos(orientYaw) * Math.sin(orientPitch);
                double lineNormalX = -orientFx, lineNormalY = -orientFy, lineNormalZ = -orientFz;
                l.scale = VisionSettings.lyricsScale;
                cy += l.lift;
                // In fixed-orientation mode the quad has a real front and back. In billboard
                // mode its normal follows the camera, so it remains readable through a full turn.
                if (!VisionSettings.lyricsFollowRotation &&
                        (cp.x - cx) * lineNormalX + (cp.y - cy) * lineNormalY + (cp.z - cz) * lineNormalZ <= 0) continue;
                double dist = Math.sqrt((cp.x - cx) * (cp.x - cx) + (cp.y - cy) * (cp.y - cy) + (cp.z - cz) * (cp.z - cz));
                alpha *= (float) Math.max(0, Math.min(1, (dist - 0.8) / 1.5)) * (float) Math.max(0, Math.min(1, (64 - dist) / 24));
                int a255 = Math.round(alpha * 255f);
                if (a255 < 4) continue;

                float hw = l.texW * BLOCKS_PER_PX * l.scale / 2f, hh = l.texH * BLOCKS_PER_PX * l.scale / 2f;
                float[] sx = new float[4], sy = new float[4], sw = new float[4];
                // Keep the complete lyric quad inside a safe monitor margin. Shrink the
                // world-space quad uniformly instead of cropping any part of its texture.
                // The upper edge recedes slightly, giving the lyric a subtle perspective tilt.
                float low = 0f, high = 1f;
                for (int fit = 0; fit < 18; fit++) {
                    float candidate = (low + high) * 0.5f;
                    if (projectQuad(vp, cp, cx, cy, cz, lineRightX, lineRightZ,
                            lineUpX, lineUpY, lineUpZ, lineNormalX, lineNormalY, lineNormalZ,
                            hw * candidate, hh * candidate, gw, gh, signX, signY, sx, sy, sw)) {
                        low = candidate;
                    } else {
                        high = candidate;
                    }
                }
                if (low > 0.001f && projectQuad(vp, cp, cx, cy, cz, lineRightX, lineRightZ,
                        lineUpX, lineUpY, lineUpZ, lineNormalX, lineNormalY, lineNormalZ,
                        hw * low, hh * low, gw, gh, signX, signY, sx, sy, sw)) {
                    drawStrips(g, tex, l.texW, l.texH, sx, sy, sw, gw, gh, a255);
                }
            }
            return true;
        } catch (Throwable error) {
            status = "3D lyrics: error (" + error.getClass().getSimpleName() + ")";
            return false;
        }
    }

    private static boolean projectQuad(Matrix4f vp, Vec3 camera, double cx, double cy, double cz,
                                       double rightX, double rightZ,
                                       double upX, double upY, double upZ,
                                       double normalX, double normalY, double normalZ,
                                       float hw, float hh, int gw, int gh, float signX, float signY,
                                       float[] sx, float[] sy, float[] sw) {
        float tiltDepth = hh * 0.18f;
        float[][] corner = {{-hw, hh}, {hw, hh}, {hw, -hh}, {-hw, -hh}}; // TL, TR, BR, BL
        for (int i = 0; i < 4; i++) {
            boolean top = i < 2;
            double depth = top ? -tiltDepth : 0.0;
            double wx = cx + rightX * corner[i][0] + upX * corner[i][1] + normalX * depth;
            double wy = cy + upY * corner[i][1] + normalY * depth;
            double wz = cz + rightZ * corner[i][0] + upZ * corner[i][1] + normalZ * depth;
            float[] n = ndc(vp, wx - camera.x, wy - camera.y, wz - camera.z);
            if (n[2] < 0.1f) return false;
            sx[i] = (n[0] * signX * 0.5f + 0.5f) * gw;
            sy[i] = (0.5f - n[1] * signY * 0.5f) * gh;
            sw[i] = n[2];
        }
        float marginX = Math.max(8f, gw * 0.035f);
        float marginY = Math.max(8f, gh * 0.05f);
        for (int i = 0; i < 4; i++) {
            if (sx[i] < marginX || sx[i] > gw - marginX ||
                    sy[i] < marginY || sy[i] > gh - marginY) return false;
        }
        return true;
    }

    private static void prune(long now) {
        Iterator<Line> it = lines.iterator();
        while (it.hasNext()) {
            Line l = it.next();
            if (now - l.born > lifeMs() || l.failed || (l.retireAt >= 0L && now >= l.retireAt)) {
                release(l);
                it.remove();
            }
        }
        while (lines.size() > VisionSettings.lyricsMaxLines) release(lines.remove(0));
    }

    /** returns {ndcX, ndcY, clipW} for a point given relative to the camera */
    private static double wrapRadians(double value) {
        while (value > Math.PI) value -= Math.PI * 2.0;
        while (value < -Math.PI) value += Math.PI * 2.0;
        return value;
    }

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
                                   float[] sx, float[] sy, float[] sw, int gw, int gh, int alpha) {
        if (Math.abs(sx[1] - sx[0]) < 1f || Math.abs(sx[2] - sx[3]) < 1f) return;
        if (sx[1] < sx[0] || sx[2] < sx[3]) return;
        float left = Math.max(sx[0], sx[3]), right = Math.min(sx[1], sx[2]);
        int x0 = Math.max(0, (int) Math.ceil(left)), x1 = Math.min(gw, (int) Math.floor(right));
        int color = (alpha << 24) | 0xFFFFFF;
        for (int x = x0; x < x1; x++) {
            float xc = x + 0.5f;
            float tT = Math.max(0f, Math.min(1f, (xc - sx[0]) / (sx[1] - sx[0])));
            float tB = Math.max(0f, Math.min(1f, (xc - sx[3]) / (sx[2] - sx[3])));
            float yTop = sy[0] + (sy[1] - sy[0]) * tT;
            float yBot = sy[3] + (sy[2] - sy[3]) * tB;
            if (yBot <= yTop) continue;
            float clippedTop = Math.max(0f, yTop), clippedBottom = Math.min((float) gh, yBot);
            if (clippedBottom <= clippedTop) continue;
            float v0 = ((clippedTop - yTop) / (yBot - yTop)) * th;
            float vHeight = ((clippedBottom - clippedTop) / (yBot - yTop)) * th;
            int sourceY = Math.max(0, Math.min(th - 1, Math.round(v0)));
            int sourceHeight = Math.max(1, Math.min(th - sourceY, Math.round(vHeight)));
            int top = Math.round(clippedTop), height = Math.max(1, Math.round(clippedBottom) - top);
            float u0 = uAt(x, sx, sw), u1 = uAt(x + 1, sx, sw);
            int uWidth = Math.max(1, Math.round((u1 - u0) * tw));
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x, top, u0 * tw, sourceY, 1, height, uWidth, sourceHeight, tw, th, color);
        }
    }
}