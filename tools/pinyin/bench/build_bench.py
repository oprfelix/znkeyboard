#!/usr/bin/env python3
"""Build tools/pinyin/bench/bench.tsv, the fixed pinyin benchmark, from Tatoeba sentences.

Usage:
  curl -sSLO https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences.tsv.bz2
  bunzip2 cmn_sentences.tsv.bz2
  pip install pypinyin   # only used to cross-check the readings
  tools/pinyin/bench/build_bench.py cmn_sentences.tsv app/src/main/assets/pinyin/dict.tsv tools/pinyin/bench/bench.tsv

Sentences are split into clauses at punctuation, the way people type them. A clause is kept
when every character is in the dictionary (which drops traditional characters, digits and
Latin) and its reading is unambiguous: the reading from the dictionary's words must agree with
pypinyin's, so a wrong label never counts against the engine.

Every clause is typed four ways, the sets in bench.tsv:
  full     whole pinyin, no separators          womenyiqiqukandianying
  initials 简拼, the first letter of each syllable  wmyqqkdy
  mixed    each syllable whole or initial         womenyqqukdy
  typo     whole pinyin with touch slips from a two-thumb typist model (at least one slip)
Clauses are bucketed by length: short (2-4 characters), medium (5-9), long (10-20).

Tatoeba sentences are CC BY 2.0 FR; the last column of bench.tsv is the sentence id
(https://tatoeba.org/sentences/show/<id>). Keep these sentences out of any language model
training data, or the benchmark measures memory instead of decoding.
"""
import math
import random
import re
import sys

SEED = 7
BUCKETS = [("short", 2, 4, 500), ("medium", 5, 9, 1000), ("long", 10, 20, 500)]
MAX_LETTERS = 60  # SentenceDecoder.MAX_INPUT
MAX_WORD_CHARS = 6
CLAUSE_SPLIT = re.compile(r"[，。！？；：、,.!?;:…—“”\"'‘’（）()《》〈〉「」『』\s~～·]+")

# ---- touch slips: geometry of tools/typing/touch_simulation.py ---------------------------------
WIDTH_DP = 411.0
PAD = 6.0
GAP = 6.0
ROW_PITCH = 54.0
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
PITCH_X = (WIDTH_DP - 2 * PAD + GAP) / 10.0
KEY_W = PITCH_X - GAP
KEYS = {}
for r, row in enumerate(ROWS):
    left = PAD + (0, 0.5, 1.5)[r] * PITCH_X
    for i, ch in enumerate(row):
        KEYS[ch] = (left + i * PITCH_X + KEY_W / 2, ROW_PITCH * (r + 0.5))
# Typist: taps scatter around the key centre and land a little low; a few letters get dropped or
# swapped when typing fast. Roughly 3-4% of letters come out wrong.
TAP_SD_X = 7.5
TAP_SD_Y = 8.5
TAP_BIAS_Y = 4.0
DROP_RATE = 0.01
SWAP_RATE = 0.005


