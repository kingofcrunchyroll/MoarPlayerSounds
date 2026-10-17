package io.moarplayersounds.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.moarplayersounds.MoarPlayerSoundsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** The user's imported clips: content-addressed files (<sha256>.ogg) plus a name index. */
public final class AudioLibrary {
    public static final int MAX_CLIPS = 32;
    public static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("moarplayersounds");
    private static final Path AUDIO = DIR.resolve("audio");
    private static final Path INDEX = DIR.resolve("library.json");
    private static final Map<String, Clip> clips = new TreeMap<>();

    public static final class Clip {
        public String hash;
        public long durationMs;
        public int bytes;
    }

    public record ImportResult(String error, Clip clip) {}

    private AudioLibrary() {}

    public static synchronized void load() {
        clips.clear();
        try {
            if (!Files.exists(INDEX)) return;
            JsonObject root = JsonParser.parseString(Files.readString(INDEX, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("clips")) return;
            for (var e : root.getAsJsonObject("clips").entrySet()) {
                Clip c = GSON.fromJson(e.getValue(), Clip.class);
                if (c != null && c.hash != null && HASH.matcher(c.hash).matches() && NAME.matcher(e.getKey()).matches()
                        && Files.isRegularFile(AUDIO.resolve(c.hash + ".ogg"))) clips.put(e.getKey(), c);
            }
        } catch (Exception e) {
            MoarPlayerSoundsMod.LOGGER.warn("Could not read audio library, starting empty: {}", e.toString());
            clips.clear();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(DIR);
            JsonObject root = new JsonObject();
            root.add("clips", GSON.toJsonTree(clips));
            Path tmp = INDEX.resolveSibling("library.json.tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, INDEX, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MoarPlayerSoundsMod.LOGGER.warn("Could not save audio library: {}", e.toString());
        }
    }

    public static synchronized Clip get(String name) { return clips.get(name); }
    public static synchronized Set<String> names() { return new TreeSet<>(clips.keySet()); }
    public static synchronized Map<String, Clip> all() { return new TreeMap<>(clips); }

    /** Path for a hash, or null if the hash is not exactly 64 lowercase hex chars (no path tricks possible). */
    public static Path pathOf(String hash) {
        if (hash == null || !HASH.matcher(hash).matches()) return null;
        return AUDIO.resolve(hash + ".ogg");
    }

    public static synchronized ImportResult importFile(String name, Path source) {
        name = name.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) return new ImportResult("Name must be 1-32 chars of a-z, 0-9, _ or -", null);
        if (!clips.containsKey(name) && clips.size() >= MAX_CLIPS) return new ImportResult("Library full (" + MAX_CLIPS + " clips). Remove one first.", null);
        try {
            if (!Files.isRegularFile(source)) return new ImportResult("Not a file: " + source, null);
            long size = Files.size(source);
            if (size > OggValidator.MAX_BYTES) return new ImportResult("File too large (" + size / 1024 + " KiB, max " + OggValidator.MAX_BYTES / 1024 + " KiB)", null);
            byte[] data = Files.readAllBytes(source);
            OggValidator.Result v = OggValidator.validate(data);
            if (!v.ok()) return new ImportResult(v.error(), null);

            String hash = sha256(data);
            long total = data.length;
            for (var e : clips.entrySet()) if (!e.getKey().equals(name) && !e.getValue().hash.equals(hash)) total += e.getValue().bytes;
            if (total > MAX_TOTAL_BYTES) return new ImportResult("Library size limit reached (" + MAX_TOTAL_BYTES / 1024 / 1024 + " MiB)", null);

            Files.createDirectories(AUDIO);
            Path target = AUDIO.resolve(hash + ".ogg");
            if (!Files.exists(target)) {
                Path tmp = AUDIO.resolve(hash + ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Clip c = new Clip();
            c.hash = hash; c.durationMs = v.durationMs(); c.bytes = data.length;
            Clip old = clips.put(name, c);
            if (old != null && !old.hash.equals(hash)) deleteIfUnused(old.hash);
            save();
            return new ImportResult(null, c);
        } catch (IOException | RuntimeException e) {
            return new ImportResult("Import failed: " + e.getMessage(), null);
        }
    }

    public static synchronized boolean remove(String name) {
        Clip c = clips.remove(name);
        if (c == null) return false;
        deleteIfUnused(c.hash);
        save();
        return true;
    }

    private static void deleteIfUnused(String hash) {
        for (Clip c : clips.values()) if (c.hash.equals(hash)) return;
        try { Files.deleteIfExists(AUDIO.resolve(hash + ".ogg")); } catch (IOException ignored) {}
    }

    static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
