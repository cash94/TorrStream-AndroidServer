"""
Готовит всё, что едет в APK, кроме Node (его кладёт tools/fetch-node.py):

  python tools/fetch-binaries.py --server-src C:/videoloop-server

- код сервера TorrStream: server.js, lib/, routes/… и рабочие зависимости
  (npm ci --omit=dev) — архивом app/src/main/assets/server.zip; приложение
  распаковывает его при первом запуске новой версии и запускает node server.js;
- ffmpeg и ffprobe: сборка NDK под bionic из релиза ffmpeg-<версия> этого репозитория
  (workflow .github/workflows/ffmpeg.yml) → app/src/main/jniLibs/<abi>/lib{ffmpeg,ffprobe}.so.

Сборки для обычного Linux (pkg/musl для Node, glibc для ffmpeg) на Android в процессе
приложения не работают: фильтр системных вызовов (seccomp) убивает их SIGSYS (код 159).
"""
import argparse
import json
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

REPO = 'cash94/TorrStream-AndroidServer'
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
    # С lock-файлом — ровно те версии, что в нём; без него — по package.json
    cmd = 'ci' if os.path.exists(os.path.join(stage, 'package-lock.json')) else 'install'
    print(f'== npm {cmd} --omit=dev')
    subprocess.run(['npm', cmd, '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'],
                   cwd=stage, check=True, shell=(os.name == 'nt'))
    os.makedirs(ASSETS, exist_ok=True)
    out = os.path.join(ASSETS, 'server.zip')
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(stage):
            for f in files:
                full = os.path.join(base, f)
                z.write(full, os.path.relpath(full, stage).replace(os.sep, '/'))
    print(f'== server.zip: {os.path.getsize(out) // 1024} КБ')


def fetch_ffmpeg(tag, abi, token):
    """ffmpeg-<abi> и ffprobe-<abi> из релиза <tag> этого репозитория (закрытого — с токеном)"""
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
    ap.add_argument('--server-src', required=True, help='папка с исходниками сервера TorrStream')
    ap.add_argument('--abi', choices=ABIS, action='append', help='только эти ABI (по умолчанию все)')
    ap.add_argument('--ffmpeg-tag', default='ffmpeg-8.0', help='релиз с ffmpeg (workflow ffmpeg.yml)')
    args = ap.parse_args()
    os.makedirs(WORK, exist_ok=True)
    bundle_server(args.server_src)
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
