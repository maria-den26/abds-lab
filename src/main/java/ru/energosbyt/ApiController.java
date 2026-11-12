package ru.energosbyt;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * HTTP API собственников, недвижимости, показаний и тарифов. Проверку данных и операции SCD выполняет сервис.
 */
@RestController
public class ApiController {
    private final EnergosbytService service;
    /**
     * Связывает HTTP-эндпоинты с сервисом обработки данных.
     *
     * @param service сервис работы с данными
     */
    public ApiController(EnergosbytService service) { this.service = service; }
    /**
     * Проверяет, что HTTP-сервис принимает запросы; подключение к ClickHouse не проверяется.
     *
     * @return статус 200 с пустым телом
     */
    @GetMapping("/ping")
    public ResponseEntity<Void> ping() { return ResponseEntity.ok().build(); }
    /**
     * Возвращает собственника по идентификатору.
     *
     * @param id идентификатор из пути
     * @return JSON собственника
     */
    @GetMapping("/users/{id}")
    public JsonNode user(@PathVariable String id) { return service.user(Input.id(id)); }
    /**
     * Применяет изменения ФИО и телефона собственника.
     *
     * @param id идентификатор из пути
     * @param body поля {@code fio} и/или {@code phone}; требуется хотя бы одно поле
     * @return статус 204 с пустым телом
     */
    @PatchMapping("/users/{id}")
    public ResponseEntity<Void> updateUser(@PathVariable String id, @RequestBody JsonNode body) {
        service.updateUser(Input.id(id), body); return ResponseEntity.noContent().build();
    }
    /**
     * Возвращает объект недвижимости.
     *
     * @param id идентификатор из пути
     * @return JSON объекта
     */
    @GetMapping("/realty/{id}")
    public JsonNode realty(@PathVariable String id) { return service.realty(Input.id(id)); }
    /**
     * Принимает показание или корректировку за дату.
     *
     * @param id идентификатор объекта
     * @param body дата, накопительные показания и идентификаторы собственника, счётчика и тарифа
     * @return статус 201 с пустым телом
     */
    @PostMapping("/realty/{id}/readings")
    public ResponseEntity<Void> addReading(@PathVariable String id, @RequestBody JsonNode body) {
        service.addReading(Input.id(id), body); return ResponseEntity.status(201).build();
    }
    /**
     * Возвращает по одному актуальному показанию на дату; обе границы периода включены.
     *
     * @param id идентификатор объекта
     * @param date_from начало периода
     * @param date_to конец периода
     * @return список актуальных показаний и его размер
     */
    @GetMapping("/realty/{id}/readings")
    public Map<String, Object> readings(@PathVariable String id, @RequestParam(required=false) String date_from,
                                      @RequestParam(required=false) String date_to) {
        return service.readings(Input.id(id), date_from, date_to);
    }
    /**
     * Возвращает счета от новых к старым; общее количество не зависит от размера страницы.
     *
     * @param id идентификатор объекта
     * @param limit неотрицательный размер страницы; ноль означает пустой список
     * @param offset число пропускаемых счетов
     * @return список invoices и count всех счетов объекта до применения пагинации
     */
    @GetMapping("/realty/{id}/invoices")
    public Map<String, Object> invoices(@PathVariable String id, @RequestParam(defaultValue="10") String limit,
                                      @RequestParam(defaultValue="0") String offset) {
        return service.invoices(Input.id(id), limit, offset);
    }
    /**
     * Возвращает тариф, интервал действия которого включает указанную дату.
     *
     * @param date обязательная дата в формате {@code YYYY-MM-DD}
     * @return действующая версия тарифа
     */
    @GetMapping("/tariffs")
    public JsonNode tariff(@RequestParam(required=false) String date) { return service.tariff(date); }
    /**
     * Устанавливает счётчик и аннулирует прежнюю установку.
     *
     * @param id идентификатор объекта
     * @param body идентификатор счётчика
     * @return статус 201 с пустым телом
     */
    @PostMapping("/realty/{id}/gauges")
    public ResponseEntity<Void> installGauge(@PathVariable String id, @RequestBody JsonNode body) {
        service.installGauge(Input.id(id), body); return ResponseEntity.status(201).build();
    }
}
