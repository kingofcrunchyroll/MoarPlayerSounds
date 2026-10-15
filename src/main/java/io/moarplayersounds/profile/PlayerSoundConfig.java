package io.moarplayersounds.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/** On-disk shape of config/moarplayersounds.json. Values: "vanilla" or "mob:<name>". */
public final class PlayerSoundConfig {
    public int version = 1;
    public boolean enabled = true;
    public Map<String, String> slots = new LinkedHashMap<>();
}