def key_at(x, y):
    """The key under (x, y) on rectangular cells, or None off the letter area."""
    r = int(y // ROW_PITCH)
    if r < 0 or r > 2:
        return None
    row = ROWS[r]
    for i, ch in enumerate(row):
        cx = KEYS[ch][0]
        lo = -1e9 if i == 0 else (KEYS[row[i - 1]][0] + cx) / 2
        hi = 1e9 if i == len(row) - 1 else (KEYS[row[i + 1]][0] + cx) / 2
        if lo <= x < hi:
            if r == 2 and (x < KEYS["z"][0] - PITCH_X / 2 or x > KEYS["m"][0] + PITCH_X / 2):
                return None  # shift / delete
            return ch
    return None


def with_slips(text, rng):
    out = []
    for ch in text:
        if rng.random() < DROP_RATE:
            continue
        cx, cy = KEYS[ch]
        hit = key_at(cx + rng.gauss(0, TAP_SD_X), cy + TAP_BIAS_Y + rng.gauss(0, TAP_SD_Y))
        if hit is None:
            continue  # landed on shift/delete or off the keys: the letter is lost
        out.append(hit)
    for i in range(len(out) - 1):
        if rng.random() < SWAP_RATE:
            out[i], out[i + 1] = out[i + 1], out[i]
    return "".join(out)


# ---- readings ----------------------------------------------------------------------------------
def load_dictionary(path):
    """text -> (syllables, weight) of its commonest reading."""
    best = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            text, pinyin, weight = line.rstrip("\n").split("\t")
            weight = int(weight)
            if text not in best or weight > best[text][1]:
                best[text] = (pinyin.split(), weight)
    return best


def reading(clause, words):
    """Splits [clause] into dictionary words by weight; returns the syllables, or None."""
    n = len(clause)
    best = [None] * (n + 1)
    best[0] = (0.0, [])
    for i in range(1, n + 1):
        for j in range(max(0, i - MAX_WORD_CHARS), i):
            if best[j] is None:
                continue
            entry = words.get(clause[j:i])
            if entry is None:
                continue
            score = best[j][0] + math.log(entry[1] + 1)
            if best[i] is None or score > best[i][0]:
                best[i] = (score, best[j][1] + entry[0])
    return best[n][1] if best[n] else None


def clauses(sentences_path, words):
    seen = set()
    with open(sentences_path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            for clause in CLAUSE_SPLIT.split(parts[2]):
                if clause in seen or not 2 <= len(clause) <= 20:
                    continue
                if any(ch not in words for ch in clause):
                    continue
                seen.add(clause)
                yield parts[0], clause


def main(sentences_path, dict_path, out_path):
    from pypinyin import lazy_pinyin

    words = load_dictionary(dict_path)
    pools = {name: [] for name, _, _, _ in BUCKETS}
    total = disagreed = 0
    for source, clause in clauses(sentences_path, words):
        syllables = reading(clause, words)
        if syllables is None or len(syllables) != len(clause):
            continue
        total += 1
        if syllables != lazy_pinyin(clause):
            disagreed += 1
            continue
        if len("".join(syllables)) > MAX_LETTERS:
            continue
        for name, low, high, _ in BUCKETS:
            if low <= len(clause) <= high:
                pools[name].append((source, clause, syllables))

    rng = random.Random(SEED)
    rows = []
    for name, _, _, want in BUCKETS:
        pool = sorted(pools[name], key=lambda c: int(c[0]))
        rng.shuffle(pool)
        for source, clause, syllables in pool[:want]:
            full = "".join(syllables)
            initials = "".join(s[0] for s in syllables)
            whole = [rng.random() < 0.5 for _ in syllables]
            keep, cut = rng.sample(range(len(whole)), 2)
            whole[keep], whole[cut] = True, False
            mixed = "".join(s if w else s[0] for s, w in zip(syllables, whole))
            typo = full
            while typo == full or not typo:
                typo = with_slips(full, rng)
            spelling = " ".join(syllables)
            for kind, typed in (("full", full), ("initials", initials), ("mixed", mixed), ("typo", typo)):
                rows.append((kind, name, typed, clause, spelling, source))

    with open(out_path, "w", encoding="utf-8") as out:
        out.write("# Pinyin benchmark built by tools/pinyin/bench/build_bench.py; do not edit by hand.\n")
        out.write("# Sentences from Tatoeba (https://tatoeba.org), CC BY 2.0 FR; source = sentence id.\n")
        out.write("set\tbucket\ttyped\texpected\tpinyin\tsource\n")
        for row in rows:
            out.write("\t".join(row) + "\n")
    print(f"{total} clauses read, {disagreed} dropped for ambiguous readings; "
          + ", ".join(f"{name} {min(len(pools[name]), want)}/{len(pools[name])}" for name, _, _, want in BUCKETS)
          + f" -> {len(rows)} cases in {out_path}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
