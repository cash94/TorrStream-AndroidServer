"""
Node.js для Android из пакетов Termux — собран под bionic, как и всё на Android.

  python tools/fetch-node.py [--abi arm64-v8a] [--abi x86_64]

Статическая сборка Node для обычного Linux (pkg, musl) в процессе приложения погибает
от фильтра системных вызовов (seccomp, SIGSYS — код выхода 159). Termux собирает Node
под Android: nodejs-lts и его библиотеки (libc++, openssl, c-ares, icu, sqlite, zlib).

Раскладка в app/src/main/jniLibs/<abi>:
  libnode.so                    — сам node (исполняемый);
  libdep_<hex имени>.so         — библиотеки;
  libdeplink_<hex>_<hex>.so     — пустые метки символических ссылок (имя → цель).
                                  Библиотеки: Из APK распаковываются только файлы вида
                                  lib*.so, а у части библиотек имя с версией
                                  (libssl.so.3), поэтому имя закодировано; приложение
                                  создаёт на них ссылки с настоящими именами
                                  (ServerService.prepareNodeLibs) и указывает
                                  LD_LIBRARY_PATH.
"""
import argparse
import io
import lzma
import os
import sys
import tarfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNI = os.path.join(ROOT, 'app', 'src', 'main', 'jniLibs')
WORK = os.path.join(ROOT, 'build', 'termux')
MIRROR = 'https://packages.termux.dev/apt/termux-main'
TERMUX_ARCH = {'arm64-v8a': 'aarch64', 'armeabi-v7a': 'arm', 'x86_64': 'x86_64'}
ROOT_PACKAGE = 'nodejs-lts'
PREFIX = 'data/data/com.termux/files/usr/'


def fetch(url):
    with urllib.request.urlopen(url) as r:
        return r.read()


def packages_index(arch):
    raw = fetch(f'{MIRROR}/dists/stable/main/binary-{arch}/Packages')
    pk, cur = {}, {}
    for line in raw.decode('utf-8').split('\n') + ['']:
        if not line:
            if 'Package' in cur:
                pk[cur['Package']] = cur
            cur = {}
        elif ':' in line and not line.startswith(' '):
            k, v = line.split(':', 1)
            cur[k] = v.strip()
    return pk


def resolve(pk, name, seen):
    """Пакет и все его зависимости (по Depends, без версий и альтернатив)"""
    if name in seen or name not in pk:
        return
    seen.add(name)
    for dep in pk[name].get('Depends', '').split(','):
        dep = dep.split('|')[0].split('(')[0].strip()
        if dep:
            resolve(pk, dep, seen)


def deb_data(deb):
    """data.tar.* из ar-архива .deb"""
    assert deb[:8] == b'!<arch>\n'
    pos = 8
    while pos < len(deb):
        name = deb[pos:pos + 16].decode().strip().rstrip('/')
        size = int(deb[pos + 48:pos + 58].decode().strip())
        body = deb[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if name.startswith('data.tar'):
            if name.endswith('.xz'):
                return tarfile.open(fileobj=io.BytesIO(lzma.decompress(body)))
            return tarfile.open(fileobj=io.BytesIO(body))
    raise RuntimeError('в .deb нет data.tar')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--abi', choices=list(TERMUX_ARCH), action='append')
    args = ap.parse_args()
    for abi in args.abi or list(TERMUX_ARCH):
        arch = TERMUX_ARCH[abi]
        pk = packages_index(arch)
        names = set()
        resolve(pk, ROOT_PACKAGE, names)
        print(f'== {abi}: {ROOT_PACKAGE} {pk[ROOT_PACKAGE]["Version"]}, пакеты: {sorted(names)}')
        dest = os.path.join(JNI, abi)
        os.makedirs(dest, exist_ok=True)
        # Прежние библиотеки — долой: состав зависимостей мог смениться
        for f in os.listdir(dest):
            if f.startswith('libdep') or f == 'libnode.so':
                os.remove(os.path.join(dest, f))
        libs = 0
        for name in sorted(names):
            deb = fetch(f'{MIRROR}/{pk[name]["Filename"]}')
            tar = deb_data(deb)
            links = {}
            for m in tar.getmembers():
                path = m.name.lstrip('./')
                if not path.startswith(PREFIX):
                    continue
                rel = path[len(PREFIX):]
                if rel == 'bin/node' and m.isfile():
                    with open(os.path.join(dest, 'libnode.so'), 'wb') as f:
                        f.write(tar.extractfile(m).read())
                elif rel.startswith('lib/') and '/' not in rel[4:] and '.so' in rel:
                    base = rel[4:]
                    # Утилиты ICU node не нужны — лишние мегабайты в APK
                    if base.startswith(('libicuio', 'libicutest', 'libicutu')):
                        continue
                    if m.issym():
                        links[base] = m.linkname
                    elif m.isfile():
                        data = tar.extractfile(m).read()
                        with open(os.path.join(dest, 'libdep_' + base.encode().hex() + '.so'), 'wb') as f:
                            f.write(data)
                        libs += 1
            # Символические ссылки (libicuuc.so.77 → libicuuc.so.77.1): копия удвоила бы APK,
            # поэтому только пустая метка libdeplink_<ссылка>_<цель>.so — ссылку создаст приложение
            for base, target in links.items():
                name = 'libdeplink_' + base.encode().hex() + '_' + os.path.basename(target).encode().hex() + '.so'
                open(os.path.join(dest, name), 'wb').close()
        if not os.path.exists(os.path.join(dest, 'libnode.so')):
            sys.exit(f'{abi}: в пакетах нет bin/node')
        print(f'== {abi}: node + {libs} библиотек')


if __name__ == '__main__':
    main()
