English: [README.md](README.md)

# plektra-linux: клавиатура Titan 2 в Linux (Lindroid) как в Plektra

Служба для Linux-контейнера Lindroid (Debian trixie, KDE Plasma 6, kwin_wayland). С ней
аппаратная клавиатура Unihertz Titan 2 ведёт себя так же, как с IME
[Plektra](https://github.com/pkb-rocks/plektra) на Android: работают Sym-страницы, Alt-слой
Titan, Fn как Ctrl с режимом навигации, одноразовые и закреплённые модификаторы, долгое
нажатие и двойной пробел.

## Лицензия и происхождение

GPL-3.0-or-later, полный текст в `COPYING`. Это порт логики Plektra
(GPL-3.0, © участники pkb-rocks/plektra, форк Pastiera). Взята та версия, которая закреплена в
`scripts/fetch-plektra.sh`: тег `nightly/v26.10-nightly.11.184948Z`, коммит `d029832c5260`.
Исходник для сверки: `downloads/plektra-src/plektra`.

Что откуда перенесено:

| Файл Plektra | Что взято |
|---|---|
| `DeviceSpecific.kt` | скан-коды Titan 2: 251 = Ctrl (Fn), 253 = Sym |
| `ModifierKeyHandler.kt`, `core/ModifierStateController.kt` | автомат Shift (off → one-shot → caps), Ctrl/Alt (one-shot, закрепление двойным нажатием, удержание, «намеренное удержание» без клавиш ничего не меняет) |
| `NavModeHandler.kt`, `core/NavModeController.kt` | режим навигации (Ctrl-раскладка, Enter = DPAD_CENTER) |
| `core/SymLayoutController.kt` | цикл страниц SYM, аккорд SYM+клавиша, автозакрытие, «Назад» закрывает страницу |
| `AlternateCharacterManager.kt` | долгое нажатие (300 мс): символ заменяется Alt-символом |
| `InputEventRouter.kt` | порядок обработки клавиши, Ctrl-действия, Alt+Space, Ctrl+Space, сброс Alt пробелом |
| `core/TextInputController.kt`, `AutoCapitalizeHelper.kt` | двойной пробел → «. » и заглавная после конца предложения |
| `SettingsManager.kt`, `SymPagesConfig.kt` | значения по умолчанию |

Таблицы в `mappings/` скопированы из assets Plektra без изменений:
`devices/titan2/device_sym_key_mappings.json` (Alt-слой и страница Titan),
`common/sym/sym_key_mappings.json` (эмодзи), `common/sym/sym_key_mappings_page2.json`
(символы), `common/ctrl/ctrl_key_mappings.json` (Ctrl и навигация).

## Как клавиши попадают в Linux (разбор)

1. Android → LindroidUI. Окно `DisplayActivity` пересылает в контейнер `event.getScanCode()`,
   то есть сырые скан-коды ядра, а не keycode Android
   (`nativeKeyEvent(mDisplayID, event.getScanCode(), ...)`). Коды попадают в uinput
   `Lindroid-Keyboard-<дисплей>`, где объявлены keybit 0..254. Отсюда:
   * Sym (253) и Fn (251) доходят, но в xkb это keycode 261/259, а xkb работает только
     до 255. Без службы обе клавиши в Linux мертвы.
   * Recents (580 / APP_SWITCH) до окна не доходит: его раньше забирает PhoneWindowManager
     («Недавние» Android). Кроме того, в uinput Lindroid этот код не объявлен.
   * Боковые клавиши 249/250 (MACRO_1/2) поглощает `Titan2Parts` KeyHandler в
     system_server. Это его настраиваемые действия.
   * Back (158) доходит. Но LindroidUI сам обрабатывает его как Android-«назад» и показывает
     диалог «остановить контейнер?». Это исправляет патч `vendor_lindroid/0007`.
2. Plektra на Android **участвует**. Окно `DisplayActivity` по умолчанию считается целью
   IME (`ImeFocusController`), поэтому Plektra видит каждую клавишу первой. Своего
   текстового поля у окна нет, так что Plektra идёт по ветке «нет поля ввода»
   (`handleKeyDownWithNoEditableField`):
   * первое нажатие Ctrl (= Fn) поглощает `NavModeHandler` («ждём второго нажатия»);
   * двойное включает навигацию Android, и буквы превращаются в DPAD-события без
     скан-кода, то есть в Linux теряются;
   * Sym включает «power shortcuts», и следующая буква может запустить приложение Android.
   
   Это видно в `out/lindroid-kbd-events.txt`: буквы и Back записаны дважды, Fn (0x00fb)
   один раз. На одном evdev-узле ядро не пропускает два DOWN подряд. Значит, запись склеена
   из двух устройств: аппаратного TitanKey и Lindroid-Keyboard-0, а Fn в Lindroid не дошёл,
   потому что его съела Plektra. Патч `vendor_lindroid/0007` ставит окну
   `FLAG_ALT_FOCUSABLE_IM`, и IME больше не получает клавиш окна Linux. На всякий случай
   служба всё равно отбрасывает повторные DOWN/UP одной клавиши.
3. Android не читает устройства Lindroid: `/system/usr/idc/Vendor_000a_Product_000a.idc`
   (`device.disabled = 1`, из vendor/lindroid). Выходное устройство службы имеет тот же
   vendor/product 0x000a/0x000a. Поэтому вывод не возвращается в Android и не уходит обратно
   в контейнер по кругу.
4. В контейнере libinput видит только устройства `Lindroid*`
   (`/usr/lib/udev/rules.d/99-lindroid-input-ignore.rules` из rootfs). Выходное устройство
   называется `Lindroid-Plektra-Keyboard`.

## Устройство

Выбран вариант «демон evdev → uinput + небольшое расширение XKB».

* `plektra-linux.py` — служба на Python 3, только стандартная библиотека. В rootfs есть
  `python3`, компилятора нет. Служба:
  * находит все `Lindroid-Keyboard-N`, берёт их в монопольное пользование (`EVIOCGRAB`) и
    подхватывает пересоздание при новом запуске дисплея;
  * реализует автомат Plektra;
  * печатает через своё uinput-устройство `Lindroid-Plektra-Keyboard`.
  
  Если служба упала, захват снимается, и kwin снова получает сырые клавиши. Касания на
  других устройствах `Lindroid-*` сбрасывают контекст двойного пробела: курсор мог уйти.
* Символы вне раскладки (цифры, знаки, `° ± – „ “ ” « »`, эмодзи) печатаются через keycode,
  которых нет на клавиатуре телефона (F13–F24 и 200–246, без кодов питания, яркости, радио
  и камеры). Файл `xkb/symbols/titan2` задаёт им Unicode-keysym на двух уровнях.
  `xkb/rules/evdev` подключает его безусловно поверх любой раскладки и опций
  (`! include %S/evdev` + `* = +titan2(plektra)`). libxkbcommon ищет правила сначала в
  `/etc/xkb`, а у клавиш слотов одна группа. Поэтому символы печатаются одинаково при
  раскладке us и ru. Обычные буквы идут обычными клавишами и следуют раскладке KDE (ru
  даёт кириллицу). Коды не выше 247, поэтому слоты работают и в X11-приложениях
  (XWayland). kwin собирает keymap при старте, поэтому служба шлёт в сеанс сигнал
  `org.kde.keyboard /Layouts reloadConfig` (его же шлёт настройка раскладок KDE). По нему
  kwin перечитывает правила, и слоты работают без перезахода. Сигнал уходит при старте
  службы, при каждом новом сеансе и после перегенерации слотов.
* **Полоса Plektra** (`plektra-linux-im.py`) — это input method kwin, он заменяет maliit
  (`kwinrc [Wayland] InputMethod=/usr/share/applications/org.plektra.linux.im.desktop`).
  kwin запускает его сам и активирует (`zwp_input_method_v1.activate`) только тогда, когда
  в фокусе текстовое поле с text-input (Qt, GTK, Firefox). Поэтому полоса, как в Plektra,
  видна лишь в режиме набора и прячется при уходе из поля. Что на ней — по
  `LedStatusView` и `StatusBarController` Plektra:
  * **LED-полоса модификаторов** внизу, сегменты на местах Plektra:
    Shift | Sym | … | Ctrl (Fn) | Alt. Тёмный — выключен, синий — одноразовый или
    удерживается, оранжевый — закреплён (CAPS, режим навигации, закреплённый Alt;
    у Sym — страница «Символы»).
  * **Сетка страницы SYM**, пока страница открыта: 3 ряда Titan 2 (Q–P, A–L, Z X C V · B
    N M) на чёрном фоне, в клавише символ страницы и буква в углу — как у Plektra.
  * Символы службы (Alt-слой, страницы SYM, долгое нажатие) уходят в поле через
    `commit_string`: не зависят от раскладки и xkb-слотов. Слоты остаются запасным путём
    вне полей и в XWayland.

  Полоса рисуется на чистом Python: растеризатор TrueType (DejaVu Sans) и цветные эмодзи из
  NotoColorEmoji (CBDT/PNG) в `plektra_render.py`. Wayland-протокол тоже реализован на
  Python, и пакетов не нужно. Связь со службой — `/run/plektra-linux/ui.sock` (только
  владелец сеанса). Как в Android, полоса стоит внизу экрана во всю ширину, окна
  сдвигаются над ней. Для этого install.sh пишет в окружение сеанса
  (`/etc/environment.d/92-plektra-linux-im.conf`) и экрана входа `KWIN_IM_SHOW_ALWAYS=1`:
  без него kwin показывает нижнюю панель только после касания поля. Вариант под текстовым
  курсором (overlay) — `PLEKTRA_IM_PANEL=cursor` там же. Вернуть maliit: «Параметры системы → Клавиатура → Виртуальная
  клавиатура».
* Если полоса не запущена (например, выбран maliit), индикация как раньше: страница SYM —
  уведомлением Plasma, закрепления — OSD. Текущее состояние всегда лежит в
  `/run/plektra-linux/state.json`.

Почему не другие варианты:

* Модуль fcitx5 или IBus. В rootfs их нет. Они видят клавиши только при активном поле
  ввода, а режим навигации Plektra работает везде. В KWin input-method v1 занимает место
  экранной клавиатуры. Модуль пришлось бы собирать на C++.
* Только XKB. В XKB нет таймеров (долгое нажатие, двойное нажатие, «удержание без
  клавиш») и нет состояния страниц. Клавиши 251 и 253 в xkb вообще недоступны (код > 255).

## Поведение и раскладка

### Shift
| Действие | Результат |
|---|---|
| нажатие | одна следующая буква заглавная |
| двойное нажатие (< 500 мс) | CAPS (OSD); ещё нажатие выключает |
| удержание + буквы | заглавные; при CAPS удержание даёт строчные |
| удержание > 300 мс без других клавиш | состояние не меняется |

Физический Shift при этом передаётся и как настоящий Shift: Shift+клик, выделение,
сочетания.

### Alt (правый Alt Titan): слой Titan
`W1 E2 R3 S4 D5 F6 X7 C8 V9 Q0 · A@ B. G* H# I_ J+ K" L' M? N, O/ P: T( U- Y) Z!`

* нажатие действует на одну клавишу;
* двойное нажатие закрепляет Alt (OSD), следующее нажатие снимает;
* удержание + клавиша;
* Space или Enter снимают одноразовый и закреплённый Alt, сам пробел печатается;
* Alt+Space — просто пробел;
* настоящий Alt в приложения не уходит, как и в Plektra.

**Долгое нажатие буквы (300 мс)**: сначала печатается буква, затем она заменяется
Alt-символом (BackSpace + символ). Автоповтора у букв нет, как в Plektra.

### Sym: страницы символов
* Нажатие Sym переключает страницы по кругу: **Titan** (Alt-слой) → **Эмодзи** → **Символы**
  → выкл. Открытую страницу показывает уведомление.
* На открытой странице клавиша печатает свой символ, и страница закрывается
  (автозакрытие). «Назад» закрывает страницу. Enter закрывает её и проходит дальше. Alt
  тоже закрывает страницу.
* Удержание Sym + клавиша печатает символ с открытой страницы, а если она закрыта — с
  первой (Titan). Страница при этом не переключается.

Эмодзи: `Q😀 W😂 E😍 R😊 T😎 Y👍 U❤️ I😘 O😡 P😉 · A😢 S😭 D😱 F😰 G😴 H🤔 J🤢 K🙄 L😌 · Z😔 X🥳 C😅 V🤗 B🥰 N👎 M😳`

Символы: ``Q~ W` E{ R} T[ Y] U< I> O° P% · A= S; D± F– G\ H| J„ K“ L” · Z» X« C& V^ B¡ N§ M$``

### Fn (251): Ctrl Plektra и режим навигации
* **Удержание Fn + клавиша** — настоящий Ctrl+клавиша: Ctrl+T, Ctrl+W, Ctrl+S в
  приложениях. Это правило Plektra: «при физически удерживаемом Ctrl — родные сочетания».
  Fn+Alt (оба удерживаются) + клавиша дают Ctrl+Alt+клавиша (дополнение для рабочего
  стола).
* **Нажатие Fn** — как в Plektra, зависит от того, в поле ли курсор:
  * в текстовом поле (kwin активировал полосу) следующая клавиша берётся из Ctrl-раскладки
    Plektra (одноразово);
  * вне поля следующая клавиша получает настоящий Ctrl.

  Настройка `fn_tap`: `auto` / `map` / `ctrl`.
* **Двойное нажатие Fn** — режим навигации (OSD), клавиши Ctrl-раскладки работают до
  следующего нажатия Fn. Enter в этом режиме — Enter.

Ctrl-раскладка (`ctrl_key_mappings.json`):

| Клавиша | Действие | | Клавиша | Действие |
|---|---|---|---|---|
| E / S / D / F | ↑ ← ↓ → | | J / K / L | ← ↓ → |
| W / R | выделить символ влево/вправо (Shift+←/→) | | U / I | выделить слово (Ctrl+Shift+←/→) |
| N / M | слово влево/вправо (Ctrl+←/→) | | Y / H | PgUp / PgDn |
| Q | Esc | | T | Tab |
| O | Enter (DPAD_CENTER) | | A | выделить всё (Ctrl+A) |
| Z / X / C / V | отменить / вырезать / копировать / вставить (Ctrl+Z/X/C/V) | | Del | удалить слово (Ctrl+BackSpace) |
| G, P, B | ничего (в Plektra: нет действия, минимальный UI, режим экранной клавиатуры) | | | |

Если при этом удерживать Shift, стрелки и переходы по словам выделяют текст. Fn+Space (Fn
нажат перед пробелом или удерживается) переключает раскладку KDE (Meta+Alt+K), Alt и Fn
при этом сбрасываются. Учтите: удерживаемое Ctrl+Space перехватывает сам Android.

### Остальные клавиши
| Клавиша | В Linux |
|---|---|
| Назад (158) | закрывает страницу SYM, иначе Esc (`back_key`, можно KEY_BACK = XF86Back) |
| Del, Enter | как есть, с автоповтором kwin |
| Space | пробел на отпускании; **удержание ≥ 0,5 с переключает раскладку KDE** (`long_space_ms`, 0 — выкл.; автоповтора у пробела нет) |
| Двойной пробел после буквы/цифры | «. » и следующая буква заглавная (`double_space_period`, `autocap_after_period`) |
| Recents (580) | остаётся за Android (выход из Lindroid); если дойдёт — Meta+W (`recents_key`) |
| Боковые 249/250 | заняты Titan2Parts; если дойдут — KEY_PROG1/2 (XF86Launch1/2 для своих сочетаний KDE) |

### Подсказки слов (без автокоррекции)
В текстовом поле полоса показывает три продолжения текущего слова по словарям Plektra
(`dict/en.tsv`, `dict/ru.tsv`, 50 000 слов из `dict_backup` Plektra, по частоте). Слово
берётся из surrounding text, который приложение (Qt, GTK) сообщает kwin. Язык определяется
по буквам. Выбрать подсказку:
* Fn (удерживать) + W / E / R — это цифры 1/2/3 Alt-слоя;
* свайп вверх по тачпаду клавиатуры — первая подсказка.

Слово заменяется целиком, и добавляется пробел. Пока подсказки на экране, Fn+W/E/R не
отдаёт Ctrl+W/E/R.

### Профили приложений (терминал и др.)
Скрипт KWin (`kwin-focus.js`, его загружает полоса) сообщает, какое окно в фокусе, и служба
применяет профиль из `profiles` в config.json: `{"name", "apps": [resourceClass…],
"overrides": {…}}`. В `overrides` годятся любые ключи настроек и ещё:
* `fn_tap`;
* `sym_tap` — клавиша вместо страниц SYM;
* `alt_space` — клавиша вместо Alt+Space;
* `keys` — полная замена физических клавиш: `{"FN": "KEY_TAB", "SYM": "KEY_ESC"}`, имена
  `SYM FN ALT SHIFT BACK SPACE DEL ENTER SIDE1 SIDE2` или буквы.

Встроенный профиль **terminal** (Konsole, Yakuake, xterm, foot, kitty, Alacritty, GNOME
Terminal/Console, Ghostty, WezTerm и др.):
* Fn, затем C — настоящий Ctrl+C (SIGINT), и так любая клавиша;
* Sym — Esc;
* Sym+клавиша берёт сначала страницу «Символы»: Q `~`, W `` ` ``, G `\`, H `|`, U `<`, I `>`,
  T/Y `[ ]`, E/R `{ }`;
* Alt+Space — Tab;
* двойной пробел и автозаглавная выключены.

Пример профиля для игры: `{"name": "game", "apps": ["supertuxkart"], "overrides": {"keys":
{"FN": "KEY_TAB"}}}`.

### Макросы
`"macros": {"SYM+D": {"text": "{date:%d.%m.%Y}"}, "SYM+M": {"text": "me@example.com"},
"FN+SYM+T": {"run": "konsole"}, "SYM+P": {"keys": ["KEY_LEFTMETA", "KEY_PRINT"]}}`:
* Sym+буква или Fn+Sym+буква вместо символа вставляет текст (`{date}`, `{time}`,
  `{datetime}`, формат strftime после двоеточия), жмёт сочетание (`keys`) или запускает
  программу от пользователя сеанса (`run`);
* текст в поле уходит через полосу (любые символы); вне поля латиница идёт обычными
  клавишами.

### Тачпад клавиатуры
Тачпад клавиатуры (`plektra_touchpad.py`) работает по тем же правилам, что и тачпад Titan2Parts на
Android, с выходом в uinput-мышь `Lindroid-Plektra-Mouse`. Всё решается в момент касания тачпада
и действует до конца жеста:
* **Вне текстового поля** (`"mode": "scroll"`) — свайп прокручивает hi-res колесом
  (`REL_WHEEL_HI_RES`/`REL_HWHEEL_HI_RES`, плюс `REL_WHEEL`/`REL_HWHEEL` на каждый полный щелчок),
  по одной оси за раз (по вертикали содержимое идёт за пальцем). Первые ~22 единицы тачпада — мёртвая
  зона (её нет, если новый свайп идёт меньше чем через полсекунды после прокрутки или застаёт
  инерцию). После быстрого свайпа прокрутка продолжается по инерции и сама замедляется
  (`"inertia": false` выключает); следующее касание её останавливает. Нажатие любой клавиши, кроме
  Shift, выключает колесо и инерцию до следующего касания, так что набор текста не крутит страницу.
  0,8 с после того, как палец ушёл с экрана, тачпад не прокручивает и не кликает.
* **В текстовом поле** тачпад никогда не прокручивает. Два быстрых тапа в одном месте (меньше
  300 мс) включают и выключают режим курсора; в нём горизонтальный штрих двигает курсор стрелками
  влево/вправо (одна стрелка на ~30 единиц), вертикальный — вверх/вниз (на ~100 единиц). Любое
  нажатие клавиши выключает режим. Без режима курсора свайп вверх выбирает первую подсказку панели.
* **Мышиная зона** (`"mode": "mouse_left"` или `mouse_right`): касание, начатое в левых или правых
  2/5 тачпада, двигает указатель (`mouse_speed`); касание в остальной части прокручивает, как выше, а
  тап там (меньше 50 единиц движения, без нажатия клавиш) — левый клик.
* Прочие настройки: `reverse` (направление колеса), `scroll_speed`, `enabled`.

Поведение проверяет `tests/test_touchpad.py` (синтетические кадры evdev, устройство не нужно).

Устройство `touchPad` общее с Android: служба его не захватывает, а читает параллельно и
работает, только пока на Android в фокусе Lindroid. Это свойство `sys.titan2.focus`,
служба читает его прямо из `/dev/__properties__` в контейнере. При старте служба ставит
`sys.titan2.lindroid.touchpad=1`: по нему Titan2Parts должен молчать в окне Lindroid (см.
«Что нужно на стороне Android»).

### Буфер обмена Android ↔ Linux
Обмен текстом идёт через общий каталог `/lindroid/clipboard` (на Android —
`/data/lindroid/mnt/clipboard`):
* при переходе в Lindroid (окно получает фокус) LindroidUI (патч `vendor_lindroid/0008`)
  кладёт буфер Android в `android.txt`, служба передаёт его Klipper через полосу, и в
  Linux вставляется по Fn+V;
* копирование в Linux (сигнал Klipper) записывается в `linux.txt`, и LindroidUI делает его
  буфером Android при выходе из Lindroid.

Иначе нельзя: читать буфер Android может только приложение в фокусе.

### Подсветка клавиатуры как индикатор
Служба выставляет свойство Android `sys.titan2.lindroid.kbd` = `caps` | `nav` | `alt` |
`sym` | `off` (CAPS, режим навигации, закреплённый Alt, открытая страница SYM). Свойство
пишется через сокет property_service, он проброшен в контейнер. Мигает подсветкой
Titan2Parts (KeyboardLight), если поддержит это свойство.

### Нет в порту
* автокоррекция (по решению), автозаглавная в начале поля;
* быстрый запуск приложений Android;
* экранная клавиатура Plektra.

Ctrl-действия «копировать/вставить» — это Ctrl+C/V. В Konsole копирование —
Ctrl+Shift+C, это особенность терминала.

## Настройка

### Приложение «Plektra (клавиатура Titan 2)»
Оно есть в меню и в «Параметрах системы → Клавиатура». Экраны и формулировки повторяют
настройки Plektra:
* «Клавиатуры и устройства»;
* «Модификаторы»;
* «Языки ввода»;
* «Умные возможности»;
* «Подсказки»;
* «Панель клавиатуры»;
* «Режим навигации»;
* «Быстрые команды SYM»;
* «Продвинутые»;
* «Спецвозможности».

Тексты — EN и RU по системной локали; где в Plektra нет русской строки, дан перевод.
Пункты для Linux помечены «(Linux)»:
* профили приложений;
* тачпад: режимы, чувствительность, инерция, мышиная зона;
* макросы;
* долгий пробел;
* положение полосы;
* буфер обмена;
* индикатор подсветки.

Есть редакторы:
* слоёв SYM — сетка Titan 2: клавиша → символ;
* порядка и включения страниц SYM;
* назначений режима навигации — как диалог Plektra «Настроить %1$s»;
* профилей;
* макросов.

Технически это plasmoid `org.plektra.settings` в окне `plasmawindowed`. В rootfs Plasma 6
нет отдельного запускателя QML Qt6 и нет компилятора для KCM. Интерфейс — Kirigami и
Kirigami Addons FormCard, вид как у программ KDE. Чтение и запись идут через
`plektra_config.py` (движок `executable` Plasma5Support), пакеты не нужны.

### Файлы и применение без перезахода
Настройки собираются в три слоя:
* значения по умолчанию (как в Plektra);
* `/etc/plektra-linux/config.json` (система);
* `~/.config/plektra-linux/config.json` владельца сеанса Plasma — его пишет приложение.

Свои таблицы лежат в `~/.config/plektra-linux/mappings/`, затем ищутся в
`/etc/plektra-linux/mappings/`, формат Plektra.

Служба (root) раз в секунду проверяет эти файлы и применяет изменения сразу: тайминги,
модификаторы, страницы SYM, профили, макросы, тачпад. Слоты xkb при необходимости
перегенерирует, kwin перечитывает раскладку. Полосе служба передаёт её настройки:
подсказки, LED, положение. Смена «внизу/под курсором» пересоздаёт поверхность панели сразу.

Требуют перезапуска (служба перезапускается сама, через systemd `Restart=always`):
* включение или выключение тачпада;
* синхронизация буфера.

Требует повторного входа в Plasma только переменная `KWIN_IM_SHOW_ALWAYS=1` (её ставит
установщик): без неё kwin показывает нижнюю панель лишь после касания поля. В приложении
это отмечено.

Вручную: `plektra_config.py get | set BASE64 | sethex HEX | reset [ключ] | getmap ИМЯ |
setmap ИМЯ BASE64 | resetmap ИМЯ` от своего пользователя. Пример всех ключей —
`config.example.json`.

## Установка

* **Прошивка**: `vendor-titan2/titan2.mk` кладёт файлы в
  `/system_ext/usr/share/titan2/plektra-linux/`. lxc template (`prepare_rootfs_gsi`,
  патч `vendor_lindroid/0006`) при создании контейнера запускает оттуда `install.sh` в
  rootfs и пробрасывает `/dev/uinput`. Служба сама создаёт `/dev/uinput`, если узла нет.
* **Готовый контейнер с ПК**: `scripts/install-plektra-linux.sh` (adb + root + nsenter).
  Пока в прошивке нет патча `vendor_lindroid/0007`, Plektra на Android продолжает
  перехватывать клавиши окна Lindroid. Временное решение: в Plektra выключить «Nav mode» и
  «Power shortcuts» или на время работы в Lindroid выбрать другую клавиатуру Android.
* **Готовый контейнер изнутри** (прошивка уже с файлами):
  `sudo sh /system_ext/usr/share/titan2/plektra-linux/install.sh /system_ext/usr/share/titan2/plektra-linux /`

Что ставится:
* `/usr/local/lib/plektra-linux/` — служба, полоса (`plektra-linux-im.py`,
  `plektra_render.py`), таблицы, пример настроек, README, COPYING;
* `/etc/xkb/symbols/titan2`, `/etc/xkb/rules/evdev`;
* `/etc/systemd/system/plektra-linux.service` (включена в multi-user.target);
* `/usr/share/applications/org.plektra.linux.im.desktop`, `/etc/xdg/kwinrc`
  (`[Wayland] InputMethod`, только если там его ещё нет), у пользователей с maliit в
  `~/.config/kwinrc` — замена на Plektra;
* `/usr/local/bin/plektra-linux-im` и `/etc/sddm.conf.d/91-plektra-linux.conf` — экран
  входа sddm тоже получает полосу вместо maliit (та же команда kwin, что в
  `plasma-wayland.conf`, но с `--inputmethod /usr/local/bin/plektra-linux-im`);
* приложение настроек: `/usr/share/plasma/plasmoids/org.plektra.settings/`, ярлык
  `/usr/share/applications/org.plektra.settings.desktop` и пункт «Параметров системы»
  `/usr/share/plasma/systemsettings/externalmodules/org.plektra.settings.desktop`;
* модули `plektra_config.py` (настройки), `plektra_dbus.py` (D-Bus на чистом Python), `plektra_android.py` (свойства
  Android), `plektra_touchpad.py`, словари `dict/`, `kwin-focus.js`.

Внутри работающего контейнера `install.sh` перезапускает службу. В открытых сеансах он
переключает input method через `kwriteconfig6 --notify`: kwin сразу запускает полосу
вместо maliit, перезаход не нужен. Пакеты из apt не нужны.

## Проверка

* С ПК: `scripts/check-plektra-linux.sh` — перегенерация слотов, тесты логики
  (`tests/test_engine.py`, `tests/test_touchpad.py`, `tests/test_config.py`), сборка keymap
  us,ru с правилом. Приложение настроек проверялось так же, во вложенном kwin:
  `plasmawindowed org.plektra.settings` с `XDG_DATA_DIRS`, указывающим на копию plasmoid.
  Полосу можно проверить без телефона во вложенном kwin:
  `kwin_wayland --virtual --inputmethod ".../plektra-linux-im.py"` (так проверялись
  commit_string, подсказки, скрипт KWin).
* Нажатия без пальцев: `tests/inject.py "W:450 ALT E SYM SYM Q +FN S -FN"` от root внутри
  контейнера. Через `/run/plektra-linux/inject.sock` (0600) клавиши идут как с
  Lindroid-Keyboard. Сеанс Plasma должен быть разблокирован: при блокировке клавиши уходят
  в экран блокировки.
* В контейнере:
  * `systemctl status plektra-linux`, `journalctl -t plektra-linux` (должна быть строка
    «захвачен /dev/input/eventN (Lindroid-Keyboard-0)»);
  * `cat /run/plektra-linux/state.json`;
  * `libinput debug-events` (если установлен libinput-tools) — события идут с
    `Lindroid-Plektra-Keyboard`;
  * в Kate/Konsole: Alt+W даёт `1`, долгое W даёт `1`, Sym Sym Q даёт 😀, Sym×3 O даёт °,
    Fn Fn E S D F двигают курсор, Fn удержать + T открывает вкладку.

## Что нужно на стороне Android (Titan2Parts — не в этом каталоге)

1. **Тачпад** (`TouchpadController.java`, сделано). Узел evdev `touchPad` не захватывает ни
   одна сторона: Titan2Parts и эта служба читают его параллельно (фреймворк Android игнорирует
   устройство через idc). Передача идёт через два свойства, которые обе стороны проверяют при
   каждом касании тачпада и не меняют до конца жеста:
   * `sys.titan2.lindroid.touchpad` служба ставит в `1`, пока работает, и возвращает в `0` при
     остановке;
   * `sys.titan2.focus` — окно в фокусе на Android (наш патч фреймворка).

   Пока фокус начинается с `org.lindroid.ui` и свойство равно `1`, Titan2Parts ничего не
   инжектит от тачпада (ни прокрутки, ни пальца, ни кликов, ни стрелок курсора), так что Lindroid
   не получает один свайп дважды. Остаётся только широкий свайп, включающий подсветку клавиатуры.
   Служба, в свою очередь, работает, только пока в фокусе `org.lindroid.ui` (или если
   `sys.titan2.focus` из контейнера не читается, см. пункт 3). Когда служба не запущена, свойство
   равно `0`, и Android обрабатывает тачпад в окне Lindroid как в любом приложении.
2. **Подсветка** (`KeyboardLight.java`). Наблюдать `sys.titan2.lindroid.kbd`
   (`caps|nav|alt|sym|off`) и, пока в фокусе `org.lindroid.ui`, показывать состояние.
   Например: при смене на не-`off` — короткая вспышка до максимальной яркости, затем
   медленное мигание, пока значение не `off`; при `off` — обычный режим.
   Наблюдатель: `SystemProperties.addChangeCallback` или опрос по смене фокуса и по
   нажатию клавиш (KeyHandler уже вызывает `mLight.onKey`).
3. **SELinux** (вне этого каталога). Свойства должны быть объявлены:
   ```
   # property_contexts
   sys.titan2.lindroid.   u:object_r:titan2_lindroid_prop:s0 prefix string
   # .te
   type titan2_lindroid_prop, property_type;
   set_prop(perspectived, titan2_lindroid_prop)   # процессы контейнера Lindroid
   get_prop(system_server, titan2_lindroid_prop)
   ```
   Если `sys.titan2.focus` нельзя читать из контейнера (тип свойства без доступа для
   perspectived), нужен `get_prop(perspectived, <тип sys.titan2.focus>)`. Без чтения
   служба работает с тачпадом без проверки фокуса.
4. Буфер обмена уже в патче `vendor_lindroid/0008` (LindroidUI + `system_app.te`), в
   Titan2Parts ничего не нужно.
