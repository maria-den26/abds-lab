package ru.energosbyt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.Map;

/**
 * Обработчики ошибок с единым JSON-ответом; подробности ошибок БД остаются в журнале.
 */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    /**
     * Преобразует ожидаемую ошибку в ответ API.
     *
     * @param error ошибка предметного сценария или валидации
     * @return статус и сообщение ошибки
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> expected(ApiException error) { return message(error.status(), error.getMessage()); }
    /**
     * Обрабатывает отсутствующее или некорректное JSON-тело.
     *
     * @param error ошибка чтения тела
     * @return ответ 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformed(Exception error) { return message(400, "invalid request body"); }
    /**
     * Обрабатывает неизвестный маршрут.
     *
     * @param error ошибка поиска ресурса
     * @return ответ 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> missing(Exception error) { return message(404, "Not found"); }
    /**
     * Обрабатывает недопустимый HTTP-метод.
     *
     * @param error ошибка маршрутизации
     * @return ответ 405
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> method(Exception error) { return message(405, "Method not allowed"); }
    /**
     * Обрабатывает неподдерживаемый формат тела.
     *
     * @param error ошибка типа содержимого
     * @return ответ 415
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> media(Exception error) { return message(415, "Unsupported media type"); }
    /**
     * Записывает подробности сбоя БД в журнал.
     *
     * @param error ошибка обращения к ClickHouse
     * @return ответ 503 без текста SQL
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException error) {
        LOG.error("ClickHouse operation failed", error);
        return message(503, "Database unavailable");
    }
    /**
     * Записывает непредвиденную ошибку в журнал.
     *
     * @param error исходная ошибка
     * @return ответ 500 без внутренних подробностей
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception error) {
        LOG.error("Unexpected request failure", error);
        return message(500, "Internal server error");
    }
    /**
     * Формирует единый JSON-конверт ошибки.
     *
     * @param status HTTP-статус
     * @param text сообщение для клиента
     * @return ответ с полем message
     */
    private ResponseEntity<Map<String, String>> message(int status, String text) {
        return ResponseEntity.status(status).body(Map.of("message", text));
    }
}
