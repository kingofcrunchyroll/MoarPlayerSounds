package io.moarplayersounds.mixin;

import io.moarplayersounds.client.PendingSounds;
import io.moarplayersounds.profile.SlotConfig;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies per-slot volume/pitch/jitter to sounds the getter hooks just swapped. A sound is only
 * adjusted if PendingSounds holds a fresh token for the same id near the local player; the
 * re-issued call finds no token, so there is no loop.
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
        if (cfg == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float p = pitch * cfg.pitch * (1.0F + (r.nextFloat() - r.nextFloat()) * cfg.jitter);
        ci.cancel();
        this.playSound(x, y, z, event, category, volume * cfg.volume, p, useDistance, seed);
    }
}