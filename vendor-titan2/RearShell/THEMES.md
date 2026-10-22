# Rear screen themes (schema 2)

A theme for the Titan 2 rear screen (410×502 px, ~200 dpi) is one JSON file, `theme.json`,
optionally next to font and picture files. You only write what you want to change: everything
else comes from the parent theme (`"extends"`) and, at the very bottom, from the built-in
defaults listed below.

Themes are managed in **Settings → Titan 2 → Rear screen → Themes** (Titan2Parts): apply,
edit, copy, export, import, delete. The rear shell (RearShell) never reads files itself; it
gets the already-resolved theme from Titan2Parts.

## A theme in 5 minutes

1. Settings → Titan 2 → Rear screen → Themes → tap the gear next to the theme you like →
   **Export**. You get a `theme.json` (or a `.zip` if the theme has fonts/pictures).
2. Make a new file with only the lines you want different, for example:

   ```json
   {
     "schema": 2,
     "name": "My green",
     "extends": "builtin:default_dark",
     "colors": { "primary": "#7CF5C4", "onPrimary": "#002116" },
     "clock": { "face": "analog", "seconds": true }
   }
   ```
3. **Import theme** and pick the file. If something is wrong you get a list of errors with the
   exact key (`colors.primary: bad color "#7CF5C"`); unknown keys are only warnings.
4. Or do it without a file: **New theme** opens the editor (color picker, fonts, clock,
   wallpaper, grid, animations, live preview); it saves only what you changed.

To ship a font or a picture, put them next to `theme.json` and zip the folder:

```
my_theme.zip
├── theme.json          "typography": { "font": "fonts/Inter.ttf" },
├── fonts/Inter.ttf     "wallpaper":  { "type": "image", "image": "bg.jpg", "dim": 0.3 }
└── bg.jpg
```

## Where themes live

| Kind | Theme id | Location |
|---|---|---|
| built-in | `builtin:<name>` | `Titan2Parts/assets/rear_themes/<name>/theme.json` (read-only) |
| yours (import, copy, editor) | `user:<name>` | `/data/user_de/0/org.titan2.parts/files/rear_themes/<name>/` |

Built-in: `builtin:default_dark` (default), `builtin:amber_terminal`, `builtin:paper_light`,
`builtin:neon`, `builtin:analog_graphite`, `builtin:aurora` (an example of `extends`).

Limits: `theme.json` ≤ 256 KiB, each file ≤ 16 MiB, zip ≤ 32 MiB and ≤ 128 files. Files allowed
in a zip: `.ttf .otf .ttc .png .jpg .jpeg .webp` (others are skipped with a warning). Paths are
relative to the theme folder; absolute paths and `..` are rejected.

## Inheritance (`extends`)

```
built-in defaults (below)  ←  builtin:default_dark  ←  user:my_green  ←  user:my_green_v2
```

* The resolved theme = defaults, then each parent from the farthest to the nearest, then the
  theme itself. Objects merge **key by key** (`"colors": {"primary": …}` changes only primary);
  everything else (strings, numbers, lists such as `wallpaper.gradient`) is **replaced**.
* A file path in a theme refers to **that theme's** folder, so a child can use the parent's font
  without copying it.
* `extends` must name an installed theme id; loops and chains longer than 8 are errors.
* Deleting a theme that others extend breaks them (the settings warn you first).
* Export flattens your own parents into one file and keeps `extends` only on a built-in theme,
  so the export works on any Titan 2.

## Colors

Every color value can be:

| Form | Example | Meaning |
|---|---|---|
| hex | `"#4FC3F7"`, `"#CC000000"`, `"#4FC"` | `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB` |
| name | `"black"`, `"white"`, `"red"` | anything `Color.parseColor` knows |
| token reference | `"@primary"` | the value of another token of the same theme |
| with opacity | `"@onSurface/0.12"`, `"#000000/0.6"` | any of the above, opacity multiplied by 0…1 |

References are resolved after inheritance, so `"surface": "@onSurface/0.12"` follows a child
that only changes `onSurface`.

## Reference: every key

Types: **color** (see above), **bool** `true`/`false`, **int**, **number**, **string**,
**enum** (one of the listed strings), **font** (system family name or a font file in the theme),
**file** (picture in the theme). Unknown keys are ignored (import shows a warning).

### Top level

| Key | Type | Default | Meaning |
|---|---|---|---|
| `schema` | int 1–2 | 2 | format version; files without it are treated as schema 1 if they look like it (see below) |
| `name` | string | file/folder name | shown in the list |
| `author` | string | `""` | shown under the name |
| `description` | string | `""` | shown under the name |
| `version` | string | — | free text, for you |
| `extends` | string | — | parent theme id, e.g. `"builtin:default_dark"` |

