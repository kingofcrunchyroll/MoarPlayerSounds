package io.moarplayersounds.profile;

import net.minecraft.sound.SoundEvent;

/** A slot resolved to a concrete sound event plus its volume/pitch settings. */
public record Resolved(SoundEvent event, SlotConfig cfg) {}
