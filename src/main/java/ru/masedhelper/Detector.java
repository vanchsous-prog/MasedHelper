package ru.masedhelper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор строк чата и поиск нарушений правил 6.x / 7.x.
 * Работает только на клиенте модератора — сервер ничего не знает о моде.
 */
public class Detector {

    public record Parsed(String nick, String msg) {}

    public record Violation(String nick, String ruleId, String reason, String message) {}

    private final Config cfg;

    private Pattern chat;
    private Pattern ignorePrefix;
    private Set<String> insultExact, insultPrefix, insultContains, insultLatin, relativePrefix, relativeVerbs, staffWords;
    private List<Pattern> threats, fakes, begging, leave, antiDonate, impersonate, serverAds, links;

    private final Map<String, Deque<long[]>> history = new HashMap<>();   // ник -> [время, хеш текста]

    public Detector(Config cfg) {
        this.cfg = cfg;
        compile();
    }

    private void compile() {
        try {
            chat = Pattern.compile(cfg.chatRegex, Pattern.UNICODE_CASE);
        } catch (Exception e) {
            MasedHelper.LOG.error("Неверный chatRegex, используется стандартный", e);
            chat = Pattern.compile(new Config().chatRegex);
        }
        try {
            ignorePrefix = Pattern.compile(cfg.ignorePrefixRegex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (Exception e) {
            ignorePrefix = Pattern.compile("(?!)");
        }
        insultExact = words(cfg.insultExact);
        insultPrefix = words(cfg.insultPrefix);
        insultContains = words(cfg.insultContains);
        insultLatin = latinWords(cfg.insultLatin);
        relativePrefix = words(cfg.relativePrefix);
        relativeVerbs = words(cfg.relativeVerbs);
        staffWords = latinWords(cfg.staffWords);
        threats = patterns(cfg.threatPatterns);
        fakes = patterns(cfg.fakePunishPatterns);
        begging = patterns(cfg.beggingPatterns);
        leave = patterns(cfg.leavePatterns);
        antiDonate = patterns(cfg.antiDonatePatterns);
        impersonate = patterns(cfg.impersonatePatterns);
        serverAds = patterns(cfg.serverAdPatterns);
        links = patterns(cfg.linkPatterns);
    }

    private static Set<String> words(List<String> src) {
        Set<String> s = new HashSet<>();
        for (String w : src) s.add(squeeze(toCyr(w.toLowerCase(Locale.ROOT).replace('ё', 'е'))));
        return s;
    }

    private static Set<String> latinWords(List<String> src) {
        Set<String> s = new HashSet<>();
        for (String w : src) s.add(squeeze(w.toLowerCase(Locale.ROOT)));
        return s;
    }

    private static List<Pattern> patterns(List<String> src) {
        List<Pattern> out = new ArrayList<>();
        for (String p : src) {
            try {
                out.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS));
            } catch (Exception e) {
                MasedHelper.LOG.warn("Пропущен неверный шаблон: {}", p);
            }
        }
        return out;
    }

    // ---------------- Нормализация ----------------

    private static final String LAT = "aeopcxykmthbu3046";
    private static final String CYR = "аеорсхукмтнвизчоб";

