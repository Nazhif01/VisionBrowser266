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
    private static final String HOME_URL = "https://www.google.com";
    private final Screen parentScreen;
    private RinkuBrowser browser;
    private EditBox addressBar;
    private int pressedButton = -1;

    public VisionBrowserScreen(Screen parentScreen) {
        super(Component.literal("Vision Browser"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        minecraft.mouseHandler.releaseMouse();
        try {
            browser = VisionBrowserClient.getOrCreateBrowser(HOME_URL);
            if (browser != null) {
                int scale = Math.max(1, (int) minecraft.getWindow().getGuiScale());
                browser.resize(Math.max(1, width * scale), Math.max(1, (height - 58) * scale));
            }
        } catch (Throwable ignored) {
            browser = null;
        }

        int tabCount = VisionBrowserClient.getTabCount();
        int tabX = 12;
        for (int i = 0; i < tabCount; i++) {
            final int tabIndex = i;
            int buttonWidth = 48;
            addRenderableWidget(Button.builder(
                    Component.literal((i == VisionBrowserClient.getActiveTab() ? "• " : "") + (i + 1)),
                    button -> switchTab(tabIndex)
            ).bounds(tabX, 34, buttonWidth, 20).build());
            tabX += buttonWidth + 4;
        }

        addRenderableWidget(Button.builder(Component.literal("+"), button -> addTab())
                .bounds(tabX, 34, 24, 20).build());

        addressBar = addRenderableWidget(new EditBox(font, 132, 7, Math.max(80, width - 260), 18, Component.literal("Search or enter URL")));
        addressBar.setMaxLength(2048);
        addressBar.setValue(HOME_URL);
        addressBar.setHint(Component.literal("Search Google or enter URL"));
    }

    private void switchTab(int index) {
        VisionBrowserClient.selectTab(index);
        minecraft.gui.setScreen(new VisionBrowserScreen(parentScreen));
    }

    private void addTab() {
        VisionBrowserClient.createTab(HOME_URL);
        minecraft.gui.setScreen(new VisionBrowserScreen(parentScreen));
    }

    private void navigateFromAddressBar() {
        if (browser == null || addressBar == null) return;
        String input = addressBar.getValue().trim();
        if (input.isEmpty()) return;
        String target;
        if (input.contains("://") || input.startsWith("about:")) {
            target = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            target = "https://" + input;
        } else {
            target = "https://www.google.com/search?q=" + URLEncoder.encode(input, StandardCharsets.UTF_8);
        }
        browser.loadURL(target);
        browser.setFocus(true);
        setFocused(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF10131A);
        graphics.fill(0, 0, width, 58, 0xE8232935);
        graphics.text(font, "VISION BROWSER", 12, 11, 0xFFEAF1FF, false);
        if (browser != null) {
            try {
                RinkuRenderCoordinator.pumpOnRenderThread();
                if (browser.isTextureReady()) {
                    Identifier texture = (Identifier) (Object) browser.getTextureIdentifier();
                    if (texture != null) {
                        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 58, 0.0F, 0.0F,
                                width, height - 58, width, height - 58);
                    }
                }
            } catch (Throwable ignored) { }
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private int browserMouseX(double x) {
        return (int) (x * minecraft.getWindow().getGuiScale());
    }

    private int browserMouseY(double y) {
        return (int) ((y - 58) * minecraft.getWindow().getGuiScale());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (browser != null && event.y() >= 58) {
            pressedButton = event.button();
            browser.sendMousePress(browserMouseX(event.x()), browserMouseY(event.y()), event.button());
            browser.setFocus(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
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
        if (browser != null && y >= 58) browser.sendMouseMove(browserMouseX(x), browserMouseY(y));
        super.mouseMoved(x, y);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (browser != null && pressedButton != -1) {
            browser.sendMouseMove(browserMouseX(event.x()), browserMouseY(event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (super.mouseScrolled(x, y, horizontal, vertical)) return true;
        if (browser != null && y >= 58) {
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
        // Keep all browser tabs alive while Minecraft is in the foreground.
        minecraft.gui.setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        // Browser tabs are intentionally retained for background playback.
        super.removed();
    }
}
