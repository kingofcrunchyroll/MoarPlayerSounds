package io.moarplayersounds.client.gui;

import io.moarplayersounds.audio.AudioLibrary;
import io.moarplayersounds.profile.MobSoundIndex;
import io.moarplayersounds.profile.ProfileManager;
import io.moarplayersounds.profile.Resolved;
import io.moarplayersounds.profile.SlotConfig;
import io.moarplayersounds.profile.SoundSlot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main MoarPlayerSounds screen: one row per slot with source picker, volume, pitch, jitter and preview. */
public final class ConfigScreen extends Screen {
    private final Screen parent;
    private final List<SoundSlot> rows = new ArrayList<>();
    private Text status = Text.empty();

    public ConfigScreen(Screen parent) {
        super(Text.literal("MoarPlayerSounds"));
        this.parent = parent;
    }

    private Text enabledLabel() {
        return Text.literal("Mod: " + (ProfileManager.isEnabled() ? "ON" : "OFF"));
    }

    @Override
    protected void init() {
        rows.clear();
        int panelW = Math.min(width - 16, 420);
        int left = (width - panelW) / 2;

        int gap = 4, topW = (panelW - gap * 3) / 4;
        addDrawableChild(ButtonWidget.builder(enabledLabel(), b -> {
            ProfileManager.setEnabled(!ProfileManager.isEnabled());
            b.setMessage(enabledLabel());
        }).dimensions(left, 22, topW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Mob preset..."), b ->
                client.setScreen(new ListPickerScreen(this, Text.literal("Apply a mob preset to all slots"), this::presetOptions)))
                .dimensions(left + (topW + gap), 22, topW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clips..."), b -> client.setScreen(new ClipsScreen(this)))
                .dimensions(left + 2 * (topW + gap), 22, topW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset all"), b -> {
            ProfileManager.reset();
            status = Text.literal("All slots reset to vanilla.");
            client.setScreen(this);   // re-init to refresh rows
        }).dimensions(left + 3 * (topW + gap), 22, topW, 20).build());

        int labelW = 56, srcW = 100, playW = 20, g = 3;
        int sliderW = (panelW - labelW - srcW - playW - g * 5) / 3;
        int y = 52;
        for (SoundSlot slot : SoundSlot.values()) {
            rows.add(slot);
            SlotConfig cfg = ProfileManager.get(slot);
            int x = left + labelW + g;

            addDrawableChild(ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(shortSource(cfg.source), srcW - 8)), b ->
                    client.setScreen(new ListPickerScreen(this, Text.literal("Sound for " + slot.key()), () -> optionsFor(slot))))
                    .dimensions(x, y, srcW, 20).build());
            x += srcW + g;
            for (int kind = 0; kind < 3; kind++) {
                Tune t = new Tune(x, y, sliderW, 20, slot, kind);
                t.active = !cfg.isVanilla();
                addDrawableChild(t);
                x += sliderW + g;
            }
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> preview(slot)).dimensions(x, y, playW, 20).build());
            y += 24;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 - 50, y + 8, 100, 20).build());
    }

    private static String shortSource(String s) {
        if (s.startsWith("mob:")) return s.substring(4);
        if (s.startsWith("custom:")) return "[clip] " + s.substring(7);
        return "vanilla";
    }

    private List<ListPickerScreen.Option> presetOptions() {
        List<ListPickerScreen.Option> l = new ArrayList<>();
        for (String mob : MobSoundIndex.mobNames())
            l.add(new ListPickerScreen.Option(mob, true, () -> {
                ProfileManager.applyPreset(mob);
                status = Text.literal("Preset '" + mob + "' applied.");
            }));
        return l;
    }

    private List<ListPickerScreen.Option> optionsFor(SoundSlot slot) {
        List<ListPickerScreen.Option> l = new ArrayList<>();
        l.add(new ListPickerScreen.Option("vanilla", true, () -> ProfileManager.setSource(slot, "vanilla")));
        for (String clip : AudioLibrary.names())
            l.add(new ListPickerScreen.Option("[clip] " + clip, true, () -> ProfileManager.setSource(slot, "custom:" + clip)));
        for (String mob : MobSoundIndex.mobNames()) {
            boolean usable = MobSoundIndex.pick(mob, slot) != null;
            l.add(new ListPickerScreen.Option(usable ? mob : mob + " (none)", usable, () -> ProfileManager.setSource(slot, "mob:" + mob)));
        }
        return l;
    }

    private void preview(SoundSlot slot) {
        Resolved r = ProfileManager.resolve(slot);
        if (r == null) { status = Text.literal(slot.key() + " uses the vanilla sound (or is disabled)."); return; }
        status = Text.empty();
        client.getSoundManager().play(PositionedSoundInstance.master(r.event(), r.cfg().pitch, Math.min(1.0F, r.cfg().volume)));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        int panelW = Math.min(width - 16, 420);
        int left = (width - panelW) / 2;
        int y = 52;
        for (SoundSlot slot : rows) {
            context.drawTextWithShadow(textRenderer, Text.literal(slot.key()), left, y + 6, 0xFFFFFF);
            y += 24;
        }
        if (!status.getString().isEmpty())
            context.drawCenteredTextWithShadow(textRenderer, status, width / 2, y + 34, 0xFFDD55);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Sliders: volume / pitch / extra pitch randomness. Vanilla slots keep Minecraft's own sound."),
                width / 2, y + 48, 0x888888);
    }

    @Override public void removed() { ProfileManager.save(); }   // flush slider changes
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }

    /** kind: 0 = volume (0-200%), 1 = pitch (50-200%), 2 = jitter (0-50%). Changes are kept in memory until the screen closes. */
    private static final class Tune extends SliderWidget {
        private final SoundSlot slot;
        private final int kind;

        Tune(int x, int y, int w, int h, SoundSlot slot, int kind) {
            super(x, y, w, h, Text.empty(), toSlider(kind, ProfileManager.get(slot)));
            this.slot = slot;
            this.kind = kind;
            updateMessage();
        }

        private static double toSlider(int kind, SlotConfig c) {
            return switch (kind) {
                case 0 -> c.volume / 2.0;
                case 1 -> (c.pitch - 0.5) / 1.5;
                default -> c.jitter / 0.5;
            };
        }

        private float actual() {
            float raw = switch (kind) {
                case 0 -> (float) (value * 2.0);
                case 1 -> (float) (0.5 + value * 1.5);
                default -> (float) (value * 0.5);
            };
            return Math.round(raw * 20F) / 20F;       // 5% steps
        }

        @Override
        protected void updateMessage() {
            String name = kind == 0 ? "Vol" : kind == 1 ? "Pitch" : "Rand";
            setMessage(Text.literal(String.format(Locale.ROOT, "%s %d%%", name, Math.round(actual() * 100F))));
        }

        @Override
        protected void applyValue() {
            float v = actual();
            ProfileManager.mutateDeferred(slot, c -> {
                switch (kind) {
                    case 0 -> c.volume = v;
                    case 1 -> c.pitch = v;
                    default -> c.jitter = v;
                }
            });
        }
    }
}
