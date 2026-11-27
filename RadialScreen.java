package ru.masedhelper;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Круговое меню модератора. ЛКМ — выбрать, ПКМ — назад, Esc — закрыть. */
public class RadialScreen extends Screen {

    private enum Page { MAIN, RULES, MORE, TARGET, PREV }

    private record Item(String label, String hint, int color, Runnable action) {}

    private Page page = Page.MAIN;
    private final List<Item> items = new ArrayList<>();
    private int hovered = -1;
    private EditBox nickBox;
    private float anim = 0f;

    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_DIM = 0xFFAAAAAA;
    private static final int C_MUTE = 0xFFFFC94A;
    private static final int C_BAN = 0xFFFF5A5A;
    private static final int C_ACT = 0xFF6FD3FF;
    private static final int C_NAV = 0xFFB98CFF;

    public RadialScreen() {
        super(Component.literal("MasedWorld Helper"));
    }

    @Override
    protected void init() {
        nickBox = new EditBox(font, width / 2 - 70, height - 32, 140, 18, Component.literal("Ник"));
        nickBox.setMaxLength(16);
        nickBox.setValue(MasedHelper.target);
        nickBox.setHint(Component.literal("ник нарушителя").withStyle(ChatFormatting.DARK_GRAY));
        nickBox.setResponder(s -> MasedHelper.target = s.strip());
        addRenderableWidget(nickBox);
        build();
    }

    @Override
    public void removed() {
        MasedHelper.rememberTarget(MasedHelper.target);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------- Пункты ----------------

    private static String humanTime(String t) {
        if (t == null || t.isBlank()) return "навсегда";
        return t.replace("mo", "мес").replace("m", "м").replace("h", "ч").replace("d", "д").replace("s", "с");
    }

    private static String typeName(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "MUTE" -> "Мут";
            case "BANIP" -> "IP-бан";
            default -> "Бан";
        };
    }

    private void go(Page p) {
        page = p;
        anim = 0f;
        build();
    }

