package io.moarplayersounds.client;

import io.moarplayersounds.profile.SlotConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived tokens linking "the getter just returned this replacement" to "the world is now
 * playing it", so volume/pitch apply only to our replaced sounds and never to a mob's identical one.
 */
public final class PendingSounds {
    private static final long TTL_NANOS = 250_000_000L;      // 250 ms
    private static final double MAX_DIST_SQ = 3.0 * 3.0;
    private record Token(SlotConfig cfg, long at) {}
    private static final Map<Identifier, Token> TOKENS = new ConcurrentHashMap<>();

    private PendingSounds() {}

    public static void record(Identifier id, SlotConfig cfg) {
        if (cfg.isNeutral()) return;
        long now = System.nanoTime();
        if (TOKENS.size() > 16) TOKENS.values().removeIf(t -> now - t.at > TTL_NANOS);
        TOKENS.put(id, new Token(cfg, now));
    }

    /** Returns and removes the matching fresh token, or null. */
    public static SlotConfig consume(Identifier id, double x, double y, double z) {
        if (TOKENS.isEmpty()) return null;
        Token t = TOKENS.remove(id);
        if (t == null || System.nanoTime() - t.at > TTL_NANOS) return null;
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null || p.squaredDistanceTo(x, y, z) > MAX_DIST_SQ) return null;
        return t.cfg;
    }
}
