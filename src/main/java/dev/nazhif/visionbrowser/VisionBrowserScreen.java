package dev.nazhif.visionbrowser;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import de.keksuccino.rinku.RinkuRenderCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class VisionBrowserScreen extends Screen {
    private static final String HOME_URL = "https://www.google.com";
    private RinkuBrowser browser;
    private String status = "Starting browser...";
    private boolean focused;
    private int pressedButton = -1;

    public VisionBrowserScreen() {
        super(Component.literal("Vision Browser"));
    }

    @Override
    protected void init() {
        super.init();
        try {
            if (!VisionBrowserClient.ensureBrowserRuntime()) {
                status = "Rinku could not be initialized. Check that Rinku is installed.";
                return;
            }
            if (browser == null) {
                browser = Rinku.createBrowser(HOME_URL, true);
                if (browser != null) {
                    browser.resize(Math.max(1, width * 2), Math.max(1, (height - 34) * 2));
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (browser != null && event.y() >= 32) {
            focused = true;
            pressedButton = event.button();
            browser.setFocus(true);
            browser.sendMousePress(event.x(), event.y() - 32, event.button());
            return true;
        }
        focused = false;
        if (browser != null) browser.setFocus(false);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (browser != null && pressedButton != -1) {
            browser.sendMouseRelease(event.x(), event.y() - 32, event.button());
            pressedButton = -1;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (browser != null && y >= 32) browser.sendMouseMove((int) x, (int) y - 32);
        super.mouseMoved(x, y);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (browser != null && pressedButton != -1) {
            browser.sendMouseMove(event.x(), event.y() - 32);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (browser != null && y >= 32) {
            browser.sendMouseWheel((int) x, (int) y - 32, vertical, 0);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (focused && browser != null) {
            browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (focused && browser != null) {
            browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (focused && browser != null) {
            Character.toChars(event.codepoint()).forEach(character -> browser.sendKeyTyped(character, 0));
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
        Minecraft.getInstance().gui.setScreen(null);
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
