package ru.energosbyt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Передаёт SQL в ClickHouse по HTTP. Значения параметров связываются сервером, CSV передаются потоком.
 */
@Component
public class ClickHouse {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String host = required("CLICKHOUSE_HOST");
    private final String port = required("CLICKHOUSE_HTTP_PORT");
    private final String user = required("CLICKHOUSE_USER");
    private final String password = System.getenv().getOrDefault("CLICKHOUSE_PASSWORD", "");
    private final String database = required("CLICKHOUSE_DATABASE");

    /**
     * Читает настройки подключения из окружения. Проверяет безопасное имя базы для DDL и десятичную запись
     * порта.
     *
     * @throws IllegalArgumentException если настройки отсутствуют или недопустимы
     */
    public ClickHouse() {
        if (!database.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid database name");
        if (!port.matches("[0-9]+")) throw new IllegalArgumentException("Invalid HTTP port");
    }

    /**
     * Читает обязательную непустую переменную окружения.
     *
     * @param name имя переменной
     * @return значение настройки
     * @throws IllegalArgumentException если значение не задано
     */
    public static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }

    /**
     * Возвращает настроенное имя базы данных.
     *
     * @return имя базы
     */
    public String database() { return database; }

    /**
     * Добавляет FORMAT JSON и возвращает только массив data, без метаданных ClickHouse.
     *
     * @param sql запрос без указания формата ответа
     * @param params значения типизированных параметров запроса
     * @return JSON-массив строк
     * @throws IllegalStateException при ошибке БД или некорректном ответе
     */
    public JsonNode rows(String sql, Map<String, ?> params) {
        try {
            return json.readTree(send(sql + " FORMAT JSON", params, false, null)).get("data");
        } catch (IOException e) {
            throw new IllegalStateException("Invalid ClickHouse response", e);
        }
    }

    /**
     * Выполняет SQL в настроенной базе без разбора тела ответа.
     *
     * @param sql текст SQL-запроса
     * @param params значения типизированных параметров
     */
    public void execute(String sql, Map<String, ?> params) { send(sql, params, false, null); }
    /**
     * Выполняет DDL из базы default, чтобы создание схемы не зависело от существования целевой базы.
     *
     * @param sql доверенный запрос управления схемой
     */
    public void system(String sql) { send(sql, Map.of(), true, null); }
    /**
     * Загружает CSV без заголовка, передавая файл потоком.
     *
     * @param table доверенное имя таблицы из DataTools.TABLES; значения из HTTP-запроса здесь недопустимы
     * @param csv путь к CSV-файлу
     */
    public void load(String table, Path csv) { send("INSERT INTO " + table + " FORMAT CSV\n", Map.of(), false, csv); }

    /**
     * Передаёт параметры отдельно от SQL и проверяет HTTP-статус ответа. При прерывании восстанавливает флаг
     * прерывания потока.
     *
     * @param sql текст запроса
     * @param params значения параметров
     * @param system признак выполнения из базы default
     * @param csv файл для загрузки или {@code null}
     * @return тело ответа сервера
     * @throws IllegalStateException при ошибке HTTP, БД или прерывании потока
     */
    private String send(String sql, Map<String, ?> params, boolean system, Path csv) {
        StringBuilder query = new StringBuilder("database=").append(encode(system ? "default" : database))
                .append("&wait_end_of_query=1&output_format_json_quote_64bit_integers=0");
        params.forEach((key, value) -> query.append("&param_").append(encode(key)).append('=').append(encode(String.valueOf(value))));
        try {
            HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofString(sql);
            if (csv != null) body = HttpRequest.BodyPublishers.concat(body, HttpRequest.BodyPublishers.ofFile(csv));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/?" + query))
                    .header("X-ClickHouse-User", user).header("X-ClickHouse-Key", password)
                    .timeout(Duration.ofSeconds(120)).POST(body).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("ClickHouse " + response.statusCode() + ": " + response.body());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ClickHouse request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("ClickHouse unavailable", e);
        }
    }

    /**
     * Кодирует значение параметра URL в UTF-8.
     *
     * @param value исходное значение
     * @return закодированное значение
     */
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
