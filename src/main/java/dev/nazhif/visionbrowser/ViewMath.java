package dev.nazhif.visionbrowser;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Screen-space placement for lyrics: a spot is chosen in normalised screen coordinates (-1..1), kept off the crosshair, fully inside the
 * monitor, and turned into a world position along the player's view each frame so it follows where the player looks.
 */
final class ViewMath {
    /** camera basis + projection scale for one frame */
    record Frame(Vec3 cp, double fx, double fy, double fz, double rx, double rz, double ux, double uy, double uz,
                 double tanH, double tanV, double signX, double signY) { }

    private ViewMath() { }

    static Frame frame(Minecraft mc) {
        try {
            Camera cam = mc.gameRenderer.mainCamera();
            CameraRenderState crs = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            Matrix4f proj = crs.projectionMatrix;
            if (proj == null || !proj.isFinite()) return null;
            double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
            double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
            double ux = -Math.sin(yaw) * Math.sin(pitch), uy = Math.cos(pitch), uz = Math.cos(yaw) * Math.sin(pitch);
            Matrix4f vp = new Matrix4f(proj).mul(new Matrix4f().rotateX((float) pitch).rotateY((float) yaw + (float) Math.PI));
            float[] mid = ndc(vp, fx * 5, fy * 5, fz * 5);
            float[] up = ndc(vp, fx * 5, fy * 5 + 0.5, fz * 5);
            float[] rt = ndc(vp, fx * 5 + rx * 0.5, fy * 5, fz * 5 + rz * 0.5);
            double signX = rt[0] >= mid[0] ? 1 : -1, signY = up[1] >= mid[1] ? 1 : -1;
            double m00 = Math.abs(proj.m00()), m11 = Math.abs(proj.m11());
            if (m00 < 1e-4 || m11 < 1e-4) return null;
            return new Frame(cam.position(), fx, fy, fz, rx, rz, ux, uy, uz, 1.0 / m00, 1.0 / m11, signX, signY);
        } catch (Throwable error) {
            return null;
        }
    }

    private static float[] ndc(Matrix4f vp, double x, double y, double z) {
        Vector4f c = vp.transform(new Vector4f((float) x, (float) y, (float) z, 1f));
        return new float[]{c.x / c.w, c.y / c.w, c.w};
    }

    /** world point for a normalised screen position at a given depth (blocks along the view direction) */
    static double[] toWorld(Frame f, double nx, double ny, double depth) {
        double a = nx * f.tanH() * depth * f.signX(), b = ny * f.tanV() * depth * f.signY();
        return new double[]{
                f.cp().x + f.fx() * depth + f.rx() * a + f.ux() * b,
                f.cp().y + f.fy() * depth + f.uy() * b,
                f.cp().z + f.fz() * depth + f.rz() * a + f.uz() * b};
    }

    /** {ndcX, ndcY, depth} of a world point */
    static double[] toScreen(Frame f, double x, double y, double z) {
        double dx = x - f.cp().x, dy = y - f.cp().y, dz = z - f.cp().z;
        double depth = dx * f.fx() + dy * f.fy() + dz * f.fz();
        double d = Math.max(0.2, depth);
        double sx = (dx * f.rx() + dz * f.rz()) / (d * f.tanH()) * f.signX();
        double sy = (dx * f.ux() + dy * f.uy() + dz * f.uz()) / (d * f.tanV()) * f.signY();
        return new double[]{sx, sy, depth};
    }

    /** moves the centre sideways/up so the whole box (half size in blocks) stays inside the screen margin */
    static double[] clampInside(Frame f, double[] pos, double hwBlocks, double hhBlocks, double margin) {
        double[] s = toScreen(f, pos[0], pos[1], pos[2]);
        double d = Math.max(0.5, s[2]);
        double hwN = hwBlocks / (d * f.tanH()), hhN = hhBlocks / (d * f.tanV());
        double limX = Math.max(0.0, 1.0 - margin - hwN), limY = Math.max(0.0, 1.0 - margin - hhN);
        double cx = Math.max(-limX, Math.min(limX, s[0])), cy = Math.max(-limY, Math.min(limY, s[1]));
        if (cx == s[0] && cy == s[1]) return pos;
        return toWorld(f, cx, cy, d);
    }

