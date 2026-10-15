package io.moarplayersounds.profile;

import io.moarplayersounds.MoarPlayerSoundsMod;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the mob -> slot -> sounds table from the live sound registry, using the vanilla
 * naming convention "entity.<mob>.<event>". Nothing is hardcoded, so nothing can name a
 * sound that does not exist in the running game version.
 */
public final class MobSoundIndex {
    private static final Set<String> EXCLUDED = Set.of("player", "generic");
    private static volatile Map<String, EnumMap<SoundSlot, List<SoundEvent>>> index;

    private MobSoundIndex() {}

    private static Map<String, EnumMap<SoundSlot, List<SoundEvent>>> index() {
        Map<String, EnumMap<SoundSlot, List<SoundEvent>>> local = index;
        if (local != null) return local;
        synchronized (MobSoundIndex.class) {
            if (index != null) return index;
            Map<String, EnumMap<SoundSlot, List<SoundEvent>>> map = new TreeMap<>();
            for (Identifier id : Registries.SOUND_EVENT.getIds()) {
                if (!"minecraft".equals(id.getNamespace())) continue;
                String[] p = id.getPath().split("\\.");
                if (p.length < 3 || !p[0].equals("entity") || EXCLUDED.contains(p[1])) continue;
                SoundSlot slot = SoundSlot.classify(String.join(".", Arrays.copyOfRange(p, 2, p.length)));
                if (slot == null) continue;
                SoundEvent ev = Registries.SOUND_EVENT.get(id);
                if (ev == null) continue;
                map.computeIfAbsent(p[1], k -> new EnumMap<>(SoundSlot.class))
                   .computeIfAbsent(slot, k -> new ArrayList<>()).add(ev);
            }
            // Only offer mobs that can voice at least one core event.
            map.entrySet().removeIf(e -> !e.getValue().containsKey(SoundSlot.HURT)
                    && !e.getValue().containsKey(SoundSlot.AMBIENT)
                    && !e.getValue().containsKey(SoundSlot.DEATH));
            MoarPlayerSoundsMod.LOGGER.info("Indexed {} mobs with usable sounds", map.size());
            index = map;
            return map;
        }
    }

    public static Set<String> mobNames() { return Collections.unmodifiableSet(index().keySet()); }

    public static boolean hasMob(String mob) { return index().containsKey(mob); }

    /** Slots a mob can voice directly (before fallbacks). */
    public static Set<SoundSlot> slotsOf(String mob) {
        EnumMap<SoundSlot, List<SoundEvent>> m = index().get(mob);
        return m == null ? Set.of() : m.keySet();
    }

    /**
     * Pick a random sound for the slot. Fallback chain keeps the result sensible rather than silent;
     * returns null when the mob has nothing suitable, in which case the caller keeps vanilla behaviour.
     */
    public static SoundEvent pick(String mob, SoundSlot slot) {
        EnumMap<SoundSlot, List<SoundEvent>> m = index().get(mob);
        if (m == null) return null;
        for (SoundSlot s : fallbackChain(slot)) {
            List<SoundEvent> l = m.get(s);
            if (l != null && !l.isEmpty()) return l.get(ThreadLocalRandom.current().nextInt(l.size()));
        }
        return null;
    }

    private static SoundSlot[] fallbackChain(SoundSlot slot) {
        return switch (slot) {
            case HURT -> new SoundSlot[]{SoundSlot.HURT, SoundSlot.AMBIENT};
            case DEATH -> new SoundSlot[]{SoundSlot.DEATH, SoundSlot.HURT};
            case SWIM -> new SoundSlot[]{SoundSlot.SWIM, SoundSlot.SPLASH};
            case SPLASH -> new SoundSlot[]{SoundSlot.SPLASH, SoundSlot.SWIM};
            default -> new SoundSlot[]{slot};
        };
    }
}
