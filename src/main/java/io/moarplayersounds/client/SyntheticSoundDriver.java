package io.moarplayersounds.client;

import io.moarplayersounds.profile.ProfileManager;
import io.moarplayersounds.profile.SoundSlot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the two slots vanilla players do not have: AMBIENT (random idle timer) and JUMP
 * (ground -> air transition with upward velocity). Local player only in Phase 1.
 */
public final class SyntheticSoundDriver {
    private static final int AMBIENT_MIN_TICKS = 100, AMBIENT_MAX_TICKS = 400;
    private static final int JUMP_COOLDOWN_TICKS = 6;

    private static int ambientCountdown = nextAmbient();
    private static int jumpCooldown;
    private static boolean wasOnGround;

    private SyntheticSoundDriver() {}

    private static int nextAmbient() {
        return ThreadLocalRandom.current().nextInt(AMBIENT_MIN_TICKS, AMBIENT_MAX_TICKS + 1);
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.isPaused()) { wasOnGround = false; return; }
        if (!ProfileManager.isEnabled() || !p.isAlive() || p.isSpectator()) { wasOnGround = p.isOnGround(); return; }

        if (jumpCooldown > 0) jumpCooldown--;
        boolean onGround = p.isOnGround();
        if (wasOnGround && !onGround && jumpCooldown == 0 && p.getVelocity().y > 0.2
                && !p.hasVehicle() && !p.isTouchingWater() && !p.isClimbing()) {
            jumpCooldown = JUMP_COOLDOWN_TICKS;
            play(p, SoundSlot.JUMP);
        }
        wasOnGround = onGround;

        if (--ambientCountdown <= 0) {
            ambientCountdown = nextAmbient();
            play(p, SoundSlot.AMBIENT);
        }
    }

    public static boolean play(ClientPlayerEntity p, SoundSlot slot) {
        SoundEvent ev = ProfileManager.resolve(slot);
        if (ev == null) return false;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float pitch = 1.0F + (r.nextFloat() - r.nextFloat()) * 0.2F; // same spread vanilla mobs use
        // Entity#playSound -> positioned at the player, PLAYERS category, respects volume sliders.
        p.playSound(ev, 1.0F, pitch);
        return true;
    }
}
