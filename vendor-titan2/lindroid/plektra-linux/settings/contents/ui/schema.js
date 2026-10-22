// SPDX-License-Identifier: GPL-3.0-or-later
// Settings screen structure: as in Plektra (SettingsScreen.kt and the section screens, EN/RU texts
// from res/values and res/values-ru; translated where Plektra has no RU), plus items for Linux.
// Item: {type: switch|choice|spin|text|page|info|action, key, title, summary, note, ...}
.pragma library

function L(en, ru) { return {en: en, ru: ru || en} }

var NOTE_BAR = L("Needs the Plektra bar (input method) running.", "Нужна полоса Plektra (input method).")
var NOTE_RESTART = L("Turning it on/off restarts the service.", "Включение/выключение перезапускает службу.")

var pages = {
main: {title: L("Plektra (Titan 2 keyboard)", "Plektra (клавиатура Titan 2)"), sections: [
    {title: L("Typing", "Ввод"), items: [
        {type: "page", page: "devices", icon: "input-keyboard",
         title: L("Keyboards & Devices", "Клавиатуры и устройства"),
         summary: L("Built-in Titan 2 keyboard, extra keys, keyboard touchpad",
                    "Встроенная клавиатура Titan 2, доп. клавиши, тачпад клавиатуры")},
        {type: "page", page: "modifiers", icon: "keyboard-caps-enabled",
         title: L("Modifiers", "Модификаторы"),
         summary: L("SYM layers, long press, modifier behaviour, and indicators",
                    "Слои SYM, долгое нажатие, поведение модификаторов и индикаторы")},
        {type: "page", page: "languages", icon: "preferences-desktop-locale",
         title: L("Input Languages", "Языки ввода"),
         summary: L("Layout switch shortcuts", "Сочетания переключения раскладки")}
    ]},
    {title: L("Smart Features", "Умные возможности"), items: [
        {type: "page", page: "textinput", icon: "format-text-capitalize",
         title: L("Smart Features", "Умные возможности"),
         summary: L("Capitalization, double space, Alt release, delete alternatives",
                    "Заглавные, двойной пробел, отмена Alt, альтернативы удаления")},
        {type: "page", page: "suggestions", icon: "tools-check-spelling",
         title: L("Suggestions", "Подсказки"),
         summary: L("Word completions from the Plektra dictionaries (no auto-correction)",
                    "Продолжения слов по словарям Plektra (без автоисправления)")}
    ]},
    {title: L("Interface", "Интерфейс"), items: [
        {type: "page", page: "bar", icon: "view-split-top-bottom",
         title: L("Keyboard bar", "Панель клавиатуры"),
         summary: L("Suggestions, LEDs and position", "Подсказки, индикаторы и положение")}
    ]},
    {title: L("Utility", "Утилиты"), items: [
        {type: "page", page: "navmode", icon: "transform-move",
         title: L("Nav Mode", "Режим навигации"),
         summary: L("Configure navigation mode key mappings", "Настроить раскладку режима навигации")},
        {type: "page", editor: "MacrosPage", icon: "media-playlist-append",
         title: L("SYM key shortcuts", "Быстрые команды SYM"),
         summary: L("Sym (or Fn+Sym) + letter: insert text, press keys or run a program",
                    "Sym (или Fn+Sym) + буква: вставить текст, нажать клавиши или запустить программу")},
        {type: "page", editor: "ProfilesPage", icon: "preferences-desktop-default-applications",
         title: L("App profiles", "Профили приложений"),
         summary: L("Own settings per application: terminal (Fn+C = Ctrl+C, Sym = Esc), games…",
                    "Свои настройки для приложений: терминал (Fn+C = Ctrl+C, Sym = Esc), игры…")},
        {type: "page", page: "clipboard", icon: "edit-paste",
         title: L("Clipboard", "Буфер обмена"),
         summary: L("Text clipboard between Android and Linux", "Текстовый буфер между Android и Linux")}
    ]},
    {title: L("Advanced", "Продвинутые"), items: [
        {type: "page", page: "advanced", icon: "configure",
         title: L("Advanced", "Продвинутые"),
         summary: L("Timings, keyboard light indicator, reset", "Тайминги, индикатор подсветки, сброс")},
        {type: "page", page: "accessibility", icon: "preferences-desktop-accessibility",
         title: L("Accessibility", "Спецвозможности"),
         summary: L("Bounce keys, overlapping key presses", "Защита от дребезга, перекрытие нажатий")}
    ]},
    {title: L("About", "О программе"), items: [
        {type: "page", page: "about", icon: "help-about", title: L("About", "О программе"),
         summary: L("plektra-linux — Plektra behaviour for Linux, GPL-3.0",
                    "plektra-linux — поведение Plektra для Linux, GPL-3.0")}
    ]}
]},

devices: {title: L("Keyboards & Devices", "Клавиатуры и устройства"), sections: [
    {title: L("Built-in Keyboards", "Встроенные клавиатуры"), items: [
        {type: "info", title: L("Unihertz Titan 2"),
         summary: L("Device-bound · scancodes from Lindroid-Keyboard (Sym 253, Fn 251, Alt 100)",
                    "Привязано к устройству · скан-коды из Lindroid-Keyboard (Sym 253, Fn 251, Alt 100)")}
    ]},
    {title: L("Extra keys", "Доп. клавиши"),
     footer: L("Back, Recents and the side keys are first handled by Android; they reach Linux only when Android lets them through. Value: a key or chord, e.g. KEY_ESC or KEY_LEFTMETA + KEY_W.",
               "«Назад», «Недавние» и боковые клавиши сначала обрабатывает Android; в Linux они попадают, только если Android их пропускает. Значение — клавиша или сочетание, напр. KEY_ESC или KEY_LEFTMETA + KEY_W."), items: [
        {type: "text", key: "back_key", title: L("Back key (when SYM is closed)", "«Назад» (когда SYM закрыт)"), placeholder: L("KEY_ESC")},
        {type: "text", key: "recents_key", list: true, title: L("Recents key", "«Недавние»"), placeholder: L("KEY_LEFTMETA + KEY_W")},
        {type: "text", key: "side_key_1", title: L("Side key 1 (Func1)", "Боковая клавиша 1 (Func1)"), placeholder: L("KEY_PROG1")},
        {type: "text", key: "side_key_2", title: L("Side key 2 (Func2)", "Боковая клавиша 2 (Func2)"), placeholder: L("KEY_PROG2")}
    ]},
    {title: L("Keyboard touchpad", "Тачпад клавиатуры"), items: [
        {type: "page", page: "touchpad", icon: "input-touchpad",
         title: L("Touchpad", "Тачпад"),
         summary: L("Scrolling, text cursor, mouse zone, sensitivity", "Прокрутка, курсор в тексте, мышиная зона, чувствительность")}
    ]}
]},

touchpad: {title: L("Touchpad", "Тачпад"), sections: [
    {footer: L("Outside text fields swipes scroll (with inertia); in a text field a double tap turns on the text cursor and swipes move it. Works only while Lindroid is focused on Android.",
               "Вне полей свайпы прокручивают (с инерцией); в поле двойной тап включает курсор, свайпы его двигают. Работает, только пока на Android в фокусе Lindroid."), items: [
        {type: "switch", key: "touchpad.enabled", title: L("Keyboard touchpad in Linux", "Тачпад клавиатуры в Linux"), note: NOTE_RESTART},
        {type: "choice", key: "touchpad.mode", title: L("Mode", "Режим"), options: [
            {value: "scroll", en: "Scroll", ru: "Прокрутка"},
            {value: "mouse_left", en: "Mouse zone on the left (2/5), scroll on the right", ru: "Мышь слева (2/5), прокрутка справа"},
            {value: "mouse_right", en: "Mouse zone on the right (2/5), scroll on the left", ru: "Мышь справа (2/5), прокрутка слева"}]},
        {type: "switch", key: "touchpad.reverse", title: L("Reverse scrolling", "Обратная прокрутка")},
        {type: "switch", key: "touchpad.inertia", title: L("Inertia", "Инерция"),
         summary: L("Keep scrolling after a fast swipe.", "Прокрутка продолжается после быстрого свайпа.")},
        {type: "spin", key: "touchpad.scroll_speed", scale: 100, from: 0.2, to: 4, step: 0.1,
         unit: L("%"), title: L("Scroll sensitivity", "Чувствительность прокрутки")},
        {type: "spin", key: "touchpad.mouse_speed", scale: 100, from: 0.2, to: 4, step: 0.1,
         unit: L("%"), title: L("Pointer speed (mouse zone)", "Скорость указателя (мышиная зона)")}
    ]}
]},

modifiers: {title: L("Modifiers", "Модификаторы"), sections: [
    {items: [
        {type: "page", page: "bar", title: L("Modifier indicators", "Индикаторы модификаторов"),
         summary: L("Choose where Shift, Ctrl, Alt, and SYM state appears for the hardware keyboard.",
                    "Где показывать состояние Shift, Ctrl, Alt и SYM аппаратной клавиатуры.")}
    ]},
    {title: L("Long press", "Долгое нажатие"), items: [
        {type: "choice", key: "long_press_mode", title: L("Long Press Modifier", "Модификатор долгого нажатия"), options: [
            {value: "alt", en: "Device SYM Layer (formerly Alt)", ru: "Слой Device SYM (бывш. Alt)"},
            {value: "shift", en: "Shift", ru: "Shift"},
            {value: "sym", en: "First Emoji or Symbols Layer", ru: "Первый слой эмодзи или символов"},
            {value: "sym_symbols", en: "Symbols Layer", ru: "Слой символов"},
            {value: "sym_emoji", en: "Emoji Layer", ru: "Слой эмодзи"},
            {value: "off", en: "Off", ru: "Выключено"}]},
        {type: "spin", key: "long_press_ms", from: 50, to: 1000, step: 50, unit: L("ms", "мс"),
         title: L("Long Press", "Долгое нажатие")}
    ]},
    {title: L("SYM"), items: [
        {type: "page", editor: "SymPagesPage", title: L("Customize SYM Layers", "Настроить слои SYM"),
         summary: L("Arrange and customize Device, Emoji, and Symbols SYM layers.",
                    "Порядок и содержимое слоёв SYM: Device, эмодзи, символы.")},
        {type: "page", editor: "MacrosPage", title: L("SYM key shortcuts", "Быстрые команды SYM"),
         summary: L("Use SYM with assigned keys to insert text, press keys or run programs.",
                    "SYM с назначенной клавишей вставляет текст, нажимает клавиши или запускает программу.")},
        {type: "text", key: "sym_tap", list: true, emptyIsNull: true,
         title: L("SYM press sends key instead of pages (Linux)", "SYM отправляет клавишу вместо страниц (Linux)"),
         placeholder: L("empty — SYM pages", "пусто — страницы SYM")}
    ]},
    {title: L("Alt key", "Клавиша Alt"), items: [
        {type: "choice", key: "alt_binding", title: L("Alt modifier binding", "Привязка модификатора Alt"),
         summary: L("Choose which SYM mapping the Alt modifier triggers. SYM cycle visibility remains independent.",
                    "Какую таблицу SYM включает Alt. Видимость страниц в цикле SYM от этого не зависит."), options: [
            {value: "device", en: "Device SYM Layer · Titan 2", ru: "Слой Device SYM · Titan 2"},
            {value: "first", en: "First key layer in SYM order", ru: "Первый слой в порядке SYM"},
            {value: "emoji", en: "Emoji Layer", ru: "Слой эмодзи"},
            {value: "symbols", en: "Symbols Layer", ru: "Слой символов"}]},
        {type: "text", key: "alt_space", list: true, emptyIsNull: true,
         title: L("Alt+Space sends key (Linux)", "Alt+Space отправляет клавишу (Linux)"),
         placeholder: L("empty — space", "пусто — пробел")}
    ]},
    {title: L("Control", "Control"), items: [
        {type: "page", page: "navmode", title: L("Control in Nav Mode", "Ctrl в режиме навигации"),
         summary: L("Ctrl is currently configured as a Nav Mode modifier. Open Nav Mode to change its key assignments and behaviour.",
                    "Ctrl работает как модификатор режима навигации. Назначения и поведение — в «Режим навигации».")},
        {type: "choice", key: "fn_tap", title: L("Single Fn press (Linux)", "Одиночное нажатие Fn (Linux)"), options: [
            {value: "auto", en: "Like Plektra: in a text field the Nav Mode map, elsewhere Ctrl", ru: "Как Plektra: в поле — раскладка навигации, вне поля — Ctrl"},
            {value: "map", en: "Always the Nav Mode map", ru: "Всегда раскладка навигации"},
            {value: "ctrl", en: "Always real Ctrl", ru: "Всегда настоящий Ctrl"}]}
    ]},
    {title: L("Advanced modifier tap behaviour", "Дополнительно: нажатия модификаторов"), items: [
        {type: "switch", key: "shift_tap_latches", title: L("Single-tap locks Shift", "Одно нажатие закрепляет Shift"),
         summary: L("Tap Shift once to lock caps instead of one-shot uppercase.", "Одно нажатие Shift включает CAPS вместо одной заглавной.")},
        {type: "switch", key: "alt_tap_latches", title: L("Single-tap locks Alt", "Одно нажатие закрепляет Alt"),
         summary: L("Tap Alt once to keep the Device SYM layer active until Alt is tapped again.", "Одно нажатие Alt держит слой Device SYM до следующего нажатия Alt.")},
        {type: "switch", key: "alt_latch_stays_on_space", title: L("Keep locked Alt after Space", "Закреплённый Alt остаётся после пробела"),
         summary: L("When Alt is locked, Space keeps the Device SYM layer active instead of ending the lock.", "Пробел не снимает закреплённый Alt.")},
        {type: "switch", key: "ctrl_tap_latches", title: L("Single-tap locks Ctrl", "Одно нажатие закрепляет Ctrl"),
         summary: L("Tap Ctrl once to keep Ctrl active until Ctrl is tapped again.", "Одно нажатие Ctrl (Fn) держит Ctrl до следующего нажатия.")},
        {type: "switch", key: "ctrl_latch_stays_on_space", title: L("Keep locked Ctrl after Ctrl+Space", "Закреплённый Ctrl остаётся после Ctrl+Space"),
         summary: L("After switching layouts with Ctrl+Space, keep Ctrl locked instead of ending the lock.", "После смены раскладки Ctrl+Space Ctrl остаётся закреплённым.")}
    ]}
]},

languages: {title: L("Input Languages", "Языки ввода"), sections: [
    {title: L("Layout Switch Shortcuts", "Сочетания переключения раскладки"),
     footer: L("Layouts themselves are set in Plasma: System Settings → Keyboard → Layouts.",
               "Сами раскладки задаются в Plasma: Параметры системы → Клавиатура → Раскладки."), items: [
        {type: "switch", key: "ctrl_space_layout_switch", title: L("Ctrl+Space Layout Switch", "Переключение раскладки Ctrl+Space"),
         summary: L("Cycle languages/layouts with Ctrl+Space.", "Переключайте языки/раскладки с Ctrl+Space.")},
        {type: "spin", key: "long_space_ms", from: 0, to: 2000, step: 100, unit: L("ms, 0 = off", "мс, 0 — выкл."),
         title: L("Long Space switches layout (Linux)", "Долгий пробел переключает раскладку (Linux)"),
         summary: L("Space is typed on release; no auto-repeat while this is on.", "Пробел печатается на отпускании; автоповтора пробела нет.")},
        {type: "text", key: "layout_switch_keys", list: true, title: L("Plasma shortcut for the next layout", "Сочетание Plasma «следующая раскладка»"),
         placeholder: L("KEY_LEFTMETA + KEY_LEFTALT + KEY_K")}
    ]}
]},

textinput: {title: L("Smart Features", "Умные возможности"), sections: [
    {title: L("Capitalization", "Заглавные буквы"), items: [
        {type: "switch", key: "autocap_first_letter", title: L("Capitalize at text start", "Автоозаглавливание первой буквы"),
         summary: L("Capitalize the first letter when starting in an empty text field.", "Автоматически озаглавливает первую букву в начале печати в пустом поле"), note: NOTE_BAR},
        {type: "switch", key: "autocap_after_period", title: L("Capitalize after sentence end", "Автозаглавные после точки"),
         summary: L("Capitalize the next letter after a period and space.", "Автоматически озаглавливает следующую букву после точки, за которой следует пробел (конец предложения).")}
    ]},
    {title: L("Spacing & punctuation", "Пробелы и пунктуация"), items: [
        {type: "switch", key: "double_space_period", title: L("Double Space inserts period", "Двойной пробел ставит точку"),
         summary: L("Press Space twice to insert a period and a space.", "Двойное нажатие пробела вставляет точку, за которой следует пробел. Озаглавливает следующую букву.")}
    ]},
    {title: L("Keyboard behaviour", "Поведение клавиатуры"), items: [
        {type: "switch", key: "clear_alt_on_space", title: L("Release Alt with Space", "Отменить Alt на Пробел"),
         summary: L("End Alt or Alt-Lock when Space is pressed.", "Автоматически отключает Alt при нажатии на Пробел")}
    ]},
    {title: L("Delete", "Удаление"), footer: L("Hint: Ctrl + key can be configured in Nav Mode settings", "Подсказка: Ctrl + клавишу можно настроить в режиме навигации"), items: [
        {type: "info", title: L("Alternative methods to delete characters to the right of cursor", "Альтернативные способы удаления символов справа от курсора")},
        {type: "switch", key: "shift_backspace_delete", title: L("Shift + Backspace")},
        {type: "switch", key: "alt_backspace_delete", title: L("Alt + Backspace")}
    ]}
]},

suggestions: {title: L("Suggestions", "Подсказки"), sections: [
    {footer: L("Shown on the keyboard bar in text fields. Pick with Fn held + W/E/R (1/2/3 of the Alt layer) or swipe up on the keyboard touchpad.",
               "Показываются на панели в текстовых полях. Выбор: удерживая Fn + W/E/R (1/2/3 Alt-слоя) или свайп вверх по тачпаду клавиатуры."), items: [
        {type: "switch", key: "suggestions", title: L("Suggestions", "Подсказки"), note: NOTE_BAR},
        {type: "spin", key: "suggestion_count", from: 1, to: 3, step: 1, title: L("Number of suggestions (Linux)", "Число подсказок (Linux)")}
    ]}
]},

bar: {title: L("Keyboard bar", "Панель клавиатуры"), sections: [
    {title: L("Contents", "Содержимое"), items: [
        {type: "switch", key: "suggestions", title: L("Suggestions", "Подсказки")},
        {type: "switch", key: "bar_sym_grid", title: L("SYM page on the bar", "Страница SYM на панели"),
         summary: L("Show the open SYM page as a Titan 2 key grid.", "Показывать открытую страницу SYM сеткой клавиш Titan 2.")}
    ]},
    {title: L("Modifier indicators", "Индикаторы модификаторов"), items: [
        {type: "switch", key: "bar_leds", title: L("Bottom Strip LEDs", "Светодиоды внизу панели")},
        {type: "choice", key: "ui", title: L("Plasma notifications when the bar is not running", "Уведомления Plasma, если панель не запущена"), options: [
            {value: "plasma", en: "SYM page as notification, locks as OSD", ru: "Страница SYM — уведомлением, закрепления — OSD"},
            {value: "none", en: "Off", ru: "Выключено"}]},
        {type: "switch", key: "osd_oneshot", title: L("OSD for one-shot modifiers too", "OSD и для одноразовых модификаторов")}
    ]},
    {title: L("Position (Linux)", "Положение (Linux)"), items: [
        {type: "choice", key: "panel", title: L("Bar position", "Положение панели"), options: [
            {value: "auto", en: "Default (bottom of the screen)", ru: "По умолчанию (внизу экрана)"},
            {value: "bottom", en: "Bottom of the screen, like Android", ru: "Внизу экрана, как в Android"},
            {value: "cursor", en: "Under the text cursor", ru: "Под текстовым курсором"}],
         summary: L("Applied immediately. A bottom bar that appears without touching the field needs KWIN_IM_SHOW_ALWAYS=1 in the session (set by the installer; takes effect after signing in again).",
                    "Применяется сразу. Чтобы нижняя панель появлялась без касания поля, в сеансе нужен KWIN_IM_SHOW_ALWAYS=1 (ставит установщик; действует после повторного входа).")}
    ]}
]},

navmode: {title: L("Nav Mode", "Режим навигации"), sections: [
    {items: [
        {type: "switch", key: "nav_mode", title: L("Enable Nav Mode", "Включить режим навигации"),
         summary: L("Double-tap Ctrl to activate navigation mode", "Двойное нажатие на Ctrl активирует режим навигации")},
        {type: "switch", key: "nav_mode_ctrl_hold", title: L("Ctrl-hold navigation", "Навигация с удержанием Ctrl"),
         summary: L("When enabled, text fields use Nav Mode mappings while Ctrl is held. When off, Ctrl shortcuts are passed to the app. Keys set to Native Ctrl are always passed through as real Ctrl shortcuts.",
                    "Если включено, текстовые поля используют назначения Nav Mode, пока удерживается Ctrl. Если выключено, сочетания Ctrl передаются приложению. Клавиши, настроенные как Native Ctrl, всегда передаются как настоящие сочетания Ctrl.")},
        {type: "page", editor: "NavEditor", file: "ctrl_key_mappings.json",
         title: L("Key mappings", "Назначения клавиш"),
         summary: L("Fn (Ctrl) + key in Nav Mode: arrows, selection, copy/paste…", "Fn (Ctrl) + клавиша в режиме навигации: стрелки, выделение, копирование…")}
    ]}
]},

clipboard: {title: L("Clipboard", "Буфер обмена"), sections: [
    {footer: L("Android lets only the focused app read its clipboard, so the exchange happens when the Lindroid window gains or loses focus (LindroidUI). In Linux the KDE clipboard (Klipper) is used.",
               "Android даёт читать буфер только приложению в фокусе, поэтому обмен идёт, когда окно Lindroid получает или теряет фокус (LindroidUI). В Linux используется буфер KDE (Klipper)."), items: [
        {type: "switch", key: "clipboard_sync", title: L("Sync text clipboard with Android", "Синхронизировать текстовый буфер с Android"), note: NOTE_RESTART}
    ]}
]},

advanced: {title: L("Advanced", "Продвинутые"), sections: [
    {title: L("Timings", "Тайминги"), items: [
        {type: "spin", key: "double_tap_ms", from: 200, to: 1000, step: 25, unit: L("ms", "мс"),
         title: L("Double-tap time (Shift/Alt/Ctrl, double Space)", "Время двойного нажатия (Shift/Alt/Ctrl, двойной пробел)"),
         summary: L("Plektra uses a fixed 500 ms.", "В Plektra — фиксированные 500 мс.")}
    ]},
    {title: L("Keyboard light", "Подсветка клавиатуры"), items: [
        {type: "text", key: "kbd_light_prop", title: L("Android property for the light indicator", "Свойство Android для индикатора подсветки"),
         placeholder: L("sys.titan2.lindroid.kbd")},
        {type: "info", title: L("caps | nav | alt | sym | off", "caps | nav | alt | sym | off"),
         summary: L("Titan2Parts blinks the keyboard light on this property. Empty — off.",
                    "По этому свойству подсветкой мигает Titan2Parts. Пусто — выключено.")}
    ]},
    {title: L("Settings file", "Файл настроек"), items: [
        {type: "action", action: "openFile", icon: "document-edit", title: L("Open config.json", "Открыть config.json"),
         summary: L("Your settings over /etc/plektra-linux/config.json", "Ваши настройки поверх /etc/plektra-linux/config.json")},
        {type: "action", action: "resetAll", icon: "edit-reset", title: L("Reset all settings", "Сбросить все настройки"),
         summary: L("Restore Plektra defaults (your SYM tables stay).", "Вернуть значения Plektra по умолчанию (свои таблицы SYM остаются).")}
    ]}
]},

accessibility: {title: L("Accessibility", "Спецвозможности"), sections: [
    {title: L("Bounce Keys", "Защита от дребезга"), items: [
        {type: "switch", key: "bounce_keys_enabled", title: L("Bounce Keys", "Защита от дребезга"),
         summary: L("Ignore accidental repeated presses of the same physical key.", "Игнорировать случайные повторные нажатия той же клавиши.")},
        {type: "spin", key: "bounce_keys_delay_ms", from: 20, to: 500, step: 10, unit: L("ms", "мс"),
         title: L("Minimum same-key delay", "Минимальная пауза той же клавиши"),
         summary: L("Same key presses below this delay are ignored.", "Нажатия той же клавиши быстрее этого игнорируются.")},
        {type: "switch", key: "bounce_keys_chars", title: L("Character keys", "Символьные клавиши"), summary: L("Letters, numbers and symbol keys.", "Буквы, цифры и символы.")},
        {type: "switch", key: "bounce_keys_modifiers", title: L("Modifier keys", "Модификаторы"), summary: L("Shift, Ctrl, Alt and Sym.", "Shift, Ctrl, Alt и Sym.")},
        {type: "switch", key: "bounce_keys_space", title: L("Space", "Пробел"), summary: L("Ignore accidental repeated spaces.", "Игнорировать случайные повторные пробелы.")},
        {type: "switch", key: "bounce_keys_enter", title: L("Enter"), summary: L("Ignore accidental repeated line breaks or send actions.", "Игнорировать случайные повторные переносы строки и отправки.")},
        {type: "switch", key: "bounce_keys_backspace", title: L("Backspace"), summary: L("Ignore accidental repeated deletes.", "Игнорировать случайные повторные удаления.")}
    ]},
    {items: [
        {type: "switch", key: "overlapping_keys_enabled", title: L("Ignore overlapping key presses", "Игнорировать перекрывающиеся нажатия"),
         summary: L("Ignore a second non-modifier key until the first is released. Modifier shortcuts still work.",
                    "Игнорировать вторую обычную клавишу, пока не отпущена первая. Сочетания с модификаторами работают.")}
    ]}
]},

about: {title: L("About", "О программе"), sections: [
    {items: [
        {type: "info", title: L("plektra-linux"), summary: L("Port of the Plektra IME behaviour (github.com/pkb-rocks/plektra, GPL-3.0) for the Unihertz Titan 2 keyboard in Lindroid.",
            "Порт поведения IME Plektra (github.com/pkb-rocks/plektra, GPL-3.0) для клавиатуры Unihertz Titan 2 в Lindroid.")},
        {type: "info", title: L("License", "Лицензия"), summary: L("GNU General Public License v3 or later", "GNU GPL версии 3 или новее")},
        {type: "info", title: L("Applying settings", "Применение настроек"), summary: L("Changes are saved to ~/.config/plektra-linux/config.json and applied within a second, without signing out.",
            "Изменения сохраняются в ~/.config/plektra-linux/config.json и применяются за секунду, без выхода из сеанса.")}
    ]}
]}
};