    /**
     * Full billboard basis for a quad centred at (cx,cy,cz) seen from the camera: the plane is perpendicular to the line of sight, so it
     * never looks squashed when the player looks up or down. Returns {rx,ry,rz, ux,uy,uz} (world "up" stays up, no roll).
     */
    static double[] billboard(Vec3 cp, double cx, double cy, double cz, double fallbackRx, double fallbackRz) {
        double nx = cp.x - cx, ny = cp.y - cy, nz = cp.z - cz;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-6) return new double[]{fallbackRx, 0, fallbackRz, 0, 1, 0};
        nx /= l; ny /= l; nz /= l;
        double rX = nz, rZ = -nx, rl = Math.hypot(rX, rZ);
        if (rl < 0.05) { rX = fallbackRx; rZ = fallbackRz; }
        else { rX /= rl; rZ /= rl; }
        return new double[]{rX, 0, rZ, ny * rZ, nz * rX - nx * rZ, -ny * rX};
    }

    /** true when a box (centre x,y, half size hw,hh in NDC) touches a zone that is hard to read: the top corners or the bottom of the screen */
    static boolean blocked(double x, double y, double hw, double hh) {
        if (y - hh < -VisionSettings.lyricsBottomLimit) return true;
        if (VisionSettings.lyricsCornerAvoid) {
            double cw = VisionSettings.lyricsCornerSize, chh = Math.min(0.9, cw * 1.3);
            boolean top = y + hh > 1.0 - chh;
            if (top && (x - hw < -1.0 + cw || x + hw > 1.0 - cw)) return true;
        }
        return false;
    }

    /**
     * Picks a screen centre for a box of half size (hwN, hhN) in NDC: inside the margins, not overlapping the crosshair zone and not
     * overlapping the boxes in {@code avoid} ({cx, cy, hw, hh}). anchor 0 = random, 1..6 = fixed spots.
     */
    static double[] pick(double hwN, double hhN, int anchor, double exclude, double margin, List<double[]> avoid) {
        double limX = Math.max(0.0, 1.0 - margin - hwN), limY = Math.max(0.0, 1.0 - margin - hhN);
        double minY = Math.min(limY, exclude + hhN + 0.03);   // smallest |y| that clears the crosshair zone
        if (anchor > 0) {
            double sx = anchor == 3 || anchor == 5 ? -1 : anchor == 4 || anchor == 6 ? 1 : 0;
            double sy = anchor == 2 || anchor == 5 || anchor == 6 ? -1 : 1;
            double x = sx * Math.min(limX, 0.62), y = sy * Math.max(minY, Math.min(limY, 0.58));
            if (!blocked(x, y, hwN, hhN)) return new double[]{x, y};
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double bestX = 0, bestY = minY, bestScore = -1;
        // keep random spots close to the middle: only a fraction of the free screen is used (always wide enough to clear the crosshair)
        double spread = VisionSettings.lyricsViewSpread;
        double rangeX = limX * spread, rangeY = Math.max(limY * spread, Math.min(limY, minY + 0.15));
        for (int t = 0; t < 120; t++) {
            double x = rangeX <= 0 ? 0 : r.nextDouble(-rangeX, rangeX), y = rangeY <= 0 ? 0 : r.nextDouble(-rangeY, rangeY);
            boolean onCrosshair = Math.abs(x) - hwN < exclude && Math.abs(y) - hhN < exclude;
            if (onCrosshair || blocked(x, y, hwN, hhN)) continue;
            double score = 1e9;
            for (double[] a : avoid) {
                double gapX = Math.abs(x - a[0]) - (hwN + a[2]), gapY = Math.abs(y - a[1]) - (hhN + a[3]);
                score = Math.min(score, Math.max(gapX, gapY));
            }
            if (score > 0.02) return new double[]{x, y};
            if (score > bestScore) { bestScore = score; bestX = x; bestY = y; }
        }
        if (bestScore >= 0 || !avoid.isEmpty()) return new double[]{bestX, bestY};
        double fy = Math.max(minY, Math.min(limY, 0.35));
        if (blocked(0, fy, hwN, hhN)) fy = Math.max(-VisionSettings.lyricsBottomLimit + hhN, -limY);
        return new double[]{0, fy};
    }
}
