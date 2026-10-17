package io.moarplayersounds.mixin;

import io.moarplayersounds.client.PendingSounds;
import io.moarplayersounds.profile.SlotConfig;
import io.moarplayersounds.remote.RemoteSounds;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Client sound choke points.
 *  1. Local player: applies per-slot volume/pitch/jitter to sounds the getter hooks just swapped
 *     (token based, see PendingSounds).
 *  2. Remote players (Phase 3, off by default): swaps another player's vanilla sound for the
 *     profile assigned to them, only when the maker can be identified unambiguously.
 * Replacement sounds are never in the player-sound whitelist, so re-issuing cannot loop.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    // The 9-argument overload is private in ClientWorld, so it is shadowed instead of called directly.
    @Shadow
    protected abstract void playSound(double x, double y, double z, SoundEvent event, SoundCategory category,
                                      float volume, float pitch, boolean useDistance, long seed);

    @Inject(method = "playSound(DDDLnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFZJ)V",
            at = @At("HEAD"), cancellable = true)
    private void moarplayersounds$adjust(double x, double y, double z, SoundEvent event, SoundCategory category,
                                         float volume, float pitch, boolean useDistance, long seed, CallbackInfo ci) {
        SlotConfig cfg = PendingSounds.consume(event.getId(), x, y, z);
        if (cfg != null) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            float p = pitch * cfg.pitch * (1.0F + (r.nextFloat() - r.nextFloat()) * cfg.jitter);
            ci.cancel();
            this.playSound(x, y, z, event, category, volume * cfg.volume, p, useDistance, seed);
            return;
        }
        RemoteSounds.Replacement rep = RemoteSounds.forPosition(event, x, y, z, volume, pitch);
        if (rep != null) {
            ci.cancel();
            this.playSound(x, y, z, rep.event(), category, rep.volume(), rep.pitch(), useDistance, seed);
        }
    }

    @Inject(method = "playSoundFromEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/sound/SoundCategory;FFJ)V",
            at = @At("HEAD"), cancellable = true)
    private void moarplayersounds$fromEntity(PlayerEntity source, Entity entity, RegistryEntry<SoundEvent> sound,
                                             SoundCategory category, float volume, float pitch, long seed, CallbackInfo ci) {
        RemoteSounds.Replacement rep = RemoteSounds.forEntity(entity, sound.value(), volume, pitch);
        if (rep != null) {
            ci.cancel();
            ((ClientWorld) (Object) this).playSoundFromEntity(source, entity, RegistryEntry.of(rep.event()),
                    category, rep.volume(), rep.pitch(), seed);
        }
    }
}
