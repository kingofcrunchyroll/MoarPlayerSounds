package io.moarplayersounds.mixin;

import io.moarplayersounds.audio.CustomSounds;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.sound.StaticSound;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Serves library clips for ids in our namespace; everything else is untouched vanilla. */
@Mixin(SoundLoader.class)
public abstract class SoundLoaderMixin {
    @Unique private final Map<Identifier, CompletableFuture<StaticSound>> moarplayersounds$cache = new ConcurrentHashMap<>();

    @Inject(method = "loadStatic(Lnet/minecraft/util/Identifier;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), cancellable = true)
    private void moarplayersounds$custom(Identifier id, CallbackInfoReturnable<CompletableFuture<StaticSound>> cir) {
        if (!CustomSounds.NAMESPACE.equals(id.getNamespace())) return;
        CompletableFuture<StaticSound> f = moarplayersounds$cache.computeIfAbsent(id, CustomSounds::load);
        if (f.isCompletedExceptionally()) { moarplayersounds$cache.remove(id); }
        cir.setReturnValue(f);
    }

    /** Cached StaticSounds are invalidated together with the loader (sound reload / device change). */
    @Inject(method = "close", at = @At("HEAD"))
    private void moarplayersounds$clear(CallbackInfo ci) {
        moarplayersounds$cache.clear();
    }
}
