# HTMLEDITORAPK

Два приложения в одном репозитории:

- **[Html Editor](#html-editor)** — мобильный HTML-редактор: пишите HTML, CSS и JavaScript и сразу смотрите результат.
- **[Glass Browser](#glass-browser)** — браузер с прокси, cookies и DevTools-инспектором в стеклянном дизайне.

Оба APK собираются одним прогоном GitHub Actions (артефакты `HtmlEditor-debug-apk` и `GlassBrowser-debug-apk`).

---

# Html Editor

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
- **Автоопределение языка** по расширению (.py/.html) и содержимому; если расширение не помогает — при открытии файла спрашиваем «HTML / Python / Авто», выбор сохраняется для файла.

### Настройки («…» на Result)
- Тема (светлая/тёмная), режим предпросмотра, размер страницы.
- **Цвета**: 4 схемы — Тёплая (по умолчанию), Классика, Синяя, Зелёная (светлые и тёмные варианты), применяются мгновенно.

### Python: что поддерживается
- Типы: int, float, str, bool, None, list, dict, tuple, range.
- Условия (if/elif/else), циклы (while/for, break/continue/pass), функции (def, аргументы по умолчанию, рекурсия), присваивание и распаковка кортежей, срезы, списочные выражения, f-строки.
- Встроенные: print, input, len, int, float, str, bool, abs, min, max, round, sum, type, list, dict, tuple, range, enumerate, sorted, reversed, isinstance.
- Модули: math, random, turtle (forward/backward, right/left, goto, pencolor, pensize, circle, penup/pendown, hideturtle/showturtle, bgcolor, clear/reset, done…).
- Методы: list.append/pop/extend/insert/remove/index/reverse/sort/clear, str.upper/lower/strip/split/join/replace/find/startswith/endswith, dict.get/keys/values/items/pop.
- Ошибки показываются красной строкой с номером строки кода.


## Палитры

Четыре цветовые схемы (4 базовых цвета: фон, панель, акцент, верхний таб), светлая и тёмная версии, выбираются в настройках — **Классика** (`#EDE4D0/#D6E5E9/#3E8FA6/#3C6970`), **Синяя** (`#D6E9F5/#BFE3F5/#8FCBEA/#E8F4FA`), **Зелёная** (`#FFFFFF/#F5FAF6/#A8D5BA/#7FB89A`), **Тёплая** (`#F5EDE3/#E8D9C5/#E8B4B8/#C9C0DC`). Контраст текста выводится автоматически; **по умолчанию — Тёплая**.

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

---

# Glass Browser

Браузер для Android со стеклянным интерфейсом: открывайте сайты по URL, работайте с ними как в Chrome — с cookies, инспектором элементов, JS/SQL и прокси. Модуль `browser/`, приложение `com.glassbrowser.app`.

## Возможности

### Просмотр
- Плавающий «омнибокс» сверху: URL или поисковый запрос (DuckDuckGo), кнопка перехода, полоса прогресса.
- Кнопки назад/вперёд/обновить в нижней стеклянной пилюле.
- **Режим ПК/телефон**: десктопный User-Agent + режим viewport (кнопка в пилюле, переключение с перезагрузкой).
- Тема: **светлая/тёмная** (меню → переключатель), запоминается; системные бары прозрачные.

### Cookies — 4 способа применения (панель «Cookies»)
1. `CookieManager.setCookie` + `flush` — основной путь WebView;
2. `document.cookie` через `evaluateJavascript` — «живое» добавление в DOM (не для HttpOnly);
3. заголовок `Cookie` при перезагрузке страницы (`loadUrl` с заголовками);
4. заголовок `Cookie`, вставляемый **LAN-прокси** в обычные HTTP-запросы (домен-матчинг).

Кнопка «Добавить и применить» прогоняет все способа сразу и пишет журнал. Есть списки cookies сайта (с удалением) и своего хранилища, HttpOnly/Secure-флаги.

### Инспектор элемента (как в DevTools Chrome)
- «Выбрать» → клик по элементу на странице (подсветка ховера, Esc — отмена).
- CSS-селектор строится как `div#id:nth-of-type(n)`-цепочка (cssPath).
- Две вкладки: **HTML** (правка outerHTML с применением замены узла) и **Стили** (правка inline-стилей через CSSOM `setProperty`).
- Обратная связь — через `prompt`-мост `__GLASS__` (JS полностью в Kotlin, без внешних скриптов).

### JS и SQL
- **JS-консоль**: выполнение кода на странице (результат/ошибки), сниппеты с сохранением и флагом «Авто» (запускаются после загрузки каждой страницы).
- **Проба WebSQL**: кнопка проверяет доступность `openDatabase` в WebView.
- **SQL локально**: консоль к SQLite приложения (CREATE/INSERT/SELECT…), таблица результата, кнопка **«Внедрить результат в страницу»** — SELECT превращается в `window.__glassSql = {columns, rows}` на текущей странице.

### Прокси (два уровня)
- **Перехватчик WebView** (`shouldInterceptRequest`): журнал всех HTTP-запросов с эхо cookies (тумблер в панели прокси).
- **Реальный LAN-прокси** (`ServerSocket` на `0.0.0.0:8888`, запасные порты): обычные HTTP-запросы форвардятся с подстановкой ваших cookies, HTTPS — через CONNECT-туннель. Адрес копируется для настройки внешних устройств; журнал LAN отдельно от журнала WebView.

## Дизайн

Своя тема «liquid glass» (не наследует палитру Html Editor): полупрозрачные панели с волосяной обводкой, тени/elevation, нижние листы со скруглением28dp и blur-behind на Android 12+, свои stroke-иконки24dp, адаптивная иконка. Светлая и тёмная версии цветов, анимации появления листов.

## Технологии

- Kotlin + XML (View), зависимости только `core-ktx` + `appcompat`
- `compileSdk`/`targetSdk` — **36**, `minSdk` — **24**
- Разрешения: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` (для прокси и LAN-адреса)
- WebView (JavaScript, DOM storage), SQLite, ServerSocket

## Сборка

```bash
./gradlew :browser:assembleDebug   # APK: browser/build/outputs/apk/debug/browser-debug.apk
```

Или заберите готовый **GlassBrowser-debug-apk** из артефактов GitHub Actions того же прогона, что и Html Editor.

## Структура модуля

```
browser/src/main/java/com/glassbrowser/app/
├── MainActivity.kt        — WebView, omnibox, режим ПК, тема, prompt-мост
├── CookieStore.kt         — хранилище cookies + 4 способа применения
├── InspectorBridge.kt     — JS инспектора в Kotlin (cssPath, выбор, правка HTML/CSS)
├── ProxyServer.kt         — LAN HTTP-прокси (CONNECT + подстановка Cookie)
├── SqlStore.kt            — локальный SQLite + JSON для внедрения в страницу
├── PrefsX.kt, LogHub.kt   — настройки/сниппеты и журналы
├── BaseSheet.kt + Sheet*  — нижние листы (меню, cookies, инспектор, JS, SQL, прокси)
```
