package ru.energosbyt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверки работающих HTTP API и ClickHouse. Требуют подготовленных данных и LAB_INTEGRATION_TEST=true;
 * адрес API задаётся через APP_BASE_URL.
 */
@EnabledIfEnvironmentVariable(named="LAB_INTEGRATION_TEST", matches="true")
class LiveScdTest {
    private final ClickHouse ch = new ClickHouse();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base = ClickHouse.required("APP_BASE_URL");
    /**
     * Читает первое числовое значение проверочного SQL-запроса.
     *
     * @param sql запрос проверки
     * @return значение первой колонки первой строки
     */
    private long scalar(String sql) { return ch.rows(sql, Map.of()).get(0).elements().next().asLong(); }
    /**
     * Вызывает API и проверяет ожидаемый HTTP-статус и формат ответа.
     *
     * @param method HTTP-метод
     * @param path путь с параметрами
     * @param body тело JSON или {@code null}
     * @param status ожидаемый статус
     * @return разобранный JSON или NullNode при пустом теле ответа
     * @throws Exception при ошибке HTTP или JSON
     */
    private JsonNode call(String method, String path, String body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), method + " " + path + " -> " + response.body());
        if (response.body().isEmpty()) return json.nullNode();
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
        return json.readTree(response.body());
    }
    /**
     * Проверяет объёмы датасетов, периоды показаний, пагинацию и исторический расчёт счетов.
     */
    @Test
    void generatedDatasetMatchesTaskAndHistoricalInvoicePrices() throws Exception {
        assertEquals(1000, scalar("SELECT count() FROM dim_realty"));
        assertEquals(1100, scalar("SELECT count() FROM dim_gauges"));
        assertEquals(1000, scalar("SELECT count() FROM dim_users FINAL"));
        assertEquals(3, scalar("SELECT count() FROM dim_tariffs FINAL"));
        assertEquals(1_096_000, scalar("SELECT count() FROM fact_meter_readings FINAL"));
        assertEquals(36_000, scalar("SELECT count() FROM fact_invoice"));
        assertEquals(100, scalar("SELECT count() FROM fact_gauge_bindings FINAL WHERE version>=2 AND sign=1"));
        assertEquals(1000, scalar("SELECT count() FROM fact_gauge_bindings FINAL WHERE sign=1"));
        assertEquals(0, scalar("SELECT count() FROM fact_invoice WHERE deadline != toDate(addMonths(toStartOfMonth(created_at),1))+9"));
        assertEquals(0, scalar("SELECT count() FROM fact_invoice i INNER JOIN (SELECT * FROM dim_tariffs FINAL) t ON i.tariff_id=t.id "
                + "WHERE i.created_at>=t.valid_from AND i.created_at<t.valid_to AND i.price != i.day_kw*t.price_day_kwph+i.night_kw*t.price_night_kwph"));
        assertEquals(0, scalar("SELECT count() FROM (SELECT realty_id,count() n,min(reading_date) lo,max(reading_date) hi FROM fact_meter_readings FINAL GROUP BY realty_id) "
                + "WHERE n!=1096 OR lo!='2023-11-01' OR hi!='2026-10-31'"));
        var page = call("GET", "/realty/1/invoices?limit=2&offset=0", null, 200);
        assertEquals(36, page.get("count").asInt());
        assertEquals(2, page.get("invoices").size());
        assertTrue(page.get("invoices").get(0).get("created_at").asText().compareTo(page.get("invoices").get(1).get("created_at").asText()) > 0);
        assertEquals(0, call("GET", "/realty/1/invoices?limit=0", null, 200).get("invoices").size());
    }
    /**
     * Проверяет SCD, корректировки, замены счётчика и ошибки API на отдельных тестовых записях.
     */
    @Test
    void scdCorrectionReplacementDeletionAndValidation() throws Exception {
        final String id = "90001";
        cleanFixtures();
        try {
            ch.execute("INSERT INTO dim_realty VALUES (90001,'Test address',50,'flat','1',2,1,1,now())", Map.of());
            ch.execute("INSERT INTO dim_gauges VALUES (90001,'Test A','Россия',now()),(90002,'Test B','Россия',now())", Map.of());
            ch.execute("INSERT INTO dim_users VALUES (90001,'Old name','+79001110000','',now(),now())", Map.of());
            call("GET", "/ping", null, 200);
            assertEquals("Old name", call("GET", "/users/" + id, null, 200).get("fio").asText());
            call("PATCH", "/users/" + id, "{\"fio\":\"New name\",\"phone\":\"+79002220000\"}", 204);
            call("PATCH", "/users/" + id, "{\"phone\":\"+79003330000\"}", 204);
            var user = call("GET", "/users/" + id, null, 200);
            assertEquals("New name", user.get("fio").asText());
            assertEquals("+79003330000", user.get("phone").asText());
            assertEquals("+79002220000", user.get("phone_prev").asText());
            assertEquals(0, scalar("SELECT count() FROM dim_users WHERE id=90001 AND fio='Old name'"));
            call("PATCH", "/users/" + id, "{\"phone\":\"+79003330000\"}", 204);
            assertEquals("+79002220000", call("GET", "/users/" + id, null, 200).get("phone_prev").asText());
            call("POST", "/realty/" + id + "/gauges", "{\"gauge_id\":90001}", 201);
            call("POST", "/realty/" + id + "/gauges", "{\"gauge_id\":90002}", 201);
            call("POST", "/realty/" + id + "/gauges", "{\"gauge_id\":90002}", 201);
            assertEquals(1, scalar("SELECT count() FROM fact_gauge_bindings FINAL WHERE realty_id=90001 AND sign=1"));
            assertEquals(3, scalar("SELECT version FROM fact_gauge_bindings FINAL WHERE realty_id=90001 AND sign=1"));
            assertEquals(90002, scalar("SELECT gauge_id FROM fact_gauge_bindings FINAL WHERE realty_id=90001 AND sign=1"));
            String reading = "{\"user_id\":90001,\"gauge_id\":90002,\"tariff_id\":1,\"reading_date\":\"2026-11-01\",\"day_kw\":%d,\"night_kw\":30}";
            for (int value : new int[]{100, 110, 120}) call("POST", "/realty/" + id + "/readings", reading.formatted(value), 201);
            var readings = call("GET", "/realty/" + id + "/readings?date_from=2026-11-01&date_to=2026-11-01", null, 200);
            assertEquals(1, readings.get("count").asInt());
            assertEquals(120, readings.get("readings").get(0).get("day_kw").asInt());
            assertEquals(0, call("GET", "/realty/" + id + "/readings?date_from=2026-12-01&date_to=2026-12-02", null, 200).get("count").asInt());
            assertEquals(620, call("GET", "/tariffs?date=2026-10-31", null, 200).get("price_day_kwph").asInt());
            assertEquals(700, call("GET", "/tariffs?date=2026-11-01", null, 200).get("price_day_kwph").asInt());
            call("GET", "/tariffs?date=2023-10-31", null, 404);
            for (String path : new String[]{"/users/999999", "/realty/999999", "/realty/999999/invoices", "/realty/999999/readings?date_from=2026-10-01&date_to=2026-10-02"})
                assertEquals("Not found", call("GET", path, null, 404).get("message").asText());
            call("POST", "/realty/" + id + "/gauges", "{\"gauge_id\":999999}", 404);
            call("POST", "/realty/" + id + "/readings", reading.formatted(100).replace("90001", "999999"), 404);
            for (String body : new String[]{"{}", "{\"fio\":\"\"}", "{\"phone\":null}", "[]", "{"}) call("PATCH", "/users/" + id, body, 400);
            for (String body : new String[]{"{}", "{\"gauge_id\":-1}", "{\"gauge_id\":1.5}", "{\"gauge_id\":\"1\"}"}) call("POST", "/realty/" + id + "/gauges", body, 400);
            call("POST", "/realty/" + id + "/readings", reading.formatted(-1), 400);
            call("POST", "/realty/" + id + "/readings", reading.formatted(1).replace("2026-11-01", "2026-02-30"), 400);
            for (String path : new String[]{"/tariffs", "/tariffs?date=2026-02-30", "/realty/1/invoices?limit=-1", "/realty/1/invoices?offset=1.2", "/realty/1/readings?date_from=2026-11-02&date_to=2026-11-01"})
                call("GET", path, null, 400);
            ch.execute("ALTER TABLE fact_meter_readings DELETE WHERE realty_id=90001 AND reading_date='2026-11-01' SETTINGS mutations_sync=2", Map.of());
            assertEquals(0, scalar("SELECT count() FROM fact_meter_readings WHERE realty_id=90001"));
        } finally { cleanFixtures(); }
    }
    /**
     * Удаляет строки собственника и объекта 90001, а также счётчики 90001 и 90002. Эти идентификаторы должны
     * быть выделены тестам.
     */
    private void cleanFixtures() {
        for (String table : DataTools.TABLES) {
            String predicate = switch (table) {
                case "dim_tariffs" -> "id=90001";
                case "dim_gauges" -> "id IN (90001,90002)";
                case "dim_realty", "dim_users" -> "id=90001";
                default -> "realty_id=90001";
            };
            ch.execute("ALTER TABLE " + table + " DELETE WHERE " + predicate + " SETTINGS mutations_sync=2", Map.of());
        }
    }
}
