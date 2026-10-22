English: [README.md](README.md)

# RearShell — оболочка заднего экрана Titan 2

Системное приложение `org.titan2.rear` (модуль `RearShell`, priv-app в system_ext, подпись
platform) для заднего дисплея 410x502 (~200 dpi; логический id меняется при повторном
добавлении дисплея и ищется через DisplayManager по физическому порту
`ro.titan2.rear_display_port`, запасной вариант — встроенный не основной дисплей с режимом
410x502; виртуальные дисплеи не берутся никогда). Даёт экран блокировки с часами и разблокировкой
свайпом вверх, домашний экран, список приложений, быструю панель, свои жесты навигации
(жестовая навигация Android на вторичных дисплеях не работает) и темы.

**Настроек у оболочки нет.** Всё — в Titan2Parts: Настройки → Titan 2 → Задний экран
(`RearScreenActivity`, action `org.titan2.parts.REAR_SETTINGS`). Оболочка читает настройки и
темы из провайдера Titan2Parts `org.titan2.parts.rear` и обновляется на лету
(ContentObserver). Включение панели, группа/фокус/питание дисплея, тайм-аут сна и пробуждение
делает системная часть (Titan2Parts `RearController`/`RearDisplay`, frameworks_base).

## Архитектура

```
Titan2Parts (uid system, persistent)                RearShell (persistent, directBootAware)
 ├─ RearScreenActivity + фрагменты  ──пишут──▶ rear_config (SharedPreferences, DE)
 │   Rear{Apps,Order,Themes,ThemeEditor}Fragment       │ notifyChange
 ├─ RearThemes: assets/rear_themes, files/rear_themes  ▼
 └─ RearConfigProvider  ◀──── query/openFile/call ──── Cfg (ключи), ThemeStore (тема, шрифты,
     (signature-право REAR_CONFIG, directBootAware)      картинки → кэш), ContentObserver
                                                     RearHomeActivity, RearGestureService, …
SystemUI: Titan2RearUnlockService ◀── Messenger ── UnlockClient (PIN/ключ, снятие блокировки)
```

* Общая библиотека `titan2-rear-common` (`common/`, java_library, sdk current) — в обоих APK:
  `RearKeys` (ключи и значения по умолчанию), `RearContract` (URI провайдера, право),
  `ThemeJson` (схема тем v2: значения по умолчанию, наследование, перевод v1, проверка),
  `ThemeSpec` (разобранная тема), `Effective` (настройка поверх темы), `Wallpapers`,
  `faces/*` (циферблаты и `ClockRenderer` — ими же рисует предпросмотр в Titan2Parts).
* Ключи `Settings.System` остались только для того, что читает системная часть:
  `titan2_rear_enabled`, `titan2_rear_screen_off_timeout`, `titan2_rear_wake_double_tap`,
  `titan2_rear_wake_double_tap_pose`, `titan2_rear_wake_flip`, `titan2_rear_system_decors`
  (системные панели на заднем дисплее, ниже). Прежние
  `titan2_rear_lock_mode`/`titan2_rear_allowed_apps` переносятся в провайдер один раз
  (`RearConfig.migrate`).
* Оболочка пишет в провайдер только `favorites` (долгое нажатие на значок) и `clock_face`
  (действие «следующий циферблат»); остальное провайдер ей не даст (`SHELL_WRITABLE`).
* Старое действие `org.titan2.rear.SETTINGS` оставлено: `SettingsRedirectActivity` открывает
  настройки Titan2Parts на основном экране.

### Системные элементы на заднем дисплее

По умолчанию на заднем дисплее **нет** системных элементов: строки состояния, шторки, панели
навигации, обработчика жеста «назад», обоев и KeyguardPresentation (frameworks_base
`Titan2RearDecor`; `KeyguardPresentation` SystemUI там не показывается никогда). Оболочка от них
не зависит: свой фон (цвет/градиент/картинка темы поверх чёрного окна), свой экран блокировки
(окно `showWhenLocked`), свои жесты. При `titan2_rear_system_decors` = 1 (Titan2Parts:
«Системные элементы») появляются системные панели: домашний экран и «Недавние» отступают на их
insets, а служба жестов не трогает левый/правый края (там работает жест «назад» SystemUI; свайп
снизу остаётся). Домашняя активити экспортирована (SECONDARY_HOME), но сразу закрывается, если
её запустили на основном дисплее или в задаче другого приложения.

