# Запуск с Docker Compose и VS Code

| Режим | HTTP backend | PostgreSQL с Windows |
| --- | --- | --- |
| Backend и база в Compose | `http://127.0.0.1:8081` | `127.0.0.1:5433` |
| Java из VS Code и установленная PostgreSQL | `http://localhost:8080` | `localhost:5432` |
| Java из VS Code и база в Compose | `http://localhost:8080` | `127.0.0.1:5433` |

Compose использует PostgreSQL 18, которую поддерживает текущая версия Flyway. Установленная PostgreSQL 19 продолжает работать на своём порту. У обеих баз имя `life_db` и пользователь `life_app`. Данные хранятся отдельно. [Образ PostgreSQL](https://hub.docker.com/_/postgres)

## Оба сервиса в Docker

Запустите Docker Desktop в режиме Linux-контейнеров. В PowerShell из каталога backend создайте локальный файл настроек.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

В `.env` задайте отдельный пароль в `LIFE_DOCKER_DB_PASSWORD`. Если пароль содержит знак `$`, заключите значение в одинарные кавычки. Файл исключён из Git и контекста сборки Docker. Поля `LIFE_DOCKER_HTTP_PORT` и `LIFE_DOCKER_DB_PORT` можно поменять, если порт уже занят.

```powershell
docker compose up -d --build --wait
docker compose ps
Invoke-RestMethod http://127.0.0.1:8081/api/v1/health
```

Проверка должна вывести `UP`. Backend в Docker подключается к `postgres:5432` по внутренней сети Compose. Перед стартом приложение ждёт готовности PostgreSQL, затем Flyway применяет миграции. При ошибке запуска проверьте `docker compose logs --tail 80 backend postgres`. [Порядок запуска в Compose](https://docs.docker.com/compose/how-tos/startup-order/)

Для 1in8b Engine Compose читает `LIFE_1IN8B_API_KEY` или `API_KEY` из того же локального `.env` и передаёт ключ в backend. После добавления ключа, если база уже запущена, пересоберите backend командой `docker compose up -d --build --no-deps --wait backend`. Проверка генерации описана в [руководстве по интеграции](integration.md#1in8b-engine).

Команда `docker compose down` останавливает сервисы. Сохранения остаются в именованном томе PostgreSQL. Пароль из `.env` применяется при создании базы в пустом томе. Для смены пароля существующей роли сначала обновите его в PostgreSQL, затем синхронизируйте `.env`.

Чтобы открыть контейнерный backend через frontend в режиме разработки, задайте `DEV_API_TARGET=http://127.0.0.1:8081` в локальном окружении Vite и перезапустите dev-сервер. Укажите другой URL, если изменили порт backend.

## Отладка Java из VS Code

Откройте каталог backend в VS Code. Для установленной PostgreSQL укажите пароль локального `life_app` в переменной `DB_PASSWORD`. Запуск из встроенного терминала описан в [руководстве по интеграции](integration.md#запуск-и-проверка).

Для запуска по F5 можно хранить переменную в локальном файле `.env.vscode` в корне backend. Добавьте строку `DB_PASSWORD=ваш_локальный_пароль`. Укажите `envFile` в конфигурации Java в локальном `.vscode/launch.json`. Если конфигурация уже существует, достаточно добавить это поле.

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Life Backend",
      "request": "launch",
      "mainClass": "com.life.life_backend.LifeBackendApplication",
      "envFile": "${workspaceFolder}/.env.vscode"
    }
  ]
}
```

Оба локальных файла исключены из Git. После запуска проверьте `Invoke-RestMethod http://localhost:8080/api/v1/health`. Если `8080` занят, задайте в `.env.vscode` другой порт, например `SERVER_PORT=18080`, и используйте его в проверке. [Конфигурация Java Debugger](https://code.visualstudio.com/docs/java/java-debugging#_configuration-options)

### Java из VS Code с базой в Docker

Запустите только базу командой `docker compose up -d --wait postgres`. В окружении запуска Java задайте `DB_PASSWORD` из `LIFE_DOCKER_DB_PASSWORD` и `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/life_db`. Если порт базы изменён в `.env`, используйте новый порт в JDBC-адресе.

При прямом обращении из браузера к двум backend используйте `localhost` для локальной Java и `127.0.0.1` для Compose. Браузер разделяет гостевые cookie по имени хоста, а порты сами по себе этого не делают.
