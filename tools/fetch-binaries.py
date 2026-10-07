"""
Готовит всё, что едет в APK, кроме Node (его кладёт tools/fetch-node.py):

  python tools/fetch-binaries.py [--server-src C:/videoloop-server]

- код сервера TorrStream: server.js, lib/, routes/… (минифицированные esbuild —
  исходники сервера закрытые, а архив лежит в открытом репозитории) и рабочие
  зависимости (npm ci --omit=dev) — архивом server/TorrStream-android-server.zip в этом
  репозитории, рядом server/version.json (версия, sha256). Его же берёт кнопка
  «Обновить сервер» (ServerUpdater) и CI. С --server-src архив собирается заново
  из исходников (закрытый cash94/TorrStream), без него — берётся уже лежащий.
  В APK он едет как app/src/main/assets/server.zip; приложение распаковывает его
  при первом запуске новой версии и запускает node server.js;
- ffmpeg и ffprobe: сборка NDK под bionic из релиза ffmpeg-<версия> этого репозитория
  (workflow .github/workflows/ffmpeg.yml) → app/src/main/jniLibs/<abi>/lib{ffmpeg,ffprobe}.so.

Сборки для обычного Linux (pkg/musl для Node, glibc для ffmpeg) на Android в процессе
приложения не работают: фильтр системных вызовов (seccomp) убивает их SIGSYS (код 159).
"""
import argparse
import datetime
import hashlib
import json
import re
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNI = os.path.join(ROOT, 'app', 'src', 'main', 'jniLibs')
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
WORK = os.path.join(ROOT, 'build', 'binaries')
SERVER_DIR = os.path.join(ROOT, 'server')
SERVER_ZIP = os.path.join(SERVER_DIR, 'TorrStream-android-server.zip')

REPO = 'cash94/TorrStream-AndroidServer'
ESBUILD_VERSION = '0.28.2'
ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86_64']
# Что из исходников сервера нужно для запуска (как scripts в package.json → pkg)
SERVER_FILES = ['server.js', 'module-loader.js', 'windows-pause.js', 'package.json', 'package-lock.json']
SERVER_DIRS = ['lib', 'middleware', 'services', 'routes', 'workers']


