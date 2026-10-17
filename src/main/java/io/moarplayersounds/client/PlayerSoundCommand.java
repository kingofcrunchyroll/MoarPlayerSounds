package io.moarplayersounds.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.moarplayersounds.audio.AudioLibrary;
import io.moarplayersounds.profile.MobSoundIndex;
import io.moarplayersounds.profile.ProfileManager;
import io.moarplayersounds.profile.SlotConfig;
import io.moarplayersounds.profile.SoundSlot;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;

import com.mojang.brigadier.arguments.BoolArgumentType;
import io.moarplayersounds.remote.RemoteProfiles;
import io.moarplayersounds.remote.RemoteSounds;
import net.minecraft.client.network.PlayerListEntry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Control surface until the GUI arrives in Phase 6: /playersound ... */
public final class PlayerSoundCommand {
    private PlayerSoundCommand() {}

    private static final SuggestionProvider<FabricClientCommandSource> MOBS =
            (ctx, b) -> CommandSource.suggestMatching(MobSoundIndex.mobNames(), b);
    private static final SuggestionProvider<FabricClientCommandSource> SLOTS =
            (ctx, b) -> CommandSource.suggestMatching(slotKeys(), b);
    private static final SuggestionProvider<FabricClientCommandSource> CLIPS =
            (ctx, b) -> CommandSource.suggestMatching(AudioLibrary.names(), b);
    private static final SuggestionProvider<FabricClientCommandSource> MOBS_OR_VANILLA =
            (ctx, b) -> {
                List<String> l = new ArrayList<>(MobSoundIndex.mobNames());
                l.add("vanilla");
                l.add("custom");
                return CommandSource.suggestMatching(l, b);
            };

