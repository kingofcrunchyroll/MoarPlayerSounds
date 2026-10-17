package io.moarplayersounds.audio;

import io.moarplayersounds.MoarPlayerSoundsMod;
import io.moarplayersounds.mixin.SoundManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.StaticSound;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays imported clips through the normal vanilla pipeline: each clip gets a runtime SoundEvent
 * id "moarplayersounds:custom/<sha256>" (so position, attenuation, category and volume sliders all work),
 * a WeightedSoundSet entry injected into the SoundManager, and a SoundLoader hook that serves the
 * bytes from the library instead of from a resource pack.
 */
public final class CustomSounds {
    public static final String NAMESPACE = "moarplayersounds";
    private static final Pattern LOCATION = Pattern.compile("^sounds/custom/([0-9a-f]{64})\\.ogg$");
    private static final int MAX_PCM_BYTES = 1024 * 1024;
    private static final Map<String, SoundEvent> EVENTS = new ConcurrentHashMap<>();
    private static boolean warned;

    private CustomSounds() {}

    /** SoundEvent for a clip hash, registered with the sound manager; null on any failure (caller keeps vanilla). */
    public static SoundEvent eventFor(String hash) {
        try {
            Path p = AudioLibrary.pathOf(hash);
            if (p == null || !Files.isRegularFile(p)) return null;
            Identifier id = Identifier.of(NAMESPACE, "custom/" + hash);
            SoundEvent ev = EVENTS.computeIfAbsent(hash, h -> SoundEvent.of(id));
            ensureRegistered(id);
            return ev;
        } catch (Throwable t) {
            if (!warned) { warned = true; MoarPlayerSoundsMod.LOGGER.warn("Custom sound setup failed; using vanilla", t); }
            return null;
        }
    }

    /** Re-checked on every use: a resource reload (F3+T) rebuilds the sound map and drops our entries. */
    private static void ensureRegistered(Identifier id) {
        SoundManager sm = MinecraftClient.getInstance().getSoundManager();
        Map<Identifier, WeightedSoundSet> sounds = ((SoundManagerAccessor) (Object) sm).moarplayersounds$getSounds();
        if (sounds.containsKey(id)) return;
        Sound sound = new Sound(id, random -> 1.0F, random -> 1.0F, 1,
                Sound.RegistrationType.getByName("file"), false, false, 16);
        WeightedSoundSet set = new WeightedSoundSet(id, null);
        set.add(sound);
        sounds.put(id, set);
    }

    /** Called from the SoundLoader mixin for ids in our namespace. */
    public static CompletableFuture<StaticSound> load(Identifier location) {
        Matcher m = LOCATION.matcher(location.getPath());
        Path file = m.matches() ? AudioLibrary.pathOf(m.group(1)) : null;
        if (file == null) return CompletableFuture.failedFuture(new IOException("Bad custom sound location " + location));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return decode(file);
            } catch (IOException e) {
                MoarPlayerSoundsMod.LOGGER.warn("Could not decode custom clip {}: {}", file.getFileName(), e.toString());
                throw new CompletionException(e);
            }
        });
    }

    private static StaticSound decode(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file));
             OggAudioStream ogg = new OggAudioStream(in)) {
            AudioFormat format = ogg.getFormat();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            for (int i = 0; i < 4096; i++) {                        // hard iteration cap
                ByteBuffer chunk = ogg.read(8192);
                if (chunk == null || !chunk.hasRemaining()) break;
                byte[] tmp = new byte[chunk.remaining()];
                chunk.get(tmp);
                pcm.write(tmp);
                if (pcm.size() > MAX_PCM_BYTES) throw new IOException("decoded audio too large");
            }
            if (pcm.size() == 0) throw new IOException("decoded no audio");
            ByteBuffer direct = ByteBuffer.allocateDirect(pcm.size());   // OpenAL needs a direct buffer
            direct.put(pcm.toByteArray()).flip();
            return new StaticSound(direct, format);
        }
    }
}
