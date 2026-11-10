package dev.nazhif.visionbrowser;

import org.endlesssource.mediainterface.SystemMediaFactory;
import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import java.util.List;
import java.util.Optional;

public final class VisionMediaService {
    private static volatile MediaSession activeSession;
    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile String application = "";
    private static volatile boolean playing;
    private static volatile boolean connected;

    private VisionMediaService() { }

    public static void start() {
        Thread worker = new Thread(() -> {
            try (SystemMediaInterface media = SystemMediaFactory.createSystemInterface(
                    SystemMediaOptions.defaults().withEventDrivenEnabled(true))) {
                connected = true;
                while (!Thread.currentThread().isInterrupted()) {
                    refresh(media);
                    Thread.sleep(650);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) {
                connected = false;
                clear();
            }
        }, "VisionBrowser-MediaSession");
        worker.setDaemon(true);
        worker.start();
    }

    private static void refresh(SystemMediaInterface media) {
        List<MediaSession> sessions = media.getAllSessions();
        MediaSession selected = media.getActiveSession().orElse(null);
        if (selected == null || selected.getNowPlaying().isEmpty()) {
            selected = sessions.stream()
                    .filter(session -> session.getNowPlaying().isPresent())
                    .sorted((a, b) -> Boolean.compare(b.isActive(), a.isActive()))
                    .findFirst().orElse(null);
        }
        if (selected == null) { clear(); return; }
        Optional<NowPlaying> now = selected.getNowPlaying();
        if (now.isEmpty()) { clear(); return; }
        activeSession = selected;
        title = now.get().getTitle().orElse("Unknown title");
        artist = now.get().getArtist().orElse(selected.getApplicationName());
        application = selected.getApplicationName();
        playing = selected.getControls().getPlaybackState() == PlaybackState.PLAYING;
    }

    private static void clear() {
        activeSession = null;
        title = "";
        artist = "";
        application = "";
        playing = false;
    }

    public static String title() { return title; }
    public static String artist() { return artist; }
    public static String application() { return application; }
    public static boolean isPlaying() { return playing; }
    public static boolean isConnected() { return connected; }
    public static void previous() { MediaSession s = activeSession; if (s != null) s.getControls().previous(); }
    public static void togglePlayback() { MediaSession s = activeSession; if (s != null) s.getControls().togglePlayPause(); }
    public static void next() { MediaSession s = activeSession; if (s != null) s.getControls().next(); }
}
