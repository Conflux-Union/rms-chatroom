#!/usr/bin/env python3
"""One-shot repair for WebP files produced by ffmpeg's webp muxer writing to
a non-seekable pipe: RIFF size field is 0 and the intended size (len-12) leaks
as 4 trailing bytes. Strict decoders (Chrome) reject such files.

For each affected file: drop the 4 trailing bytes and write size = len-8 into
the RIFF header. Idempotent — files with a correct header are skipped.
Run with --dry-run (default off) to preview changes.
"""
import argparse
import glob
import os
import struct


def needs_repair(path):
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        head = f.read(12)
        if len(head) < 12 or head[:4] != b"RIFF" or head[8:12] != b"WEBP":
            return False
        riff = struct.unpack("<I", head[4:8])[0]
        if riff == size - 8:
            return False
        f.seek(-4, os.SEEK_END)
        tail = struct.unpack("<I", f.read(4))[0]
        return riff == 0 and tail == size - 12


def repair(path):
    with open(path, "rb+") as f:
        f.seek(0, os.SEEK_END)
        size = f.tell()
        f.seek(-4, os.SEEK_END)
        f.truncate(size - 4)
        f.seek(4)
        f.write(struct.pack("<I", size - 4 - 8))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base", help="uploads directory containing channel subdirs")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    total = fixed = 0
    for path in sorted(glob.glob(os.path.join(args.base, "*", "*.webp"))):
        total += 1
        if not needs_repair(path):
            continue
        fixed += 1
        if args.dry_run:
            print(f"would fix {path}")
        else:
            repair(path)
            # verify after writing
            if not (needs_repair(path) is False and os.path.getsize(path) > 0):
                print(f"VERIFY FAILED: {path}")
    print(f"scanned {total} webp files, {'would fix' if args.dry_run else 'fixed'} {fixed}")


if __name__ == "__main__":
    main()
