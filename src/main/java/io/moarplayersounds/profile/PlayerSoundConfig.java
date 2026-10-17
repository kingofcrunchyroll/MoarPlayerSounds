package io.moarplayersounds.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/** On-disk shape of config/moarplayersounds.json (version 2). */
public final class PlayerSoundConfig {
    public int version = 2;
    public boolean enabled = true;
    public Map<String, SlotConfig> slots = new LinkedHashMap<>();
}
