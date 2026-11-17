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
        // player-view placement
        boolean view, placed;
        double ndcX, ndcY, viewDepth, hwN, hhN;
        float fit = 1f;
        long exitAt;
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
            if (VisionSettings.lyricsViewMode) {
                long nowMs = System.currentTimeMillis();
                for (Line old : lines) if (old.exitAt == 0) old.exitAt = nowMs;
                Line v = new Line(text, cp.x, cp.y, cp.z, 1, 0, 0, 1, 0, 0, 0, yaw, pitch);
                v.view = true;
                lines.add(v);
                while (lines.size() > VisionSettings.lyricsMaxLines) release(lines.remove(0));
                rasterize(v);
                return;
            }
            for (Line old : lines) old.liftTarget += 1.0f;
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
        if (line.view && !line.placed) placeInView(line);
        line.texture = id;
    }

    /** chooses the screen spot once the texture size (and so the on-screen size) is known */
    private static void placeInView(Line line) {
        ViewMath.Frame fr = ViewMath.frame(Minecraft.getInstance());
        if (fr == null) { line.view = false; line.placed = true; return; }
        double depth = VisionSettings.lyricsViewDepth, margin = VisionSettings.lyricsScreenMargin;
        double scale = VisionSettings.lyricsScale;
        double hwB = line.texW * BLOCKS_PER_PX * scale / 2.0, hhB = line.texH * BLOCKS_PER_PX * scale / 2.0;
        double hwN = hwB / (depth * fr.tanH()), hhN = hhB / (depth * fr.tanV());
        float fit = 1f;
        if (VisionSettings.lyricsFitScreen) {
            double room = 1.0 - margin;
            fit = (float) Math.min(1.0, Math.min(room / Math.max(0.001, hwN), room / Math.max(0.001, hhN)));
            hwN *= fit; hhN *= fit;
        }
        List<double[]> avoid = new ArrayList<>();
        for (Line o : lines) if (o != line && o.view && o.placed && o.exitAt == 0) avoid.add(new double[]{o.ndcX, o.ndcY, o.hwN, o.hhN});
        double[] spot = ViewMath.pick(hwN, hhN, VisionSettings.lyricsViewAnchor, VisionSettings.lyricsCrosshairExclude, margin, avoid);
        line.ndcX = spot[0]; line.ndcY = spot[1]; line.viewDepth = depth; line.hwN = hwN; line.hhN = hhN; line.fit = fit;
        line.placed = true;
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
                if (l.view) { renderView(g, l, tex, vp, cp, signX, signY, gw, gh, dt, now); continue; }
                l.lift += (l.liftTarget - l.lift) * (1f - (float) Math.exp(-dt * 5f));
                long age = now - l.born;
                float alpha = Math.min(1f, age / (float) FADE_IN_MS) * Math.max(0f, Math.min(1f, (lifeMs() - age) / (float) FADE_OUT_MS));
                alpha *= Math.max(0.3f, 1f - 0.35f * l.liftTarget);
                double useYaw = VisionSettings.lyricsFollowRotation ? yaw : l.spawnYaw;
                double usePitch = VisionSettings.lyricsFollowRotation ? pitch : l.spawnPitch;
                double fx = -Math.sin(useYaw) * Math.cos(usePitch), fy = -Math.sin(usePitch), fz = Math.cos(useYaw) * Math.cos(usePitch);
                double rightX = -Math.cos(useYaw), rightZ = -Math.sin(useYaw);
                double cx = l.cx, cy = l.cy, cz = l.cz;
                double lineRx = l.rx, lineRz = l.rz, lineNx = l.nx, lineNz = l.nz;
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
                    double renderYaw = l.smoothYaw, renderPitch = l.smoothPitch;
                    double renderRightX = -Math.cos(renderYaw), renderRightZ = -Math.sin(renderYaw);
                    cx = l.smoothX; cy = l.smoothY; cz = l.smoothZ;
                    lineRx = renderRightX; lineRz = renderRightZ;
                    lineNx = Math.sin(renderYaw); lineNz = -Math.cos(renderYaw);
                }
                l.scale = VisionSettings.lyricsScale;
                cy += l.lift;
                boolean faceCam = VisionSettings.lyricsFaceAlways;
                if (!faceCam && (cp.x - cx) * lineNx + (cp.z - cz) * lineNz <= 0) continue; // seen from behind
                double dist = Math.sqrt((cp.x - cx) * (cp.x - cx) + (cp.y - cy) * (cp.y - cy) + (cp.z - cz) * (cp.z - cz));
                alpha *= (float) Math.max(0, Math.min(1, (dist - 0.8) / 1.5)) * (float) Math.max(0, Math.min(1, (64 - dist) / 24));
                int a255 = Math.round(alpha * 255f);
                if (a255 < 4) continue;

                float hw = l.texW * BLOCKS_PER_PX * l.scale / 2f, hh = l.texH * BLOCKS_PER_PX * l.scale / 2f;
                float[][] corner = {{-hw, hh}, {hw, hh}, {hw, -hh}, {-hw, -hh}}; // TL, TR, BR, BL
                float[] sx = new float[4], sy = new float[4], sw = new float[4];
                boolean visible = true;
                double[] bs = faceCam ? ViewMath.billboard(cp, cx, cy, cz, lineRx, lineRz) : new double[]{lineRx, 0, lineRz, 0, 1, 0};
                for (int i = 0; i < 4; i++) {
                    double wx = cx + bs[0] * corner[i][0] + bs[3] * corner[i][1];
                    double wy = cy + bs[1] * corner[i][0] + bs[4] * corner[i][1];
                    double wz = cz + bs[2] * corner[i][0] + bs[5] * corner[i][1];
                    float[] n = ndc(vp, wx - cp.x, wy - cp.y, wz - cp.z);
                    if (n[2] < 0.1f) { visible = false; break; }
                    sx[i] = (n[0] * signX * 0.5f + 0.5f) * gw;
                    sy[i] = (0.5f - n[1] * signY * 0.5f) * gh;
                    sw[i] = n[2];
                }
                if (visible) drawStrips(g, tex, l.texW, l.texH, sx, sy, sw, gw, a255);
            }
            return true;
        } catch (Throwable error) {
            status = "3D lyrics: error (" + error.getClass().getSimpleName() + ")";
            return false;
        }
    }

    private static void renderView(GuiGraphicsExtractor g, Line l, Identifier tex, Matrix4f vp, Vec3 cp, float signX, float signY,
                                   int gw, int gh, float dt, long now) {
        if (!l.placed) return;
        ViewMath.Frame fr = ViewMath.frame(Minecraft.getInstance());
        if (fr == null) return;
        long age = now - l.born;
        LyricAnim.Pose pose = LyricAnim.idle(now, l.born);
        pose = LyricAnim.combine(pose, LyricAnim.enter(VisionSettings.lyricsAnimIn, age / (float) LyricAnim.inMs()));
        long es = exitStart(l);
        if (now >= es) pose = LyricAnim.combine(pose, LyricAnim.exit(VisionSettings.lyricsAnimOut, (now - es) / (float) LyricAnim.outMs()));
        int a255 = Math.round(pose.alpha() * 255f);
        if (a255 < 4) return;

        double[] target = ViewMath.toWorld(fr, l.ndcX, l.ndcY, l.viewDepth);
        double smooth = VisionSettings.lyricsViewSmoothing;
        double k = smooth <= 0.001 ? 1.0 : 1.0 - Math.exp(-dt / (0.02 + smooth * 0.45));
        if (!l.smoothInitialized) { l.smoothX = target[0]; l.smoothY = target[1]; l.smoothZ = target[2]; l.smoothInitialized = true; }
        else { l.smoothX += (target[0] - l.smoothX) * k; l.smoothY += (target[1] - l.smoothY) * k; l.smoothZ += (target[2] - l.smoothZ) * k; }

        float sc = VisionSettings.lyricsScale * l.fit * pose.scale();
        float hw = l.texW * BLOCKS_PER_PX * sc / 2f, hh = l.texH * BLOCKS_PER_PX * sc / 2f;
        double unit = l.texH * BLOCKS_PER_PX * VisionSettings.lyricsScale * l.fit * 0.6;
        double[] p = {l.smoothX + fr.rx() * pose.dx() * unit + fr.ux() * pose.dy() * unit,
                l.smoothY + fr.uy() * pose.dy() * unit,
                l.smoothZ + fr.rz() * pose.dx() * unit + fr.uz() * pose.dy() * unit};
        if (VisionSettings.lyricsFitScreen) p = ViewMath.clampInside(fr, p, hw, hh, VisionSettings.lyricsScreenMargin);
        double cx = p[0], cy = p[1], cz = p[2];

        // always face the player: the quad normal points from its centre to the camera, so the text is never seen from behind
        double yawNow = Math.toRadians(Minecraft.getInstance().gameRenderer.mainCamera().yRot());
        double[] bs = VisionSettings.lyricsFaceAlways
                ? ViewMath.billboard(cp, cx, cy, cz, -Math.cos(yawNow), -Math.sin(yawNow))
                : new double[]{-Math.cos(yawNow), 0, -Math.sin(yawNow), 0, 1, 0};
        float[][] corner = {{-hw, hh}, {hw, hh}, {hw, -hh}, {-hw, -hh}};
        float[] sx = new float[4], sy = new float[4], sw = new float[4];
        for (int i = 0; i < 4; i++) {
            double wx = cx + bs[0] * corner[i][0] + bs[3] * corner[i][1];
            double wy = cy + bs[1] * corner[i][0] + bs[4] * corner[i][1];
            double wz = cz + bs[2] * corner[i][0] + bs[5] * corner[i][1];
            float[] n = ndc(vp, wx - cp.x, wy - cp.y, wz - cp.z);
            if (n[2] < 0.1f) return;
            sx[i] = (n[0] * signX * 0.5f + 0.5f) * gw;
            sy[i] = (0.5f - n[1] * signY * 0.5f) * gh;
            sw[i] = n[2];
        }
        drawStrips(g, tex, l.texW, l.texH, sx, sy, sw, gw, a255);
    }

    private static long exitStart(Line l) {
        return l.exitAt > 0 ? l.exitAt : l.born + lifeMs() - LyricAnim.outMs();
    }

    private static void prune(long now) {
        Iterator<Line> it = lines.iterator();
        while (it.hasNext()) {
            Line l = it.next();
            boolean expired = l.view ? now > exitStart(l) + LyricAnim.outMs() : now - l.born > lifeMs();
            if (expired || l.failed) {
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
                                   float[] sx, float[] sy, float[] sw, int gw, int alpha) {
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
            int top = Math.round(yTop), height = Math.round(yBot) - top;
            if (height < 1) continue;
            float u0 = uAt(x, sx, sw), u1 = uAt(x + 1, sx, sw);
            int uWidth = Math.max(1, Math.round((u1 - u0) * tw));
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x, top, u0 * tw, 0f, 1, height, uWidth, th, tw, th, color);
        }
    }
}