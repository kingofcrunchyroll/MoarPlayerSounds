package io.moarplayersounds.mixin;

import io.moarplayersounds.client.PlayerSoundHooks;
import io.moarplayersounds.profile.SoundSlot;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Narrow RETURN injections on the sound getters PlayerEntity itself declares. */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    @Inject(method = "getHurtSound", at = @At("RETURN"), cancellable = true)
    private void playersound$hurt(DamageSource source, CallbackInfoReturnable<SoundEvent> cir) {
        swap(SoundSlot.HURT, cir);
    }

    @Inject(method = "getDeathSound", at = @At("RETURN"), cancellable = true)
    private void playersound$death(CallbackInfoReturnable<SoundEvent> cir) {
        swap(SoundSlot.DEATH, cir);
    }

    @Inject(method = "getSwimSound", at = @At("RETURN"), cancellable = true)
    private void playersound$swim(CallbackInfoReturnable<SoundEvent> cir) {
        swap(SoundSlot.SWIM, cir);
    }

    @Inject(method = "getSplashSound", at = @At("RETURN"), cancellable = true)
    private void playersound$splash(CallbackInfoReturnable<SoundEvent> cir) {
        swap(SoundSlot.SPLASH, cir);
    }

    @Inject(method = "getHighSpeedSplashSound", at = @At("RETURN"), cancellable = true)
    private void playersound$splashFast(CallbackInfoReturnable<SoundEvent> cir) {
        swap(SoundSlot.SPLASH, cir);
    }

    private void swap(SoundSlot slot, CallbackInfoReturnable<SoundEvent> cir) {
        SoundEvent original = cir.getReturnValue();
        SoundEvent replacement = PlayerSoundHooks.replace((PlayerEntity) (Object) this, slot, original);
        if (replacement != original) cir.setReturnValue(replacement);
    }
}
