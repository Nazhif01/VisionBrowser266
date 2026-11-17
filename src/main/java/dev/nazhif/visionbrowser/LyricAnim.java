package dev.nazhif.visionbrowser;

/** Enter / exit / idle animations shared by the 3D text, glow and HUD lyric styles. Offsets are in "line heights". */
final class LyricAnim {
    static final String[] IN = {"None", "Fade", "Pop", "Rise", "Drop", "Slide left", "Slide right", "Zoom in", "Elastic"};
    static final String[] OUT = {"Fade", "Shrink", "Rise", "Drop", "Zoom out", "Slide left", "Slide right"};

    record Pose(float dx, float dy, float scale, float alpha) {
        static final Pose REST = new Pose(0f, 0f, 1f, 1f);
    }

    private LyricAnim() { }

    static long inMs() { return Math.round(420f / VisionSettings.lyricsAnimSpeed); }
    static long outMs() { return Math.round(330f / VisionSettings.lyricsAnimSpeed); }

    private static float clamp01(float t) { return Math.max(0f, Math.min(1f, t)); }
    private static float easeOut(float t) { float u = 1f - clamp01(t); return 1f - u * u * u; }
    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = clamp01(t) - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
    private static float easeOutElastic(float t) {
        t = clamp01(t);
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
    }

    static Pose enter(int type, float t) {
        t = clamp01(t);
        float e = easeOut(t);
        return switch (type) {
            case 1 -> new Pose(0, 0, 0.92f + 0.08f * e, e);
            case 2 -> new Pose(0, 0, Math.max(0.01f, easeOutBack(t)), Math.min(1f, t * 4f));
            case 3 -> new Pose(0, -(1f - e) * 1.3f, 1f, e);
            case 4 -> new Pose(0, (1f - e) * 1.3f, 1f, e);
            case 5 -> new Pose(-(1f - e) * 3f, 0, 1f, e);
            case 6 -> new Pose((1f - e) * 3f, 0, 1f, e);
            case 7 -> new Pose(0, 0, 1f + (1f - e) * 1.3f, e);
            case 8 -> new Pose(0, 0, Math.max(0.01f, easeOutElastic(t)), Math.min(1f, t * 5f));
            default -> Pose.REST;
        };
    }

    static Pose exit(int type, float t) {
        t = clamp01(t);
        float e = easeOut(t), a = 1f - t;
        return switch (type) {
            case 1 -> new Pose(0, 0, 1f - 0.85f * e, a);
            case 2 -> new Pose(0, e * 1.3f, 1f, a);
            case 3 -> new Pose(0, -e * 1.3f, 1f, a);
            case 4 -> new Pose(0, 0, 1f + 0.9f * e, a);
            case 5 -> new Pose(-e * 3f, 0, 1f, a);
            case 6 -> new Pose(e * 3f, 0, 1f, a);
            default -> new Pose(0, 0, 1f, a);
        };
    }

    /** continuous motion while the line is on screen: gentle bobbing and beat-like pulsing */
    static Pose idle(long nowMs, long bornMs) {
        float ph = (nowMs - bornMs) / 1000f;
        float bob = VisionSettings.lyricsBob, pulse = VisionSettings.lyricsPulse;
        if (bob <= 0f && pulse <= 0f) return Pose.REST;
        float dy = bob * 0.22f * (float) Math.sin(ph * 2.1f);
        float dx = bob * 0.10f * (float) Math.sin(ph * 1.3f + 1.2f);
        float s = 1f + pulse * 0.08f * (0.5f + 0.5f * (float) Math.sin(ph * 6.0f));
        return new Pose(dx, dy, s, 1f);
    }

    static Pose combine(Pose a, Pose b) {
        return new Pose(a.dx() + b.dx(), a.dy() + b.dy(), a.scale() * b.scale(), a.alpha() * b.alpha());
    }
}