### `colors` — tokens

| Key | Default | Used for |
|---|---|---|
| `background` | `#000000` | screen background (alpha ignored), text on active tiles in v1 themes |
| `surface` | `@onSurface/0.12` | tiles, PIN keys, hint bubble, icon plates (blended over background) |
| `onSurface` | `#FFFFFF` | main text: digits, date, labels, PIN digits |
| `onSurfaceVariant` | `@onSurface/0.62` | secondary text: battery/notifications line, minor ticks, handles |
| `primary` | `#4FC3F7` | active tiles, slider, accents |
| `onPrimary` | `#000000` | icon and text on active tiles |
| `secondary` | `@onSurfaceVariant` | second accent (theme previews, free for element overrides) |
| `accent` | `@primary` | seconds, second hand, minimal-face minutes, pattern line |
| `error` | `#FF6E6E` | wrong PIN, lockout countdown |
| `scrim` | `#000000/0.6` | dimming under the quick panel |

### `colors.elements` — single elements (optional)

Any of these overrides one element; the default is shown in brackets.

| Element | Default | Element | Default |
|---|---|---|---|
| `clock.hours` | onSurface | `label` | onSurface (app names, mono icons) |
| `clock.minutes` | onSurface (minimal, words: accent) | `drawer.background` | background at 95 % |
| `clock.seconds` | accent | `pager.indicator` | onSurfaceVariant/0.4 |
| `clock.separator` | as hours | `pager.indicatorActive` | onSurface |
| `clock.date` | onSurface | `qs.background` | surface over background |
| `clock.status` | onSurfaceVariant | `qs.tile` | surface over background |
| `clock.titles` | onSurface (lock-screen notification titles) | `qs.tileActive` | primary |
| `clock.handHour` | onSurface | `qs.onTile` | onSurface |
| `clock.handMinute` | onSurface | `qs.onTileActive` | onPrimary |
| `clock.handSecond` | accent | `qs.slider` | primary |
| `clock.ticks` | onSurfaceVariant | `bouncer.background` | surface tint over background |
| `clock.ticksMajor` | onSurface | `bouncer.title` | onSurface |
| `clock.numbers` | onSurface | `bouncer.key` | surface over background |
| `clock.center` | accent | `bouncer.onKey` | onSurface |
| `hint.background` | surface over background | `bouncer.dot` | onSurface |
| `hint.text` | onSurface | `bouncer.pattern` | accent |
| | | `bouncer.error` | error |

### `typography`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `font` | font | `sans-serif` | text font: `sans-serif`, `sans-serif-light`, `sans-serif-thin`, `sans-serif-medium`, `sans-serif-black`, `sans-serif-condensed`, `sans-serif-condensed-light`, `serif`, `monospace`, `serif-monospace`, `casual`, `cursive`, `sans-serif-smallcaps`, or a file `fonts/X.ttf` |
| `weight` | int 100–900 | 400 | text weight (variable fonts follow it exactly) |
| `clockFont` | font | `""` = same as `font` | digits font |
| `clockWeight` | int 0–900 | 0 = same as `weight` | digits weight (`clock.digits` bold/thin override it) |
| `scale` | number 0.6–1.6 | 1.0 | size of date/status/notification text and app labels |
| `labelSize` | number 8–20 | 12 | app label size, sp (multiplied by `scale`) |

### `clock`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `face` | enum | `digital` | `digital`, `digital_seconds`, `analog`, `words`, `minimal` |
| `seconds` | bool | false | small seconds (digital) / second hand (analog) |
| `24h` | bool or `"system"` | `"system"` | 24-hour time |
| `date` | bool | true | date line |
| `dateFormat` | string | `EEE d MMM` | skeleton for `DateFormat.getBestDateTimePattern` (order and punctuation follow the language) |
| `battery` | bool | true | battery % |
| `notifications` | bool | true | notification count (needs notification access) |
| `size` | number 0.4–1.2 | 1.0 | share of the clock area the face uses |
| `digits` | enum | `regular` | `regular`, `bold`, `thin`, `outline`, `tabular` (equal-width digits) |
| `faces` | object | `{}` | per-face parameters, below |

`clock.faces.<face>`:

