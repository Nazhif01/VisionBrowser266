package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Downloads the current cover art, rounds its corners and exposes it as a GUI texture. */
final class IslandArt {
    private static final int SIZE = 128;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static volatile String requestedUrl = "";
    private static volatile Identifier current;
    private static int counter;

    private IslandArt() { }

    static Identifier current() { return current; }

    static void request(String url) {
        String wanted = url == null ? "" : url;
        if (wanted.equals(requestedUrl)) return;
        requestedUrl = wanted;
        if (wanted.isEmpty()) {
            Minecraft.getInstance().execute(() -> install(null, ""));
            return;
        }
        Thread worker = new Thread(() -> {
            NativeImage image = null;
            // Unverified assumption: Google image hosts accept a "=w256-h256-l90-rj" size/format suffix (JPEG output).
            String[] candidates = wanted.contains("googleusercontent.com") && wanted.matches(".*=w\\d+-h\\d+.*")
                    ? new String[]{wanted.replaceAll("=w\\d+-h\\d+[^?]*$", "=w256-h256-l90-rj"), wanted}
                    : new String[]{wanted};
            for (String candidate : candidates) {
                try {
                    image = download(candidate);
                    if (image != null) break;
                } catch (Throwable ignored) { }
            }
            final NativeImage result = image;
            if (!wanted.equals(requestedUrl)) {
                if (result != null) result.close();
                return; // a newer request replaced this one
            }
            Minecraft.getInstance().execute(() -> install(result, wanted));
        }, "VisionBrowser-IslandArt");
        worker.setDaemon(true);
        worker.start();
    }

    private static NativeImage download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("User-Agent", "VisionBrowser/0.1.0 (Minecraft Fabric mod)").GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0 || response.body().length > 6_000_000) return null;
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(response.body())); // JPEG/PNG; WebP is not supported by ImageIO
        if (source == null) return null;
        BufferedImage square = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int side = Math.min(source.getWidth(), source.getHeight());
        int sx = (source.getWidth() - side) / 2, sy = (source.getHeight() - side) / 2;
        g.drawImage(source, 0, 0, SIZE, SIZE, sx, sy, sx + side, sy + side, null);
        g.dispose();
        NativeImage out = new NativeImage(SIZE, SIZE, false);
        float radius = 24f, half = SIZE / 2f;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float qx = Math.abs(x + 0.5f - half) - (half - radius);
                float qy = Math.abs(y + 0.5f - half) - (half - radius);
                float d = (float) Math.hypot(Math.max(qx, 0f), Math.max(qy, 0f)) + Math.min(Math.max(qx, qy), 0f) - radius;
                float coverage = Math.max(0f, Math.min(1f, 0.5f - d));
                int argb = square.getRGB(x, y);
                int a = Math.round(((argb >>> 24) & 0xFF) * coverage);
                int r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
                out.setPixelABGR(x, y, (a << 24) | (b << 16) | (gr << 8) | r);
            }
        }
        return out;
    }

    /** Must run on the client thread. */
    private static void install(NativeImage image, String url) {
        if (!url.equals(requestedUrl)) {
            if (image != null) image.close();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Identifier old = current;
        if (image == null) {
            current = null;
        } else {
            Identifier id = Identifier.fromNamespaceAndPath("visionbrowser", "island_art_" + (++counter));
            mc.getTextureManager().register(id, new DynamicTexture(() -> "Vision island cover", image));
            current = id;
        }
        if (old != null) mc.getTextureManager().release(old);
    }
}
