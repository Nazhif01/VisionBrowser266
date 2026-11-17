package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Screen for Remote mode: shows and controls a real Chromium-family browser (Brave, Chrome, Edge...). */
public final class RemoteScreen extends Screen {
    private static final int MOUSE_LEFT = 1;
    private static final int HEADER = 58;
    private static final int VIRT = 8192;
    private static final int TAB_W = 96, TAB_H = 18, MAX_TABS = 7;

    private final Screen parentScreen;
    private EditBox addressBar;
    private int wx, wy, ww, wh;
    private int pressedButton = -1;
    private String shownUrl = "";

    public RemoteScreen(Screen parentScreen) {
        super(Component.literal("Remote Browser"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        FloatWindow.onScreenOpened();
        minecraft.mouseHandler.releaseMouse();
        RemoteBrowser.ensureRunning();
        ww = Math.min(width - 24, 1200);
        wh = Math.min(height - 24, 760);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        resizeRemote();

        addRenderableWidget(Button.builder(Component.literal("‹"), b -> RemoteBrowser.back())
                .bounds(wx + 12, wy + 7, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("›"), b -> RemoteBrowser.forward())
                .bounds(wx + 40, wy + 7, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("⟳"), b -> RemoteBrowser.reload())
                .bounds(wx + 68, wy + 7, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose())
                .bounds(wx + ww - 30, wy + 7, 22, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Float"), b -> { FloatWindow.setEnabled(true); onClose(); })
                .bounds(wx + ww - 90, wy + 7, 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Launch"), b -> RemoteBrowser.launch())
                .bounds(wx + ww - 160, wy + 7, 66, 20).build());

        addressBar = addRenderableWidget(new EditBox(font, wx + 98, wy + 7, Math.max(100, ww - 266), 18,
                Component.literal("Search or enter URL")));
        addressBar.setMaxLength(2048);
        addressBar.setHint(Component.literal("Search Google or enter URL"));
    }

    private void resizeRemote() {
        int scale = Math.max(1, (int) minecraft.getWindow().getGuiScale());
        RemoteBrowser.resize(ww * scale, (wh - HEADER) * scale);
    }

    private void navigateFromAddressBar() {
        String input = addressBar.getValue().trim();
        if (input.isEmpty()) return;
        String target;
        if (input.contains("://") || input.startsWith("about:")) target = input;
        else if (input.contains(".") && !input.contains(" ")) target = "https://" + input;
        else target = "https://www.google.com/search?q=" + URLEncoder.encode(input, StandardCharsets.UTF_8);
        RemoteBrowser.navigate(target);
        setFocused(null);
    }

    private static String shorten(String s, int max) {
        if (s == null || s.isEmpty()) return "New tab";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int pageY = wy + HEADER, pageH = wh - HEADER;
        g.fill(wx, wy, wx + ww, pageY - 2, 0xC80C0C14);

        RemoteBrowser.Tab active = RemoteBrowser.activeTab();
        if (active != null && addressBar != null && !addressBar.isFocused() && !active.url().equals(shownUrl)) {
            shownUrl = active.url();
            addressBar.setValue(shownUrl);
        }

        // tab chips
        List<RemoteBrowser.Tab> tabs = RemoteBrowser.tabs();
        int x = wx + 12;
        for (int i = 0; i < tabs.size() && i < MAX_TABS; i++) {
            RemoteBrowser.Tab t = tabs.get(i);
            boolean on = t.id().equals(RemoteBrowser.activeId());
            g.fill(x, wy + 33, x + TAB_W, wy + 33 + TAB_H, on ? 0xFF3A3A55 : 0xFF22222F);
            g.text(font, shorten(t.title(), 12), x + 4, wy + 38, 0xFFFFFFFF, false);
            g.text(font, "×", x + TAB_W - 9, wy + 38, 0xFFFF8888, false);
            x += TAB_W + 3;
        }
        g.fill(x, wy + 33, x + 20, wy + 33 + TAB_H, 0xFF22222F);
        g.text(font, "+", x + 7, wy + 38, 0xFFFFFFFF, false);

        Identifier tex = RemoteBrowser.pump();
        if (tex != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, tex, wx, pageY, 0f, 0f, ww, pageH, VIRT, VIRT, VIRT, VIRT, 0xFFFFFFFF);
        } else {
            g.fill(wx, pageY, wx + ww, wy + wh, 0xFF101018);
            String st = RemoteBrowser.status();
            g.text(font, st, wx + (ww - font.width(st)) / 2, pageY + pageH / 2 - 4, 0xFFFFCC88, false);
            String hint = "Browser: " + RemoteBrowser.NAMES[Math.floorMod(VisionSettings.remoteBrowser, RemoteBrowser.NAMES.length)]
                    + "  -  press Launch";
            g.text(font, hint, wx + (ww - font.width(hint)) / 2, pageY + pageH / 2 + 10, 0xFFAAAAAA, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    // ------------------------------------------------------------------ input

    private boolean insidePage(double x, double y) {
        return x >= wx && x < wx + ww && y >= wy + HEADER && y < wy + wh;
    }

    private int px(double x) { return (int) ((x - wx) * minecraft.getWindow().getGuiScale()); }
    private int py(double y) { return (int) ((y - wy - HEADER) * minecraft.getWindow().getGuiScale()); }

    private boolean clickTabs(double mx, double my) {
        if (my < wy + 33 || my >= wy + 33 + TAB_H) return false;
        List<RemoteBrowser.Tab> tabs = RemoteBrowser.tabs();
        int x = wx + 12;
        for (int i = 0; i < tabs.size() && i < MAX_TABS; i++) {
            if (mx >= x && mx < x + TAB_W) {
                if (mx >= x + TAB_W - 14) RemoteBrowser.closeTab(tabs.get(i).id());
                else RemoteBrowser.selectTab(tabs.get(i).id());
                return true;
            }
            x += TAB_W + 3;
        }
        if (mx >= x && mx < x + 20) {
            RemoteBrowser.newTab("https://www.google.com");
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double x = event.x(), y = event.y();
        if (clickTabs(x, y)) return true;
        if (insidePage(x, y)) {
            setFocused(null);
            pressedButton = event.button();
            RemoteBrowser.mousePress(px(x), py(y), event.button(), doubleClick ? 2 : 1);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressedButton != -1) {
            RemoteBrowser.mouseRelease(px(event.x()), py(event.y()), event.button());
            pressedButton = -1;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (insidePage(x, y)) RemoteBrowser.mouseMove(px(x), py(y));
        super.mouseMoved(x, y);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (pressedButton != -1) {
            RemoteBrowser.mouseMove(px(event.x()), py(event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (super.mouseScrolled(x, y, horizontal, vertical)) return true;
        if (insidePage(x, y)) {
            RemoteBrowser.mouseWheel(px(x), py(y), vertical, horizontal);
            return true;
        }
        return false;
    }

    private boolean addressFocused() {
        return addressBar != null && addressBar.isFocused();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (addressFocused()
                && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
                && !addressBar.getValue().trim().isEmpty()) {
            navigateFromAddressBar();
            return true;
        }
        if (!addressFocused() && event.key() == InputConstants.KEY_TAB) {
            RemoteBrowser.keyDown(event.key(), event.modifiers());
            return true;
        }
        if (super.keyPressed(event)) return true;
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        RemoteBrowser.keyDown(event.key(), event.modifiers());
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (super.keyReleased(event)) return true;
        if (!addressFocused()) {
            RemoteBrowser.keyUp(event.key(), event.modifiers());
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (super.charTyped(event)) return true;
        if (event.codepoint() != 0) {
            RemoteBrowser.typed(event.codepoint());
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

    @Override
    public void removed() {
        super.removed();
        FloatWindow.onScreenClosed();
    }
}
