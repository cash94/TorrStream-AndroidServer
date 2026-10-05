# TorrStream-AndroidServer

Сервер TorrStream на Android-телефоне: TorrServer + сервер TorrStream (Node) + ffmpeg.
Телевизоры (Vidaa и др.) в той же Wi-Fi сети подключаются к `http://<IP телефона>:3000`.

## Сборка

**В CI (обычный путь).** Тег `v*` → workflow `build.yml` собирает и подписывает APK
(arm64-v8a, armeabi-v7a) и выкладывает релиз с постоянными именами файлов:
`releases/latest/download/TorrStream-AndroidServer-armeabi-v7a.apk` — всегда последняя
версия. Подпись — секреты `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

**Код сервера** лежит в `server/`: `TorrStream-android-server.zip` и `version.json`
(версия, sha256). Его берут и сборка, и кнопка «Обновить сервер» в приложении
(`raw.githubusercontent.com/.../main/server/`). Обновить его — на машине с исходниками
сервера (закрытый `cash94/TorrStream`):
`python tools/fetch-binaries.py --server-src C:/videoloop-server`, затем коммит `server/`.

**Локально:**
1. FFmpeg под Android (NDK, bionic) — workflow `ffmpeg.yml` (Actions → ffmpeg → Run),
   ассеты попадают в релиз `ffmpeg-<версия>`.
2. `python tools/fetch-node.py` и `python tools/fetch-binaries.py` — Node из Termux,
   ffmpeg из релиза, `server/` → `app/src/main/assets/server.zip`.
3. APK: `./gradlew :app:assembleDebug` (JDK 11) → `app/build/outputs/apk/debug/app-<abi>-debug.apk`.

## Почему так

- Исполняемые файлы лежат в APK как `lib*.so`: система распаковывает их в
  `nativeLibraryDir`, откуда запуск разрешён. TorrServer скачивается в память
  приложения — это работает, пока `targetSdkVersion` < 29.
- У статического Node на Android нет системного DNS (нет `/etc/resolv.conf`): приложение
  передаёт DNS сети в `TORRSTREAM_DNS`, сервер подменяет `dns.lookup` (`lib/android-dns.js`).
- Сборки FFmpeg для обычного Linux (glibc) в процессе приложения убивает seccomp
  (`set_robust_list`, `rseq`) — поэтому своя сборка под bionic.

## Отладка без пересборки

`files/server.bin` — запустить вместо встроенного сервера; `files/server.args` и
`files/server.env` — дополнительные аргументы и переменные (через `adb shell run-as`).
