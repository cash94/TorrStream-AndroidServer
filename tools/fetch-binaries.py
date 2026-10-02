"""
Раскладывает бинарники в app/src/main/jniLibs перед сборкой APK.

  python tools/fetch-binaries.py --server-src C:/videoloop-server

- сервер TorrStream: собирается из исходников (--server-src) через @yao-pkg/pkg в
  статические сборки Node (node24-linuxstatic-arm64 / -x64) — на Android работают,
  потому что не зависят от библиотек системы;
- ffmpeg и ffprobe: сборка NDK под bionic из релиза ffmpeg-<версия> этого репозитория
  (workflow .github/workflows/ffmpeg.yml). Статические сборки для обычного Linux на
  Android не годятся: glibc при старте делает set_robust_list/rseq, и фильтр системных
  вызовов приложений (seccomp) убивает процесс.

На Android исполняемые файлы должны лежать в APK как lib*.so (jniLibs): система
распаковывает их в nativeLibraryDir, откуда запуск разрешён.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNI = os.path.join(ROOT, 'app', 'src', 'main', 'jniLibs')
WORK = os.path.join(ROOT, 'build', 'binaries')

REPO = 'cash94/TorrStream-AndroidServer'
ABIS = {
    # ABI Android: цель pkg
    'arm64-v8a': 'node24-linuxstatic-arm64',
    'x86_64': 'node24-linuxstatic-x64',
}


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


def build_server(src, target, out):
    print(f'== сервер {target}')
    cmd = ['npx', '-y', '@yao-pkg/pkg@6.19.0', '.', '--targets', target,
           '--no-bytecode', '--public', '--public-packages', '*', '--output', out]
    subprocess.run(cmd, cwd=src, check=True, shell=(os.name == 'nt'))


def fetch_ffmpeg(tag, abi, token):
    """ffmpeg-<abi> и ffprobe-<abi> из релиза <tag> этого репозитория (закрытого — с токеном)"""
    rel = json.loads(github_get(f'https://api.github.com/repos/{REPO}/releases/tags/{tag}', token))
    assets = {a['name']: a['url'] for a in rel.get('assets', [])}
    found = {}
    for name in ('ffmpeg', 'ffprobe'):
        asset = f'{name}-{abi}'
        if asset not in assets:
            sys.exit(f'в релизе {tag} нет {asset}')
        print(f'== {asset} из {tag}')
        found[name] = github_get(assets[asset], token, binary=True)
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--server-src', required=True, help='папка с исходниками сервера TorrStream')
    ap.add_argument('--abi', choices=list(ABIS), action='append', help='только эти ABI (по умолчанию все)')
    ap.add_argument('--ffmpeg-tag', default='ffmpeg-8.0', help='релиз с ffmpeg (workflow ffmpeg.yml)')
    args = ap.parse_args()
    os.makedirs(WORK, exist_ok=True)
    token = github_token()
    for abi in args.abi or list(ABIS):
        target = ABIS[abi]
        dest = os.path.join(JNI, abi)
        os.makedirs(dest, exist_ok=True)
        server_out = os.path.join(WORK, f'torrstream-{abi}')
        build_server(args.server_src, target, server_out)
        shutil.copyfile(server_out, os.path.join(dest, 'libtorrstream.so'))
        for name, data in fetch_ffmpeg(args.ffmpeg_tag, abi, token).items():
            with open(os.path.join(dest, f'lib{name}.so'), 'wb') as f:
                f.write(data)
        print(f'== {abi}: готово')


if __name__ == '__main__':
    main()