    private static final SuggestionProvider<FabricClientCommandSource> PLAYERS = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        var h = MinecraftClient.getInstance().getNetworkHandler();
        if (h != null) for (PlayerListEntry e : h.getPlayerList()) names.add(e.getProfile().getName());
        return CommandSource.suggestMatching(names, b);
    };

    private static PlayerListEntry findPlayer(String name) {
        var h = MinecraftClient.getInstance().getNetworkHandler();
        return h == null ? null : h.getPlayerListEntry(name);
    }

    private static List<String> slotKeys() {
        List<String> l = new ArrayList<>();
        for (SoundSlot s : SoundSlot.values()) l.add(s.key());
        return l;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, String> slotArg() {
        return ClientCommandManager.argument("slot", StringArgumentType.word()).suggests(SLOTS);
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("playersound")
            .then(ClientCommandManager.literal("status").executes(c -> {
                StringBuilder sb = new StringBuilder("MoarPlayerSounds " + (ProfileManager.isEnabled() ? "enabled" : "disabled"));
                for (SoundSlot s : SoundSlot.values()) {
                    SlotConfig cfg = ProfileManager.get(s);
                    sb.append("\n  ").append(s.key()).append(": ").append(cfg.source);
                    if (!cfg.isNeutral()) sb.append(String.format(Locale.ROOT, "  (volume %d%%, pitch %d%%, jitter %d%%)",
                            Math.round(cfg.volume * 100), Math.round(cfg.pitch * 100), Math.round(cfg.jitter * 100)));
                }
                c.getSource().sendFeedback(Text.literal(sb.toString()));
                return 1;
            }))
            .then(ClientCommandManager.literal("remote")
                .then(ClientCommandManager.literal("enable").executes(c -> { RemoteProfiles.setEnabled(true); return say(c.getSource(), "Remote players: ON (profiles you assigned are used for those players' sounds)."); }))
                .then(ClientCommandManager.literal("disable").executes(c -> { RemoteProfiles.setEnabled(false); return say(c.getSource(), "Remote players: OFF (vanilla sounds)."); }))
                .then(ClientCommandManager.literal("debug").then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
                    boolean on = BoolArgumentType.getBool(c, "on");
                    RemoteSounds.setDebug(on);
                    return say(c.getSource(), "Remote debug logging " + (on ? "ON: watch latest.log for [remote] lines." : "OFF."));
                })))
                .then(ClientCommandManager.literal("status").executes(c -> {
                    StringBuilder sb = new StringBuilder("Remote players: " + (RemoteProfiles.isEnabled() ? "ON" : "OFF")
                            + ", debug " + (RemoteSounds.isDebug() ? "ON" : "OFF"));
                    for (String line : RemoteProfiles.describe()) sb.append("\n  ").append(line);
                    c.getSource().sendFeedback(Text.literal(sb.toString()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("set")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .then(ClientCommandManager.argument("mob", StringArgumentType.word()).suggests(MOBS).executes(c -> {
                            PlayerListEntry e = findPlayer(StringArgumentType.getString(c, "player"));
                            String mob = StringArgumentType.getString(c, "mob").toLowerCase(Locale.ROOT);
                            if (e == null) return say(c.getSource(), "That player is not on this server right now.");
                            if (!MobSoundIndex.hasMob(mob)) return say(c.getSource(), "Unknown mob '" + mob + "'. Try /playersound list");
                            List<String> set = RemoteProfiles.setPreset(e.getProfile().getId(), e.getProfile().getName(), mob);
                            return say(c.getSource(), e.getProfile().getName() + " now sounds like '" + mob + "' for you: " + String.join(", ", set)
                                    + (RemoteProfiles.isEnabled() ? "" : "\n(Remote players is OFF: /playersound remote enable)"));
                        }))))
                .then(ClientCommandManager.literal("clear")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(c -> {
                        String name = StringArgumentType.getString(c, "player");
                        PlayerListEntry e = findPlayer(name);
                        boolean had = (e != null && RemoteProfiles.clear(e.getProfile().getId())) || RemoteProfiles.clearByName(name);
                        return say(c.getSource(), had ? "Cleared " + name + "." : "No profile for " + name + ".");
                    })))
                .then(ClientCommandManager.literal("reset").executes(c -> { RemoteProfiles.clearAll(); return say(c.getSource(), "All remote profiles cleared."); })))
            .then(ClientCommandManager.literal("gui").executes(c -> { MoarPlayerSoundsClient.requestOpenConfig(); return 1; }))
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
                .then(slotArg()
                    .then(ClientCommandManager.literal("custom")
                        .then(ClientCommandManager.argument("clip", StringArgumentType.word()).suggests(CLIPS).executes(c -> {
                            SoundSlot slot = SoundSlot.byKey(StringArgumentType.getString(c, "slot")).orElse(null);
                            String clip = StringArgumentType.getString(c, "clip").toLowerCase(Locale.ROOT);
                            if (slot == null) return say(c.getSource(), "Unknown slot. Slots: " + String.join(", ", slotKeys()));
                            if (AudioLibrary.get(clip) == null) return say(c.getSource(), "No imported clip '" + clip + "'. See /playersound audio list");
                            ProfileManager.setSource(slot, "custom:" + clip);
                            return say(c.getSource(), slot.key() + " -> custom clip " + clip);
                        })))
                    .then(ClientCommandManager.argument("source", StringArgumentType.word()).suggests(MOBS_OR_VANILLA).executes(c -> {
                        SoundSlot slot = SoundSlot.byKey(StringArgumentType.getString(c, "slot")).orElse(null);
                        String src = StringArgumentType.getString(c, "source").toLowerCase(Locale.ROOT);
                        if (slot == null) return say(c.getSource(), "Unknown slot. Slots: " + String.join(", ", slotKeys()));
                        if (src.equals("vanilla")) { ProfileManager.setSource(slot, "vanilla"); return say(c.getSource(), slot.key() + " -> vanilla"); }
                        if (src.equals("custom")) return say(c.getSource(), "Usage: /playersound slot " + slot.key() + " custom <clip>");
                        if (!MobSoundIndex.hasMob(src)) return say(c.getSource(), "Unknown mob '" + src + "'.");
                        if (MobSoundIndex.pick(src, slot) == null) return say(c.getSource(), src + " has no usable sound for " + slot.key() + "; vanilla would be kept.");
                        ProfileManager.setSource(slot, "mob:" + src);
                        return say(c.getSource(), slot.key() + " -> " + src);
                    }))))
            .then(ClientCommandManager.literal("volume").then(slotArg()
                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 200)).executes(c ->
                    tune(c.getSource(), StringArgumentType.getString(c, "slot"), "volume", IntegerArgumentType.getInteger(c, "percent"))))))
            .then(ClientCommandManager.literal("pitch").then(slotArg()
                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(50, 200)).executes(c ->
                    tune(c.getSource(), StringArgumentType.getString(c, "slot"), "pitch", IntegerArgumentType.getInteger(c, "percent"))))))
            .then(ClientCommandManager.literal("jitter").then(slotArg()
                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 50)).executes(c ->
                    tune(c.getSource(), StringArgumentType.getString(c, "slot"), "jitter", IntegerArgumentType.getInteger(c, "percent"))))))
            .then(ClientCommandManager.literal("import")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .then(ClientCommandManager.argument("path", StringArgumentType.greedyString()).executes(c -> {
                        String name = StringArgumentType.getString(c, "name");
                        String raw = StringArgumentType.getString(c, "path").trim();
                        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw = raw.substring(1, raw.length() - 1);
                        Path path;
                        try { path = Paths.get(raw); } catch (RuntimeException e) { return say(c.getSource(), "Invalid path."); }
                        AudioLibrary.ImportResult r = AudioLibrary.importFile(name, path);
                        if (r.error() != null) return say(c.getSource(), "Import rejected: " + r.error());
                        return say(c.getSource(), String.format(Locale.ROOT, "Imported '%s' (%.2f s, %d KiB). Use: /playersound slot <slot> custom %s",
                                name.toLowerCase(Locale.ROOT), r.clip().durationMs / 1000.0, r.clip().bytes / 1024, name.toLowerCase(Locale.ROOT)));
                    }))))
            .then(ClientCommandManager.literal("audio")
                .then(ClientCommandManager.literal("list").executes(c -> {
                    var all = AudioLibrary.all();
                    if (all.isEmpty()) return say(c.getSource(), "No imported clips. Use /playersound import <name> <path-to-mono.ogg>");
                    StringBuilder sb = new StringBuilder("Imported clips:");
                    all.forEach((n, clip) -> sb.append(String.format(Locale.ROOT, "\n  %s  (%.2f s, %d KiB)", n, clip.durationMs / 1000.0, clip.bytes / 1024)));
                    c.getSource().sendFeedback(Text.literal(sb.toString()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("remove")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word()).suggests(CLIPS).executes(c -> {
                        String n = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
                        // Slots still pointing at the clip fall back to vanilla automatically (resolve() returns null).
                        return say(c.getSource(), AudioLibrary.remove(n) ? "Removed '" + n + "'." : "No clip '" + n + "'.");
                    }))))
            .then(ClientCommandManager.literal("preview")
                .then(slotArg().executes(c -> {
                    SoundSlot slot = SoundSlot.byKey(StringArgumentType.getString(c, "slot")).orElse(null);
                    var player = MinecraftClient.getInstance().player;
                    if (slot == null || player == null) return say(c.getSource(), "Unknown slot or no player.");
                    return SyntheticSoundDriver.play(player, slot) ? 1 : say(c.getSource(), slot.key() + " is vanilla (or its clip failed to load); nothing to preview.");
                }))));
    }

    private static int tune(FabricClientCommandSource src, String slotKey, String what, int percent) {
        SoundSlot slot = SoundSlot.byKey(slotKey).orElse(null);
        if (slot == null) return say(src, "Unknown slot. Slots: " + String.join(", ", slotKeys()));
        float f = percent / 100.0F;
        ProfileManager.mutate(slot, cfg -> {
            switch (what) {
                case "volume" -> cfg.volume = f;
                case "pitch" -> cfg.pitch = f;
                default -> cfg.jitter = f;
            }
        });
        return say(src, slot.key() + " " + what + " = " + percent + "%");
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
