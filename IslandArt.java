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
    private static volatile int accent = 0xFF7C8CFF;
    private static volatile int pendingAccent = 0xFF7C8CFF;
    private static int counter;

    private IslandArt() { }

    static Identifier current() { return current; }

    /** Average colour of the current cover (opaque ARGB), used for the glass glow. */
    static int accent() { return accent; }

    static void request(String url) {
        String wanted = url == null ? "" : url;
        if (wanted.equals(requestedUrl)) return;
        requestedUrl = wanted;
        if (wanted.isEmpty()) {
            Minecraft.getInstance().execute(() -> install(null, "", 0xFF7C8CFF));
            return;
        }
        Thread worker = new Thread(() -> {
            NativeImage image = null;
            int found = 0xFF7C8CFF;
            // Unverified assumption: Google image hosts accept a "=w256-h256-l90-rj" size/format suffix (JPEG output).
            String[] candidates = wanted.contains("googleusercontent.com") && wanted.matches(".*=w\\d+-h\\d+.*")
                    ? new String[]{wanted.replaceAll("=w\\d+-h\\d+[^?]*$", "=w256-h256-l90-rj"), wanted}
                    : new String[]{wanted};
            for (String candidate : candidates) {
                try {
                    image = download(candidate);
                    if (image != null) { found = pendingAccent; break; }
                } catch (Throwable ignored) { }
            }
            final NativeImage result = image;
            final int resultAccent = found;
            if (!wanted.equals(requestedUrl)) {
                if (result != null) result.close();
                return; // a newer request replaced this one
            }
            Minecraft.getInstance().execute(() -> install(result, wanted, resultAccent));
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
        long sr = 0, sg = 0, sb = 0, count = 0;
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
                if (coverage > 0.9f) { sr += r; sg += gr; sb += b; count++; }
                out.setPixelABGR(x, y, (a << 24) | (b << 16) | (gr << 8) | r);
            }
        }
        if (count > 0) {
            int ar = (int) (sr / count), ag = (int) (sg / count), ab = (int) (sb / count);
            int peak = Math.max(ar, Math.max(ag, ab));
            if (peak < 150 && peak > 0) { float k = 150f / peak; ar = Math.min(255, Math.round(ar * k)); ag = Math.min(255, Math.round(ag * k)); ab = Math.min(255, Math.round(ab * k)); }
            pendingAccent = 0xFF000000 | (ar << 16) | (ag << 8) | ab;
        }
        return out;
    }

    /** Must run on the client thread. */
    private static void install(NativeImage image, String url, int accentColor) {
        if (!url.equals(requestedUrl)) {
            if (image != null) image.close();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Identifier old = current;
        accent = accentColor;
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
