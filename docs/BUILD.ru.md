English: [BUILD.md](BUILD.md)

# Сборка из исходников

Всё собирается в Docker: на хосте нужны только `git`, `docker`, `curl` и `bash`.

| Что | Сколько |
|---|---|
| Место на диске | ~250 ГБ под дерево Android и out, ещё 20–80 ГБ под ccache |
| Память | 32 ГБ при `-j6` (меньше памяти — уменьшить `-j` в `scripts/android-build-crdroid16.sh`) |
| Первая сборка | несколько часов; повторные — 30–60 минут с ccache |

## 1. Образы Docker

```bash
docker build -t titan2-aosp docker/aosp     # Android
docker build -t titan2-kbuild docker         # ядро и мелкие бинарники Lindroid
```

## 2. Исходники Android

```bash
scripts/android-sync-crdroid16.sh
```

Скрипт кладёт crDroid 16.0 в `android/crdroid-16`, добавляет патчи TrebleDroid (MisterZtr,
`lineage-23.2`) и манифест Lindroid, затем накладывает наши патчи (`patches/titan2-a16`,
`patches/lindroid-a16`). Повторный запуск обновляет дерево; уже наложенные патчи пропускаются.

Часть патчей TrebleDroid писалась под чистый LineageOS и на форки crDroid ложится не вся — они
печатают `FAILED APPLYING`. На момент публикации известные неудачи на сборку не влияют.

## 3. Ключи подписи (по желанию)

Без ключей образ подписывается тестовыми ключами AOSP — для личного использования это работает,
но любой может подписать APK «системным» ключом. Свои ключи — по
[инструкции LineageOS](https://wiki.lineageos.org/signing_builds) (`make_key`), положить в
`secrets/android-certs/` (`releasekey`, `platform`, `shared`, `media`, `networkstack`, ...).
Каталог `secrets/` в `.gitignore`.

## 4. Стоковая камера MTK и Google Phone (по желанию)

Эти приложения проприетарные, поэтому в репозитории и релизах их нет. Их можно взять из **своей**
стоковой прошивки V01.00.10:

1. Распакуйте `super.img` пакета прошивки (`simg2img`, затем `lpunpack`) и извлеките файлы
   логических разделов `system_a`, `system_ext_a`, `product_a` в каталоги с теми же именами
   (`7z x`, `debugfs -R rdump` или `erofsfuse`, в зависимости от файловой системы).
2. `scripts/prepare-vendor-titan2.sh <каталог с system_a/ system_ext_a/ product_a/>`

Скрипт патчит камеру (`scripts/patch-mtkcamera.sh`: заглушки Agui из `patches/mtkcamera`) и
кладёт её в `vendor-titan2/MtkCamera`. Камера проверялась только с версией V01.00.10: на другой
скрипт остановится с ошибкой о числе найденных вызовов. Google Phone входит только в сборку
с GApps (`bgN4`).

Без этого шага `prepare-vendor-titan2.sh` скачивает только Plektra, и образ собирается без них.

## 5. Сборка образа

```bash
scripts/android-build-crdroid16.sh          # bvN4: без GApps
scripts/android-build-crdroid16.sh bgN4     # с MindTheGapps (см. GAPPS.ru.md)
```

Сборка долгая — удобно запускать в `tmux`. Результат:
`android/home/build-output/crdroid-16.0-trebledroid-<цель>-<дата>.img` (это `system.img`).

## 6. Ядро

```bash
scripts/kernel-prepare.sh       # NothingOSS 6.1 @ BASE_COMMIT + patches/kernel, clang r487747c
scripts/build-kernel.sh lindroid-ksu-bbr configs/lindroid-ksu-bbr-titan2.config
```

Результат: `out/boot-lindroid-ksu-bbr.img`. Это boot только с ядром: ramdisk остаётся в
стоковом `init_boot`. Стоковые модули из `vendor_dlkm` грузятся без изменений; при правке
конфига не включайте опции, ломающие KMI (`CGROUP_DEVICE`, `CGROUP_PIDS`, `FANOTIFY`,
`NET_L3_MASTER_DEV`) — список в начале `configs/lindroid-ksu-bbr-titan2.config`.

## 7. Бинарники для контейнера Lindroid

`vendor-titan2/lindroid/gpu/libvulkan-hybris.so` и `vendor-titan2/lindroid/hybris-fix/libtls-padding.so`
лежат в репозитории готовыми и пересобираются из исходников:

```bash
scripts/build-lindroid-hybris-vulkan.sh    # из external/libhybris дерева Android (Apache-2.0)
scripts/build-lindroid-hybris-fix.sh       # из tls-padding.c (нужны titan2-kbuild и clang из шага 6)
```

## 8. Прошивка

См. [INSTALL.ru.md](INSTALL.ru.md). Со своей сборкой то же самое делает скрипт:

```bash
VBMETA=<стоковый vbmeta.img> scripts/flash-crdroid16.sh <system.img> [boot.img]   # стирает данные
```

Обновление без потери данных — раздел 9 инструкции.

## Изменения в патчах

Патчи — обычный вывод `git format-patch`. Чтобы поправить: внести изменения в проект дерева
`android/crdroid-16/<путь>`, закоммитить и выгрузить заново:

```bash
git -C android/crdroid-16/frameworks/base format-patch -o patches/titan2-a16/frameworks_base <база>..HEAD
```
