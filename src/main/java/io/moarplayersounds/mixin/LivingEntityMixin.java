package io.moarplayersounds.mixin;

import io.moarplayersounds.client.PlayerSoundHooks;
import io.moarplayersounds.profile.SoundSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LivingEntity#getFallSound(int) is the single place the damaging-fall sound is chosen.
 * Players do not override it (they override getFallSounds), so hooking here avoids
 * constructing the FallSounds record.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "getFallSound", at = @At("RETURN"), cancellable = true)
    private void playersound$fall(int distance, CallbackInfoReturnable<SoundEvent> cir) {
        if (!((Object) this instanceof PlayerEntity player)) return;
        SoundEvent original = cir.getReturnValue();
        SoundEvent replacement = PlayerSoundHooks.replace(player, SoundSlot.FALL, original);
        if (replacement != original) cir.setReturnValue(replacement);
    }
}
