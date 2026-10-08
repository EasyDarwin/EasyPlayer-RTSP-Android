#!/usr/bin/env python3
"""Rebuild the FFmpeg JNI library. Requires NDK r28+, CMake and make."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '7.1.5'
SHA256 = 'de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f'


def run(args, cwd=None):
    print('+', ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--abis', nargs='+', choices=['arm64-v8a', 'armeabi-v7a', 'x86'],
                        default=['arm64-v8a', 'armeabi-v7a', 'x86'])
    parser.add_argument('--jobs', type=int, default=min(os.cpu_count() or 4, 10))
    args = parser.parse_args()
    if not args.ndk:
        parser.error('Set ANDROID_NDK_HOME or pass --ndk (NDK r28 or newer).')
    ndk = Path(args.ndk).resolve()
    revision = next(line.split('=')[1].strip() for line in (ndk / 'source.properties').read_text().splitlines()
                    if line.startswith('Pkg.Revision'))
    if int(revision.split('.')[0]) < 28:
        parser.error('NDK r28+ is required for flexible page sizes.')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}[platform.system()]
    toolchain = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    cache = ROOT / '.native'
    cache.mkdir(exist_ok=True)
    archive = ROOT / 'third_party/ffmpeg' / f'ffmpeg-{VERSION}.tar.xz'
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        run(['curl', '-fL', f'https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz', '-o', archive])
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError('FFmpeg archive checksum mismatch')
    source = cache / f'ffmpeg-{VERSION}'
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(cache, filter='data')
    cmake = shutil.which('cmake')
    if not cmake:
        parser.error('CMake must be installed and available on PATH.')
    targets = {'arm64-v8a': ('aarch64', 'aarch64-linux-android'),
               'armeabi-v7a': ('arm', 'armv7a-linux-androideabi'),
               'x86': ('x86', 'i686-linux-android')}
    for abi in args.abis:
        arch, triple = targets[abi]
        build = cache / VERSION / abi / 'ffmpeg'
        prefix = cache / VERSION / abi / 'prefix'
        build.mkdir(parents=True, exist_ok=True)
        # Keep FFmpeg's embedded configuration string free of machine paths.
        # The wrappers hold local toolchain paths outside the checked-in tree;
        # configure only records stable, build-directory-relative command names.
        wrappers = build / 'toolchain'
        wrappers.mkdir(exist_ok=True)
        file_maps = [f'-ffile-prefix-map={ROOT}/.native=ffmpeg-{VERSION}',
                     f'-ffile-prefix-map={ROOT}=.']
        commands = {'cc': toolchain / (triple + '21-clang'),
                    'cxx': toolchain / (triple + '21-clang++'),
                    'ar': toolchain / 'llvm-ar', 'ranlib': toolchain / 'llvm-ranlib',
                    'strip': toolchain / 'llvm-strip'}
        for name, target in commands.items():
            arguments = [shlex.quote(str(target))]
            if name in ('cc', 'cxx'):
                arguments += [shlex.quote(flag) for flag in file_maps]
            wrapper = wrappers / name
            wrapper.write_text('#!/bin/sh\nexec ' + ' '.join(arguments) + ' "$@"\n')
            wrapper.chmod(0o755)
        relative = lambda path: os.path.relpath(path, build)
        config = [Path(relative(source / 'configure')), f'--prefix={relative(prefix)}', '--target-os=android', f'--arch={arch}',
                  '--enable-cross-compile', '--cc=toolchain/cc', '--cxx=toolchain/cxx',
                  '--ar=toolchain/ar', '--ranlib=toolchain/ranlib', '--strip=toolchain/strip',
                  '--enable-pic', '--disable-shared', '--enable-static', '--disable-programs',
                  '--disable-doc', '--disable-debug', '--disable-autodetect', '--disable-everything',
                  '--disable-network', '--disable-avdevice', '--disable-avfilter', '--disable-postproc',
                  '--enable-avcodec', '--enable-avformat', '--enable-avutil', '--enable-swscale',
                  '--enable-swresample', '--enable-decoder=h264,hevc,mjpeg,mpeg4,aac,pcm_alaw,pcm_mulaw,adpcm_g726,adpcm_g726le',
                  '--enable-parser=h264,hevc,aac', '--enable-encoder=aac', '--enable-muxer=mp4,mov',
                  '--enable-protocol=file', '--extra-cflags=-O2 -fPIC',
                  '--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384']
        if abi == 'armeabi-v7a':
            config += ['--cpu=armv7-a', '--enable-neon']
        if abi == 'x86':
            config += ['--disable-x86asm', '--disable-inline-asm']
        # Rerun configure when any build input (including NDK) changes.
        stamp = build / '.configuration'
        fingerprint = '\n'.join(map(str, config)) + '\n' + revision
        if not stamp.exists() or stamp.read_text() != fingerprint:
            run(config, build)
            stamp.write_text(fingerprint)
        run(['make', f'-j{args.jobs}'], build)
        run(['make', 'install'], build)
        jni_build = cache / VERSION / abi / 'jni'
        run([cmake, '-S', ROOT / 'library/src/main/cpp', '-B', jni_build,
             f'-DCMAKE_TOOLCHAIN_FILE={ndk / "build/cmake/android.toolchain.cmake"}',
             f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-21', '-DANDROID_STL=c++_static',
             '-DCMAKE_BUILD_TYPE=Release', f'-DCMAKE_C_FLAGS=-ffile-prefix-map={ROOT}=.',
             f'-DCMAKE_CXX_FLAGS=-ffile-prefix-map={ROOT}=.', f'-DFFMPEG_ROOT={prefix}'])
        run([cmake, '--build', jni_build, '-j', args.jobs])
        output = ROOT / 'library/src/main/jniLibs' / abi / 'libEasyPlayerFFmpeg.so'
        shutil.copy2(jni_build / 'libEasyPlayerFFmpeg.so', output)
        run([toolchain / 'llvm-strip', '--strip-unneeded', output])
        print(f'Built {output}', flush=True)
    run(['python3', ROOT / 'scripts/check-native-alignment.py', ROOT / 'library/src/main/jniLibs'])


if __name__ == '__main__':
    main()
