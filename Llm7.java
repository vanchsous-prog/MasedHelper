package ru.masedhelper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Проверка через бесплатный ИИ LLM7 (OpenAI-совместимый API). Никогда не блокирует игру: всё асинхронно. */
public class Llm7 {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private static final String PROMPT = "Ты фильтр чата Minecraft-сервера. Тебе дают одно сообщение игрока. "
            + "Определи, оскорбляет ли оно ДРУГОГО человека (например «ты пидор», «он дебил», «вы все тупые»). "
            + "Если игрок говорит про себя («я пидор», «я дебил», «я тупой») или это не оскорбление — это НЕ оскорбление другого. "
            + "Ответь одним словом: YES — оскорбляет другого, NO — не оскорбляет другого.";

    /**
     * TRUE — оскорбление направлено на другого, FALSE — нет (про себя / не оскорбление),
     * null — ИИ недоступен или ответил непонятно (тогда мод решает сам, как раньше).
     */
    public static CompletableFuture<Boolean> isDirectedInsult(Config cfg, String message) {
        try {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", PROMPT);
            JsonObject usr = new JsonObject();
            usr.addProperty("role", "user");
            usr.addProperty("content", message.length() > 300 ? message.substring(0, 300) : message);
            JsonArray msgs = new JsonArray();
            msgs.add(sys);
            msgs.add(usr);

            JsonObject body = new JsonObject();
            body.addProperty("model", cfg.llm7Model);
            body.addProperty("temperature", 0);
            body.addProperty("max_tokens", 10);
            body.add("messages", msgs);

            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.llm7Url))
                    .timeout(Duration.ofSeconds(Math.max(2, cfg.llm7TimeoutSec)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (cfg.llm7Token == null || cfg.llm7Token.isBlank() ? "unused" : cfg.llm7Token))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(Llm7::parse)
                    .exceptionally(e -> {
                        MasedHelper.LOG.warn("LLM7 недоступен: {}", e.toString());
                        return null;
                    });
        } catch (Exception e) {
            MasedHelper.LOG.warn("LLM7 ошибка запроса: {}", e.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    private static Boolean parse(HttpResponse<String> resp) {
        try {
            if (resp.statusCode() != 200) return null;
            String text = JsonParser.parseString(resp.body()).getAsJsonObject()
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString()
                    .trim().toUpperCase();
            if (text.startsWith("YES")) return Boolean.TRUE;
            if (text.startsWith("NO")) return Boolean.FALSE;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
