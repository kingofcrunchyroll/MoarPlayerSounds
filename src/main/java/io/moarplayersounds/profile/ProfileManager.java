package io.moarplayersounds.profile;

import com.google.gson.*;
import io.moarplayersounds.MoarPlayerSoundsMod;
import io.moarplayersounds.audio.AudioLibrary;
import io.moarplayersounds.audio.CustomSounds;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.sound.SoundEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;

/** Loads, validates, saves and resolves the local player's profile. Reads version 1 and 2 files. */
public final class ProfileManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("moarplayersounds.json");
    private static PlayerSoundConfig config = new PlayerSoundConfig();

    private ProfileManager() {}

    public static synchronized void load() {
        PlayerSoundConfig c = new PlayerSoundConfig();
        try {
            if (Files.exists(FILE)) {
                JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("enabled")) c.enabled = root.get("enabled").getAsBoolean();
                if (root.has("slots") && root.get("slots").isJsonObject()) {
                    for (var e : root.getAsJsonObject("slots").entrySet()) {
                        SoundSlot slot = SoundSlot.byKey(e.getKey()).orElse(null);
                        if (slot == null) continue;
                        JsonElement v = e.getValue();
                        SlotConfig sc = null;
                        if (v.isJsonPrimitive()) sc = new SlotConfig(v.getAsString());          // version 1: plain string
                        else if (v.isJsonObject()) sc = GSON.fromJson(v, SlotConfig.class);     // version 2: object
                        if (sc != null) c.slots.put(slot.key(), sc.sanitize());
                    }
                }
            }
        } catch (Exception e) {
            // Corrupt file: keep running on defaults, never crash the game.
            MoarPlayerSoundsMod.LOGGER.warn("Could not read {}, using defaults: {}", FILE, e.toString());
            c = new PlayerSoundConfig();
        }
        config = c;
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling("moarplayersounds.json.tmp");
            Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MoarPlayerSoundsMod.LOGGER.warn("Could not save {}: {}", FILE, e.toString());
        }
    }

    public static synchronized boolean isEnabled() { return config.enabled; }
    public static synchronized void setEnabled(boolean v) { config.enabled = v; save(); }

    /** Copy of a slot's settings (defaults if unset). */
    public static synchronized SlotConfig get(SoundSlot slot) {
        SlotConfig c = config.slots.get(slot.key());
        return c == null ? new SlotConfig() : c.copy();
    }

    public static synchronized void mutate(SoundSlot slot, Consumer<SlotConfig> change) {
        mutateDeferred(slot, change);
        save();
    }

    /** Same as mutate but does not write to disk (for slider drags); call save() afterwards. */
    public static synchronized void mutateDeferred(SoundSlot slot, Consumer<SlotConfig> change) {
        SlotConfig c = config.slots.getOrDefault(slot.key(), new SlotConfig()).copy();
        change.accept(c);
        c.sanitize();
        if (c.isVanilla() && c.isNeutral()) config.slots.remove(slot.key());
        else config.slots.put(slot.key(), c);
    }

    public static void setSource(SoundSlot slot, String source) { mutate(slot, c -> c.source = source); }

    /** Applies one mob to every slot it can voice (directly or by fallback); resets volume/pitch. */
    public static synchronized void applyPreset(String mob) {
        config.slots.clear();
        for (SoundSlot s : SoundSlot.values()) {
            if (MobSoundIndex.pick(mob, s) != null) config.slots.put(s.key(), new SlotConfig("mob:" + mob));
        }
        save();
    }

    public static synchronized void reset() { config.slots.clear(); save(); }

    /** The replacement for a slot, or null meaning "leave vanilla alone". */
    public static Resolved resolve(SoundSlot slot) {
        SlotConfig cfg;
        synchronized (ProfileManager.class) {
            if (!config.enabled) return null;
            SlotConfig c = config.slots.get(slot.key());
            if (c == null || c.isVanilla()) return null;
            cfg = c.copy();
        }
        SoundEvent ev = null;
        if (cfg.source.startsWith("mob:")) {
            ev = MobSoundIndex.pick(cfg.source.substring(4), slot);
        } else if (cfg.source.startsWith("custom:")) {
            AudioLibrary.Clip clip = AudioLibrary.get(cfg.source.substring(7));
            if (clip != null) ev = CustomSounds.eventFor(clip.hash);   // null on any failure -> vanilla
        }
        return ev == null ? null : new Resolved(ev, cfg);
    }
}
