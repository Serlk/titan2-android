English: [GAPPS.md](GAPPS.md)

# Сервисы Google (GApps)

Готовый образ из релизов собран **без GApps**: распространять приложения Google мы не вправе.
Есть два пути.

## Вариант 1: собрать образ с GApps (рекомендуется)

Сборка с MindTheGapps проверена на Titan 2 и используется автором как основная.

1. Подготовьте сборку по [BUILD.ru.md](BUILD.ru.md), шаги 1–4. `scripts/android-sync-crdroid16.sh`
   сам скачивает MindTheGapps — сторонний проект
   ([gitlab.com/MindTheGapps/vendor_gapps](https://gitlab.com/MindTheGapps/vendor_gapps), ветка
   `baklava`, его подключает манифест TrebleDroid) — в `vendor/gapps`. В этом репозитории
   приложений Google нет: только два небольших патча к сборочным файлам MindTheGapps
   (`patches/titan2-a16/vendor_gapps`). Скачивая и собирая GApps, вы сами отвечаете за
   соблюдение условий Google.
2. Соберите цель `bgN4`:

   ```bash
   scripts/android-build-crdroid16.sh bgN4
   ```

3. Прошейте полученный `system.img`. Если раньше стоял образ без GApps, данные нужно
   **стереть** (полная установка, раздел 5 [INSTALL.ru.md](INSTALL.ru.md)): добавление сервисов
   Google на уже настроенную систему без сброса обычно заканчивается падениями Play Services.

Google Phone из стоковой прошивки входит в такую сборку, если выполнен шаг 4 BUILD.ru.md.

### Сертификация Play Protect

Образ из собственной сборки не сертифицирован. Если Play Market пишет «Устройство не
сертифицировано», зарегистрируйте Android ID на
[google.com/android/uncertified](https://www.google.com/android/uncertified) и подождите
несколько часов.

## Вариант 2: без сервисов Google

Образ без GApps полностью рабочий. Приложения можно ставить из F-Droid или из APK. Приложениям,
которым нужны Google Play Services, этого может не хватить —
тогда вариант 1.

Сторонние способы «доставить» GApps в готовый GSI (модули KernelSU/Magisk, перепаковка
`system.img`) на Titan 2 не проверялись, и мы их не поддерживаем.
