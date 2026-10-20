package io.moarplayersounds.remote;

import io.moarplayersounds.MoarPlayerSoundsMod;
import io.moarplayersounds.profile.Resolved;
import io.moarplayersounds.profile.SoundSlot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides whether a sound the client is about to play was made by a remote player with a profile,
 * and if so what to play instead. Replacement happens only when the attribution is unambiguous;
 * anything unclear returns null and vanilla plays unchanged.
 *
 * Two arrival paths are handled because it must be confirmed in-game which one vanilla uses
 * for other players' sounds (see /playersound remote debug):
 *  - position packets: no entity attached, so the maker is inferred from where the sound is;
 *  - entity packets: the maker is known exactly.
 */
public final class RemoteSounds {
    /** Vanilla player sound ids -> slot. Ids that do not exist simply never match. */
    private static final Map<String, SoundSlot> PLAYER_SOUNDS = new HashMap<>();
    static {
        for (String s : new String[]{"hurt", "hurt_drown", "hurt_on_fire", "hurt_freeze", "hurt_sweet_berry_bush"})
            PLAYER_SOUNDS.put("entity.player." + s, SoundSlot.HURT);
        PLAYER_SOUNDS.put("entity.player.death", SoundSlot.DEATH);
        PLAYER_SOUNDS.put("entity.player.small_fall", SoundSlot.FALL);
        PLAYER_SOUNDS.put("entity.player.big_fall", SoundSlot.FALL);
        PLAYER_SOUNDS.put("entity.player.swim", SoundSlot.SWIM);
        PLAYER_SOUNDS.put("entity.player.splash", SoundSlot.SPLASH);
        PLAYER_SOUNDS.put("entity.player.splash.high_speed", SoundSlot.SPLASH);
    }

    private static final double HORIZONTAL_TOLERANCE_SQ = 3.0 * 3.0;   // other players lag behind the server position
    private static final double VERTICAL_TOLERANCE = 4.0;   // falling players lag further vertically
    private static final double MARGIN = 1.0;               // the nearest player must be this much closer than the next

    private static volatile boolean debug;
    private static boolean warned;

    private RemoteSounds() {}

    public record Replacement(SoundEvent event, float volume, float pitch) {}

    public static boolean isDebug() { return debug; }
    public static void setDebug(boolean v) { debug = v; }

    private static SoundSlot slotOf(SoundEvent ev) {
        Identifier id = ev.getId();
        return "minecraft".equals(id.getNamespace()) ? PLAYER_SOUNDS.get(id.getPath()) : null;
    }

    /**
     * Position-packet path (the one vanilla uses for other players' sounds, confirmed in testing).
     * The maker is the NEAREST player to the sound position, accepted only when no other player is
     * nearly as close, so melee fights no longer count as ambiguous while overlapping players still do.
     */
    public static Replacement forPosition(SoundEvent ev, double x, double y, double z, float volume, float pitch) {
        try {
            SoundSlot slot = slotOf(ev);
            if (slot == null) return null;                                   // not a player sound: untouched, no cost
            boolean enabled = RemoteProfiles.isEnabled();
            if (!enabled && !debug) return null;
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientWorld w = mc.world;
            if (w == null) return null;

            PlayerEntity best = null;
            double bestD = Double.MAX_VALUE, secondD = Double.MAX_VALUE, nearestAny = Double.MAX_VALUE;
            int candidates = 0;
            for (PlayerEntity p : w.getPlayers()) {
                double dx = p.getX() - x, dy = p.getY() - y, dz = p.getZ() - z;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < nearestAny) nearestAny = d;
                if (dx * dx + dz * dz > HORIZONTAL_TOLERANCE_SQ || Math.abs(dy) > VERTICAL_TOLERANCE) continue;
                candidates++;
                if (d < bestD) { secondD = bestD; bestD = d; best = p; }
                else if (d < secondD) secondD = d;
            }
            boolean clear = best != null && (secondD == Double.MAX_VALUE || secondD - bestD >= MARGIN);
            if (debug) {
                MoarPlayerSoundsMod.LOGGER.info("[remote] position packet {} at {},{},{} -> {} candidate(s); nearest {}{}{}",
                        ev.getId(), round(x), round(y), round(z), candidates,
                        best == null ? "none (closest player of any kind " + round(nearestAny) + " blocks away)"
                                : best.getGameProfile().getName() + (best == mc.player ? " (LOCAL)" : "") + " at " + round(bestD),
                        secondD == Double.MAX_VALUE ? "" : ", next at " + round(secondD),
                        best != null && !clear ? " -> AMBIGUOUS" : "");
            }
            if (!enabled || !clear || best == mc.player) return null;      // unclear or local: keep vanilla
            return build(best.getUuid(), slot, volume, pitch, "position");
        } catch (Throwable t) {
            return failed(t);
        }
    }

    /** Entity-packet path: the maker is known. */
    public static Replacement forEntity(Entity entity, SoundEvent ev, float volume, float pitch) {
        try {
            SoundSlot slot = slotOf(ev);
            if (slot == null) return null;
            boolean enabled = RemoteProfiles.isEnabled();
            if (!enabled && !debug) return null;
            if (!(entity instanceof PlayerEntity p)) return null;
            boolean local = p == MinecraftClient.getInstance().player;
            if (debug) MoarPlayerSoundsMod.LOGGER.info("[remote] entity packet {} from {}{}", ev.getId(), p.getGameProfile().getName(), local ? " (LOCAL)" : "");
            if (!enabled || local) return null;
            return build(p.getUuid(), slot, volume, pitch, "entity");
        } catch (Throwable t) {
            return failed(t);
        }
    }

    private static Replacement build(UUID player, SoundSlot slot, float volume, float pitch, String path) {
        Resolved r = RemoteProfiles.resolve(player, slot);
        if (r == null) {
            if (debug) MoarPlayerSoundsMod.LOGGER.info("[remote]   no profile/mapping for this player+slot -> vanilla");
            return null;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        float p = pitch * r.cfg().pitch * (1.0F + (rnd.nextFloat() - rnd.nextFloat()) * r.cfg().jitter);
        if (debug) MoarPlayerSoundsMod.LOGGER.info("[remote]   replaced ({}) with {}", path, r.event().getId());
        return new Replacement(r.event(), volume * r.cfg().volume, p);
    }

    private static Replacement failed(Throwable t) {
        if (!warned) { warned = true; MoarPlayerSoundsMod.LOGGER.warn("Remote sound replacement failed, using vanilla", t); }
        return null;
    }

    private static String round(double d) { return String.format(java.util.Locale.ROOT, "%.1f", d); }
}
