# Duty Scheduler — Android app

Минимальное Android-приложение, которое:

1. Принимает `.xls` (BIFF8) — шаблон **«График дежурств на дому»** (РП-МТС-176-7, Приложение 1).
2. Парсит шапку, дни, ФИО и для каждого сотрудника отмечает дни дежурства.
3. Различает **главного** и **резервного** дежурного по заливке ячейки: цветная (жёлтая) = главный, без заливки = резерв.
4. По одному тапу пушит дни выбранного сотрудника в выбранный системный календарь устройства (Google Calendar / Local / любой `CalendarContract` с правом записи).
5. Перед записью **чистит** старые события с префиксом «Дежурство…» в том же месяце для того же календаря, чтобы повторный импорт не плодил дубли.

## Стек

- Kotlin 1.9, Android Gradle Plugin 8.5, JVM 17.
- `com.google.android.material` 1.12 (Material 3), AndroidX Core/AppCompat/ConstraintLayout/Activity-ktx.
- `net.sourceforge.jexcelapi:jxl:2.6.12` — лёгкий парсер старого .xls (BIFF8). Используется только `Workbook`, `Sheet`, `Cell.cellFormat.pattern` — этого хватает, чтобы отличить «жёлтую» (`Pattern.SOLID` / любой не-`NONE` паттерн) от пустой ячейки.
- `kotlinx-coroutines-android` 1.8 — для IO в фоне.
- `core-library-desugaring` 2.0 — нужен `java.time` на API 24+.
- `minSdk 24`, `targetSdk 35`.

## Структура

```
apps/duty-scheduler/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/ru/mts/duty/
│       │   ├── model/Model.kt              # DutyEntry, DutyRole, Schedule, CalendarRef
│       │   ├── parser/ScheduleParser.kt    # XLS → Schedule
│       │   ├── calendar/CalendarSync.kt    # list / clear / insert calendar events
│       │   └── ui/MainActivity.kt          # UI + intent flow
│       └── res/ (layout, themes, strings, xml)
├── fixtures/
│   └── duty_2026_06.xls                    # рабочий шаблон, на котором всё проверено
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

## Сборка (локально)

Нужны JDK 17 и Android SDK (API 35, build-tools 35).

```bash
cd apps/duty-scheduler
# первый раз генерируем wrapper (если не закоммичен gradle-wrapper.jar):
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# APK будет в app/build/outputs/apk/debug/app-debug.apk
```

## Сборка (CI / Capy Build agent)

`tools/build-apk.sh` ставит cmdline-tools и платформу 35, генерирует wrapper и собирает дебаг-APK без интерактивных шагов.

## Поведение

1. На старте — запрашивает `READ_CALENDAR` / `WRITE_CALENDAR`.
2. Спиннер «Календарь» собирается из `CalendarContract.Calendars` с `CALENDAR_ACCESS_LEVEL >= CONTRIBUTOR`. Если календарей нет — показывает Snackbar.
3. Кнопка «Выбрать файл графика» открывает SAF, фильтры MIME `application/vnd.ms-excel` + `*/*` (некоторые файл-менеджеры отдают `octet-stream`).
4. После парсинга — показывает «Разобрано: <имя> — N сотрудников, <месяц год>». Если файл нестандартный — выводит ошибку и подсказку.
5. Спиннер «Сотрудник» — список ФИО. Под ним краткая статистика: «Главных: X · Резерв: Y».
6. «Записать в календарь»:
   - удаляет все события с `TITLE LIKE 'Дежурство%'` и стартом в выбранном месяце для выбранного календаря;
   - вставляет новые события: одно событие на сутки, заголовок «Дежурство (главный)» либо «Дежурство (резерв)», `DTSTART = 00:00`, `DTEND = 23:59` в системной TZ, без напоминаний.
   - в логе показывает: «Удалено N прежних…» и «Добавлено M событий: главных X, резерв Y».

## Что детектируется как «главный»

В шаблоне для главного дежурного ячейка с «1» заливается жёлтым, для резерва — оставляется пустой. На практике в файлах встречаются три состояния:

| Состояние ячейки | jxl: `pattern` | jxl: `backgroundColour` | Интерпретация |
|---|---|---|---|
| Без заливки | `NONE` | `DEFAULT_BACKGROUND` / `null` | **резерв** |
| Сплошная белая заливка (визуально неотличима от «без заливки») | `SOLID` | `WHITE` | **резерв** |
| Сплошная жёлтая заливка | `SOLID` | `YELLOW` | **главный** |
| Любая другая сплошная заливка нелаком (RGB не near-white) | `SOLID` | non-white | **главный** |

Реализовано в `ScheduleParser.roleOf()`.

## Очистка перед вставкой

При повторном импорте того же месяца приложение вызывает `clearMonth(...)`, который:

- выбирает события в выбранном календаре с `DTSTART` в этом месяце;
- удаляет те, у кого `TITLE LIKE 'Дежурство%'` ИЛИ `DESCRIPTION LIKE '[duty-scheduler]%'`;
- удаляет через **sync-adapter URI** (`CALLER_IS_SYNCADAPTER=true` + account_name/type календаря). Это даёт **hard-delete**, иначе провайдер просто помечает `deleted=1`, и на повторных импортах накапливаются дубли.

При вставке новые события получают в описании сентинел `[duty-scheduler]`, по которому их потом находит `clearMonth`, даже если кто-то изменит схему заголовка.

## Известные ограничения

- Только `.xls` (BIFF8). `.xlsx` (Office Open XML) сейчас не поддерживается — нужен `poi-ooxml`, увеличит APK ~ на 6 МБ.
- Один лист на файл. Если в шаблоне когда-то появится «Лист2» с дежурствами — парсер их проигнорирует.
- TZ всегда системная. Если у сотрудника часовой пояс отличается от устройства — отредактируй вручную или допиши выбор TZ в UI.
- Главный/Резерв — детектируются по `CellFormat.pattern != NONE`. Если в шаблоне для «жёлтых» решат использовать рамку, а не заливку — нужно адаптировать (`borderColor`).
