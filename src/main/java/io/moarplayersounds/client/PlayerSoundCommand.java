package io.moarplayersounds.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.moarplayersounds.profile.MobSoundIndex;
import io.moarplayersounds.profile.ProfileManager;
import io.moarplayersounds.profile.SoundSlot;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Phase 1 control surface (the GUI arrives in Phase 6): /playersound ... */
public final class PlayerSoundCommand {
    private PlayerSoundCommand() {}

    private static final SuggestionProvider<FabricClientCommandSource> MOBS =
            (ctx, b) -> CommandSource.suggestMatching(MobSoundIndex.mobNames(), b);
    private static final SuggestionProvider<FabricClientCommandSource> SLOTS =
            (ctx, b) -> CommandSource.suggestMatching(slotKeys(), b);
    private static final SuggestionProvider<FabricClientCommandSource> MOBS_OR_VANILLA =
            (ctx, b) -> {
                List<String> l = new ArrayList<>(MobSoundIndex.mobNames());
                l.add("vanilla");
                return CommandSource.suggestMatching(l, b);
            };

    private static List<String> slotKeys() {
        List<String> l = new ArrayList<>();
        for (SoundSlot s : SoundSlot.values()) l.add(s.key());
        return l;
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("playersound")
            .then(ClientCommandManager.literal("status").executes(c -> {
                StringBuilder sb = new StringBuilder("PlayerSound " + (ProfileManager.isEnabled() ? "enabled" : "disabled"));
                for (SoundSlot s : SoundSlot.values()) sb.append("\n  ").append(s.key()).append(": ").append(ProfileManager.sourceOf(s));
                c.getSource().sendFeedback(Text.literal(sb.toString()));
                return 1;
            }))
            .then(ClientCommandManager.literal("enable").executes(c -> { ProfileManager.setEnabled(true); return say(c.getSource(), "Enabled."); }))
            .then(ClientCommandManager.literal("disable").executes(c -> { ProfileManager.setEnabled(false); return say(c.getSource(), "Disabled (vanilla sounds)."); }))
            .then(ClientCommandManager.literal("reset").executes(c -> { ProfileManager.reset(); return say(c.getSource(), "Profile reset to vanilla."); }))
            .then(ClientCommandManager.literal("list").executes(c -> {
                c.getSource().sendFeedback(Text.literal("Mobs: " + String.join(", ", MobSoundIndex.mobNames())));
                return 1;
            }))
            .then(ClientCommandManager.literal("preset")
                .then(ClientCommandManager.argument("mob", StringArgumentType.word()).suggests(MOBS).executes(c -> {
                    String mob = StringArgumentType.getString(c, "mob").toLowerCase(Locale.ROOT);
                    if (!MobSoundIndex.hasMob(mob)) return say(c.getSource(), "Unknown mob '" + mob + "'. Try /playersound list");
                    ProfileManager.applyPreset(mob);
                    return say(c.getSource(), "Preset '" + mob + "' applied to: " + describe(mob));
                })))
            .then(ClientCommandManager.literal("slot")
                .then(ClientCommandManager.argument("slot", StringArgumentType.word()).suggests(SLOTS)
                    .then(ClientCommandManager.argument("source", StringArgumentType.word()).suggests(MOBS_OR_VANILLA).executes(c -> {
                        SoundSlot slot = SoundSlot.byKey(StringArgumentType.getString(c, "slot")).orElse(null);
                        String src = StringArgumentType.getString(c, "source").toLowerCase(Locale.ROOT);
                        if (slot == null) return say(c.getSource(), "Unknown slot. Slots: " + String.join(", ", slotKeys()));
                        if (src.equals("vanilla")) { ProfileManager.setSlot(slot, null); return say(c.getSource(), slot.key() + " -> vanilla"); }
                        if (!MobSoundIndex.hasMob(src)) return say(c.getSource(), "Unknown mob '" + src + "'.");
                        if (MobSoundIndex.pick(src, slot) == null) return say(c.getSource(), src + " has no usable sound for " + slot.key() + "; vanilla would be kept.");
                        ProfileManager.setSlot(slot, "mob:" + src);
                        return say(c.getSource(), slot.key() + " -> " + src);
                    }))))
            .then(ClientCommandManager.literal("preview")
                .then(ClientCommandManager.argument("slot", StringArgumentType.word()).suggests(SLOTS).executes(c -> {
                    SoundSlot slot = SoundSlot.byKey(StringArgumentType.getString(c, "slot")).orElse(null);
                    var player = net.minecraft.client.MinecraftClient.getInstance().player;
                    if (slot == null || player == null) return say(c.getSource(), "Unknown slot or no player.");
                    return SyntheticSoundDriver.play(player, slot) ? 1 : say(c.getSource(), slot.key() + " is vanilla; nothing to preview.");
                }))));
    }

    private static String describe(String mob) {
        List<String> l = new ArrayList<>();
        for (SoundSlot s : SoundSlot.values()) if (MobSoundIndex.pick(mob, s) != null) l.add(s.key());
        return String.join(", ", l);
    }

    private static int say(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Text.literal(msg));
        return 1;
    }
}