def github_token():
    token = os.environ.get('GITHUB_TOKEN')
    if token:
        return token
    out = subprocess.run(['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n',
                         capture_output=True, text=True).stdout
    for line in out.splitlines():
        if line.startswith('password='):
            return line.split('=', 1)[1]
    sys.exit('нет токена GitHub (GITHUB_TOKEN или git credential)')


def github_get(url, token, binary=False):
    req = urllib.request.Request(url, headers={
        'Authorization': 'token ' + token,
        'Accept': 'application/octet-stream' if binary else 'application/vnd.github+json',
    })
    with urllib.request.urlopen(req) as r:
        return r.read()


def bundle_server(src):
    stage = os.path.join(WORK, 'server')
    shutil.rmtree(stage, ignore_errors=True)
    os.makedirs(stage)
    for f in SERVER_FILES:
        path = os.path.join(src, f)
        # Пустой package-lock.json (бывает в рабочей копии) npm ci не примет
        if os.path.exists(path) and os.path.getsize(path) > 2:
            shutil.copy2(path, stage)
    for d in SERVER_DIRS:
        shutil.copytree(os.path.join(src, d), os.path.join(stage, d))
    version = minify_server(stage)
    # С lock-файлом — ровно те версии, что в нём; без него — по package.json
    cmd = 'ci' if os.path.exists(os.path.join(stage, 'package-lock.json')) else 'install'
    print(f'== npm {cmd} --omit=dev')
    subprocess.run(['npm', cmd, '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'],
                   cwd=stage, check=True, shell=(os.name == 'nt'))
    os.makedirs(SERVER_DIR, exist_ok=True)
    with zipfile.ZipFile(SERVER_ZIP, 'w', zipfile.ZIP_DEFLATED) as z:
        for base, _, files in sorted(os.walk(stage)):
            for f in sorted(files):
                full = os.path.join(base, f)
                z.write(full, os.path.relpath(full, stage).replace(os.sep, '/'))
    data = open(SERVER_ZIP, 'rb').read()
    info = {
        'version': version,
        'sha256': hashlib.sha256(data).hexdigest(),
        'size': len(data),
        'built': datetime.date.today().isoformat(),
    }
    with open(os.path.join(SERVER_DIR, 'version.json'), 'w', encoding='utf-8') as f:
        json.dump(info, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print(f'== server/TorrStream-android-server.zip: {len(data) // 1024} КБ, {info["version"]}')


def minify_server(stage):
    """
    Свой код сервера — в минифицированном виде (esbuild): без комментариев, локальные
    имена сокращены. Архив лежит в открытом репозитории и в APK, а исходники сервера
    закрытые. Раскладка файлов та же — пути воркеров, require и __dirname не меняются;
    node_modules (открытые библиотеки) не трогаем. Возвращает версию сервера.
    """
    server_js = os.path.join(stage, 'server.js')
    # Версия — из первой строки server.js, как её показывает приложение
    m = re.search(r"'([^']+)'", open(server_js, encoding='utf-8').readline())
    version = m.group(1) if m else None
    files = [os.path.relpath(os.path.join(base, f), stage)
             for base, _, names in os.walk(stage) for f in names if f.endswith('.js')]
    print(f'== esbuild --minify: {len(files)} файлов')
    subprocess.run(['npx', '-y', f'esbuild@{ESBUILD_VERSION}', *files,
                    '--minify', '--format=cjs', '--platform=node', '--target=node20',
                    '--legal-comments=none', '--charset=utf8', '--log-level=warning',
                    '--outdir=.', '--outbase=.', '--allow-overwrite'],
                   cwd=stage, check=True, shell=(os.name == 'nt'))
    # После минификации первая строка — уже не `const version = '…'`, а приложение
    # (ServerUpdater.currentVersion) берёт версию именно из первой строки server.js
    body = open(server_js, encoding='utf-8').read()
    with open(server_js, 'w', encoding='utf-8') as f:
        f.write(f"// '{version}'\n" + body)
    return version


def copy_server_to_assets():
    if not os.path.isfile(SERVER_ZIP):
        sys.exit('нет server/TorrStream-android-server.zip — запустите с --server-src')
    os.makedirs(ASSETS, exist_ok=True)
    shutil.copyfile(SERVER_ZIP, os.path.join(ASSETS, 'server.zip'))


def fetch_ffmpeg(tag, abi, token):
    """ffmpeg-<abi> и ffprobe-<abi> из релиза <tag> этого репозитория (токен — на случай закрытого)"""
    rel = json.loads(github_get(f'https://api.github.com/repos/{REPO}/releases/tags/{tag}', token))
    assets = {a['name']: a['url'] for a in rel.get('assets', [])}
    for name in ('ffmpeg', 'ffprobe'):
        asset = f'{name}-{abi}'
        if asset not in assets:
            sys.exit(f'в релизе {tag} нет {asset}')
        print(f'== {asset} из {tag}')
        with open(os.path.join(JNI, abi, f'lib{name}.so'), 'wb') as f:
            f.write(github_get(assets[asset], token, binary=True))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--server-src', help='собрать server/ заново из исходников сервера TorrStream')
    ap.add_argument('--abi', choices=ABIS, action='append', help='только эти ABI (по умолчанию все)')
    ap.add_argument('--ffmpeg-tag', default='ffmpeg-8.0', help='релиз с ffmpeg (workflow ffmpeg.yml)')
    args = ap.parse_args()
    os.makedirs(WORK, exist_ok=True)
    if args.server_src:
        bundle_server(args.server_src)
    copy_server_to_assets()
    token = github_token()
    for abi in args.abi or ABIS:
        os.makedirs(os.path.join(JNI, abi), exist_ok=True)
        # Прежняя сборка сервера через pkg (musl) — на Android не запускается
        old = os.path.join(JNI, abi, 'libtorrstream.so')
        if os.path.exists(old):
            os.remove(old)
        fetch_ffmpeg(args.ffmpeg_tag, abi, token)


if __name__ == '__main__':
    main()
