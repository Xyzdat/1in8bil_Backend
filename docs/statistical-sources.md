# Источники расширенной карточки рождения и смертности

Дополнение от 26.11.2026: [семья, условия жилья, работа и доходы для 36 стран](socioeconomic-statistics.md). Наборы хранятся в отдельных справочных таблицах. [Подключение к новым жизням](socioeconomic-engine.md) сохраняет прежние правила для старых сохранений.

Текущий релиз `birth-context-2026-v2` от 24.11.2026: 36 страновых профилей и 559 944 строки смертности. Покрытие — [country-expansion.md](country-expansion.md). Ресурсы в `statistics/expanded-v2/`: manifest с SHA-256, countries.json, mortality/*.json.gz. Прежний v1 (3 страны и 46 662 строки в statistics/expanded/) и 12 наблюдений WDI 2023 сохранены для совместимости.

## ООН, World Population Prospects 2024

[Официальный источник](https://population.un.org/wpp/), [описатель файлов и лицензии](https://population.un.org/wpp/assets/downloads.json). Лицензия CC BY 3.0 IGO. Атрибуция: United Nations, Department of Economic and Social Affairs, Population Division (2024). World Population Prospects 2024, Online Edition.

Использованы CSV medium projection:

- `WPP2024_Demographic_Indicators_Medium.csv.gz`: Births, LEx, Q5, SRB, 2026, 36 стран из country-catalog.json и мировое число рождений. SRB (мальчиков на 100 девочек) делится на 100 для получения отношения полов.
- `WPP2024_Life_Table_Complete_Medium_Male_2024-2100.csv.gz` и аналогичный Female: qx, mx по возрасту и году.
- База URL: `https://population.un.org/wpp/assets/Excel%20Files/1_Indicator%20(Standard)/CSV_FILES/`.

Преобразования: Births в тысячах → число рождений × 1000; Q5 на 1000 рождений → вероятность /1000; qx уже вероятность. Прогнозы не выдаются за измерения 2026 года. ВВП не берётся из WPP. Таблица 100+ открытая: qx=1 не используется как годовой риск, берётся mx. Ограничения индивидуального моделирования описаны в `birth-and-life-v2.md`.

## Всемирный банк, WDI

[ВВП на душу населения](https://data.worldbank.org/indicator/NY.GDP.PCAP.CD), [официальная CSV-выгрузка](https://api.worldbank.org/v2/en/indicator/NY.GDP.PCAP.CD?downloadformat=csv), [лицензия](https://datacatalog.worldbank.org/public-licenses). CC BY 4.0, атрибуция: World Bank, World Development Indicators, NY.GDP.PCAP.CD. Извлечены значения 2023 года для 36 стран; выпуск обновлён 11.11.2026. Единицы — текущие доллары США, не PPP и не рубли. Из-за недоступности JSON API использован официальный канал массовой CSV-выгрузки.

Доля городского населения для 36 стран взята из [SP.URB.TOTL.IN.ZS](https://api.worldbank.org/v2/en/indicator/SP.URB.TOTL.IN.ZS?downloadformat=csv), WDI 2023, обновление 11.11.2026. Это приближение городской доли рождений, не отдельная оценка городских рождений. Для новых жизней соотношение полов берётся из UN WPP 2026; прежний WDI снимок сохранён для совместимости.

## Unicode CLDR 48

[Релиз](https://github.com/unicode-org/cldr-json/tree/48.0.0), [лицензия Unicode License V3](https://github.com/unicode-org/cldr-json/blob/48.0.0/LICENSE). Копия лицензии включена в `statistics/expanded/UNICODE-LICENSE.txt`.

Использованы `cldr-core/supplemental/territoryInfo.json` и `currencyData.json`: доли владения языками и действующая валюта территории. Языковые доли включают вторые языки, пересекаются и не являются распределением домашнего языка новорождённых. Выбор языка семьи пропорционально этим весам помечен SAMPLED_PROXY. Это открытые справочные данные, не обследование семей.

## Pew Research Center

[Открытая таблица Religious Composition by Country, 2010–2070](https://www.pewresearch.org/feature/religious-composition-by-country-2010-2070/), [условия использования](https://www.pewresearch.org/about/terms-and-conditions/). Использованы семь долей религиозных групп населения 36 стран за 2020 год из публичной таблицы. Закрытая загрузка полного набора за аккаунтом не использовалась. Полная HTML-страница не публикуется в репозитории: только извлечённые значения 36 стран. Условия предусматривают атрибуцию; это не CC BY и не передача прав на весь сайт.

Атрибуция: Pew Research Center, Religious Composition by Country, 2010–2070, 2026 public table, 2020 estimates. DOI: 10.58094/5shf-2d69. Материал преобразован: выбраны 36 стран, исходные названия категорий сохранены как коды, в интерфейсе возможен русский перевод. Pew Research Center has published the original content in English but has not reviewed or approved this translation. Организация не одобряет и не спонсирует игру.

Доли населения не равны долям семей новорождённых. Генерация веры семьи по этим долям помечена SAMPLED_PROXY, религия не определяет характер, ауру, богатство или моральные решения персонажа.

## Что не является статистикой

`family-model-2`: два родителя, возраст матери 22–35, отец старше на 0–5 лет, 0–3 старших детей с согласованными возрастами; небольшой список имён; начальная близость, профессии и жилище; равновероятный выбор одного из пяти игровых уровней достатка. Это явно обозначенные GAME_ASSUMPTION. Равновероятность квинтилей среди рождений не доказана; модель пока не учитывает связь достатка и рождаемости. Профессии не формируют статистически точный рынок труда. Собственность на жильё и электричество остаются null.

## Воспроизведение

`scripts/download-expanded-statistics.ps1` скачивает исходники в игнорируемую папку `data/statistics/expanded`. `node scripts/prepare-expanded-statistics.mjs` извлекает три страны без обращения к сети. Ресурсы для обычного запуска уже находятся в репозитории; скачивать сотни мегабайт на каждом компьютере не нужно. Автоматические тесты не запускают скачивание.

Если источник или контрольная сумма изменились, публикуется новый идентификатор релиза; существующий импорт не перезаписывается. При прежних исходниках экстрактор сохраняет прежнюю дату manifest. Импорт транзакционный, с блокировкой и проверкой количества строк. Секретов или персональных данных реальных людей в наборе нет.
