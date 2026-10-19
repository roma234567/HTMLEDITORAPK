# Html Editor

Мобильный HTML-редактор для Android: пишите HTML, CSS и JavaScript и сразу смотрите результат.

## Возможности

### Редактор
- Подсветка HTML/CSS/JS, номера строк, поиск по тексту («следующее» с переходом по кругу).
- Несколько файлов во вкладках: автосохранение при переключении, память процесса, открытие через системный выборщик (SAF), «Сохранить» / «Сохранить как…».

### Обзор
- Карточка текущего файла, статус изменений, открытие файла.

### Result
- HTML: предпросмотр в WebView (автообновление только при изменении ≥2 символов, ручное обновление по кнопке).
- Режим ПК/телефон и размер страницы −/+/ % (в настройках панели «…»).
- **Python**: встроенная офлайн-интерпретация (без сети). Result делится на **GUI** (холст turtle) и **консоль** print/input — ввод синхронный, строка ввода появляется по запросу input(). Остановка — кнопка «стоп», перезапуск — кнопка обновления.
- **Автоопределение языка** по расширению (.py/.html) и содержимому; вручную — строка «Язык» в настройках (Авто/HTML/Python).

### Настройки («…» на Result)
- Тема (светлая/тёмная), режим предпросмотра, размер страницы.
- **Цвета**: 4 схемы — Классика, Синяя, Зелёная, Тёплая (светлые и тёмные варианты), применяются мгновенно.
- **Язык**: Авто / HTML / Python.

### Python: что поддерживается
- Типы: int, float, str, bool, None, list, dict, tuple, range.
- Условия (if/elif/else), циклы (while/for, break/continue/pass), функции (def, аргументы по умолчанию, рекурсия), присваивание и распаковка кортежей, срезы, списочные выражения, f-строки.
- Встроенные: print, input, len, int, float, str, bool, abs, min, max, round, sum, type, list, dict, tuple, range, enumerate, sorted, reversed, isinstance.
- Модули: math, random, turtle (forward/backward, right/left, goto, pencolor, pensize, circle, penup/pendown, hideturtle/showturtle, bgcolor, clear/reset, done…).
- Методы: list.append/pop/extend/insert/remove/index/reverse/sort/clear, str.upper/lower/strip/split/join/replace/find/startswith/endswith, dict.get/keys/values/items/pop.
- Ошибки показываются красной строкой с номером строки кода.


## Палитры

Четыре цветовые схемы (4 базовых цвета: фон, панель, акцент, верхний таб), светлая и тёмная версии, выбираются в настройках — **Классика** (`#EDE4D0/#D6E5E9/#3E8FA6/#3C6970`), **Синяя** (`#D6E9F5/#BFE3F5/#8FCBEA/#E8F4FA`), **Зелёная** (`#FFFFFF/#F5FAF6/#A8D5BA/#7FB89A`), **Тёплая** (`#F5EDE3/#E8D9C5/#E8B4B8/#C9C0DC`). Контраст текста выводится автоматически.

Без градиентов, glow и неона — только плоские цвета.


## Технологии

- Kotlin + XML (View), минимально зависимостей
- `compileSdk` / `targetSdk` — **36**, `minSdk` — 24
- WebView для предпросмотра, SAF для файлов
- Без лишних разрешений (только `INTERNET` для загрузки ресурсов страниц)

## Сборка

1. Откройте проект в **Android Studio** (Ladybug/Otter или новее).
2. Дождитесь синхронизации Gradle (нужен JDK 17+, интернет — Gradle 8.13 и AGP 8.10.0 скачиваются автоматически).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

Или из командной строки:

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # Release (нужна подпись)
```

## Структура

```
app/src/main/java/com/htmleditor/app/
├── MainActivity.kt        — вкладки, настройки, открытие/сохранение
├── CodeEditor.kt          — подсветка, номера строк, синхронная прокрутка
├── HtmlHighlighter.kt     — regex-подсветка HTML (только span, курсор не сбивается)
├── PreviewController.kt   — WebView: режим ПК/телефон, зум, загрузка кода
├── FileStore.kt           — чтение/запись файлов через SAF
├── TextDiff.kt            — подсчёт изменившихся символов (условие ≥ 2)
└── Prefs.kt               — черновик и настройки в SharedPreferences
```
