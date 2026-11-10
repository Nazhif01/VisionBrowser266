package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.InputConstants;
import de.keksuccino.rinku.Rinku;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;

public final class VisionBrowserClient implements ClientModInitializer {
    private static KeyMapping openBrowser;

    @Override
    public void onInitializeClient() {
        openBrowser = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.open",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("visionbrowser", "main"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
}
