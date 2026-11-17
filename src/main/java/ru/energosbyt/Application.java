package ru.energosbyt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Выбирает режим запуска: HTTP-сервис или команда подготовки данных с завершением процесса.
 */
@SpringBootApplication
public class Application {
    /**
     * Запускает HTTP-сервис без аргументов или выполняет указанную команду.
     *
     * @param args имя команды; второй аргумент задаёт каталог CSV для генерации или загрузки; для
     * HTTP-сервиса массив пуст
     * @throws Exception если запуск сервиса или выполнение команды завершились ошибкой
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            DataTools.run(args);
        } else {
            SpringApplication app = new SpringApplication(Application.class);
            app.setDefaultProperties(java.util.Map.of("server.port", ClickHouse.required("APP_PORT")));
            app.run(args);
        }
    }
}
