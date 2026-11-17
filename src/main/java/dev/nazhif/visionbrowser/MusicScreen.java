package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Music mode screen: search YouTube Music and play from the list. No browser window; the player runs hidden. */
public final class MusicScreen extends Screen {
    private static final int MOUSE_LEFT = 1, ROW_H = 26;
    private final Screen parentScreen;
    private EditBox searchBox;
    private int px, py, pw, ph;

    public MusicScreen(Screen parentScreen) {
        super(Component.literal("Vision Music"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        minecraft.mouseHandler.releaseMouse();
        pw = Math.min(520, width - 40);
        ph = Math.min(360, height - 40);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        MusicPlayer.ensure();
        searchBox = addRenderableWidget(new EditBox(font, px + 14, py + 14, pw - 108, 20, Component.literal("Search songs")));
        searchBox.setMaxLength(120);
        searchBox.setHint(Component.literal("Search songs on YouTube Music"));
        searchBox.setValue(MusicPlayer.query());
        setFocused(searchBox);
        searchBox.setFocused(true);
        addRenderableWidget(Button.builder(Component.literal("Search"), b -> doSearch())
                .bounds(px + pw - 88, py + 14, 74, 20).build());
        int fy = py + ph - 30;
        addRenderableWidget(Button.builder(Component.literal("<<"), b -> VisionMediaBridge.previous())
                .bounds(px + 14, fy, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Play/Pause"), b -> VisionMediaBridge.togglePlay())
                .bounds(px + 48, fy, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">>"), b -> VisionMediaBridge.next())
                .bounds(px + 132, fy, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Lyrics"), b -> FloatingLyrics.toggle())
                .bounds(px + pw - 70, fy, 56, 20).build());
    }

    private void doSearch() {
        MusicPlayer.search(searchBox.getValue());
    }

    private int maxRows() { return Math.max(1, (ph - 46 - 44) / ROW_H); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        DynamicIsland.roundedRect(g, px, py, pw, ph, 14, 0xC00C0C14);
        DynamicIsland.roundedRect(g, px + 2, py + 2, pw - 4, 34, 12, 0x12FFFFFF);
        Font f = font;
        List<MusicPlayer.Result> list = MusicPlayer.results();
        int y = py + 44, shown = Math.min(list.size(), maxRows());
        for (int i = 0; i < shown; i++) {
            MusicPlayer.Result r = list.get(i);
            boolean hot = mouseX >= px + 10 && mouseX < px + pw - 10 && mouseY >= y && mouseY < y + ROW_H - 2;
            DynamicIsland.roundedRect(g, px + 10, y, pw - 20, ROW_H - 2, 8, hot ? 0x40FFFFFF : 0x18FFFFFF);
            g.text(f, DynamicIsland.ellipsize(f, r.title(), pw - 40), px + 18, y + 3, 0xFFFFFFFF, false);
            g.text(f, DynamicIsland.ellipsize(f, r.artist(), pw - 40), px + 18, y + 14, 0xFF9AA6C4, false);
            y += ROW_H;
        }
        String status = MusicPlayer.status();
        if (!status.isEmpty()) g.text(f, DynamicIsland.ellipsize(f, status, pw - 28), px + 14, py + 46 + (shown == 0 ? 4 : shown * ROW_H), 0xFFB6BED6, false);
        if (VisionMediaBridge.hasMedia()) {
            String now = "Now: " + VisionMediaBridge.title() + " - " + VisionMediaBridge.artist();
            g.text(f, DynamicIsland.ellipsize(f, now, pw - 28), px + 14, py + ph - 44, 0xFFD6DAE8, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != MOUSE_LEFT) return false;
        double mx = event.x(), my = event.y();
        List<MusicPlayer.Result> list = MusicPlayer.results();
        int y = py + 44, shown = Math.min(list.size(), maxRows());
        for (int i = 0; i < shown; i++) {
            if (mx >= px + 10 && mx < px + pw - 10 && my >= y && my < y + ROW_H - 2) {
                MusicPlayer.play(list.get(i));
                return true;
            }
            y += ROW_H;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && searchBox != null && searchBox.isFocused()) {
            doSearch();
            return true;
        }
        if (super.keyPressed(event)) return true;
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
