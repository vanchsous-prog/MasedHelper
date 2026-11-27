package ru.masedhelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Конфиг: config/masedhelper/config.json
 * Всё (команды, правила, слова, кнопки меню) можно менять без перекомпиляции.
 */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("masedhelper");
    }

    public static Path file() {
        return dir().resolve("config.json");
    }

    // ---------------- Типы ----------------

    public static class RuleDef {
        public String id;
        /** MUTE, BAN или BANIP */
        public String type;
        /** Срок в формате плагина сервера (30m, 3h, 2d). Пусто = навсегда. */
        public String time;
        public String title;
        /** Правило требует снять донат — мод напомнит об этом в чате. */
        public boolean removeDonate;

        public RuleDef() {}

        public RuleDef(String id, String type, String time, String title, boolean removeDonate) {
            this.id = id;
            this.type = type;
            this.time = time;
            this.title = title;
            this.removeDonate = removeDonate;
        }
    }

    public static class Action {
        public String label;
        public String command; // {nick} — цель
        public Action() {}
        public Action(String label, String command) {
            this.label = label;
            this.command = command;
        }
    }

    // ---------------- Основное ----------------

    /** OFF — не следить за чатом, SUGGEST — предлагать наказание (по клику / клавише Y), AUTO — наказывать самому. */
    public String mode = "SUGGEST";

    /** Шаблоны команд. {nick} {time} {rule} {title}. Если на сервере "/ban ник 6.1" без срока — убери {time}. */
    public String muteCommand = "mute {nick} {time} {rule}";
    public String banCommand = "ban {nick} {time} {rule}";
    public String banIpCommand = "banip {nick} {time} {rule}";

    /**
     * Как вытащить ник и текст из строки чата. Группы nick, sep, msg обязательны.
     * По умолчанию понимает: "[G] [Игрок] Nick » текст", "Nick: текст", "Ⓛ Nick > текст".
     */
    public String chatRegex = "^(?:\\[[^\\]]{0,32}\\]\\s*|[^\\sA-Za-z0-9_\\[\\]]{1,3}\\s+|[А-Яа-яЁё]{1,12}\\s+)*"
            + "(?<nick>[A-Za-z0-9_]{3,16})\\s*(?<sep>»|->|→|⇨|➥|:|>|\\|)\\s*(?<msg>.+)$";
    /** Если это найдено в начале строки (до текста) — строка не считается сообщением игрока (ЛС, системные). */
    public String ignorePrefixRegex = "(?i)\\[(лс|л\\.с\\.?|pm|msg|личн\\S*|сервер|server)\\]";

    /** Команда /s ник — телепортирует игрока к тебе. {nick} — ник. Если на сервере другая, поменяй здесь. */
    public String tpHereCommand = "tphere {nick}";
    /** Предыдущие цели (последние выбранные), новые сверху. */
    public List<String> targetHistory = new ArrayList<>();

    /** ИИ LLM7 помогает отличить «ты пидор» (наказываем) от «я пидор» (не наказываем). */
    public boolean llm7Enabled = true;
    public String llm7Url = "https://api.llm7.io/v1/chat/completions";
    public String llm7Model = "gpt-4";
    /** Бесплатный токен с dash.llm7.io даёт лимиты побольше. Без него работает "unused". */
    public String llm7Token = "unused";
    public int llm7TimeoutSec = 6;

    public boolean soundAlert = true;
    /** Повтор одного и того же нарушения от того же игрока не чаще, чем раз в N секунд. */
    public int dedupeSec = 30;
    /** В авто-режиме: не наказывать одного игрока чаще, чем раз в N секунд. */
    public int autoCooldownSec = 60;
    /** В авто-режиме: минимальная пауза между любыми командами (чтобы сервер не кикнул за спам). */
    public int autoGlobalIntervalSec = 3;

    // ---------------- 7.1 флуд/спам ----------------
    /** "Одинаковые сообщения более 2 раз" — на 3-й повтор срабатывает. */
    public int floodRepeats = 3;
    public int floodWindowSec = 120;
    public int spamMessages = 6;
    public int spamWindowSec = 10;

    // ---------------- Списки ----------------
    public List<String> staffNicks = new ArrayList<>();
    public List<String> ignoredNicks = new ArrayList<>();
    /** Ссылки с этими кусками не считаются нарушением 7.4. */
    public List<String> officialDomains = list("masedworld");

    /** Слова-оскорбления: точное слово. */
    public List<String> insultExact = list(
            "даун", "дауны", "дауна", "дауном", "чмо", "дура", "тупой", "тупая", "тупые", "тупица",
            "овца", "соси", "сасай", "лох", "лохи", "лоха", "мразь", "тварь", "сука", "суки", "бомж");
    /** Слова-оскорбления: начало слова. */
    public List<String> insultPrefix = list(
            "дебил", "дибил", "идиот", "имбецил", "кретин", "дегенерат", "аутист", "дурак", "дурачок",
            "лошар", "чмош", "урод", "ублюд", "мраз", "гнид", "твар", "скотин", "ничтожеств",
            "пидор", "пидр", "пидар", "педик", "педрил", "гандон", "гондон", "мудак", "мудил",
            "долбо", "далба", "еблан", "ебло", "ебан", "уебок", "уебан", "уебищ", "выблядок", "сучк", "сучар",
            "петух", "отсос", "хуесос", "хуйл", "хуил", "конченн", "конченый", "тупорыл", "тупоголов",
            "нищеброд", "нищук", "шалав", "шлюх", "дебик");
    /** Слова-оскорбления: в любом месте слова. */
    public List<String> insultContains = list(
            "пизд", "хуесос", "долбоеб", "далбаеб", "долбаеб", "уебок", "уебан", "еблан", "пидор", "пидар", "гандон", "шлюх", "мудак");
    /** Латиница (транслит). Начало слова. */
    public List<String> insultLatin = list(
            "debil", "idiot", "loh", "lox", "pidor", "pidr", "suka", "daun", "dayn", "mraz", "chmo", "gandon",
            "shluh", "shlyuh", "eblan", "ueb", "mudak", "dolboeb", "retard", "fuckyou", "fuck_you", "bitch");

    /** 6.1 угрозы (regex по тексту). */
    public List<String> threatPatterns = list(
            "(убью|прибью|зарежу|задушу|урою|закопаю|уничтожу)\\s+(тебя|тя|вас)",
            "(тебя|тя)\\s+(убью|прибью|найду|вычислю|зарежу)",
            "вычислю\\s+(тебя\\s+)?(по\\s+)?(ip|айпи|ипи)",
            "\\bсдохни\\b", "чтоб(ы)?\\s+ты\\s+сдох");

    /** 6.3 — родные (начало слова). Срабатывает вместе с оскорблением/пошлым глаголом. */
    public List<String> relativePrefix = list(
            "мамк", "мамаш", "мамоч", "мама", "маму", "мамы", "мамой", "маме", "мать", "матер", "матуш",
            "батя", "батю", "бати", "батей", "бате", "отец", "отца", "отцу", "папаш", "папк",
            "сестр", "бабк", "бабуш", "родак", "родител");
    public List<String> relativeVerbs = list("ебал", "ебу", "выеб", "трах", "сдох", "здох", "сосал", "сосет", "сосёт", "дрочил", "шлюх", "шалав", "проститут");

    /** 6.2 — упоминание персонала (начало слова). */
    public List<String> staffWords = list("админ", "модер", "хелпер", "персонал", "стаф", "овнер", "саппорт", "куратор", "admin", "moder", "helper", "owner", "staff");

    public List<String> fakePunishPatterns = list(
            "\\[(лс|л\\.с\\.?|pm|me)\\]", "\\[[^\\]]*(->|→)\\s*(я|me)\\]", "(->|→)\\s*(я|me)\\s*:",
            "(был|была|были)\\s+(забанен|замучен|заблокирован)", "получил(а)?\\s+(бан|мут|блокировку)",
            "(забанен|замучен|заблокирован)\\w*\\s+(администратором|модератором|хелпером)",
            "игрок\\s+\\S+\\s+(заблокирован|забанен|замучен)");

    public List<String> beggingPatterns = list(
            "(дайте|дай|кинь|киньте|скинь|скиньте|подари|подарите|одолжи|займи|задонать\\S*|купи|купите|дашь|дадите)"
                    + "(\\s+(мне|пж|пжл|пожалуйста|плиз|плз|пж\\S*|кто-?нибудь|кто|нибудь|немного|хоть))*\\s+"
                    + "(денег|деньги|деньжат|монет|бабл|бабки|бабок|рес|ресы|ресурс|алмаз|незер|донат|привилег|привку|кейс|ключ|вещ|бронь|броню|меч|еды|еду|хавк|шалкер|тотем|эндер|элитр|\\d+)",
            "(кто|кто-?нибудь|кто\\s+нибудь)\\s+(даст|подарит|скинет|кинет|задонатит|купит|одолжит)",
            "помогите\\s+(деньгами|ресами|ресурсами|с\\s+донатом)", "(подайте|подайте\\s+бедному)");

    public List<String> leavePatterns = list(
            "(ливайте|уходите|валите|бегите|сваливайте|уйдите|ливните)\\s+(с|из|отсюда)",
            "(ливайте|уходите|валите|сваливайте)\\s+(с\\s+)?(сервер|проект|масед)",
            "(сервер|проект|масед\\S*)\\s+(умер|сдох|мертв|загибается)",
            "\\bбунт\\S*", "(восста\\S*|свергн\\S*|все\\s+против)\\s+(админ|модер|персонал|хелпер)",
            "(давайте|го|погнали)\\s+(все\\s+)?(ливнем|уйдем|уйдём|бунт\\S*|восстан\\S*)");

    public List<String> antiDonatePatterns = list(
            "не\\s+(покупайте|берите|покупай|бери|трать|тратьте|донать\\S*)(\\s+\\S+){0,3}\\s+донат",
            "донат\\S*\\s+(не\\s+покупайте|не\\s+берите|развод|лохотрон|скам|кидалово|кидалов\\S*|обман)",
            "(не\\s+донатьте|не\\s+донать\\b)",
            "(сервер|проект|масед\\S*)\\s+(говно|гавно|херня|хуйня|дерьмо|отстой|помойка|параша|кал|мусор|ужасн\\S*|днище|убог\\S*)",
            "(говно|гавно|херов\\S*|хуёв\\S*|хуев\\S*|убог\\S*|днищ\\S*|помоечн\\S*|дерьмов\\S*)[\\s-]+(сервер|проект|масед)");

    public List<String> impersonatePatterns = list(
            "\\bя\\s+(админ|модер|модератор|хелпер|helper|admin|moder|персонал|стафф|стаф|гл\\.?\\s*админ|главный\\s+админ|овнер|создатель)",
            "(я\\s+из|работаю\\s+в)\\s+(персонала|администрации|стаффа)",
            "\\bя\\s+(тебя|тя)\\s+(щас\\s+|сейчас\\s+)?(забаню|замучу|кикну|заблокирую)");

    public List<String> serverAdPatterns = list(
            "\\b(\\d{1,3}\\.){3}\\d{1,3}(:\\d{2,5})?\\b",
            "\\b(play|mc|mine|join|pvp|go)\\.[a-z0-9-]+\\.(ru|su|net|com|org|pro|me|fun|xyz|online|gg|world|club)\\b",
            "(залетайте|заходите|заходи|залетай|переходите|переходи|го|приглашаю)\\s+(ко\\s+мне\\s+)?на\\s+(мой|наш|лучший|новый|другой|крутой|свой)\\s+сервер",
            "(есть|у\\s+меня)\\s+(свой|крутой|новый)\\s+сервер",
            "(айпи|ip|ип)\\s*(сервера|серва)?\\s*[:=-]");

    public List<String> linkPatterns = list(
            "(https?://|www\\.)\\S+",
            "\\b[a-z0-9-]{2,}\\.(ru|com|net|org|su|gg|me|io|tv|be|ly|xyz|pro|fun|online|site|shop|cc|club|рф)(/\\S*)?",
            "youtube|youtu\\.be|ютуб\\S*\\s+канал|\\bt\\.me\\b|discord\\.gg|twitch\\.tv|tiktok",
            "(подпишись|подписывайтесь|подпишитесь)\\s+на\\s+(мой|моего)?\\s*(канал|ютуб|тт|тикток|твич)",
            "мой\\s+(ютуб|канал|тик\\s?ток|твич|тг)");

    // ---------------- Правила ----------------
    public List<RuleDef> rules = new ArrayList<>(Arrays.asList(
            new RuleDef("6.1", "MUTE", "3h", "Оскорбление/унижение/угрозы игрокам", false),
            new RuleDef("6.2", "BAN", "5h", "Оскорбление/угрозы персоналу", false),
            new RuleDef("6.3", "BAN", "2d", "Оскорбление родных", false),
            new RuleDef("7.1", "MUTE", "30m", "Флуд/спам", false),
            new RuleDef("7.2", "MUTE", "1h", "Фейковые ЛС/наказания", false),
            new RuleDef("7.3", "MUTE", "30m", "Попрошайничество", false),
            new RuleDef("7.4", "BANIP", "2d", "Пиар YouTube/ссылки", false),
            new RuleDef("7.5", "BANIP", "", "Пиар других серверов", true),
            new RuleDef("7.6", "BAN", "", "Призыв покинуть проект/бунт", true),
            new RuleDef("7.7", "BAN", "", "Призыв не донатить/оскорбление сервера", false),
            new RuleDef("7.8", "BAN", "1d", "Выдача себя за персонал", false)
    ));

    // ---------------- Кнопки кругового меню ----------------
    public List<Action> mainActions = new ArrayList<>(Arrays.asList(
            new Action("Телепорт", "tp {nick}"),
            new Action("История", "history {nick}"),
            new Action("Предупр.", "msg {nick} Предупреждение от модератора: соблюдайте правила чата (разделы 6-7). Следующее нарушение - наказание."),
            new Action("Размут", "unmute {nick}"),
            new Action("Кик", "kick {nick}")
    ));
    public List<Action> moreActions = new ArrayList<>(Arrays.asList(
            new Action("Разбан", "unban {nick}"),
            new Action("Ваниш", "vanish"),
            new Action("Инвентарь", "invsee {nick}"),
            new Action("Эндерсундук", "ec {nick}"),
            new Action("Наблюдать", "spectate {nick}")
    ));

    // ---------------- I/O ----------------

    private static List<String> list(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    public RuleDef rule(String id) {
        for (RuleDef r : rules) if (r.id.equals(id)) return r;
        return null;
    }

    public static Config load() {
        Path f = file();
        try {
            if (Files.exists(f)) {
                Config c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Config.class);
                if (c != null) {
                    c.save(); // дописать новые поля, если конфиг старый
                    return c;
                }
            }
        } catch (Exception e) {
            MasedHelper.LOG.error("Не удалось прочитать конфиг, используются значения по умолчанию", e);
            Config c = new Config();
            return c; // битый файл не перезаписываем
        }
        Config c = new Config();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(dir());
            Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            MasedHelper.LOG.error("Не удалось сохранить конфиг", e);
        }
    }
}
