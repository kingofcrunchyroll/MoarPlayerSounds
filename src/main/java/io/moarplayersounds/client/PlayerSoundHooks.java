package io.moarplayersounds.client;

import io.moarplayersounds.MoarPlayerSoundsMod;
import io.moarplayersounds.profile.ProfileManager;
import io.moarplayersounds.profile.SoundSlot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;

/**
 * Called from mixins. Only ever *selects* a SoundEvent; it never plays anything, so it
 * cannot recurse into the sound system. Any failure falls back to the vanilla event.
 */
public final class PlayerSoundHooks {
    private static boolean warned;
    private PlayerSoundHooks() {}

    public static SoundEvent replace(PlayerEntity player, SoundSlot slot, SoundEvent original) {
        try {
            if (!player.getWorld().isClient()) return original;           // never touch integrated-server entities
            if (player != MinecraftClient.getInstance().player) return original; // Phase 1: local player only
            SoundEvent r = ProfileManager.resolve(slot);
            return r != null ? r : original;
        } catch (Throwable t) {
            if (!warned) { warned = true; MoarPlayerSoundsMod.LOGGER.warn("Sound replacement failed, using vanilla", t); }
            return original;
        }
    }
}
