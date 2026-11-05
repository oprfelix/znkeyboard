#!/usr/bin/env python3
"""Builds app/src/main/assets/pinyin/trade.tsv from trade_terms.txt.

These words load after dict.tsv instead of going into it, because lm.bin only fits the dict.tsv it
was built from and retraining needs the corpus and 3 GB. Rows are `text<TAB>pinyin<TAB>weight`.
Words dict.tsv already has keep their reading and rank there; listing them marks them as trade
words, which the sentence decoder favours a little.

Usage: PYTHONPATH=<pypinyin> tools/pinyin/build_trade.py
"""
from pypinyin import Style, lazy_pinyin

WEIGHT = 20_000
TERMS = "tools/pinyin/trade_terms.txt"
MAIN = "app/src/main/assets/pinyin/dict.tsv"
OUT = "app/src/main/assets/pinyin/trade.tsv"

# Readings pypinyin gets wrong in these words; review its polyphones when adding terms.
READINGS = {
    "还盘": "huan pan",
    "还柜": "huan gui",
    "中间行": "zhong jian hang",
    "报关行": "bao guan hang",
    "长尾词": "chang wei ci",
    "侧唛": "ce mai",
    "箱唛": "xiang mai",
    "唛头": "mai tou",
}

# Words typed both ways: a second row for the other reading.
ALSO = {"开模": "kai mo", "开模费": "kai mo fei"}


def spell(word):
    return " ".join(p.replace("ü", "v") for p in lazy_pinyin(word, style=Style.NORMAL, v_to_u=False))


def main():
    syllables, known = set(), {}
    with open(MAIN, encoding="utf8") as f:
        for line in f:
            text, pinyin, _ = line.rstrip("\n").split("\t")
            known.setdefault(text, pinyin)
            syllables.update(pinyin.split())
    terms = []
    with open(TERMS, encoding="utf8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            terms.extend(line.split())
    rows, seen, skipped = [], set(), 0
    for word in terms:
        if word in seen:
            continue
        seen.add(word)
        if word in known:
            skipped += 1
            rows.append(f"{word}\t{known[word]}\t{WEIGHT}")
            continue
        if not all("一" <= c <= "鿿" for c in word):
            print("not Chinese, left out:", word)
            continue
        pinyin = READINGS.get(word) or spell(word)
        if word == "开模" or word == "开模费":
            pinyin = pinyin.replace("kai mo", "kai mu")
        bad = [s for s in pinyin.split() if s not in syllables]
        if bad or len(pinyin.split()) != len(word):
            print("left out:", word, pinyin)
            continue
        rows.append(f"{word}\t{pinyin}\t{WEIGHT}")
        if word in ALSO:
            rows.append(f"{word}\t{ALSO[word]}\t{WEIGHT}")
    with open(OUT, "w", encoding="utf8") as f:
        f.write("\n".join(rows) + "\n")
    print(len(seen), "terms,", skipped, "already in dict.tsv,", len(rows), "rows")


if __name__ == "__main__":
    main()
