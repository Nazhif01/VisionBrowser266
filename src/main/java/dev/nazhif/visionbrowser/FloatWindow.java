package dev.nazhif.visionbrowser;

import de.keksuccino.rinku.RinkuBrowser;
import de.keksuccino.rinku.RinkuRenderCoordinator;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Apple-Vision-Pro-style floating browser window in the 3D world.
 * LOCKED: the window follows the player (smooth, floaty) and always faces them.
 * FREE  : the window stays exactly where it was when the lock was released.
 * The live browser texture is projected with the game's own camera + projection matrix and drawn perspective-correct as
 * thin strips through the verified GUI blit call (see Lyrics3D). Corners are rounded in plane space, with a glass rim and glow.
 * Limitation: no occlusion by terrain; display only (control playback with the Dynamic Island or open the browser).
 */
final class FloatWindow {
    private static final int PX_W = 1280, PX_H = 720;          // browser resolution used while floating
    private static final float ASPECT = PX_W / (float) PX_H;
    private static final int VIRT = 8192;                       // virtual texture size: UVs are normalised, real size is irrelevant

    private static boolean enabled, locked = true, screenOpen;
    private static float widthBlocks = 1.8f, distance = 3.0f;
    private static double cx, cy, cz, rx = 1, rz = 0, nx = 0, nz = 1;
    private static boolean poseInit, faceInit;
    private static long enabledAt, lastNanos, toastUntil;
    private static String toast = "";
    private static volatile String status = "Floating window: off";

    private interface Painter { void paint(int x, int top, int height, int step, float t0, float t1, float u0, float u1); }

