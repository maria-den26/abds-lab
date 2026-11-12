package ru.energosbyt;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Чтение данных и изменение состояния собственников, показаний и установок счётчиков. Блокировка записи
 * действует только внутри этого экземпляра сервиса.
 */
@Service
public class EnergosbytService {
    private final ClickHouse ch;
    /**
     * Создаёт сервис с общим клиентом ClickHouse для чтения и изменения данных.
     *
     * @param ch клиент ClickHouse
     */
    public EnergosbytService(ClickHouse ch) { this.ch = ch; }

    /**
     * Извлекает первую строку запроса или сообщает об отсутствии сущности.
     *
     * @param sql запрос чтения
     * @param params значения параметров
     * @return первая строка
     * @throws ApiException если строк нет
     */
    private JsonNode one(String sql, Map<String, ?> params) {
        JsonNode rows = ch.rows(sql, params);
        if (rows.isEmpty()) throw ApiException.notFound();
        return rows.get(0);
    }
    /**
     * Проверяет наличие идентификатора измерения; FINAL применяется только к таблицам с замещением версий.
     *
     * @param table имя таблицы, заданное кодом сервиса
     * @param id идентификатор сущности
     * @throws ApiException если сущность отсутствует
     */
    private void exists(String table, Object id) {
        one("SELECT id FROM " + table + (table.equals("dim_gauges") ? "" : " FINAL") + " WHERE id={id:UInt64} LIMIT 1", Map.of("id", id));
    }
    /**
     * Читает актуальное состояние собственника с учётом слияния версий.
     *
     * @param id проверенный идентификатор
     * @return собственник без служебных колонок
     */
    public JsonNode user(String id) {
        return one("SELECT id,fio,phone,phone_prev FROM dim_users FINAL WHERE id={id:UInt64}", Map.of("id", id));
    }
    /**
     * Применяет SCD 1 к ФИО и SCD 3 к телефону, сохраняя дату создания собственника.
     * При смене ФИО синхронная мутация исправляет все доступные версии строки. Затем INSERT сохраняет
     * актуальные ФИО и телефоны; эти операции не образуют транзакцию.
     * Новая версия updated_at строго больше предыдущей: колонка версии движка недоступна для ALTER UPDATE.
     *
     * @param id проверенный идентификатор собственника
     * @param body непустое изменение fio и/или phone; повтор того же телефона сохраняет phone_prev
     */
    public synchronized void updateUser(String id, JsonNode body) {
        Input.object(body);
        if (!body.has("fio") && !body.has("phone")) throw ApiException.invalidField("fio");
        String fio = body.has("fio") ? Input.text(body, "fio") : null;
        String phone = body.has("phone") ? Input.text(body, "phone") : null;
        JsonNode current = one("SELECT * FROM dim_users FINAL WHERE id={id:UInt64}", Map.of("id", id));
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("fio", fio == null ? current.get("fio").asText() : fio);
        p.put("phone", phone == null ? current.get("phone").asText() : phone);
        p.put("prev", phone != null && !phone.equals(current.get("phone").asText())
                ? current.get("phone").asText() : current.get("phone_prev").asText());
        long previous = ch.rows("SELECT max(toUnixTimestamp(updated_at)) AS v FROM dim_users WHERE id={id:UInt64}", p)
                .get(0).get("v").asLong();
        long version = Math.max(Instant.now().getEpochSecond(), previous + 1);
        if (version > 4294967295L) throw new ApiException(409, "User version exhausted");
        p.put("created", current.get("created_at").asText());
        p.put("version", version);
        if (fio != null && !fio.equals(current.get("fio").asText()))
            ch.execute("ALTER TABLE dim_users UPDATE fio={fio:String} WHERE id={id:UInt64} SETTINGS mutations_sync=2", p);
        ch.execute("INSERT INTO dim_users VALUES ({id:UInt64},{fio:String},{phone:String},{prev:String},"
                + "{created:DateTime},toDateTime({version:UInt32}))", p);
    }
    /**
     * Читает объект недвижимости без служебных колонок.
     *
     * @param id проверенный идентификатор объекта
     * @return сведения об объекте
     */
    public JsonNode realty(String id) {
        return one("SELECT id,address,square,type,number,rooms,floor,porch FROM dim_realty WHERE id={id:UInt64}", Map.of("id", id));
    }
    /**
     * Добавляет показание или корректировку без удаления старой строки.
     * Новая версия строго позже предыдущей, даже при запросах в одну секунду.
     *
     * @param id проверенный идентификатор объекта
     * @param body показание и идентификаторы связанных измерений
     */
    public synchronized void addReading(String id, JsonNode body) {
        Input.object(body);
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        for (String field : new String[]{"user_id", "gauge_id", "tariff_id", "day_kw", "night_kw"})
            p.put(field, Input.number(body, field, field.endsWith("_id")));
        JsonNode dateNode = body.get("reading_date");
        if (dateNode == null || !dateNode.isTextual()) throw ApiException.invalidField("reading_date");
        p.put("date", Input.date(dateNode.asText(), "reading_date", false));
        realty(id);
        exists("dim_users", p.get("user_id"));
        exists("dim_gauges", p.get("gauge_id"));
        exists("dim_tariffs", p.get("tariff_id"));
        long previous = ch.rows("SELECT max(toUnixTimestamp(created_at)) AS v FROM fact_meter_readings "
                + "WHERE realty_id={id:UInt64} AND reading_date={date:Date}", p).get(0).get("v").asLong();
        long next = Math.max(Instant.now().getEpochSecond(), previous + 1);
        if (next > 4294967295L) throw new ApiException(409, "Reading version exhausted");
        p.put("version", next);
        ch.execute("INSERT INTO fact_meter_readings VALUES ({id:UInt64},{user_id:UInt64},{tariff_id:UInt64},"
                + "{gauge_id:UInt64},{date:Date},{day_kw:UInt32},{night_kw:UInt32},toDateTime({version:UInt32}))", p);
    }
    /**
     * Возвращает актуальное показание на каждую дату включительного периода.
     *
     * @param id проверенный идентификатор объекта
     * @param from начало периода
     * @param to конец периода
     * @return список показаний и его размер
     */
    public Map<String, Object> readings(String id, String from, String to) {
        LocalDate start = Input.date(from, "date_from", true);
        LocalDate end = Input.date(to, "date_to", true);
        if (end.isBefore(start)) throw ApiException.invalidParameter("date_to");
        realty(id);
        JsonNode rows = ch.rows("SELECT reading_date,user_id,tariff_id,gauge_id,day_kw,night_kw "
                + "FROM fact_meter_readings FINAL WHERE realty_id={id:UInt64} AND reading_date BETWEEN {start:Date} AND {end:Date} "
                + "ORDER BY reading_date", Map.of("id", id, "start", start, "end", end));
        return Map.of("readings", rows, "count", rows.size());
    }
    /**
     * Читает страницу счетов в порядке убывания даты выставления.
     *
     * @param id проверенный идентификатор объекта
     * @param limit неотрицательный размер страницы; ноль означает пустой список
     * @param offset число пропускаемых строк
     * @return список invoices и count всех счетов объекта до применения пагинации
     */
    public Map<String, Object> invoices(String id, String limit, String offset) {
        long size = Input.page(limit, "limit");
        long skip = Input.page(offset, "offset");
        realty(id);
        long count = ch.rows("SELECT count() AS n FROM fact_invoice WHERE realty_id={id:UInt64}", Map.of("id", id)).get(0).get("n").asLong();
        JsonNode rows = ch.rows("SELECT tariff_id,gauge_id,day_kw,night_kw,price,deadline,created_at FROM fact_invoice "
                + "WHERE realty_id={id:UInt64} ORDER BY created_at DESC LIMIT {limit:UInt64} OFFSET {offset:UInt64}",
                Map.of("id", id, "limit", size, "offset", skip));
        return Map.of("invoices", rows, "count", count);
    }
    /**
     * Выбирает тариф по условию {@code valid_from <= date && date < valid_to}. При нескольких подходящих
     * тарифах возвращает первый по id.
     *
     * @param date дата поиска
     * @return версия тарифа, действующая на дату
     */
    public JsonNode tariff(String date) {
        LocalDate day = Input.date(date, "date", true);
        return one("SELECT id,name,price_day_kwph,price_night_kwph,valid_from,valid_to FROM dim_tariffs FINAL "
                + "WHERE valid_from <= toDateTime({date:Date}) AND toDateTime({date:Date}) < valid_to "
                + "ORDER BY id,valid_from DESC LIMIT 1", Map.of("date", day));
    }
    /**
     * Записывает отмены активных установок и новую установку одним INSERT; при отсутствии активной установки
     * использует версию 1.
     * Отмена повторяет ключ, время и версию исходной строки; новая установка получает следующую версию.
     *
     * @param id проверенный идентификатор объекта
     * @param body идентификатор нового счётчика
     */
    public synchronized void installGauge(String id, JsonNode body) {
        Input.object(body);
        Object gauge = Input.number(body, "gauge_id", true);
        realty(id);
        exists("dim_gauges", gauge);
        JsonNode active = ch.rows("SELECT gauge_id,created_at,version FROM fact_gauge_bindings FINAL "
                + "WHERE realty_id={id:UInt64} AND sign=1 ORDER BY version DESC", Map.of("id", id));
        long version = 1;
        StringBuilder insert = new StringBuilder("INSERT INTO fact_gauge_bindings VALUES ");
        Map<String, Object> p = new HashMap<>(Map.of("id", id, "gauge", gauge));
        for (int i = 0; i < active.size(); i++) {
            JsonNode row = active.get(i);
            version = Math.max(version, Math.addExact(row.get("version").asLong(), 1));
            p.put("old" + i, row.get("gauge_id").asText());
            p.put("time" + i, row.get("created_at").asText());
            p.put("v" + i, row.get("version").asText());
            insert.append("({old").append(i).append(":UInt64},{id:UInt64},{time").append(i)
                    .append(":DateTime},-1,{v").append(i).append(":UInt64}),");
        }
        p.put("version", version);
        insert.append("({gauge:UInt64},{id:UInt64},now(),1,{version:UInt64})");
        ch.execute(insert.toString(), p);
    }
}
