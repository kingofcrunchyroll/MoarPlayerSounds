package io.moarplayersounds.remote;

import io.moarplayersounds.profile.Resolved;
import io.moarplayersounds.profile.SoundSlot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Remote players' AMBIENT and JUMP, which vanilla has no sound for: produced locally by the listener
 * from observed state (an idle timer, and a ground -> air step with upward movement). Nothing is
 * sent over any network. JUMP detection for other players is experimental.
 */
public final class RemoteSyntheticDriver {
    private static final double MAX_DISTANCE_SQ = 24.0 * 24.0;
    private static final class State { int ambient = next(); int cooldown; boolean wasOnGround; double lastY; }
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static int cleanup;

    private RemoteSyntheticDriver() {}

    private static int next() { return ThreadLocalRandom.current().nextInt(100, 401); }

    public static void tick(MinecraftClient mc) {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null || mc.isPaused() || !RemoteProfiles.isEnabled()) { STATES.clear(); return; }

        for (PlayerEntity p : w.getPlayers()) {
            if (p == mc.player || !RemoteProfiles.has(p.getUuid()) || !p.isAlive() || p.isSpectator()) continue;
            if (p.squaredDistanceTo(mc.player) > MAX_DISTANCE_SQ) continue;
            State s = STATES.computeIfAbsent(p.getUuid(), k -> new State());

            if (s.cooldown > 0) s.cooldown--;
            boolean onGround = p.isOnGround();
            if (s.wasOnGround && !onGround && s.cooldown == 0 && p.getY() - s.lastY > 0.15
                    && !p.hasVehicle() && !p.isTouchingWater() && !p.isClimbing()) {
                s.cooldown = 6;
                play(w, p, SoundSlot.JUMP);
            }
            s.wasOnGround = onGround;
            s.lastY = p.getY();

            if (--s.ambient <= 0) {
                s.ambient = next();
                play(w, p, SoundSlot.AMBIENT);
            }
        }

        if (++cleanup >= 200) {                                  // forget players who left
            cleanup = 0;
            Set<UUID> present = w.getPlayers().stream().map(PlayerEntity::getUuid).collect(Collectors.toSet());
            for (Iterator<UUID> it = STATES.keySet().iterator(); it.hasNext(); ) if (!present.contains(it.next())) it.remove();
        }
    }

    private static void play(ClientWorld w, PlayerEntity p, SoundSlot slot) {
        Resolved r = RemoteProfiles.resolve(p.getUuid(), slot);
        if (r == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        float pitch = r.cfg().pitch * (1.0F + (rnd.nextFloat() - rnd.nextFloat()) * (0.2F + r.cfg().jitter));
        // Positional sound at the other player's location; distance falloff handled by the sound engine.
        w.playSound(p.getX(), p.getY(), p.getZ(), r.event(), SoundCategory.PLAYERS, r.cfg().volume, pitch, false);
    }
}
