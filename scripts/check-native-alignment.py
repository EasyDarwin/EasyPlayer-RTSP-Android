#!/usr/bin/env python3
"""Check 64-bit ELF LOAD and RELRO alignment, including libraries inside APK/AAR."""
import argparse
from pathlib import Path
import struct
import sys
import zipfile


def check(name, data):
    if data[:4] != b'\x7fELF':
        return [f'{name}: not an ELF library']
    if data[4] != 2:
        return []  # Android 16 KB page sizes concern 64-bit ABIs.
    order = '<' if data[5] == 1 else '>'
    phoff = struct.unpack_from(order + 'Q', data, 32)[0]
    entsize, count = struct.unpack_from(order + 'HH', data, 54)
    errors = []
    headers = [struct.unpack_from(order + 'IIQQQQQQ', data, phoff + i * entsize) for i in range(count)]
    for kind, flags, offset, addr, _, size, memsize, align in headers:
        if kind == 1 and (align < 16384 or (addr - offset) % 16384):
            errors.append(f'{name}: LOAD alignment=0x{align:x}, offset=0x{offset:x}, address=0x{addr:x}')
        if kind == 0x6474e552 and (addr + memsize) % 16384:
            end = addr + memsize
            protected_end = (end + 16383) & ~16383
            # Linkers may leave a gap after RELRO. Rounding its protection up
            # is safe if that gap contains no writable LOAD data (as in the
            # existing RTSP client). Reject writable tails in the same page.
            if any(h[0] == 1 and h[1] & 2 and h[3] < protected_end and h[3] + h[6] > end for h in headers):
                errors.append(f'{name}: RELRO ending at 0x{end:x} shares a 16 KB page with writable LOAD data')
    if not errors:
        print(f'PASS {name}: 16 KB LOAD alignment; RELRO does not overlap writable data')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', type=Path)
    args = parser.parse_args()
    errors = []
    if args.path.is_dir():
        entries = [(str(p), p.read_bytes()) for p in sorted(args.path.rglob('*.so'))]
    elif zipfile.is_zipfile(args.path):
        with zipfile.ZipFile(args.path) as archive:
            entries = [(name, archive.read(name)) for name in archive.namelist() if name.endswith('.so')]
    else:
        entries = [(str(args.path), args.path.read_bytes())]
    if not entries:
        parser.error('No native libraries found')
    for name, data in entries:
        errors.extend(check(name, data))
    for error in errors:
        print('FAIL ' + error, file=sys.stderr)
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
