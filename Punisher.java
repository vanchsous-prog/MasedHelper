package ru.masedhelper;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Сборка и отправка команд наказаний, очередь авто-режима, журнал. */
public class Punisher {

    private final Deque<String> queue = new ArrayDeque<>();
    private long lastSent = 0;
    private final Map<String, Long> lastPunished = new HashMap<>();
    private final Map<String, Long> lastReported = new HashMap<>();

    /** Последнее найденное нарушение — его применяет клавиша Y и кнопка в меню. */
    public Detector.Violation pending;

    public static String buildCommand(Config cfg, Config.RuleDef r, String nick) {
        String tpl = switch (r.type.toUpperCase(Locale.ROOT)) {
            case "MUTE" -> cfg.muteCommand;
            case "BANIP" -> cfg.banIpCommand;
            default -> cfg.banCommand;
        };
        String cmd = tpl.replace("{nick}", nick)
                .replace("{time}", r.time == null ? "" : r.time)
                .replace("{rule}", r.id)
                .replace("{title}", r.title == null ? "" : r.title);
        cmd = cmd.replaceAll("\\s+", " ").strip();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        return cmd;
    }

    /** Отправить команду на сервер (без "/"). */
    public static void send(String cmd) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        mc.player.connection.sendCommand(cmd);
    }

    public void punish(Config cfg, String nick, String ruleId) {
        Config.RuleDef r = cfg.rule(ruleId);
        if (r == null) {
            MasedHelper.say(Component.literal("Нет правила " + ruleId).withStyle(ChatFormatting.RED));
            return;
        }
        String cmd = buildCommand(cfg, r, nick);
        send(cmd);
        lastPunished.put(nick.toLowerCase(Locale.ROOT), System.currentTimeMillis());
        log("PUNISH " + nick + " " + r.id + " -> /" + cmd);
        if (r.removeDonate) {
            MasedHelper.say(Component.literal("Правило " + r.id + ": не забудь снять донат у " + nick + "!")
                    .withStyle(ChatFormatting.GOLD));
        }
        if (pending != null && pending.nick().equalsIgnoreCase(nick)) pending = null;
    }

    /** Обработка найденного нарушения с учётом режима. */
    public void onViolation(Config cfg, Detector.Violation v) {
        long now = System.currentTimeMillis();
        String key = v.nick().toLowerCase(Locale.ROOT) + "|" + v.ruleId();
        Long prev = lastReported.get(key);
        if (prev != null && now - prev < cfg.dedupeSec * 1000L) return;
        lastReported.put(key, now);

        Config.RuleDef r = cfg.rule(v.ruleId());
        if (r == null) return;
        String cmd = buildCommand(cfg, r, v.nick());
        log("DETECT " + v.nick() + " " + v.ruleId() + " (" + v.reason() + "): " + v.message());

        if ("AUTO".equalsIgnoreCase(cfg.mode)) {
            Long lp = lastPunished.get(v.nick().toLowerCase(Locale.ROOT));
            if (lp != null && now - lp < cfg.autoCooldownSec * 1000L) return;
            lastPunished.put(v.nick().toLowerCase(Locale.ROOT), now);
            queue.add(cmd);
            log("AUTO -> /" + cmd);
            MasedHelper.say(Component.literal("[MH] ").withStyle(ChatFormatting.DARK_RED)
                    .append(Component.literal("Авто: /" + cmd).withStyle(ChatFormatting.RED))
                    .append(Component.literal("  «" + cut(v.message()) + "»").withStyle(ChatFormatting.GRAY)));
            if (r.removeDonate)
                MasedHelper.say(Component.literal("Правило " + r.id + ": не забудь снять донат у " + v.nick() + "!")
                        .withStyle(ChatFormatting.GOLD));
            MasedHelper.ping(cfg);
            return;
        }

        pending = v;
        MutableComponent line = Component.literal("[MH] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(v.nick()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" → " + r.id + " ").withStyle(ChatFormatting.RED))
                .append(Component.literal("(" + v.reason() + ") ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("«" + cut(v.message()) + "» ").withStyle(ChatFormatting.GRAY))
                .append(button("[НАКАЗАТЬ]", ChatFormatting.GREEN, "/" + cmd,
                        "Вставит в чат: /" + cmd + "\nНажми Enter. Или клавиша Y — сразу."))
                .append(Component.literal(" "))
                .append(button("[ИГНОР]", ChatFormatting.DARK_GRAY, "/mh ignore " + v.nick(),
                        "Больше не проверять этого игрока"));
        MasedHelper.say(line);
        MasedHelper.ping(cfg);
    }

    private static MutableComponent button(String text, ChatFormatting color, String suggest, String hover) {
        return Component.literal(text).withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent.SuggestCommand(suggest))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** Вызывать каждый тик: отправляет команды авто-режима с паузой. */
    public void tick(Config cfg) {
        if (queue.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastSent < cfg.autoGlobalIntervalSec * 1000L) return;
        lastSent = now;
        send(queue.poll());
    }

    public void applyPending(Config cfg) {
        if (pending == null) {
            MasedHelper.say(Component.literal("[MH] Нет нарушения для применения").withStyle(ChatFormatting.GRAY));
            return;
        }
        Detector.Violation v = pending;
        punish(cfg, v.nick(), v.ruleId());
        MasedHelper.say(Component.literal("[MH] Применено: " + v.nick() + " " + v.ruleId()).withStyle(ChatFormatting.GREEN));
    }

    static String cut(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void log(String line) {
        try {
            Files.createDirectories(Config.dir());
            Files.writeString(Config.dir().resolve("log.txt"),
                    "[" + LocalDateTime.now().format(TS) + "] " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