    /** Латинские двойники букв -> кириллица (обход "дyрaк" латиницей). */
    static String toCyr(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            int i = LAT.indexOf(c);
            b.append(i >= 0 ? CYR.charAt(i) : c);
        }
        return b.toString();
    }

    /** "дууурак" -> "дурак". */
    static String squeeze(String s) {
        StringBuilder b = new StringBuilder(s.length());
        char prev = 0;
        for (char c : s.toCharArray()) {
            if (c != prev) b.append(c);
            prev = c;
        }
        return b.toString();
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("[^\\p{L}\\p{N}_]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private static boolean hasCyr(String t) {
        for (char c : t.toCharArray()) if (c >= 'а' && c <= 'я') return true;
        return false;
    }

    // ---------------- Разбор чата ----------------

    public Parsed parse(String line) {
        line = line.replaceAll("§.", "").strip();
        Matcher m = chat.matcher(line);
        if (!m.find()) return null;
        String nick = m.group("nick");
        String before = line.substring(0, m.start("nick"));
        if (ignorePrefix.matcher(before).find()) return null;
        String msg = m.group("msg").strip();
        if (msg.isEmpty()) return null;
        return new Parsed(nick, msg);
    }

    // ---------------- Проверка ----------------

    private boolean any(List<Pattern> ps, String text) {
        for (Pattern p : ps) if (p.matcher(text).find()) return true;
        return false;
    }

    private boolean insultWord(String t) {
        if (insultExact.contains(t)) return true;
        for (String p : insultPrefix) if (t.startsWith(p)) return true;
        for (String p : insultContains) if (t.contains(p)) return true;
        return false;
    }

    private static final Set<String> LINK_WORDS = Set.of("твоя", "твою", "твой", "твоей", "твоего", "твои", "твоих", "у", "ты", "тваю", "твая", "-");

    /** 6.3: слово о родных и рядом (±2 слова) оскорбление или пошлый глагол. */
    private boolean relativeInsult(List<String> t) {
        for (int i = 0; i < t.size(); i++) {
            boolean rel = false;
            for (String p : relativePrefix) if (t.get(i).startsWith(p)) rel = true;
            if (!rel) continue;
            for (int j = Math.max(0, i - 2); j <= Math.min(t.size() - 1, i + 2); j++) {
                if (j == i) continue;
                String w = t.get(j);
                for (String v : relativeVerbs) if (w.contains(v)) return true;
                // оскорбление — вплотную или через "твоя/твою/у"
                boolean near = Math.abs(j - i) == 1 || LINK_WORDS.contains(t.get((i + j) / 2));
                if (near && insultWord(w)) return true;
            }
        }
        return false;
    }

    private boolean insult(List<String> cyrTokens, List<String> latTokens) {
        for (String t : cyrTokens) if (insultWord(t)) return true;
        for (String t : latTokens) for (String p : insultLatin) if (t.startsWith(p)) return true;
        return false;
    }

    private static boolean startsAny(List<String> tokens, Set<String> prefixes) {
        for (String t : tokens) for (String p : prefixes) if (t.startsWith(p)) return true;
        return false;
    }

    private static boolean containsAny(List<String> tokens, Set<String> parts) {
        for (String t : tokens) for (String p : parts) if (t.contains(p)) return true;
        return false;
    }

    private boolean mentionsStaff(String low, List<String> cyrTokens, List<String> latTokens) {
        if (startsAny(cyrTokens, staffWords) || startsAny(latTokens, staffWords)) return true;
        for (String n : cfg.staffNicks) if (!n.isBlank() && low.contains(n.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private boolean linkIsOfficial(String low) {
        if (cfg.officialDomains.isEmpty()) return false;
        // нарушение, если есть хоть одна ссылка не из официального списка
        Matcher m = Pattern.compile("(https?://\\S+|www\\.\\S+|\\b[\\w-]{2,}\\.[a-zа-я]{2,}\\S*)").matcher(low);
        boolean found = false;
        while (m.find()) {
            found = true;
            String link = m.group();
            boolean ok = false;
            for (String d : cfg.officialDomains) if (!d.isBlank() && link.contains(d.toLowerCase(Locale.ROOT))) ok = true;
            if (!ok) return false;
        }
        return found;
    }

    private static final Set<String> FIRST_PERSON = Set.of("я", "мы", "меня", "мне", "мной");
    private static final Set<String> SECOND_PERSON = Set.of("ты", "тебя", "тебе", "тобой", "вы", "вас", "вам", "вами",
            "твой", "твоя", "твое", "твои", "тя");

    private static boolean hasSecondPerson(List<String> t) {
        for (String w : t) if (SECOND_PERSON.contains(w)) return true;
        return false;
    }

    /** Все оскорбительные слова стоят сразу после «я» / «мы» (в пределах 3 слов). */
    private boolean selfInsult(List<String> t) {
        boolean any = false;
        for (int i = 0; i < t.size(); i++) {
            if (!insultWord(t.get(i))) continue;
            boolean self = false;
            for (int j = Math.max(0, i - 3); j < i; j++) if (FIRST_PERSON.contains(t.get(j))) self = true;
            if (!self) return false;
            any = true;
        }
        return any;
    }

    /** Возвращает нарушение или null. Учитывает историю сообщений (флуд). */
    public Violation check(String nick, String msg) {
        long now = System.currentTimeMillis();
        String low = lower(msg);
        String cyr = squeeze(toCyr(low));
        List<String> cyrTokens = new ArrayList<>();
        for (String t : tokens(cyr)) cyrTokens.add(t);
        List<String> latTokens = new ArrayList<>();
        for (String t : tokens(squeeze(low))) if (!hasCyr(t)) latTokens.add(t);

        // история для флуда
        Deque<long[]> h = history.computeIfAbsent(nick.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>());
        long hash = cyr.replaceAll("[^\\p{L}\\p{N}]", "").hashCode();
        h.addLast(new long[]{now, hash});
        long keep = Math.max(cfg.floodWindowSec, cfg.spamWindowSec) * 1000L;
        while (!h.isEmpty() && now - h.peekFirst()[0] > keep) h.pollFirst();

        boolean isInsult = insult(cyrTokens, latTokens);
        // «я пидор» — человек говорит про себя, это не оскорбление игрока
        if (isInsult && selfInsult(cyrTokens) && !hasSecondPerson(cyrTokens)) isInsult = false;

        if (any(serverAds, low)) return v(nick, "7.5", "пиар другого сервера", msg);
        if (any(links, low) && !linkIsOfficial(low)) return v(nick, "7.4", "ссылка / пиар канала", msg);
        if (any(leave, cyr) || any(leave, low)) return v(nick, "7.6", "призыв покинуть проект / бунт", msg);
        if (relativeInsult(cyrTokens))
            return v(nick, "6.3", "оскорбление родных", msg);
        boolean threat = any(threats, cyr) || any(threats, low);
        if ((isInsult || threat) && mentionsStaff(low, cyrTokens, latTokens))
            return v(nick, "6.2", "оскорбление персонала", msg);
        if (any(antiDonate, cyr) || any(antiDonate, low)) return v(nick, "7.7", "против доната / оскорбление сервера", msg);
        if (isInsult || threat) return v(nick, "6.1", threat ? "угроза игроку" : "оскорбление игрока", msg);
        if (any(impersonate, low)) return v(nick, "7.8", "выдаёт себя за персонал", msg);
        if (any(fakes, low)) return v(nick, "7.2", "фейковое ЛС / наказание", msg);
        if (any(begging, cyr) || any(begging, low)) return v(nick, "7.3", "попрошайничество", msg);

        int same = 0, recent = 0;
        for (long[] e : h) {
            if (e[1] == hash && now - e[0] <= cfg.floodWindowSec * 1000L) same++;
            if (now - e[0] <= cfg.spamWindowSec * 1000L) recent++;
        }
        if (same >= cfg.floodRepeats) {
            h.clear();
            return v(nick, "7.1", "флуд (" + same + " одинаковых)", msg);
        }
        if (recent >= cfg.spamMessages) {
            h.clear();
            return v(nick, "7.1", "спам (" + recent + " сообщ. за " + cfg.spamWindowSec + "с)", msg);
        }
        return null;
    }

    /** Проверка без учёта истории — для /mh test. */
    public Violation test(String msg) {
        Violation r = check("__test__", msg);
        history.remove("__test__");
        return r;
    }

    private static Violation v(String nick, String rule, String reason, String msg) {
        return new Violation(nick, rule, reason, msg);
    }
}