    private boolean needTarget() {
        if (MasedHelper.target.isBlank()) {
            MasedHelper.say(Component.literal("[MH] Сначала укажи ник (поле внизу, «Цель» или наведи прицел на игрока)")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    private void run(String cmdTemplate) {
        if (cmdTemplate.contains("{nick}") && !needTarget()) return;
        Punisher.send(cmdTemplate.replace("{nick}", MasedHelper.target));
        onClose();
    }

    private void build() {
        items.clear();
        Config cfg = MasedHelper.cfg;
        switch (page) {
            case MAIN -> {
                items.add(new Item("Наказать ▸", "Выбрать правило 6.x / 7.x", C_BAN, () -> go(Page.RULES)));
                Detector.Violation p = MasedHelper.punisher.pending;
                items.add(new Item(p == null ? "Последнее —" : "⚡ " + p.nick() + " " + p.ruleId(),
                        p == null ? "Нарушений пока нет" : "Наказать за: " + p.reason() + " «" + Punisher.cut(p.message()) + "»",
                        p == null ? C_DIM : C_MUTE, () -> {
                    if (MasedHelper.punisher.pending != null) {
                        MasedHelper.punisher.applyPending(cfg);
                        onClose();
                    }
                }));
                for (Config.Action a : cfg.mainActions)
                    items.add(new Item(a.label, "/" + a.command, C_ACT, () -> run(a.command)));
                items.add(new Item("Ещё ▸", "Другие действия", C_NAV, () -> go(Page.MORE)));
                items.add(new Item("Цель ▸", "Выбрать из последних писавших в чат", C_NAV, () -> go(Page.TARGET)));
                items.add(new Item("Режим: " + MasedHelper.modeName(cfg.mode), "Выкл → подсказки → авто", C_NAV, () -> {
                    String next = switch (cfg.mode.toUpperCase(Locale.ROOT)) {
                        case "OFF" -> "suggest";
                        case "SUGGEST" -> "auto";
                        default -> "off";
                    };
                    MasedHelper.setMode(next);
                    build();
                }));
            }
            case RULES -> {
                for (Config.RuleDef r : cfg.rules) {
                    String label = r.id + " " + typeName(r.type) + " " + humanTime(r.time);
                    String hint = r.title + (r.removeDonate ? " (+ снять донат)" : "") + " → /"
                            + Punisher.buildCommand(cfg, r, MasedHelper.target.isBlank() ? "<ник>" : MasedHelper.target);
                    int color = r.type.equalsIgnoreCase("MUTE") ? C_MUTE : C_BAN;
                    items.add(new Item(label, hint, color, () -> {
                        if (!needTarget()) return;
                        MasedHelper.punisher.punish(cfg, MasedHelper.target, r.id);
                        onClose();
                    }));
                }
                items.add(new Item("◂ Назад", "", C_NAV, () -> go(Page.MAIN)));
            }
            case MORE -> {
                for (Config.Action a : cfg.moreActions)
                    items.add(new Item(a.label, "/" + a.command, C_ACT, () -> run(a.command)));
                items.add(new Item("Перезагр.", "Перечитать config.json", C_NAV, () -> {
                    MasedHelper.reload();
                    build();
                }));
                items.add(new Item("◂ Назад", "", C_NAV, () -> go(Page.MAIN)));
            }
            case TARGET -> {
                items.add(new Item("Предыдущие ▸", "Кого ты выбирал раньше", C_NAV, () -> go(Page.PREV)));
                for (String n : MasedHelper.targets())
                    items.add(new Item(n, "Сделать целью", C_TEXT, () -> {
                        MasedHelper.target = n;
                        nickBox.setValue(n);
                        go(Page.MAIN);
                    }));
                if (MasedHelper.targets().isEmpty())
                    items.add(new Item("Пусто", "Никто ещё не писал в чат", C_DIM, () -> go(Page.MAIN)));
                items.add(new Item("◂ Назад", "", C_NAV, () -> go(Page.MAIN)));
            }
            case PREV -> {
                if (cfg.targetHistory != null)
                    for (String n : cfg.targetHistory)
                        items.add(new Item(n, "Сделать целью", C_TEXT, () -> {
                            MasedHelper.target = n;
                            nickBox.setValue(n);
                            go(Page.MAIN);
                        }));
                if (items.isEmpty())
                    items.add(new Item("Пусто", "Ты ещё никого не выбирал", C_DIM, () -> go(Page.TARGET)));
                items.add(new Item("◂ Назад", "", C_NAV, () -> go(Page.TARGET)));
            }
        }
    }

    // ---------------- Геометрия ----------------

    private int cx() { return width / 2; }
    private int cy() { return (height - 40) / 2; }
    private int radius() { return Math.max(60, Math.min(120, (height - 40) / 2 - 30)); }

    private double angleOf(int i) {
        return -Math.PI / 2 + i * 2 * Math.PI / items.size();
    }

    private int pick(double mx, double my) {
        double dx = mx - cx(), dy = my - cy();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 28 || dist > radius() + 80 || items.isEmpty()) return -1;
        double a = Math.atan2(dy, dx) + Math.PI / 2;           // 0 = верх
        double step = 2 * Math.PI / items.size();
        a = (a + step / 2) % (2 * Math.PI);
        if (a < 0) a += 2 * Math.PI;
        return (int) (a / step) % items.size();
    }

    // ---------------- Отрисовка ----------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // фон убран: рисуем только круг
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        anim = Math.min(1f, anim + delta * 0.25f);
        float ease = 1f - (1f - anim) * (1f - anim);
        int cx = cx(), cy = cy();
        int r = (int) (radius() * (0.6f + 0.4f * ease));

        hovered = nickBox.isFocused() && mouseY > height - 40 ? -1 : pick(mouseX, mouseY);

        // кольцо из точек, подсвеченный сектор
        int n = Math.max(1, items.size());
        int dots = 96;
        for (int d = 0; d < dots; d++) {
            double a = -Math.PI / 2 + d * 2 * Math.PI / dots;
            int sector = (int) Math.round((a + Math.PI / 2) / (2 * Math.PI / n)) % n;
            boolean hot = sector == hovered;
            int rr = r - 30;
            int x = cx + (int) (Math.cos(a) * rr), y = cy + (int) (Math.sin(a) * rr);
            int s = hot ? 2 : 1;
            g.fill(x - s, y - s, x + s + 1, y + s + 1, hot ? items.get(hovered).color : 0x66FFFFFF);
        }

        // центр
        g.fill(cx - 26, cy - 14, cx + 26, cy + 14, 0xCC101018);
        outline(g, cx - 26, cy - 14, 52, 28, 0xFF444455);
        String tgt = MasedHelper.target.isBlank() ? "нет цели" : MasedHelper.target;
        g.drawCenteredString(font, pageTitle(), cx, cy - 10, C_DIM);
        g.drawCenteredString(font, fit(tgt, 90), cx, cy + 2, MasedHelper.target.isBlank() ? C_BAN : C_TEXT);

        // линия к выбранному
        if (hovered >= 0) {
            double a = angleOf(hovered);
            for (int k = 28; k < r - 34; k += 3) {
                int x = cx + (int) (Math.cos(a) * k), y = cy + (int) (Math.sin(a) * k);
                g.fill(x, y, x + 1, y + 1, items.get(hovered).color);
            }
        }

        // пункты
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            double a = angleOf(i);
            int x = cx + (int) (Math.cos(a) * r), y = cy + (int) (Math.sin(a) * r);
            String label = fit(it.label, 110);
            int w = font.width(label) + 12, h = 18;
            boolean hot = i == hovered;
            int bx = x - w / 2, by = y - h / 2;
            g.fill(bx, by, bx + w, by + h, hot ? 0xEE22222E : 0xCC101018);
            outline(g, bx, by, w, h, hot ? it.color : 0xFF333344);
            if (hot) g.fill(bx, by + h - 2, bx + w, by + h, it.color);
            g.drawString(font, label, bx + 6, by + 5, hot ? it.color : C_TEXT);
        }

        // подсказка
        String hint = hovered >= 0 ? items.get(hovered).hint : "ЛКМ — выбрать · ПКМ — назад · Esc — закрыть";
        if (!hint.isEmpty()) g.drawCenteredString(font, fit(hint, width - 20), cx, height - 46, C_DIM);
    }

    private String pageTitle() {
        return switch (page) {
            case MAIN -> "Цель";
            case RULES -> "Правило";
            case MORE -> "Ещё";
            case TARGET -> "Выбор";
            case PREV -> "Раньше";
        };
    }

    private String fit(String s, int max) {
        if (font.width(s) <= max) return s;
        while (s.length() > 1 && font.width(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    // ---------------- Ввод ----------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int target = pick(event.x(), event.y());
        if (event.button() == 1) {
            if (page == Page.MAIN) onClose();
            else go(Page.MAIN);
            return true;
        }
        if (event.button() == 0 && target >= 0) {
            items.get(target).action.run();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!nickBox.isFocused()) {
            int k = event.key();
            if (k >= 49 && k <= 57) {                       // 1..9 — быстрый выбор
                int i = k - 49;
                if (i < items.size()) items.get(i).action.run();
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
