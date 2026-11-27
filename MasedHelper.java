package ru.masedhelper;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class MasedHelper implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("masedhelper");

    public static Config cfg;
    public static Detector detector;
    public static final Punisher punisher = new Punisher();
    /** Последние писавшие в чат — для быстрого выбора цели в меню. */
    public static final LinkedList<String> recentNicks = new LinkedList<>();
    public static String target = "";

    private static KeyMapping radialKey, applyKey;

    @Override
    public void onInitializeClient() {
        reload();

        radialKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.masedhelper.radial", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KeyMapping.Category.MISC));
        applyKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.masedhelper.apply", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            punisher.tick(cfg);
            while (radialKey.consumeClick()) {
                if (mc.screen == null) {
                    String looked = lookedAt(mc);
                    if (looked != null) target = looked;
                    else if (target.isEmpty() && punisher.pending != null) target = punisher.pending.nick();
                    mc.setScreen(new RadialScreen());
                }
            }
            while (applyKey.consumeClick()) punisher.applyPending(cfg);
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChatLine(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
                onChatLine(message.getString()));

        ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> {
        d.register(literal("s").then(argument("nick", StringArgumentType.word())
                .suggests((c, b) -> {
                    for (String n : recentNicks) b.suggest(n);
                    return b.buildFuture();
                })
                .executes(c -> {
                    String n = StringArgumentType.getString(c, "nick");
                    rememberTarget(n);
                    Punisher.send(cfg.tpHereCommand.replace("{nick}", n));
                    say(Component.literal("[MH] Телепорт к себе: " + n).withStyle(ChatFormatting.GREEN));
                    return 1;
                })));
        d.register(literal("mh")
                .executes(c -> {
                    help();
                    return 1;
                })
                .then(literal("reload").executes(c -> {
                    reload();
                    say(Component.literal("[MH] Конфиг перезагружен").withStyle(ChatFormatting.GREEN));
                    return 1;
                }))
                .then(literal("mode").then(argument("m", StringArgumentType.word())
                        .suggests((c, b) -> {
                            b.suggest("off");
                            b.suggest("suggest");
                            b.suggest("auto");
                            return b.buildFuture();
                        })
                        .executes(c -> {
                            setMode(StringArgumentType.getString(c, "m"));
                            return 1;
                        })))
                .then(literal("punish").then(argument("nick", StringArgumentType.word())
                        .then(argument("rule", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (Config.RuleDef r : cfg.rules) b.suggest(r.id);
                                    return b.buildFuture();
                                })
                                .executes(c -> {
                                    punisher.punish(cfg, StringArgumentType.getString(c, "nick"),
                                            StringArgumentType.getString(c, "rule"));
                                    return 1;
                                }))))
                .then(literal("ignore").then(argument("nick", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "nick");
                    toggle(cfg.ignoredNicks, n, "игнор");
                    return 1;
                })))
                .then(literal("staff").then(argument("nick", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "nick");
                    toggle(cfg.staffNicks, n, "персонал");
                    return 1;
                })))
                .then(literal("test").then(argument("text", StringArgumentType.greedyString()).executes(c -> {
                    Detector.Violation v = detector.test(StringArgumentType.getString(c, "text"));
                    say(Component.literal(v == null ? "[MH] Нарушений не найдено"
                                    : "[MH] Нарушение " + v.ruleId() + " (" + v.reason() + ")")
                            .withStyle(v == null ? ChatFormatting.GREEN : ChatFormatting.RED));
                    return 1;
                })))
                .then(literal("apply").executes(c -> {
                    punisher.applyPending(cfg);
                    return 1;
                }))
                .then(literal("menu").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(new RadialScreen()));
                    return 1;
                }))
        );
        });
        LOG.info("MasedWorld Helper загружен");
    }

    public static void reload() {
        cfg = Config.load();
        detector = new Detector(cfg);
    }

    private static void help() {
        say(Component.literal("""
                [MasedWorld Helper]
                R — круговое меню, Y — наказать за последнее нарушение
                /mh mode off|suggest|auto — режим слежения (сейчас: %s)
                /mh punish <ник> <правило> — наказать по правилу
                /mh ignore <ник> — вкл/выкл игнор игрока
                /mh staff <ник> — вкл/выкл ник как персонал (для 6.2)
                /s <ник> — телепортировать игрока к себе
                /mh test <текст> — проверить фразу детектором
                /mh reload — перечитать config/masedhelper/config.json""".formatted(cfg.mode))
                .withStyle(ChatFormatting.AQUA));
    }

    public static void setMode(String m) {
        m = m.toUpperCase(Locale.ROOT);
        if (!m.equals("OFF") && !m.equals("SUGGEST") && !m.equals("AUTO")) {
            say(Component.literal("[MH] Режимы: off, suggest, auto").withStyle(ChatFormatting.RED));
            return;
        }
        cfg.mode = m;
        cfg.save();
        say(Component.literal("[MH] Режим: " + modeName(m)).withStyle(ChatFormatting.GREEN));
    }

    public static String modeName(String m) {
        return switch (m.toUpperCase(Locale.ROOT)) {
            case "OFF" -> "выключен";
            case "AUTO" -> "АВТО-наказание";
            default -> "подсказки";
        };
    }

    private static void toggle(List<String> list, String nick, String what) {
        boolean removed = list.removeIf(s -> s.equalsIgnoreCase(nick));
        if (!removed) list.add(nick);
        cfg.save();
        detector = new Detector(cfg);
        say(Component.literal("[MH] " + nick + (removed ? " убран из списка: " : " добавлен в список: ") + what)
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void onChatLine(String line) {
        if (line.startsWith("[MH]") || line.startsWith("[MasedWorld Helper]")) return;
        Detector.Parsed p = detector.parse(line);
        if (p == null) return;
        Minecraft mc = Minecraft.getInstance();
        String self = mc.player != null ? mc.player.getName().getString() : "";

        recentNicks.removeIf(n -> n.equalsIgnoreCase(p.nick()));
        recentNicks.addFirst(p.nick());
        while (recentNicks.size() > 8) recentNicks.removeLast();

        if ("OFF".equalsIgnoreCase(cfg.mode)) return;
        if (p.nick().equalsIgnoreCase(self)) return;
        for (String n : cfg.ignoredNicks) if (n.equalsIgnoreCase(p.nick())) return;
        for (String n : cfg.staffNicks) if (n.equalsIgnoreCase(p.nick())) return;

        Detector.Violation v = detector.check(p.nick(), p.msg());
        if (v == null) return;

        // оскорбление игрока (6.1) дополнительно проверяет ИИ: «ты ...» наказываем, «я ...» нет
        if (cfg.llm7Enabled && v.ruleId().equals("6.1") && !v.reason().startsWith("угроза")) {
            final Detector.Violation viol = v;
            Llm7.isDirectedInsult(cfg, p.msg()).thenAccept(directed -> {
                if (Boolean.FALSE.equals(directed)) {
                    Punisher.log("LLM7 SKIP " + viol.nick() + ": " + viol.message());
                    return;
                }
                Minecraft.getInstance().execute(() -> punisher.onViolation(cfg, viol));
            });
        } else {
            punisher.onViolation(cfg, v);
        }
    }

    /** Запоминает цель в список «предыдущих целей». */
    public static void rememberTarget(String nick) {
        if (nick == null || !nick.matches("[A-Za-z0-9_]{3,16}")) return;
        if (cfg.targetHistory == null) cfg.targetHistory = new ArrayList<>();
        if (!cfg.targetHistory.isEmpty() && cfg.targetHistory.get(0).equalsIgnoreCase(nick)) return;
        cfg.targetHistory.removeIf(n -> n.equalsIgnoreCase(nick));
        cfg.targetHistory.add(0, nick);
        while (cfg.targetHistory.size() > 10) cfg.targetHistory.remove(cfg.targetHistory.size() - 1);
        cfg.save();
    }

    private static String lookedAt(Minecraft mc) {
        if (mc.crosshairPickEntity instanceof Player pl && pl != mc.player) return pl.getName().getString();
        return null;
    }

    public static List<String> targets() {
        List<String> out = new ArrayList<>(recentNicks);
        return out;
    }

    public static void say(Component c) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.getChat().addMessage(c));
    }

    public static void ping(Config cfg) {
        if (!cfg.soundAlert) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f)));
    }
}