| Face | Key | Type | Default | Meaning |
|---|---|---|---|---|
| `digital`, `digital_seconds` | `separator` | string | `:` | between hours and minutes |
| | `blink` | bool | false | separator blinks every second |
| | `leadingZero` | bool | as 24h | `09:05` vs `9:05` |
| `analog` | `ticks` | enum | `all` | `all`, `hours`, `none` |
| | `numbers` | bool | false | 12, 3, 6, 9 |
| | `handWidth` | number 0.5–2 | 1.0 | hand thickness |
| `words` | `language` | enum | `system` | `system` (Russian for ru, else English), `ru`, `en` |
| | `case` | enum | `lower` | `lower`, `upper`, `title` |
| `minimal` | `leadingZero` | bool | as 24h | |

The user can still override face, seconds, 24h, date, format, battery and notifications in
Settings → Clock (each has "From theme").

### `wallpaper`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `type` | enum | `color` | `color`, `gradient`, `image` |
| `color` | color | `@background` | `type: color` |
| `gradient` | list of 2–4 colors | `[]` | `type: gradient` (fewer than 2 → plain background) |
| `angle` | int 0–359 | 90 | gradient direction like CSS: 0 bottom→top, 90 left→right, 180 top→bottom; rounded to 45° |
| `image` | file | `""` | `type: image`, center-cropped, scaled down to the screen |
| `dim` | number 0–1 | 0 | black overlay over the wallpaper |
| `blur` | number 0–25 | 0 | blur radius (dp) of the picture, done on the GPU |

### `layout`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `columns`, `rows` | int 1–6 | 3, 3 | favorites page grid; the app list uses the same cell size and scrolls |
| `iconSize` | number 0.6–1.4 | 1.0 | icon scale |
| `iconShape` | enum | `none` | `none` (as the system draws it), `circle`, `squircle`, `rounded` |
| `iconStyle` | enum | `color` | `color`, `mono` (monochrome layer of adaptive icons tinted with `label`; otherwise greyscale) |
| `labels` | bool | true | app names |
| `labelLines` | int 1–2 | 1 | lines per name |
| `pageIndicator` | bool | true | page dots |

Settings → Home screen can override columns, rows, labels, shape, style and size.

### `animations`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `speed` | number 0.25–4 | 1.0 | 2 = twice as fast; the system animation scale also applies |
| `style` | enum | `standard` | `standard` (Material 3 emphasized curves, spring settle), `expressive` (softer springs with a little overshoot, clip-reveal app launch), `subtle` (less movement, no launch zoom), `none` |

Settings → Animations can turn them off or change speed/style for every theme.

## Annotated example

```jsonc
{
  "schema": 2,
  "name": "Night tram",               // shown in the list
  "author": "me",
  "extends": "builtin:default_dark",  // everything not written here comes from it

  "colors": {
    "primary": "#FFB74D",             // active tiles, slider
    "onPrimary": "#2B1700",
    "accent": "@primary",             // seconds, second hand = primary
    "surface": "@primary/0.14",       // tiles tinted with primary
    "elements": {
      "clock.minutes": "@primary",    // only the minutes in orange
      "bouncer.key": "#1E1E1E"
    }
  },
  "typography": { "font": "fonts/Tram.otf", "weight": 500, "clockWeight": 300 },
  "clock": {
    "face": "digital",
    "digits": "tabular",
    "faces": { "digital": { "blink": true, "separator": "·" } }
  },
  "wallpaper": { "type": "image", "image": "tram.jpg", "dim": 0.35, "blur": 6 },
  "layout": { "columns": 4, "rows": 4, "iconShape": "squircle" },
  "animations": { "style": "expressive" }
}
```

(Comments are only for this page; real JSON has none.)

## Old themes (schema 1)

Flat files from the first RearShell (`background`, `foreground`, `accent`, `secondary`, `font`,
`backgroundImage`, `clock`, `icons`, `grid`) are converted on import:
`foreground → colors.onSurface`, `accent → colors.primary + colors.accent`,
`secondary → colors.secondary + colors.onSurfaceVariant`, `backgroundImage → wallpaper.image`,
`grid → layout.columns/rows`, `icons.style plain/circle/mono → layout.iconShape/iconStyle`,
`icons.labels → layout.labels`.

## For developers

* Schema, defaults, inheritance, validation: `RearShell/common/src/org/titan2/rear/common/ThemeJson.java`
  (`SPECS` is the key table above — change both together). Parsed form: `ThemeSpec.java`.
  Effective values (user settings over the theme): `Effective.java`.
* Storage, import/export: `Titan2Parts/src/org/titan2/parts/RearThemes.java`.
* The shell asks `content://org.titan2.parts.rear/themes/<id>` for the resolved JSON and
  `content://org.titan2.parts.rear/file/<id>/<path>` for files (`RearContract.java`).
