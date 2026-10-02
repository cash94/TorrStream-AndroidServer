# TorrStream-AndroidServer

Сервер TorrStream на Android-телефоне: TorrServer + сервер TorrStream (Node) + ffmpeg.
Телевизоры (Vidaa и др.) в той же Wi-Fi сети подключаются к `http://<IP телефона>:3000`.

## Сборка

1. FFmpeg под Android (NDK, bionic) — workflow `ffmpeg.yml` (Actions → ffmpeg → Run),
   ассеты попадают в релиз `ffmpeg-<версия>`.
2. Бинарники в `app/src/main/jniLibs`:
   `python tools/fetch-binaries.py --server-src C:/videoloop-server`
   (сервер — `@yao-pkg/pkg`, цель `node24-linuxstatic-*`; ffmpeg — из релиза).
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
