package dev.nazhif.visionbrowser;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal, independent floating synced-lyrics HUD. */
final class FloatingLyrics {
    private record Line(double time, String text) { }
    private static volatile List<Line> lines = List.of();
    private static volatile String song = "";
    private static volatile String artist = "";
    private static volatile String message = "Play music in Vision Browser";
    private static volatile boolean enabled = true;
    private static volatile String requestedTrack = "";
    private static final AtomicLong requestId = new AtomicLong();
    private static volatile Vec3 worldAnchor;

    private FloatingLyrics() { }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) {
                worldAnchor = null;
                return;
            }
            worldAnchor = client.player.getEyePosition()
                    .add(client.player.getLookAngle().scale(3.0))
                    .add(0.0, 0.16, 0.0);
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(FloatingLyrics::renderWorld);
    }

    private static void renderWorld(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        if (!enabled || lines.isEmpty()) return;
        Vec3 anchor = worldAnchor;
        Minecraft client = Minecraft.getInstance();
        if (anchor == null || client.player == null) return;
        List<Line> current = lines;
        double position = VisionMediaBridge.positionSeconds();
        int active = -1;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).time() <= position) active = i;
            else break;
        }
        if (active < 0) active = 0;

        PoseStack pose = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        pose.translate(anchor.x, anchor.y, anchor.z);
        pose.mulPose(client.gameRenderer.mainCamera().rotation());
        pose.scale(-0.025f, -0.025f, 0.025f);

        var collector = context.submitNodeCollector().order(1);
        Font font = client.font;
        int light = 0x00F000F0;
        String heading = song + (artist.isBlank() ? "" : "  ·  " + artist);
        submitCentered(collector, pose, font, heading, -30f, 0xBBDDEBFF, 0x55000000, light, false);
        if (active > 0) submitCentered(collector, pose, font, current.get(active - 1).text(),
                -13f, 0x99C8D6E8, 0x44000000, light, false);
        submitCentered(collector, pose, font, current.get(active).text(),
                5f, 0xFFFFFFFF, 0x88000000, light, true);
        if (active + 1 < current.size()) submitCentered(collector, pose, font,
                current.get(active + 1).text(), 23f, 0x99C8D6E8, 0x44000000, light, false);
        pose.popPose();
    }

    private static void submitCentered(net.minecraft.client.renderer.OrderedSubmitNodeCollector collector,
                                        PoseStack pose, Font font, String text, float y,
                                        int color, int background, int light, boolean highlighted) {
        String safe = text == null ? "" : text;
        float x = -font.width(safe) / 2.0f;
        collector.submitText(pose, x, y, Component.literal(safe).getVisualOrderText(),
                true, Font.DisplayMode.SEE_THROUGH, light, color, background,
                highlighted ? 0xFF8BCBFF : 0);
    }

    static void toggle() { enabled = !enabled; }
    static boolean isEnabled() { return enabled; }
    static boolean hasLyrics() { return !lines.isEmpty(); }

    static void loadTrack(String title, String artist, double duration) {
        String key = title + "|" + artist;
        if (key.equals(requestedTrack)) return;
        requestedTrack = key;
        long id = requestId.incrementAndGet();
        lines = List.of();
        song = title;
        FloatingLyrics.artist = artist;
        message = "Loading synchronized lyrics...";
        VisionMediaBridge.fetchLyrics(title, artist, duration);
    }

    static void clear() {
        requestId.incrementAndGet();
        requestedTrack = "";
        lines = List.of();
        song = "";
        artist = "";
        message = "Play music in Vision Browser";
    }

    static void setUnavailable(String text) {
        message = text;
        lines = List.of();
    }

    static void setLyrics(String title, String artistName, String lrc) {
        List<Line> parsed = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]\\s*(.*)")
                    .matcher(raw);
            while (matcher.find()) {
                double minutes = Double.parseDouble(matcher.group(1));
                double seconds = Double.parseDouble(matcher.group(2));
                String fraction = matcher.group(3);
                double millis = fraction == null ? 0 : Double.parseDouble("0." + fraction);
                String text = matcher.group(4).trim();
                if (!text.isEmpty()) parsed.add(new Line(minutes * 60 + seconds + millis, text));
            }
        }
        parsed.sort(java.util.Comparator.comparingDouble(Line::time));
        if (parsed.isEmpty()) {
            setUnavailable("No timed lyrics available");
            return;
        }
        song = title;
        artist = artistName;
        lines = List.copyOf(parsed);
        message = "";
    }


}
