package io.moarplayersounds.profile;

import java.util.regex.Pattern;

/** Settings for one sound slot. Source is "vanilla", "mob:<name>" or "custom:<clip>". */
public final class SlotConfig {
    private static final Pattern SOURCE = Pattern.compile("vanilla|mob:[a-z0-9_]{1,40}|custom:[a-z0-9_-]{1,32}");

    public String source = "vanilla";
    public float volume = 1.0F;   // 0..2 (vanilla gain is capped at 1.0; above 1 only extends audible range)
    public float pitch = 1.0F;    // 0.5..2 multiplier
    public float jitter = 0.0F;   // 0..0.5 extra random pitch spread

    public SlotConfig() {}
    public SlotConfig(String source) { this.source = source; sanitize(); }

    public SlotConfig sanitize() {
        if (source == null || !SOURCE.matcher(source).matches()) source = "vanilla";
        volume = clamp(volume, 0F, 2F, 1F);
        pitch = clamp(pitch, 0.5F, 2F, 1F);
        jitter = clamp(jitter, 0F, 0.5F, 0F);
        return this;
    }

    public boolean isNeutral() { return volume == 1F && pitch == 1F && jitter == 0F; }
    public boolean isVanilla() { return "vanilla".equals(source); }

    public SlotConfig copy() {
        SlotConfig c = new SlotConfig();
        c.source = source; c.volume = volume; c.pitch = pitch; c.jitter = jitter;
        return c;
    }

    private static float clamp(float v, float lo, float hi, float dflt) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return dflt;
        return Math.max(lo, Math.min(hi, v));
    }
}
