package io.moarplayersounds.client;

import io.moarplayersounds.MoarPlayerSoundsMod;
import io.moarplayersounds.profile.ProfileManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class MoarPlayerSoundsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ProfileManager.load();
        ClientTickEvents.END_CLIENT_TICK.register(SyntheticSoundDriver::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> PlayerSoundCommand.register(dispatcher));
        MoarPlayerSoundsMod.LOGGER.info("MoarPlayerSounds Phase 1 (local replacement) initialised");
    }
}
