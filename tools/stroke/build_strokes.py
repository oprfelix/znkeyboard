#!/usr/bin/env python3
"""Build app/src/main/assets/stroke/strokes.tsv from rime-stroke (LGPL-3.0).

Usage:
  curl -sSLO https://raw.githubusercontent.com/rime/rime-stroke/master/stroke.dict.yaml
  tools/stroke/build_strokes.py stroke.dict.yaml app/src/main/assets/pinyin/dict.tsv app/src/main/assets/stroke/strokes.tsv

Keeps the characters the pinyin dictionary has, so stroke input offers the same set, most
frequent first. Rows are `character<TAB>strokes`, strokes as h s p n z (横 竖 撇 点 折).
"""
import sys


def strokes(path):
    table = {}
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
            if len(parts) >= 2 and len(parts[0]) == 1 and parts[1] and set(parts[1]) <= set("hspnz"):
                # The first (main) table row for a character wins over later variants.
                table.setdefault(parts[0], parts[1])
    return table


def char_weights(path):
    weights = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            text, _, weight = line.rstrip("\n").split("\t")
            if len(text) == 1:
                weights[text] = max(weights.get(text, 0), int(weight))
    return weights


def main(stroke_path, dict_path, out_path):
    table = strokes(stroke_path)
    weights = char_weights(dict_path)
    kept = [(c, table[c]) for c in sorted(weights, key=lambda c: -weights[c]) if c in table]
    with open(out_path, "w", encoding="utf-8") as out:
        for char, code in kept:
            out.write(f"{char}\t{code}\n")
    missing = len(weights) - len(kept)
    print(f"{len(kept)} characters written, {missing} without stroke data", file=sys.stderr)


if __name__ == "__main__":
    main(*sys.argv[1:4])
