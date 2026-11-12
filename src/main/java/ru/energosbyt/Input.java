package ru.energosbyt;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Строгая проверка JSON и параметров URL: дроби, логические значения и числовые строки не считаются целыми
 * числами.
 */
public final class Input {
    private static final BigInteger UINT64_MAX = new BigInteger("18446744073709551615");
    private Input() { }
    /**
     * Принимает только десятичные цифры и сохраняет полный диапазон UInt64, не ограничивая его типом long.
     *
     * @param value значение из пути запроса
     * @return нормализованное десятичное представление
     * @throws ApiException если идентификатор недопустим
     */
    public static String id(String value) {
        try {
            if (value == null || !value.matches("[0-9]+")) throw new IllegalArgumentException();
            BigInteger id = new BigInteger(value);
            if (id.compareTo(UINT64_MAX) > 0) throw new IllegalArgumentException();
            return id.toString();
        } catch (IllegalArgumentException e) { throw ApiException.invalidParameter("id"); }
    }
    /**
     * Проверяет, что тело запроса является JSON-объектом.
     *
     * @param body разобранное тело запроса
     * @throws ApiException если передан другой тип или {@code null}
     */
    public static void object(JsonNode body) {
        if (body == null || !body.isObject()) throw new ApiException(400, "invalid request body");
    }
    /**
     * Проверяет целое неотрицательное JSON-число в диапазоне ClickHouse.
     *
     * @param body тело запроса
     * @param field имя проверяемого поля
     * @param wide признак диапазона UInt64 вместо UInt32
     * @return проверенное число
     * @throws ApiException если тип или диапазон недопустимы
     */
    public static BigInteger number(JsonNode body, String field, boolean wide) {
        JsonNode value = body.get(field);
        BigInteger max = wide ? UINT64_MAX : BigInteger.valueOf(4294967295L);
        if (value == null || !value.isIntegralNumber() || value.bigIntegerValue().signum() < 0 || value.bigIntegerValue().compareTo(max) > 0)
            throw ApiException.invalidField(field);
        return value.bigIntegerValue();
    }
    /**
     * Отклоняет пустую строку и значение длиннее 500 символов; после проверки удаляет внешние пробелы.
     *
     * @param body тело запроса
     * @param field имя поля
     * @return строка без внешних пробелов
     * @throws ApiException если строка недопустима
     */
    public static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 500)
            throw ApiException.invalidField(field);
        return value.textValue().trim();
    }
    /**
     * Проверяет формат YYYY-MM-DD, существование календарной даты и диапазон от 1970-01-01 до 2106-02-06
     * включительно.
     *
     * @param value исходная строка
     * @param field имя поля или параметра для сообщения об ошибке
     * @param parameter {@code true} для сообщения об ошибке параметра URL, {@code false} для поля JSON
     * @return разобранная дата
     * @throws ApiException если дата недопустима
     */
    public static LocalDate date(String value, String field, boolean parameter) {
        LocalDate date = calendarDate(value, field, parameter);
        if (date.isBefore(LocalDate.of(1970, 1, 1)) || date.isAfter(LocalDate.of(2106, 2, 6)))
            throw parameter ? ApiException.invalidParameter(field) : ApiException.invalidField(field);
        return date;
    }

    /**
     * Разбирает календарную дату независимо от диапазона хранения ClickHouse.
     * Поиск по корректной дате вне истории данных должен отличаться от ошибки формата запроса.
     *
     * @param value дата в формате {@code YYYY-MM-DD}
     * @param field имя поля или параметра для сообщения об ошибке
     * @param parameter признак параметра URL вместо поля JSON
     * @return существующая календарная дата
     * @throws ApiException если дата отсутствует, имеет неверный формат или не существует
     */
    public static LocalDate calendarDate(String value, String field, boolean parameter) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("date", "", 0);
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw parameter ? ApiException.invalidParameter(field) : ApiException.invalidField(field);
        }
    }
    /**
     * Проверяет неотрицательное целое значение пагинации.
     *
     * @param value исходная строка
     * @param name имя параметра
     * @return целое значение от нуля до {@link Long#MAX_VALUE}
     * @throws ApiException если параметр недопустим
     */
    public static long page(String value, String name) {
        try {
            if (value == null || !value.matches("[0-9]+")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException e) { throw ApiException.invalidParameter(name); }
    }
}
