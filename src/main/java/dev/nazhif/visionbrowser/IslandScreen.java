package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Transparent screen that frees the cursor so the Dynamic Island can be clicked and dragged without opening the browser. */
public final class IslandScreen extends Screen {
    /** Minecraft 26.3 uses SDL button numbering: left = 1. */
    private static final int MOUSE_LEFT = 1;
    private final Screen parentScreen;
    private long lastVolumeSend;

    // ---- music search panel under the island
    private static final int PANEL_W = 300, ROW_H = 24, MAX_ROWS = 5, FIELD_H = 18;
    private static String searchText = "";
    private boolean searchFocused;
    private int panelX, panelY;

    public IslandScreen(Screen parentScreen) {
        super(Component.literal("Vision Island"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        minecraft.mouseHandler.releaseMouse();
        DynamicIsland.setTarget(1f);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately not calling super: no dimmed background, no widgets; the world stays visible.
        DynamicIsland.draw(graphics, minecraft, mouseX, mouseY);
        drawSearch(graphics, mouseX, mouseY);
    }

    private void layoutPanel() {
        float sc = VisionSettings.islandScale;
        panelX = (int) (width / 2f + VisionSettings.islandOffsetX) - PANEL_W / 2;
        panelY = (int) (VisionSettings.islandOffsetY + DynamicIsland.TOP + DynamicIsland.EXP_H * sc) + 12;
        panelX = Math.max(6, Math.min(panelX, width - PANEL_W - 6));
        panelY = Math.max(40, Math.min(panelY, height - 60));
    }

    private int rowsShown() {
        int fit = Math.max(0, (height - panelY - FIELD_H - 24) / ROW_H);
        return Math.min(Math.min(MusicPlayer.results().size(), MAX_ROWS), fit);
    }

    private void drawSearch(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        layoutPanel();
        var f = minecraft.font;
        int shown = rowsShown();
        String status = MusicPlayer.status();
        int h = FIELD_H + 12 + shown * ROW_H + (status.isEmpty() ? 0 : 14);
        DynamicIsland.roundedRect(g, panelX, panelY, PANEL_W, h, 12, 0xC00C0C14);
        int fx = panelX + 8, fy = panelY + 6, fw = PANEL_W - 16 - 58;
        DynamicIsland.roundedRect(g, fx, fy, fw, FIELD_H, 8, searchFocused ? 0x40FFFFFF : 0x22FFFFFF);
        boolean caret = searchFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        String shownText = searchText.isEmpty() && !searchFocused ? "Search songs..." : searchText + (caret ? "|" : "");
        g.text(f, DynamicIsland.ellipsize(f, shownText, fw - 12), fx + 6, fy + 5,
                searchText.isEmpty() && !searchFocused ? 0xFF8A93AD : 0xFFFFFFFF, false);
        boolean overBtn = mouseX >= fx + fw + 4 && mouseX < fx + fw + 54 && mouseY >= fy && mouseY < fy + FIELD_H;
        DynamicIsland.roundedRect(g, fx + fw + 4, fy, 50, FIELD_H, 8, overBtn ? 0x60FFFFFF : 0x30FFFFFF);
        g.text(f, "Search", fx + fw + 11, fy + 5, 0xFFFFFFFF, false);
        List<MusicPlayer.Result> list = MusicPlayer.results();
        int y = panelY + FIELD_H + 12;
        for (int i = 0; i < shown; i++) {
            MusicPlayer.Result r = list.get(i);
            boolean hot = mouseX >= panelX + 6 && mouseX < panelX + PANEL_W - 6 && mouseY >= y && mouseY < y + ROW_H - 2;
            DynamicIsland.roundedRect(g, panelX + 6, y, PANEL_W - 12, ROW_H - 2, 8, hot ? 0x40FFFFFF : 0x18FFFFFF);
            g.text(f, DynamicIsland.ellipsize(f, r.title(), PANEL_W - 30), panelX + 12, y + 3, 0xFFFFFFFF, false);
            g.text(f, DynamicIsland.ellipsize(f, r.artist(), PANEL_W - 30), panelX + 12, y + 13, 0xFF9AA6C4, false);
            y += ROW_H;
        }
        if (!status.isEmpty()) g.text(f, DynamicIsland.ellipsize(f, status, PANEL_W - 20), panelX + 10, y + 2, 0xFFB6BED6, false);
    }

    private void doSearch() {
        MusicPlayer.search(searchText);
    }

    /** returns true when the click was inside the search panel and handled */
    private boolean clickSearch(double mx, double my) {
        layoutPanel();
        int shown = rowsShown();
        String status = MusicPlayer.status();
        int h = FIELD_H + 12 + shown * ROW_H + (status.isEmpty() ? 0 : 14);
        if (mx < panelX || mx >= panelX + PANEL_W || my < panelY || my >= panelY + h) {
            searchFocused = false;
            return false;
        }
        int fx = panelX + 8, fy = panelY + 6, fw = PANEL_W - 16 - 58;
        if (my >= fy && my < fy + FIELD_H) {
            if (mx >= fx + fw + 4) { searchFocused = false; doSearch(); }
            else searchFocused = true;
            return true;
        }
        int y = panelY + FIELD_H + 12;
        List<MusicPlayer.Result> list = MusicPlayer.results();
        for (int i = 0; i < shown; i++) {
            if (my >= y && my < y + ROW_H - 2) {
                MusicPlayer.play(list.get(i));
                return true;
            }
            y += ROW_H;
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != MOUSE_LEFT) return false;
        double x = event.x(), y = event.y();
        if (clickSearch(x, y)) return true;
        DynamicIsland.Hit hit = DynamicIsland.hitTest(width, x, y);
        switch (hit) {
            case PLAY -> VisionMediaBridge.togglePlay();
            case PREV -> VisionMediaBridge.previous();
            case NEXT -> VisionMediaBridge.next();
            case MUTE -> VisionMediaBridge.toggleMute();
            case SEEK -> {
                DynamicIsland.draggingSeek = true;
                DynamicIsland.seekFrac = DynamicIsland.seekFracAt(width, x);
            }
            case VOLUME -> {
                DynamicIsland.draggingVolume = true;
                DynamicIsland.volumeFrac = DynamicIsland.volumeFracAt(width, x);
                VisionMediaBridge.setVolume(DynamicIsland.volumeFrac);
                lastVolumeSend = System.currentTimeMillis();
            }
            case ISLAND -> { }
            case NONE -> onClose();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (DynamicIsland.draggingSeek) {
            DynamicIsland.seekFrac = DynamicIsland.seekFracAt(width, event.x());
            return true;
        }
        if (DynamicIsland.draggingVolume) {
            DynamicIsland.volumeFrac = DynamicIsland.volumeFracAt(width, event.x());
            long now = System.currentTimeMillis();
            if (now - lastVolumeSend >= 60) {
                VisionMediaBridge.setVolume(DynamicIsland.volumeFrac);
                lastVolumeSend = now;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (DynamicIsland.draggingSeek) {
            DynamicIsland.draggingSeek = false;
            VisionMediaBridge.seek(DynamicIsland.seekFrac * VisionMediaBridge.durationSeconds());
            return true;
        }
        if (DynamicIsland.draggingVolume) {
            DynamicIsland.draggingVolume = false;
            VisionMediaBridge.setVolume(DynamicIsland.volumeFrac);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (searchFocused) {
            int k = event.key();
            if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) {
                doSearch();
            } else if (k == InputConstants.KEY_BACKSPACE) {
                if (!searchText.isEmpty()) searchText = searchText.substring(0, searchText.length() - 1);
            } else if (k == InputConstants.KEY_ESCAPE) {
                searchFocused = false;
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_I) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (searchFocused && event.codepoint() >= 32 && event.codepoint() != 127 && searchText.length() < 100) {
            searchText += new String(Character.toChars(event.codepoint()));
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        DynamicIsland.draggingSeek = false;
        DynamicIsland.draggingVolume = false;
        DynamicIsland.setTarget(0f);
    }
}
