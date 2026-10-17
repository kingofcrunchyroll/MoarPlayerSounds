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

    private static final double HORIZONTAL_TOLERANCE_SQ = 1.5 * 1.5;
    private static final double VERTICAL_TOLERANCE = 3.0;   // falling players lag behind the server position

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

    /** Position-packet path. */
    public static Replacement forPosition(SoundEvent ev, double x, double y, double z, float volume, float pitch) {
        try {
            SoundSlot slot = slotOf(ev);
            if (slot == null) return null;                                   // not a player sound: untouched, no cost
            boolean enabled = RemoteProfiles.isEnabled();
            if (!enabled && !debug) return null;
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientWorld w = mc.world;
            if (w == null) return null;

            PlayerEntity match = null;
            int candidates = 0;
            for (PlayerEntity p : w.getPlayers()) {
                double dx = p.getX() - x, dz = p.getZ() - z;
                if (dx * dx + dz * dz <= HORIZONTAL_TOLERANCE_SQ && Math.abs(p.getY() - y) <= VERTICAL_TOLERANCE) {
                    candidates++;
                    match = p;
                }
            }
            if (debug) MoarPlayerSoundsMod.LOGGER.info("[remote] position packet {} at {},{},{} -> {} candidate(s){}",
                    ev.getId(), round(x), round(y), round(z), candidates,
                    candidates == 1 ? " (" + match.getGameProfile().getName() + (match == mc.player ? ", LOCAL" : "") + ")" : "");
            if (!enabled || candidates != 1 || match == mc.player) return null;   // ambiguous or local: keep vanilla
            return build(match.getUuid(), slot, volume, pitch, "position");
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
