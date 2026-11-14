package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.InputConstants;
import de.keksuccino.rinku.RinkuBrowser;
import de.keksuccino.rinku.RinkuRenderCoordinator;
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

public final class VisionBrowserScreen extends Screen {
    /** Minecraft 26.3 uses SDL button numbering: left = 1 (Rinku's toCefMouseButton maps 1,2,3 to CEF 0,1,2). */
    private static final int MOUSE_LEFT = 1;
    private static final String HOME_URL = "https://www.google.com";
    private static final int HEADER = 58;
    private final Screen parentScreen;
    private RinkuBrowser browser;
    private EditBox addressBar;
    private static int windowX, windowY, windowW, windowH;
    private boolean draggingWindow;
    private boolean resizingWindow;
    private double dragOffsetX, dragOffsetY;
    private int pressedButton = -1;
    private static boolean rebuilding;

    public VisionBrowserScreen(Screen parentScreen) {
        super(Component.literal("Vision Browser"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        rebuilding = false;
        FloatWindow.onScreenOpened();
        minecraft.mouseHandler.releaseMouse();
        if (windowW <= 0 || windowH <= 0) {
            windowW = Math.min(900, Math.max(520, width - 100));
            windowH = Math.min(600, Math.max(360, height - 100));
            windowX = (width - windowW) / 2;
            windowY = (height - windowH) / 2;
        }
        clampWindow();
        try {
            browser = VisionBrowserClient.getOrCreateBrowser(HOME_URL);
            resizeBrowser();
        } catch (Throwable ignored) {
            browser = null;
        }

        int tabX = windowX + 12;
        for (int i = 0; i < VisionBrowserClient.getTabCount(); i++) {
            final int tabIndex = i;
            boolean active = i == VisionBrowserClient.getActiveTab();
            addRenderableWidget(Button.builder(
                    Component.literal((active ? "• " : "") + (i + 1)),
                    button -> switchTab(tabIndex)
            ).bounds(tabX, windowY + 34, 38, 20).build());
            addRenderableWidget(Button.builder(Component.literal("×"), button -> closeTab(tabIndex))
                    .bounds(tabX + 39, windowY + 34, 20, 20).build());
            tabX += 63;
        }
        addRenderableWidget(Button.builder(Component.literal("+"), button -> addTab())
                .bounds(tabX, windowY + 34, 24, 20).build());

        addRenderableWidget(Button.builder(Component.literal("‹"), button -> navigateBack())
                .bounds(windowX + 12, windowY + 7, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("›"), button -> navigateForward())
                .bounds(windowX + 40, windowY + 7, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Float"), button -> floatWindow())
                .bounds(windowX + windowW - 188, windowY + 7, 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal(FloatWindow.isLocked() ? "Locked" : "Free"), button -> toggleFloatLock())
                .bounds(windowX + windowW - 128, windowY + 7, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal("□"), button -> toggleSize())
                .bounds(windowX + windowW - 58, windowY + 7, 22, 20).build());
        addRenderableWidget(Button.builder(Component.literal("×"), button -> onClose())
                .bounds(windowX + windowW - 30, windowY + 7, 22, 20).build());

        addressBar = addRenderableWidget(new EditBox(font, windowX + 70, windowY + 7,
                Math.max(100, windowW - 266), 18, Component.literal("Search or enter URL")));
        addressBar.setMaxLength(2048);
        addressBar.setValue(HOME_URL);
        addressBar.setHint(Component.literal("Search Google or enter URL"));
    }

    private void clampWindow() {
        windowW = Math.max(420, Math.min(windowW, width - 24));
        windowH = Math.max(280, Math.min(windowH, height - 24));
        windowX = Math.max(12, Math.min(windowX, width - windowW - 12));
        windowY = Math.max(12, Math.min(windowY, height - windowH - 12));
    }

    private void resizeBrowser() {
        if (browser != null) {
            int scale = Math.max(1, (int) minecraft.getWindow().getGuiScale());
            browser.resize(Math.max(1, windowW * scale), Math.max(1, (windowH - HEADER) * scale));
        }
    }

    private void rebuild() {
        rebuilding = true;
        minecraft.gui.setScreen(new VisionBrowserScreen(parentScreen));
    }

    private void switchTab(int index) {
        VisionBrowserClient.selectTab(index);
        rebuild();
    }

    private void addTab() {
        VisionBrowserClient.createTab(HOME_URL);
        rebuild();
    }

    private void closeTab(int index) {
        VisionBrowserClient.closeTab(index, HOME_URL);
        rebuild();
    }

    /** Turn the page into a floating 3D window and return to the game. */
    private void floatWindow() {
        FloatWindow.setEnabled(true);
        onClose();
    }

    private void toggleFloatLock() {
        FloatWindow.toggleLock();
        rebuild();
    }

    private void navigateBack() {
        if (browser != null) browser.goBack();
    }

    private void navigateForward() {
        if (browser != null) browser.goForward();
    }

    private void toggleSize() {
        if (windowW < width - 40 || windowH < height - 40) {
            windowW = width - 24;
            windowH = height - 24;
            windowX = 12;
            windowY = 12;
        } else {
            windowW = Math.min(900, width - 100);
            windowH = Math.min(600, height - 100);
            windowX = (width - windowW) / 2;
            windowY = (height - windowH) / 2;
        }
        rebuild();
    }

    private static void roundedRect(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius, int color) {
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        for (int row = 0; row < h; row++) {
            int inset = 0;
            if (row < r) {
                double dy = r - row - 0.5;
                inset = r - (int) Math.floor(Math.sqrt(Math.max(0, r * r - dy * dy)));
            } else if (row >= h - r) {
                double dy = row - (h - r) + 0.5;
                inset = r - (int) Math.floor(Math.sqrt(Math.max(0, r * r - dy * dy)));
            }
            graphics.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
        }
    }

    private void navigateFromAddressBar() {
        if (browser == null || addressBar == null) return;
        String input = addressBar.getValue().trim();
        if (input.isEmpty()) return;
        String target;
        if (input.contains("://") || input.startsWith("about:")) target = input;
        else if (input.contains(".") && !input.contains(" ")) target = "https://" + input;
        else target = "https://www.google.com/search?q=" + URLEncoder.encode(input, StandardCharsets.UTF_8);
        browser.loadURL(target);
        browser.setFocus(true);
        setFocused(null);
    }

    /** Frameless look: a translucent toolbar capsule floating above a rounded page. No shell, border, glow or corner masks. */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int pageY = windowY + HEADER, pageH = windowH - HEADER, radius = 16;
        roundedRect(graphics, windowX, windowY, windowW, HEADER - 3, 14, 0xA80C0C14);          // toolbar capsule
        roundedRect(graphics, windowX + 2, windowY + 2, windowW - 4, 22, 12, 0x12FFFFFF);        // soft sheen
        if (browser != null) {
            try {
                RinkuRenderCoordinator.pumpOnRenderThread();
                if (browser.isTextureReady()) {
                    Identifier texture = browser.getTextureIdentifier();
                    if (texture != null) drawRoundedPage(graphics, texture, windowX, pageY, windowW, pageH, radius);
                }
            } catch (Throwable ignored) { }
        }
        // faint resize handle in the bottom-right corner
        graphics.fill(windowX + windowW - 14, windowY + windowH - 6, windowX + windowW - 6, windowY + windowH - 5, 0x66FFFFFF);
        graphics.fill(windowX + windowW - 10, windowY + windowH - 10, windowX + windowW - 6, windowY + windowH - 9, 0x66FFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static final int VIRT = 8192; // virtual texture size: UVs are normalised, the real browser texture size does not matter

    /** The page texture with rounded corners: corner rows are narrower strips, the middle is a single blit. */
    private static void drawRoundedPage(GuiGraphicsExtractor g, Identifier tex, int x, int y, int w, int h, int r) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int row = 0; row < r; row++) {
            int inset = cornerInset(r, row);
            part(g, tex, x + inset, y + row, w - 2 * inset, 1, inset, row, w, h);
            part(g, tex, x + inset, y + h - 1 - row, w - 2 * inset, 1, inset, h - 1 - row, w, h);
        }
        if (h - 2 * r > 0) part(g, tex, x, y + r, w, h - 2 * r, 0, r, w, h);
    }

    private static int cornerInset(int r, int row) {
        double dy = r - row - 0.5;
        return r - (int) Math.floor(Math.sqrt(Math.max(0, r * (double) r - dy * dy)));
    }

    /** draws the page-space rectangle (sx, sy, dw x dh) of a page that is totalW x totalH */
    private static void part(GuiGraphicsExtractor g, Identifier tex, int dx, int dy, int dw, int dh, int sx, int sy, int totalW, int totalH) {
        if (dw <= 0 || dh <= 0) return;
        float u = sx / (float) totalW * VIRT, v = sy / (float) totalH * VIRT;
        int uW = Math.max(1, Math.round(dw / (float) totalW * VIRT)), vH = Math.max(1, Math.round(dh / (float) totalH * VIRT));
        g.blit(RenderPipelines.GUI_TEXTURED, tex, dx, dy, u, v, dw, dh, uW, vH, VIRT, VIRT, 0xFFFFFFFF);
    }

    private boolean insidePage(double x, double y) {
        return x >= windowX && x < windowX + windowW && y >= windowY + HEADER && y < windowY + windowH;
    }

    private int browserMouseX(double x) {
        return (int) ((x - windowX) * minecraft.getWindow().getGuiScale());
    }

    private int browserMouseY(double y) {
        return (int) ((y - windowY - HEADER) * minecraft.getWindow().getGuiScale());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double x = event.x(), y = event.y();
        if (event.button() == MOUSE_LEFT && x >= windowX + windowW - 18 && y >= windowY + windowH - 18) {
            resizingWindow = true;
            return true;
        }
        if (event.button() == MOUSE_LEFT && x >= windowX && x <= windowX + windowW && y >= windowY && y < windowY + HEADER) {
            draggingWindow = true;
            dragOffsetX = x - windowX;
            dragOffsetY = y - windowY;
            return true;
        }
        if (browser != null && insidePage(x, y)) {
            pressedButton = event.button();
            browser.sendMousePress(browserMouseX(x), browserMouseY(y), event.button());
            browser.setFocus(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingWindow || resizingWindow) {
            draggingWindow = false;
            resizingWindow = false;
            rebuild();
            return true;
        }
        if (browser != null && pressedButton != -1) {
            browser.sendMouseRelease(browserMouseX(event.x()), browserMouseY(event.y()), event.button());
            pressedButton = -1;
            browser.setFocus(true);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (browser != null && insidePage(x, y)) browser.sendMouseMove(browserMouseX(x), browserMouseY(y));
        super.mouseMoved(x, y);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingWindow) {
            windowX = (int) (event.x() - dragOffsetX);
            windowY = (int) (event.y() - dragOffsetY);
            clampWindow();
            return true;
        }
        if (resizingWindow) {
            windowW = (int) event.x() - windowX;
            windowH = (int) event.y() - windowY;
            clampWindow();
            resizeBrowser();
            return true;
        }
        if (browser != null && pressedButton != -1) {
            browser.sendMouseMove(browserMouseX(event.x()), browserMouseY(event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (super.mouseScrolled(x, y, horizontal, vertical)) return true;
        if (browser != null && insidePage(x, y)) {
            browser.sendMouseWheel(browserMouseX(x), browserMouseY(y), vertical, 0);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (addressBar != null
                && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
                && !addressBar.getValue().trim().isEmpty()) {
            navigateFromAddressBar();
            return true;
        }
        if (super.keyPressed(event)) return true;
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (browser != null) {
            browser.sendKeyPress(event);
            browser.setFocus(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (super.keyReleased(event)) return true;
        if (browser != null && (addressBar == null || !addressBar.isFocused())) {
            browser.sendKeyRelease(event);
            browser.setFocus(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (super.charTyped(event)) return true;
        if (browser != null && event.codepoint() != 0) {
            browser.sendKeyTyped((char) event.codepoint(), 0);
            browser.setFocus(true);
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
        if (!rebuilding) FloatWindow.onScreenClosed();
    }
}
