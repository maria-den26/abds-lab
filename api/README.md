# API ЭнергоСбыта

Коллекция `Energosbyt.postman_collection.json` содержит запросы всех эндпоинтов лабораторной №1,
сохранённые примеры ответов и проверки HTTP-статусов. Примеры иллюстративные: реальные значения зависят
от датасета и выполненных изменений.

## Импорт и настройки

1. Из корня проекта выполните `make run`, затем `make setup` для пустой учебной БД.
2. В Postman выберите Import и файл `api/Energosbyt.postman_collection.json`.
3. Проверьте переменную коллекции `base_url`: `http://localhost:8080` или адрес с вашим `APP_PORT`.
4. `realty_id` и `user_id` используются в путях запросов. Идентификаторы в JSON-теле задаются отдельно:
   перед отправкой проверьте `user_id`, `gauge_id` и `tariff_id` в теле.

Авторизации, cookie и сессий нет. PATCH и POST меняют учебные данные; запросы чтения не требуют предварительного входа.

## Последовательность ручной проверки

| Действие | Что проверить |
| --- | --- |
| Ping, Get user, Get realty | Доступность и исходные данные |
| Update user | Новое ФИО и телефон; при повторной смене телефона обновляется `phone_prev` |
| Submit or correct reading дважды за одну дату | Get readings возвращает последнее показание без дублей |
| Get invoices | Порядок по убыванию даты, пагинация и общее количество |
| Get tariff before/after change | Разные версии на границе 2026-11-01 |
| Replace gauge | Через SQL проверить новую активную установку и схлопывание старой |
| Missing user, Invalid invoice limit | Статусы 404/400 и JSON с полем `message` |

SQL для всех сценариев SCD и удаления ошибочной строки приведён в [README проекта](../README.md#примеры-sql-для-scd).

## Запросы из PowerShell

```powershell
$baseUrl = 'http://localhost:8080'
Invoke-RestMethod "$baseUrl/users/1"
Invoke-RestMethod "$baseUrl/realty/1/readings?date_from=2026-10-01&date_to=2026-10-31"

$body = @{ fio = 'Петрова Анна Сергеевна'; phone = '+79111111111' } | ConvertTo-Json
Invoke-RestMethod "$baseUrl/users/1" -Method Patch -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

При изменении порта обновите `base_url` в Postman и `$baseUrl` в примерах.
