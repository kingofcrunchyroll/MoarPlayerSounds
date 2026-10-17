package io.moarplayersounds.client;

import io.moarplayersounds.MoarPlayerSoundsMod;
import io.moarplayersounds.audio.AudioLibrary;
import io.moarplayersounds.profile.ProfileManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class MoarPlayerSoundsClient implements ClientModInitializer {
    private static volatile boolean openConfigRequested;

    /** Commands run while the chat screen is closing, so the screen is opened on the next tick instead. */
    public static void requestOpenConfig() { openConfigRequested = true; }

    @Override
    public void onInitializeClient() {
        AudioLibrary.load();
        ProfileManager.load();
        ClientTickEvents.END_CLIENT_TICK.register(SyntheticSoundDriver::tick);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (openConfigRequested) {
                openConfigRequested = false;
                mc.setScreen(new io.moarplayersounds.client.gui.ConfigScreen(null));
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> PlayerSoundCommand.register(dispatcher));
        MoarPlayerSoundsMod.LOGGER.info("MoarPlayerSounds Phase 2/6 (profiles, imported audio, GUI) initialised");
    }
}
