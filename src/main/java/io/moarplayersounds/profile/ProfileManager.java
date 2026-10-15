package io.moarplayersounds.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.moarplayersounds.MoarPlayerSoundsMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.sound.SoundEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Loads, validates, saves and resolves the local player's profile. */
public final class ProfileManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("moarplayersounds.json");
    private static PlayerSoundConfig config = new PlayerSoundConfig();

    private ProfileManager() {}

    public static synchronized void load() {
        try {
            if (Files.exists(FILE)) {
                PlayerSoundConfig c = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), PlayerSoundConfig.class);
                if (c != null && c.slots != null) config = c;
            }
        } catch (Exception e) {
            // Corrupt file: keep running on defaults, never crash the game.
            MoarPlayerSoundsMod.LOGGER.warn("Could not read {}, using defaults: {}", FILE, e.toString());
            config = new PlayerSoundConfig();
        }
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

    public static synchronized String sourceOf(SoundSlot slot) {
        return config.slots.getOrDefault(slot.key(), "vanilla");
    }

    public static synchronized void setSlot(SoundSlot slot, String source) {
        if (source == null || source.equals("vanilla")) config.slots.remove(slot.key());
        else config.slots.put(slot.key(), source);
        save();
    }

    /** Applies one mob to every slot it can voice (directly or by fallback). */
    public static synchronized void applyPreset(String mob) {
        config.slots.clear();
        for (SoundSlot s : SoundSlot.values()) {
            if (MobSoundIndex.pick(mob, s) != null) config.slots.put(s.key(), "mob:" + mob);
        }
        save();
    }

    public static synchronized void reset() { config.slots.clear(); save(); }

    /** The replacement for a slot, or null meaning "leave vanilla alone". */
    public static SoundEvent resolve(SoundSlot slot) {
        String src;
        synchronized (ProfileManager.class) {
            if (!config.enabled) return null;
            src = config.slots.get(slot.key());
        }
        if (src == null || !src.startsWith("mob:")) return null;
        return MobSoundIndex.pick(src.substring(4), slot);
    }
}
