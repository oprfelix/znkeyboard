#!/usr/bin/env python3
"""Build app/src/main/assets/pinyin/dict.tsv from rime-ice (GPL-3.0).

Usage:
  curl -sSLO https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/8105.dict.yaml
  curl -sSLO https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/base.dict.yaml
  tools/pinyin/build_dict.py 8105.dict.yaml base.dict.yaml app/src/main/assets/pinyin/dict.tsv

Output rows are `text<TAB>space-separated pinyin<TAB>weight`, sorted by weight
descending. Every character is kept; words are capped at MAX_WORDS by weight.
"""
import sys

MAX_WORDS = 150_000
MAX_SYLLABLES = 6

# Upstream rows whose weights are jokes or crawl artifacts (19260817, 12345678...) that would
# otherwise rank them above everyday words. Values put them among ordinary words.
WEIGHT_OVERRIDES = {
    "江泽民": 100_000,
    "江泽民同志": 10_000,
    "三国群英传": 1_000,
    "五个亿": 1_000,
    "版权": 300_000,
}


def rows(path):
    in_body = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line == "...":
                in_body = True
                continue
            if not in_body or not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 3 and parts[2].isdigit() and parts[1].replace(" ", "").isalpha():
                yield parts[0], parts[1].strip(), WEIGHT_OVERRIDES.get(parts[0], int(parts[2]))


def main(chars_path, words_path, out_path):
    chars = list(rows(chars_path))
    words = [r for r in rows(words_path) if len(r[1].split()) <= MAX_SYLLABLES]
    words.sort(key=lambda r: -r[2])
    merged = chars + words[:MAX_WORDS]
    merged.sort(key=lambda r: -r[2])
    with open(out_path, "w", encoding="utf-8") as out:
        for text, pinyin, weight in merged:
            out.write(f"{text}\t{pinyin}\t{weight}\n")
    print(f"{len(chars)} chars + {min(len(words), MAX_WORDS)} words -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
