#!/usr/bin/env python3
"""Run 16 KB native checks and optional live RTSP playback/recording on an emulator."""
import argparse
import json
from pathlib import Path
import shutil
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--serial', required=True)
    parser.add_argument('--rtsp-url')
    parser.add_argument('--software', choices=['true', 'false'], default='true')
    parser.add_argument('--ffprobe', default=shutil.which('ffprobe'))
    parser.add_argument('--output', type=Path, default=ROOT / '.native/test-results')
    args = parser.parse_args()
    if not args.adb or not args.ffprobe:
        parser.error('adb and ffprobe must be on PATH or provided explicitly')
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, '-s', args.serial]
    def run(command, **kwargs):
        return subprocess.run(command, check=True, **kwargs)
    page = run(adb + ['shell', 'getconf', 'PAGE_SIZE'], capture_output=True, text=True).stdout.strip()
    if page != '16384':
        raise RuntimeError(f'Expected a 16 KB device, got PAGE_SIZE={page}')
    for apk in ['debug/simpleplayer-debug.apk', 'androidTest/debug/simpleplayer-debug-androidTest.apk']:
        run(adb + ['install', '-r', str(ROOT / 'simpleplayer/build/outputs/apk' / apk)])
    command = adb + ['shell', 'am', 'instrument', '-w', '-e', 'require_16k', 'true']
    if args.rtsp_url:
        command += ['-e', 'rtsp_url', args.rtsp_url, '-e', 'software', args.software]
    command += ['org.easydarwin.easyplayer.test/org.easydarwin.player.simpleplayer.test.NativeSmokeInstrumentation']
    shell_index = command.index('shell')
    command = command[:shell_index + 1] + [shlex.join(command[shell_index + 1:])]
    result = run(command, capture_output=True, text=True, timeout=90)
    print(result.stdout)
    (args.output / 'instrumentation.txt').write_text(result.stdout + result.stderr)
    if 'PASS:' not in result.stdout or 'FAIL:' in result.stdout:
        raise RuntimeError('Native instrumentation failed')
    names = ['native-test-0.mp4', 'native-test-1.mp4']
    if args.rtsp_url:
        names += ['live-test-soft.mp4' if args.software == 'true' else 'live-test-hard.mp4']
    for name in names:
        local = args.output / name
        recording = run(adb + ['exec-out', 'run-as', 'org.easydarwin.easyplayer', 'cat', 'files/' + name],
                        capture_output=True)
        local.write_bytes(recording.stdout)
        result = run([args.ffprobe, '-v', 'error', '-count_frames', '-show_streams', '-of', 'json', str(local)],
                     capture_output=True, text=True)
        if result.stderr.strip():
            raise RuntimeError('Recording decode errors: ' + result.stderr)
        streams = json.loads(result.stdout)['streams']
        video = next(s for s in streams if s['codec_type'] == 'video')
        audio = next(s for s in streams if s['codec_type'] == 'audio')
        if name.startswith('native-test'):
            assert int(video['nb_read_frames']) == 20, 'Lost video frame'
            assert abs(float(video['duration']) - 2.0) < .01, 'Incorrect video duration'
            assert abs(float(audio['duration']) - 2.0) < .01, 'Incorrect audio duration'
        else:
            assert int(video['nb_read_frames']) > 10, 'Live recording is empty'
            assert int(audio['nb_read_frames']) > 0, 'Live recording has no audio'
            assert float(video['duration']) >= 8.0, 'Live recording ended too early'
        (args.output / (name + '.json')).write_text(result.stdout)
        print(f'PASS {name}: {video["codec_name"]}, {video["nb_read_frames"]} video frames; {audio["codec_name"]}')


if __name__ == '__main__':
    main()