## Файлы оболочки

| Файл | Что делает |
|---|---|
| `RearHomeActivity` | SECONDARY_HOME, singleTop, showWhenLocked: часы → страницы избранного; шторки поверх (список приложений, быстрая панель, ввод PIN), маршрутизация вертикальных жестов, блокировка |
| `Sheet` | прогресс шторки 0…1: за пальцем, отпускание — пружина (androidx.dynamicanimation) с учётом скорости, программно — кривые M3 |
| `Motion` | кривые M3 (emphasized, emphasized decelerate/accelerate, standard), длительности, пружины; множитель скорости темы/настроек, системный масштаб анимаций |
| `PagerView`, `PageIndicator` | горизонтальные страницы (доводка M3, соседние страницы гаснут/уменьшаются), вертикальные жесты непрерывно отдаются домашнему экрану |
| `ClockView` | часы через общий `ClockRenderer`; смена циферблата — cross-fade; касание/двойное/долгое — действия из настроек |
| `BouncerView`, `UnlockView`, `UnlockClient` | разблокировка с заднего экрана (ниже) |
| `DrawerView`, `AppGridView`, `Icons`, `Apps` | список приложений и сетки, формы/стиль значков, запуск со scale-up из значка |
| `QuickPanel`, `Torch`, `BrightnessControl` | плитки из настроек, яркость заднего дисплея |
| `RearGestureService`, `RearActions` | навигационные жесты поверх приложений |
| `RearRecentsActivity` | «Недавние» заднего дисплея |
| `RearNotificationListener` | число уведомлений / заголовки для режима блокировки |
| `Cfg`, `ThemeStore` | настройки и тема из провайдера Titan2Parts |

## Экран блокировки и разблокировка

* Страница по умолчанию — часы. На блокировке — только часы в объёме `lock_mode`:
  `off` (чёрный экран), `clock`, `clock_count` (по умолчанию: часы, заряд, число уведомлений),
  `clock_titles` (плюс заголовки; помечено как менее приватное; ничего, если в системе выключены
  уведомления на экране блокировки; VISIBILITY_SECRET, в том числе по настройке канала или
  пользователя, пропускаются, для PRIVATE — publicVersion или имя приложения). Избранного, списка приложений, «Недавних»,
  Wi-Fi/DND/звука/настроек на блокировке нет; фонарик — по настройке `lock_torch`.
* Свайп вверх с часов (настройка «Разблокировка с заднего экрана», по умолчанию вкл.):
  «bouncer» выезжает снизу за пальцем, часы одновременно уходят вверх, уменьшаются и гаснут;
  отпустили за серединой или бросок вверх — открывается, иначе пружиной назад. Когда открылся —
  `MSG_QUERY` в `Titan2RearUnlockService`:
  * `can_skip` (нет PIN, доверенное устройство, уже распознан отпечаток) → `MSG_DISMISS`, без ввода;
  * `available` и тип PIN/ключ → ввод (`UnlockView`, оформлен темой): отсчёт блокировки ввода,
    покачивание при ошибке, автоподтверждение PIN только если оно включено в системе;
  * иначе (пароль, политика администратора) → «снимите блокировку на основном экране» +
    `KeyguardManager.requestDismissKeyguard`.
  Закрытие — свайп вниз за верхнюю часть, «Назад» (жест), погасание экрана (основного или
  только заднего), уход активити:
  набранное стирается (byte[] затирается нулями после отправки и при закрытии).
  Выключенная настройка — свайп вверх только показывает подсказку.
* После разблокировки ввод уезжает вниз, а страницы проявляются (тот же прогресс); при смене
  состояния не через ввод — содержимое плавно появляется (alpha/scale, emphasized decelerate).
* `UnlockClient` держит привязку к службе всё время жизни процесса: пока она есть, SystemUI
  заново блокирует телефон, когда задний экран гаснет при погашенном основном.

## Домашний экран и жесты

