#!/usr/bin/env python3
"""Build the bundled English letter-trigram prior used by the adaptive touch decoder.

Input: Peter Norvig's word-frequency list (count_1w.txt, derived from the Google
Web Trillion Word Corpus): https://norvig.com/ngrams/count_1w.txt

Output: app/src/main/assets/typing/en_char_trigram.bin
  27*27*27 unsigned bytes. Alphabet index 0 = boundary (space / non-letter),
  1..26 = a..z. Byte [a*729 + b*27 + c] = round(-log2(P(c | a b)) * 16), clamped
  to 0..255, so a decoder recovers P = 2^(-byte/16).

Only aggregate letter statistics are shipped; no words are stored.
"""
import math
import sys
from pathlib import Path

ALPHA = 27
SCALE = 16.0
ADD_K = 0.5


def idx(ch: str) -> int:
    o = ord(ch)
    if 97 <= o <= 122:
        return o - 96
    return 0


def main(src: Path, dst: Path, max_words: int = 200_000) -> None:
    counts = [0.0] * (ALPHA ** 3)
    with src.open() as fh:
        for n, line in enumerate(fh):
            if n >= max_words:
                break
            word, count = line.split("\t")
            c = float(count)
            seq = [0, 0] + [idx(ch) for ch in word.lower()] + [0]
            for i in range(2, len(seq)):
                counts[seq[i - 2] * 729 + seq[i - 1] * 27 + seq[i]] += c
    out = bytearray(ALPHA ** 3)
    for a in range(ALPHA):
        for b in range(ALPHA):
            base = a * 729 + b * 27
            row = counts[base:base + ALPHA]
            # Boundary->boundary transitions are meaningless for typing; keep them unlikely.
            total = sum(row) + ADD_K * ALPHA
            for c in range(ALPHA):
                p = (row[c] + ADD_K) / total
                out[base + c] = max(0, min(255, round(-math.log2(p) * SCALE)))
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(bytes(out))
    print(f"wrote {dst} ({len(out)} bytes)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: build_char_trigrams.py count_1w.txt out.bin")
    main(Path(sys.argv[1]), Path(sys.argv[2]))

