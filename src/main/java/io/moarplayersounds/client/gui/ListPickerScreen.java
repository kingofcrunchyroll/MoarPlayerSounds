package io.moarplayersounds.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Searchable, paged list of choices. Uses a fixed pool of buttons that are re-labelled on
 * search/page changes, so no widgets are added or removed while the user types.
 */
public final class ListPickerScreen extends Screen {
    public record Option(String label, boolean enabled, Runnable onPick) {}

    private static final int COLS = 3, ROWS = 7, PER_PAGE = COLS * ROWS;

    private final Screen parent;
    private final Supplier<List<Option>> supplier;
    private List<Option> all = List.of();
    private final List<Option> filtered = new ArrayList<>();
    private final List<ButtonWidget> pool = new ArrayList<>();
    private String filter = "";
    private int page;
    private ButtonWidget prev, next;

    public ListPickerScreen(Screen parent, Text title, Supplier<List<Option>> supplier) {
        super(title);
        this.parent = parent;
        this.supplier = supplier;
    }

    @Override
    protected void init() {
        all = supplier.get();
        pool.clear();

        TextFieldWidget search = new TextFieldWidget(textRenderer, width / 2 - 100, 24, 200, 18, Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search..."));
        search.setText(filter);
        search.setChangedListener(s -> { filter = s.toLowerCase(Locale.ROOT).trim(); page = 0; refresh(); });
        addDrawableChild(search);

        int panelW = Math.min(width - 16, 420);
        int left = (width - panelW) / 2;
        int gap = 4;
        int bw = (panelW - gap * (COLS - 1)) / COLS;
        for (int i = 0; i < PER_PAGE; i++) {
            final int slotIndex = i;
            int x = left + (i % COLS) * (bw + gap);
            int y = 50 + (i / COLS) * 22;
            ButtonWidget b = ButtonWidget.builder(Text.empty(), btn -> pick(slotIndex)).dimensions(x, y, bw, 20).build();
            pool.add(addDrawableChild(b));
        }

        int navY = 50 + ROWS * 22 + 4;
        prev = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page--; refresh(); })
                .dimensions(width / 2 - 100, navY, 40, 20).build());
        next = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page++; refresh(); })
                .dimensions(width / 2 + 60, navY, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(width / 2 - 50, navY + 24, 100, 20).build());
        refresh();
    }

    private void refresh() {
        filtered.clear();
        for (Option o : all) if (filter.isEmpty() || o.label().toLowerCase(Locale.ROOT).contains(filter)) filtered.add(o);
        int pages = Math.max(1, (filtered.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < pool.size(); i++) {
            ButtonWidget b = pool.get(i);
            int idx = page * PER_PAGE + i;
            if (idx < filtered.size()) {
                Option o = filtered.get(idx);
                b.visible = true;
                b.active = o.enabled();
                b.setMessage(Text.literal(textRenderer.trimToWidth(o.label(), b.getWidth() - 8)));
            } else {
                b.visible = false;
            }
        }
        prev.active = page > 0;
        next.active = page < pages - 1;
    }

    private void pick(int i) {
        int idx = page * PER_PAGE + i;
        if (idx >= filtered.size()) return;
        Option o = filtered.get(idx);
        if (!o.enabled()) return;
        o.onPick().run();
        close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        int pages = Math.max(1, (filtered.size() + PER_PAGE - 1) / PER_PAGE);
        int navY = 50 + ROWS * 22 + 4;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal((page + 1) + " / " + pages + "  (" + filtered.size() + ")"),
                width / 2, navY + 6, 0xAAAAAA);
    }

    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
