package ru.energosbyt;

/**
 * Ожидаемая ошибка API с HTTP-статусом и сообщением для клиента.
 */
public class ApiException extends RuntimeException {
    private final int status;
    /**
     * Создаёт ожидаемую ошибку API.
     *
     * @param status HTTP-статус ответа
     * @param message сообщение для клиента
     */
    public ApiException(int status, String message) { super(message); this.status = status; }
    /**
     * Возвращает HTTP-статус ошибки.
     *
     * @return код статуса
     */
    public int status() { return status; }
    /**
     * Создаёт ошибку отсутствующей сущности.
     *
     * @return ошибка со статусом 404
     */
    public static ApiException notFound() { return new ApiException(404, "Not found"); }
    /**
     * Создаёт ошибку поля JSON.
     *
     * @param name имя поля
     * @return ошибка со статусом 400
     */
    public static ApiException invalidField(String name) { return new ApiException(400, "invalid \"" + name + "\" field"); }
    /**
     * Создаёт ошибку параметра URL.
     *
     * @param name имя параметра
     * @return ошибка со статусом 400
     */
    public static ApiException invalidParameter(String name) { return new ApiException(400, "invalid \"" + name + "\" parameter"); }
}