* Без блокировки свайп вверх с часов/избранного — действие `home_swipe_up` (по умолчанию список
  приложений, тянется за пальцем), свайп вниз — `home_swipe_down` (быстрая панель; в любом месте
  или только от верхнего края). Касание/двойное/долгое нажатие на часах — свои действия
  (по умолчанию долгое нажатие открывает настройки на основном экране).
* Нажатие на значок — запуск на заднем дисплее (`setLaunchDisplayId`), окно «вырастает» из
  значка (`makeScaleUpAnimation`; стиль expressive — `makeClipRevealAnimation`). Долгое
  нажатие — добавить в избранное / убрать (порядок меняется перетаскиванием в Titan2Parts).
* Навигация поверх приложений: `InputManager.monitorGestureInput` на заднем дисплее +
  `pilferPointers`. Свайп вверх от низа (по умолчанию «Домой»), с удержанием («Недавние»), от
  левого и правого края (по отдельности, по умолчанию «Назад»); любое — Домой/Назад/Недавние/
  Фонарик/Погасить/Ничего, чувствительность, вибрация. При включённых системных элементах
  левый/правый края оставлены системному жесту «назад».

## Темы

Справочник — `THEMES.md` (схема 2: токены цветов и цвета элементов, шрифты, циферблаты и их
параметры, фон-цвет/градиент/картинка с размытием и затемнением, сетка и значки, анимации,
наследование `extends`). Встроенные темы — в Titan2Parts (`assets/rear_themes`), импорт/экспорт
JSON/ZIP, редактор с палитрой и предпросмотром — там же.

## Права

Signature (по platform-подписи): MONITOR_INPUT, INJECT_EVENTS, CONTROL_DISPLAY_BRIGHTNESS,
REMOVE_TASKS, NETWORK_SETTINGS, MANAGE_NOTIFICATIONS, DEVICE_POWER (погасить задний экран),
CONTROL_KEYGUARD (привязка к Titan2RearUnlockService), `org.titan2.permission.REAR_CONFIG`
(объявлено в Titan2Parts). signature|privileged — `privapp-permissions-org.titan2.rear.xml`:
REAL_GET_TASKS, START_ACTIVITIES_FROM_BACKGROUND, START_TASKS_FROM_RECENTS,
SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE.

## Проверено / не проверено

* **Проверено:** `m RearShell Titan2Parts` собирается (crDroid 16, Docker).
* **Не проверено на устройстве — ничего из этого не запускалось:**
  * разблокировка: вся цепочка с `Titan2RearUnlockService` (привязка с CONTROL_KEYGUARD,
    ответы, `can_skip`/`MSG_DISMISS`, автоподтверждение PIN, отсчёт блокировки), что keyguard
    после этого снимается и оболочка видит это через KeyguardManager/USER_PRESENT;
    `requestDismissKeyguard` из активити заднего дисплея;
  * провайдер: доступ по REAR_CONFIG (порядок установки permission при первой загрузке),
    работа до первой разблокировки (DE-хранилище обоих приложений), живое обновление;
  * анимации на GPU телефона (плавность пружин, RenderEffect-размытие, saveLayerAlpha при
    смене циферблата), scale-up/clip-reveal при запуске на вторичном дисплее;
  * жесты: что `monitorGestureInput` получает касания sub_touch и pilfer снимает касание
    с приложения; инъекция BACK с displayId; что `goToSleep(displayId)` гасит только задний;
  * `DisplayManager.setBrightness(rearId)` (зависит от своего DisplayPowerController заднего
    дисплея), Wi-Fi/DND/звук из priv-app, выдача доступа к уведомлениям из Titan2Parts;
  * размеры шрифтов/значков/клавиатуры PIN при ~200 dpi; редактор тем и импорт ZIP на
    реальных файлах; предпросмотр в Titan2Parts против того, как рисует оболочка.

## Ограничения

* «Недавние» — только задачи на заднем дисплее, без миниатюр; долгое нажатие закрывает задачу.
* Жест «назад» — KEYCODE_BACK без predictive back.
* Быстрая панель — только внутри домашнего экрана (поверх чужих приложений её нет).
* Поиска в списке приложений на самом заднем экране нет (клавиатуры там нет); поиск — в настройках.
* Плитки Bluetooth нет (нужны runtime-права BLUETOOTH_CONNECT/PRIVILEGED).
