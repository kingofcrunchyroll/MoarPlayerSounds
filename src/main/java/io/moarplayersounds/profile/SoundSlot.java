package io.moarplayersounds.profile;

import java.util.Locale;
import java.util.Optional;

/**
 * Player sound events PlayerSound can replace.
 * AMBIENT and JUMP do not exist as vanilla player sounds; they are synthesised by the mod.
 */
public enum SoundSlot {
    AMBIENT(false), HURT(true), DEATH(true), FALL(true), JUMP(false), SWIM(true), SPLASH(true);

    /** true when vanilla already plays a player sound for this slot (so we replace it rather than add one). */
    public final boolean existsInVanilla;

    SoundSlot(boolean existsInVanilla) { this.existsInVanilla = existsInVanilla; }

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<SoundSlot> byKey(String key) {
        if (key == null) return Optional.empty();
        for (SoundSlot s : values()) if (s.key().equals(key.toLowerCase(Locale.ROOT))) return Optional.of(s);
        return Optional.empty();
    }

    /** Classify the part of a sound id after "entity.<mob>." into a slot, or null. */
    public static SoundSlot classify(String rest) {
        if (rest.startsWith("ambient") || rest.endsWith("ambient") || rest.startsWith("idle")) return AMBIENT;
        if (rest.startsWith("hurt") || rest.endsWith("hurt")) return HURT;
        if (rest.startsWith("death") || rest.endsWith("death")) return DEATH;
        if (rest.equals("jump")) return JUMP;
        if (rest.equals("swim")) return SWIM;
        if (rest.equals("splash")) return SPLASH;
        if (rest.equals("fall") || rest.endsWith("_fall")) return FALL;
        return null;
    }
}