    private FloatWindow() { }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("visionbrowser", "floating_window"), FloatWindow::render);
    }

    static boolean isEnabled() { return enabled; }
    static boolean isLocked() { return locked; }
    static String status() { return status; }

    static void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            poseInit = false;
            faceInit = false;
            enabledAt = System.currentTimeMillis();
            applySize();
            say("Floating window ON (" + (locked ? "locked" : "free") + ")");
        } else {
            say("Floating window OFF");
        }
    }

    static void toggle() { setEnabled(!enabled); }

    static void toggleLock() {
        locked = !locked;
        say(locked ? "Window LOCKED: it follows you" : "Window FREE: it stays where you left it");
    }

    static void bigger() { widthBlocks = Math.min(7f, widthBlocks * 1.15f); }

    static void smaller() { widthBlocks = Math.max(0.5f, widthBlocks / 1.15f); }

    static void onScreenOpened() { screenOpen = true; }

    static void onScreenClosed() {
        screenOpen = false;
        if (enabled) applySize();
    }

    private static void applySize() {
        RinkuBrowser b = VisionBrowserClient.getActiveBrowser();
        if (b == null) return;
        try {
            b.resize(PX_W, PX_H);
        } catch (Throwable error) {
            status = "Floating window: cannot resize browser (" + error.getClass().getSimpleName() + ")";
        }
    }

    private static void say(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 1800;
    }

    // ------------------------------------------------------------------ per-frame

    private static void render(GuiGraphicsExtractor g, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now < toastUntil) {
            int w = mc.font.width(toast);
            g.text(mc.font, toast, (mc.getWindow().getGuiScaledWidth() - w) / 2, 44, 0xFFFFFFFF, false);
        }
        if (!enabled || screenOpen) return;
        RinkuBrowser browser = VisionBrowserClient.getActiveBrowser();
        if (browser == null) {
            String t = "Floating window: open the browser once first";
            g.text(mc.font, t, (mc.getWindow().getGuiScaledWidth() - mc.font.width(t)) / 2, 56, 0xFFFFAA66, false);
            return;
        }
        try {
            RinkuRenderCoordinator.pumpOnRenderThread();
            if (!browser.isTextureReady()) return;
            Identifier tex = browser.getTextureIdentifier();
            if (tex == null) return;

            long nano = System.nanoTime();
            float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (nano - lastNanos) / 1_000_000_000f);
            lastNanos = nano;

            Camera cam = mc.gameRenderer.mainCamera();
            CameraRenderState crs = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            Matrix4f proj = crs.projectionMatrix;
            if (proj == null || !proj.isFinite()) { status = "Floating window: no projection matrix"; return; }
            double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
            Matrix4f vp = new Matrix4f(proj).mul(new Matrix4f().rotateX((float) pitch).rotateY((float) yaw + (float) Math.PI));
            Vec3 cp = cam.position();

            double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
            double rxv = -Math.cos(yaw), rzv = -Math.sin(yaw);
            float[] mid = ndc(vp, lx * 5, ly * 5, lz * 5);
            float[] up = ndc(vp, lx * 5, ly * 5 + 0.5, lz * 5);
            float[] rt = ndc(vp, lx * 5 + rxv * 0.5, ly * 5, lz * 5 + rzv * 0.5);
            if (mid[2] <= 0 || Math.abs(mid[0]) > 0.1f || Math.abs(mid[1]) > 0.1f) {
                status = String.format(java.util.Locale.ROOT, "Floating window: camera mismatch (look ndc %.2f, %.2f)", mid[0], mid[1]);
                return;
            }
            float signY = up[1] >= mid[1] ? 1f : -1f, signX = rt[0] >= mid[0] ? 1f : -1f;
            status = "Floating window: ok" + (locked ? " (locked)" : " (free)");

            // pose: follow the camera while locked, freeze when free
            double tx = cp.x + lx * distance, ty = cp.y + ly * distance, tz = cp.z + lz * distance;
            if (!poseInit) { cx = tx; cy = ty; cz = tz; poseInit = true; }
            if (locked) {
                double k = 1.0 - Math.exp(-dt * 9.0);
                cx += (tx - cx) * k; cy += (ty - cy) * k; cz += (tz - cz) * k;
            }
            if (locked || !faceInit) {
                double dx = cp.x - cx, dz = cp.z - cz, len = Math.hypot(dx, dz);
                if (len > 0.3) { nx = dx / len; nz = dz / len; rx = nz; rz = -nx; faceInit = true; }
            }
            if ((cp.x - cx) * nx + (cp.z - cz) * nz <= 0) return; // seen from behind

            double dist = Math.sqrt((cp.x - cx) * (cp.x - cx) + (cp.y - cy) * (cp.y - cy) + (cp.z - cz) * (cp.z - cz));
            float fade = Math.min(1f, (now - enabledAt) / 350f) * (float) Math.max(0, Math.min(1, (dist - 0.5) / 1.0));
            int a = Math.round(fade * 255f);
            if (a < 4) return;

            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            float hw = widthBlocks / 2f, hh = hw / ASPECT;
            int accent = IslandArt.accent() & 0xFFFFFF;
            Ctx c = new Ctx(vp, cp, signX, signY, gw, gh);

            // glow + glass rim (fills), then the live page (blit)
            layer(g, c, cy, hw, hh, 0.20f, 2, fillPainter(g, (a * 0x14 / 255 << 24) | accent));
            layer(g, c, cy, hw, hh, 0.11f, 2, fillPainter(g, (a * 0x24 / 255 << 24) | accent));
            layer(g, c, cy, hw, hh, 0.035f, 1, fillPainter(g, (a * 0x70 / 255 << 24) | 0xFFFFFF));
            int color = (a << 24) | 0xFFFFFF;
            layer(g, c, cy, hw, hh, 0f, 1, (x, top, height, step, t0, t1, u0, u1) ->
                    g.blit(RenderPipelines.GUI_TEXTURED, tex, x, top, u0 * VIRT, t0 * VIRT, step, height,
                            Math.max(1, Math.round((u1 - u0) * VIRT)), Math.max(1, Math.round((t1 - t0) * VIRT)), VIRT, VIRT, color));
            // lock indicator capsule under the window: bright accent = locked, white = free
            double barY = cy - (hh + 0.14);
            int barColor = locked ? ((a * 0xE0 / 255 << 24) | accent | 0x303030) : ((a * 0x90 / 255 << 24) | 0xFFFFFF);
            capsule(g, c, barY, 0.22f, 0.025f, barColor);
        } catch (Throwable error) {
            status = "Floating window: error (" + error.getClass().getSimpleName() + ")";
        }
    }

    private static Painter fillPainter(GuiGraphicsExtractor g, int argb) {
        return (x, top, height, step, t0, t1, u0, u1) -> g.fill(x, top, x + step, top + height, argb);
    }

    private record Ctx(Matrix4f vp, Vec3 cp, float signX, float signY, int gw, int gh) { }

    /** one rounded quad of the window plane, enlarged by margin (blocks) */
    private static void layer(GuiGraphicsExtractor g, Ctx c, double centerY, float hw, float hh, float margin, int step, Painter p) {
        float w = hw + margin, h = hh + margin;
        float rv = Math.min(0.5f, (0.14f * hh + margin) / (2f * h));
        quad(c, cx, centerY, cz, w, h, rv, w / h, step, p);
    }

    private static void capsule(GuiGraphicsExtractor g, Ctx c, double centerY, float hw, float hh, int argb) {
        quad(c, cx, centerY, cz, hw, hh, 0.5f, hw / hh, 1, fillPainter(g, argb));
    }

    private static void quad(Ctx c, double qx, double qy, double qz, float hw, float hh, float rv, float aspect, int step, Painter p) {
        float[][] corner = {{-hw, hh}, {hw, hh}, {hw, -hh}, {-hw, -hh}};   // TL, TR, BR, BL
        float[] sx = new float[4], sy = new float[4], sw = new float[4];
        for (int i = 0; i < 4; i++) {
            double wx = qx + rx * corner[i][0], wy = qy + corner[i][1], wz = qz + rz * corner[i][0];
            float[] n = ndc(c.vp(), wx - c.cp().x, wy - c.cp().y, wz - c.cp().z);
            if (n[2] < 0.1f) return;
            sx[i] = (n[0] * c.signX() * 0.5f + 0.5f) * c.gw();
            sy[i] = (0.5f - n[1] * c.signY() * 0.5f) * c.gh();
            sw[i] = n[2];
        }
        strips(sx, sy, sw, c.gw(), rv, aspect, step, p);
    }

    // ------------------------------------------------------------------ projection helpers

    private static float[] ndc(Matrix4f vp, double rx, double ry, double rz) {
        Vector4f v = vp.transform(new Vector4f((float) rx, (float) ry, (float) rz, 1f));
        return new float[]{v.x / v.w, v.y / v.w, v.w};
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

    private static float cornerInset(float p, float rv, float aspect) {
        float ru = rv / aspect, d;
        if (p < ru) d = (ru - p) * aspect;
        else if (p > 1f - ru) d = (p - (1f - ru)) * aspect;
        else return 0f;
        return rv - (float) Math.sqrt(Math.max(0f, rv * rv - d * d));
    }

    private static void strips(float[] sx, float[] sy, float[] sw, int gw, float rv, float aspect, int step, Painter p) {
        if (Math.abs(sx[1] - sx[0]) < 1f || Math.abs(sx[2] - sx[3]) < 1f) return;
        if (sx[1] < sx[0] || sx[2] < sx[3]) return;
        float left = Math.max(sx[0], sx[3]), right = Math.min(sx[1], sx[2]);
        int x0 = Math.max(0, (int) Math.ceil(left)), x1 = Math.min(gw, (int) Math.floor(right));
        for (int x = x0; x < x1; x += step) {
            float xc = x + step * 0.5f;
            float tT = Math.max(0f, Math.min(1f, (xc - sx[0]) / (sx[1] - sx[0])));
            float tB = Math.max(0f, Math.min(1f, (xc - sx[3]) / (sx[2] - sx[3])));
            float pu = 0.5f * (persp(tT, sw[0], sw[1]) + persp(tB, sw[3], sw[2]));
            float inset = rv <= 0f ? 0f : cornerInset(pu, rv, aspect);
            float yTop = sy[0] + (sy[1] - sy[0]) * tT, yBot = sy[3] + (sy[2] - sy[3]) * tB;
            float h = yBot - yTop;
            float t0 = inset, t1 = 1f - inset;
            int top = Math.round(yTop + h * t0), bottom = Math.round(yTop + h * t1);
            if (bottom - top < 1) continue;
            p.paint(x, top, bottom - top, step, t0, t1, uAt(x, sx, sw), uAt(x + step, sx, sw));
        }
    }
}