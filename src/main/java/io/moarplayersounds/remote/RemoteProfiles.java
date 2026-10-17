package io.moarplayersounds.remote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.moarplayersounds.MoarPlayerSoundsMod;
import io.moarplayersounds.profile.MobSoundIndex;
import io.moarplayersounds.profile.ProfileManager;
import io.moarplayersounds.profile.Resolved;
import io.moarplayersounds.profile.SlotConfig;
import io.moarplayersounds.profile.SoundSlot;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Sound profiles assigned to OTHER players, keyed by UUID. Phase 3 fills these by hand
 * (/playersound remote ...) so the detection/replacement logic can be tested without a network
 * layer; Phase 4 will fill the same structure from synced profiles. Disabled by default.
 */
public final class RemoteProfiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("moarplayersounds").resolve("remote.json");

    public static final class Entry {
        public String name = "";
        public Map<String, SlotConfig> slots = new LinkedHashMap<>();
    }
    private static final class Data {
        boolean enabled = false;
        Map<String, Entry> players = new LinkedHashMap<>();
    }

    private static Data data = new Data();

    private RemoteProfiles() {}

    public static synchronized void load() {
        Data d = new Data();
        try {
            if (Files.exists(FILE)) {
                Data read = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Data.class);
                if (read != null && read.players != null) {
                    d.enabled = read.enabled;
                    for (var e : read.players.entrySet()) {
                        try { UUID.fromString(e.getKey()); } catch (IllegalArgumentException bad) { continue; }
                        if (e.getValue() == null || e.getValue().slots == null) continue;
                        Entry en = new Entry();
                        en.name = e.getValue().name == null ? "" : e.getValue().name;
                        for (var s : e.getValue().slots.entrySet()) {
                            if (SoundSlot.byKey(s.getKey()).isPresent() && s.getValue() != null)
                                en.slots.put(s.getKey(), s.getValue().sanitize());
                        }
                        d.players.put(e.getKey(), en);
                    }
                }
            }
        } catch (Exception ex) {
            MoarPlayerSoundsMod.LOGGER.warn("Could not read remote.json, starting empty: {}", ex.toString());
            d = new Data();
        }
        data = d;
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling("remote.json.tmp");
            Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MoarPlayerSoundsMod.LOGGER.warn("Could not save remote.json: {}", e.toString());
        }
    }

    public static synchronized boolean isEnabled() { return data.enabled; }
    public static synchronized void setEnabled(boolean v) { data.enabled = v; save(); }

    /** Assign one mob to every slot it can voice for this player. Returns the slots that were set. */
    public static synchronized List<String> setPreset(UUID id, String name, String mob) {
        Entry e = new Entry();
        e.name = name;
        List<String> set = new ArrayList<>();
        for (SoundSlot s : SoundSlot.values()) {
            if (MobSoundIndex.pick(mob, s) != null) { e.slots.put(s.key(), new SlotConfig("mob:" + mob)); set.add(s.key()); }
        }
        data.players.put(id.toString(), e);
        save();
        return set;
    }

    public static synchronized void setSlot(UUID id, String name, SoundSlot slot, String source) {
        Entry e = data.players.computeIfAbsent(id.toString(), k -> new Entry());
        e.name = name;
        if ("vanilla".equals(source)) e.slots.remove(slot.key());
        else e.slots.put(slot.key(), new SlotConfig(source));
        if (e.slots.isEmpty()) data.players.remove(id.toString());
        save();
    }

    public static synchronized boolean clear(UUID id) {
        boolean had = data.players.remove(id.toString()) != null;
        if (had) save();
        return had;
    }

    public static synchronized boolean clearByName(String name) {
        boolean had = data.players.values().removeIf(e -> e.name.equalsIgnoreCase(name));
        if (had) save();
        return had;
    }

    public static synchronized void clearAll() { data.players.clear(); save(); }

    public static synchronized boolean has(UUID id) { return data.players.containsKey(id.toString()); }

    public static synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        for (var e : data.players.entrySet()) {
            StringBuilder sb = new StringBuilder(e.getValue().name.isEmpty() ? e.getKey() : e.getValue().name).append(": ");
            e.getValue().slots.forEach((k, v) -> sb.append(k).append("=").append(v.source).append(' '));
            out.add(sb.toString().trim());
        }
        return out;
    }

    /** Resolved sound for a remote player's slot, or null (no profile, vanilla, or failure). */
    public static Resolved resolve(UUID id, SoundSlot slot) {
        SlotConfig cfg;
        synchronized (RemoteProfiles.class) {
            Entry e = data.players.get(id.toString());
            if (e == null) return null;
            SlotConfig c = e.slots.get(slot.key());
            if (c == null) return null;
            cfg = c.copy();
        }
        return ProfileManager.resolveConfig(slot, cfg);
    }
}
