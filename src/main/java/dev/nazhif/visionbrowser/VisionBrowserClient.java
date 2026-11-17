package dev.nazhif.visionbrowser;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;

public final class VisionBrowserClient implements ClientModInitializer {
    private static KeyMapping openBrowser;
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("visionbrowser", "main"));
    private static KeyMapping toggleLyrics;
    private static KeyMapping openIsland;
    private static KeyMapping syncEarlier;
    private static KeyMapping syncLater;

    @Override
    public void onInitializeClient() {
        openBrowser = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.open",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B,
                CATEGORY
        ));

        VisionSettings.load();
        VisionWorldPanel.register();
        FloatingLyrics.register();
        DynamicIsland.register();
        FloatWindow.register();
        NameTagLyrics3D.register();
        ChatPanel.register();
        VisionMediaBridge.start();
        MusicPlayer.register();
        toggleLyrics = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.toggle_lyrics", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY));
        openIsland = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.island", InputConstants.Type.KEYBOARD, InputConstants.KEY_I, CATEGORY));

        syncEarlier = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.sync_earlier", InputConstants.Type.KEYBOARD, InputConstants.KEY_LBRACKET, CATEGORY));
        syncLater = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.visionbrowser.sync_later", InputConstants.Type.KEYBOARD, InputConstants.KEY_RBRACKET, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (syncEarlier.consumeClick()) FloatingLyrics.nudge(50);
            while (syncLater.consumeClick()) FloatingLyrics.nudge(-50);
            while (toggleLyrics.consumeClick()) FloatingLyrics.toggle();
            while (openIsland.consumeClick()) {
                if (client.gui.screen() instanceof IslandScreen islandScreen) {
                    islandScreen.onClose();
                } else if (client.gui.screen() == null && client.player != null) {
                    client.gui.setScreen(new IslandScreen(null));
                }
            }
            while (openBrowser.consumeClick()) {
                if (client.gui.screen() instanceof RemoteScreen remoteScreen) {
                    remoteScreen.onClose();
                } else if (client.gui.screen() instanceof MusicScreen musicScreen) {
                    musicScreen.onClose();
                } else if (client.gui.screen() == null || client.gui.screen() instanceof TitleScreen) {
                    client.gui.setScreen(VisionSettings.musicMode
                            ? new MusicScreen(client.gui.screen())
                            : new RemoteScreen(client.gui.screen()));
                }
            }
        });
    }
}
