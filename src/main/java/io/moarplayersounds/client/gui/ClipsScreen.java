package io.moarplayersounds.client.gui;

import io.moarplayersounds.audio.AudioLibrary;
import io.moarplayersounds.audio.CustomSounds;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Manage imported clips. Drag a mono .ogg file onto the game window to import it. */
public final class ClipsScreen extends Screen {
    private static final int ROWS = 8;

    private final Screen parent;
    private final List<String> names = new ArrayList<>();
    private final List<ButtonWidget> playButtons = new ArrayList<>();
    private final List<ButtonWidget> removeButtons = new ArrayList<>();
    private ButtonWidget prev, next;
    private int page;
    private Text status = Text.empty();

    public ClipsScreen(Screen parent) {
        super(Text.literal("Imported clips"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        playButtons.clear();
        removeButtons.clear();
        int panelW = Math.min(width - 16, 420);
        int left = (width - panelW) / 2;
        for (int i = 0; i < ROWS; i++) {
            final int row = i;
            int y = 40 + i * 22;
            playButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> play(row))
                    .dimensions(left + panelW - 88, y, 40, 20).build()));
            removeButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal("Remove"), b -> remove(row))
                    .dimensions(left + panelW - 44, y, 44, 20).build()));
        }
        int navY = 40 + ROWS * 22 + 4;
        prev = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page--; refresh(); })
                .dimensions(width / 2 - 100, navY, 40, 20).build());
        next = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page++; refresh(); })
                .dimensions(width / 2 + 60, navY, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(width / 2 - 50, navY + 24, 100, 20).build());
        refresh();
    }

    private void refresh() {
        names.clear();
        names.addAll(AudioLibrary.names());
        int pages = Math.max(1, (names.size() + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < ROWS; i++) {
            boolean has = page * ROWS + i < names.size();
            playButtons.get(i).visible = has;
            removeButtons.get(i).visible = has;
        }
        prev.active = page > 0;
        next.active = page < pages - 1;
    }

    private String nameAt(int row) {
        int idx = page * ROWS + row;
        return idx < names.size() ? names.get(idx) : null;
    }

    private void play(int row) {
        String n = nameAt(row);
        AudioLibrary.Clip clip = n == null ? null : AudioLibrary.get(n);
        if (clip == null) return;
        SoundEvent ev = CustomSounds.eventFor(clip.hash);
        if (ev == null) { status = Text.literal("Could not load '" + n + "' (see log)."); return; }
        client.getSoundManager().play(PositionedSoundInstance.master(ev, 1.0F, 1.0F));
    }

    private void remove(int row) {
        String n = nameAt(row);
        if (n == null) return;
        AudioLibrary.remove(n);
        status = Text.literal("Removed '" + n + "'. Slots using it now play the vanilla sound.");
        refresh();
    }

    /** Vanilla drag-and-drop hook: files dropped onto the window while this screen is open. */
    @Override
    public void filesDragged(List<Path> paths) {
        int ok = 0;
        String lastError = null;
        for (Path p : paths) {
            String file = p.getFileName() == null ? "" : p.getFileName().toString();
            if (!file.toLowerCase(Locale.ROOT).endsWith(".ogg")) { lastError = file + ": only .ogg files are supported"; continue; }
            AudioLibrary.ImportResult r = AudioLibrary.importFile(freeName(sanitize(file)), p);
            if (r.error() != null) lastError = file + ": " + r.error(); else ok++;
        }
        refresh();
        if (lastError != null) status = Text.literal((ok > 0 ? "Imported " + ok + ". " : "") + "Rejected - " + lastError);
        else status = Text.literal("Imported " + ok + " clip" + (ok == 1 ? "" : "s") + ".");
    }

    private static String sanitize(String file) {
        String base = file.substring(0, file.length() - 4).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (base.isEmpty()) base = "clip";
        return base.length() > 28 ? base.substring(0, 28) : base;
    }

    private static String freeName(String base) {
        if (AudioLibrary.get(base) == null) return base;
        for (int i = 2; i < 100; i++) if (AudioLibrary.get(base + "_" + i) == null) return base + "_" + i;
        return base;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Drag a mono .ogg file (max 5 s, 256 KiB) onto the game window to import it"), width / 2, 22, 0xAAAAAA);
        int panelW = Math.min(width - 16, 420);
        int left = (width - panelW) / 2;
        Map<String, AudioLibrary.Clip> all = AudioLibrary.all();
        for (int i = 0; i < ROWS; i++) {
            String n = nameAt(i);
            AudioLibrary.Clip c = n == null ? null : all.get(n);
            if (c == null) continue;
            String line = String.format(Locale.ROOT, "%s  (%.2f s, %d KiB)", n, c.durationMs / 1000.0, c.bytes / 1024);
            context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(line, panelW - 100)), left, 40 + i * 22 + 6, 0xFFFFFF);
        }
        int navY = 40 + ROWS * 22 + 4;
        int pages = Math.max(1, (names.size() + ROWS - 1) / ROWS);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal((page + 1) + " / " + pages), width / 2, navY + 6, 0xAAAAAA);
        if (!status.getString().isEmpty())
            context.drawCenteredTextWithShadow(textRenderer, status, width / 2, navY + 50, 0xFFDD55);
    }

    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
