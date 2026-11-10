package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.InputConstants;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class VisionBrowserClient implements ClientModInitializer {
    private static KeyMapping openBrowser;
    private static KeyMapping toggleMusicIsland;
    private static KeyMapping mediaPrevious;
    private static KeyMapping mediaToggle;
    private static KeyMapping mediaNext;
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("visionbrowser", "main"));
    private static final List<RinkuBrowser> tabs = new ArrayList<>();
    private static int activeTab = 0;

    @Override
    public void onInitializeClient() {
        openBrowser = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.open",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B,
                CATEGORY
        ));

        toggleMusicIsland = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.toggle_music_island",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_O,
                CATEGORY
        ));
        mediaPrevious = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.media_previous", InputConstants.Type.KEYBOARD, GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY));
        mediaToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.media_toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_P, CATEGORY));
        mediaNext = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.media_next", InputConstants.Type.KEYBOARD, GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY));
        VisionMusicHud.register();
        VisionMediaService.start();
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenMouseEvents.allowMouseClick(screen).register((currentScreen, event) ->
                    !VisionMusicHud.handleClick(event.x(), event.y(), event.button())
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleMusicIsland.consumeClick()) {
                VisionMusicHud.toggle();
            }
            while (mediaPrevious.consumeClick()) VisionMediaService.previous();
            while (mediaToggle.consumeClick()) VisionMediaService.togglePlayback();
            while (mediaNext.consumeClick()) VisionMediaService.next();
            while (openBrowser.consumeClick()) {
                if (client.gui.screen() instanceof VisionBrowserScreen visionScreen) {
                    visionScreen.onClose();
                } else if (client.gui.screen() == null || client.gui.screen() instanceof TitleScreen) {
                    client.gui.setScreen(new VisionBrowserScreen(client.gui.screen()));
                }
            }
        });
    }

    static boolean ensureBrowserRuntime() {
        return Rinku.isInitialized() || (Rinku.isInitializationAllowed() && Rinku.initialize());
    }

    static synchronized RinkuBrowser getActiveBrowser() {
        if (tabs.isEmpty()) return null;
        activeTab = Math.max(0, Math.min(activeTab, tabs.size() - 1));
        return tabs.get(activeTab);
    }

    static synchronized RinkuBrowser getOrCreateBrowser(String startUrl) {
        if (!ensureBrowserRuntime()) return null;
        if (tabs.isEmpty()) {
            RinkuBrowser first = Rinku.createBrowser(startUrl, true);
            if (first != null) tabs.add(first);
            activeTab = 0;
        }
        if (tabs.isEmpty()) return null;
        activeTab = Math.max(0, Math.min(activeTab, tabs.size() - 1));
        return tabs.get(activeTab);
    }

    static synchronized RinkuBrowser createTab(String startUrl) {
        if (!ensureBrowserRuntime()) return null;
        RinkuBrowser tab = Rinku.createBrowser(startUrl, true);
        if (tab != null) {
            tabs.add(tab);
            activeTab = tabs.size() - 1;
        }
        return tab;
    }

    static synchronized RinkuBrowser selectTab(int index) {
        if (index >= 0 && index < tabs.size()) activeTab = index;
        return tabs.isEmpty() ? null : tabs.get(activeTab);
    }

    static synchronized RinkuBrowser closeTab(int index, String fallbackUrl) {
        if (index < 0 || index >= tabs.size()) return null;
        RinkuBrowser removed = tabs.remove(index);
        try {
            removed.close();
        } catch (Throwable ignored) { }
        if (tabs.isEmpty()) {
            RinkuBrowser replacement = Rinku.createBrowser(fallbackUrl, true);
            if (replacement != null) tabs.add(replacement);
            activeTab = 0;
        } else if (activeTab > index) {
            activeTab--;
        } else if (activeTab >= tabs.size()) {
            activeTab = tabs.size() - 1;
        }
        return tabs.isEmpty() ? null : tabs.get(activeTab);
    }

    static synchronized int getTabCount() {
        return tabs.size();
    }

    static synchronized int getActiveTab() {
        return activeTab;
    }
}
