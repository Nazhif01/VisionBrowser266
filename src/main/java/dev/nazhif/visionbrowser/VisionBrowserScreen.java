package dev.nazhif.visionbrowser;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import de.keksuccino.rinku.RinkuRenderCoordinator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;

public final class VisionBrowserScreen extends Screen {
    private static final String HOME_URL = "https://www.google.com";
    private final Screen parentScreen;
    private RinkuBrowser browser;
    private String status = "Starting browser...";
    private boolean focused;
    private int pressedButton = -1;

    public VisionBrowserScreen(Screen parentScreen) {
        super(Component.literal("Vision Browser"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        // Ensure first-person mouse capture is released while the browser screen is open.
        minecraft.mouseHandler.releaseMouse();
        try {
            if (!VisionBrowserClient.ensureBrowserRuntime()) {
                status = "Rinku could not be initialized. Check that Rinku is installed.";
                return;
            }
            if (browser == null) {
                browser = Rinku.createBrowser(HOME_URL, true);
                if (browser != null) {
                    int scale = Math.max(1, (int) minecraft.getWindow().getGuiScale());
                    browser.resize(Math.max(1, width * scale), Math.max(1, (height - 32) * scale));
                    status = "Loading " + HOME_URL;
                } else {
                    status = "Could not create browser.";
                }
            }
        } catch (Throwable error) {
            status = "Browser startup failed: " + error.getClass().getSimpleName();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF10131A);
        graphics.fill(0, 0, width, 32, 0xE8232935);
        graphics.text(font, "VISION BROWSER", 12, 11, 0xFFEAF1FF, false);
        graphics.text(font, status, 132, 11, 0xFFB8C4D8, false);

        if (browser != null) {
            try {
                RinkuRenderCoordinator.pumpOnRenderThread();
                if (browser.isTextureReady()) {
                    Identifier texture = (Identifier) (Object) browser.getTextureIdentifier();
                    if (texture != null) {
                        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 32, 0.0F, 0.0F,
                                width, height - 32, width, height - 32);
                    }
                }
            } catch (Throwable error) {
                status = "Browser rendering error: " + error.getClass().getSimpleName();
            }
        } else {
            graphics.centeredText(font, status, width / 2, height / 2, 0xFFEAF1FF);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private int browserMouseX(double x) { return (int) (x * minecraft.getWindow().getGuiScale()); }

    private int browserMouseY(double y) { return (int) ((y - 32) * minecraft.getWindow().getGuiScale()); }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (browser != null && event.y() >= 32) {
            focused = true;
            pressedButton = event.button();
            browser.setFocus(true);
            browser.sendMousePress(browserMouseX(event.x()), browserMouseY(event.y()), event.button());
            return true;
        }
        focused = false;
        if (browser != null) browser.setFocus(false);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (browser != null && pressedButton != -1) {
            browser.sendMouseRelease(browserMouseX(event.x()), browserMouseY(event.y()), event.button());
            pressedButton = -1;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (browser != null && y >= 32) browser.sendMouseMove(browserMouseX(x), browserMouseY(y));
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
        if (browser != null && y >= 32) {
            browser.sendMouseWheel(browserMouseX(x), browserMouseY(y), vertical, 0);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (focused && browser != null) {
            browser.sendKeyPress(event.key(), 0L, event.modifiers());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (focused && browser != null) {
            browser.sendKeyRelease(event.key(), 0L, event.modifiers());
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (focused && browser != null) {
            for (char character : Character.toChars(event.codepoint())) { browser.sendKeyTyped(character, 0); }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        if (browser != null) {
            try { browser.close(); } catch (Throwable ignored) { }
            browser = null;
        }
        minecraft.gui.setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (browser != null) {
            try { browser.close(); } catch (Throwable ignored) { }
            browser = null;
        }
        super.removed();
    }
}
