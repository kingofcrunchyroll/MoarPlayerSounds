package io.moarplayersounds.client;

import io.moarplayersounds.MoarPlayerSoundsMod;
import io.moarplayersounds.profile.ProfileManager;
import io.moarplayersounds.profile.Resolved;
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
            if (!player.getWorld().isClient()) return original;
            if (player != MinecraftClient.getInstance().player) return original; // local player only until Phase 3
            Resolved r = ProfileManager.resolve(slot);
            if (r == null) return original;
            PendingSounds.record(r.event().getId(), r.cfg());
            return r.event();
        } catch (Throwable t) {
            if (!warned) { warned = true; MoarPlayerSoundsMod.LOGGER.warn("Sound replacement failed, using vanilla", t); }
            return original;
        }
    }
}
