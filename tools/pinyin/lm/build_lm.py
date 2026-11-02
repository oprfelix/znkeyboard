#!/usr/bin/env python3
"""Build app/src/main/assets/pinyin/lm.bin, the word bigram model the sentence decoder ranks by.

Usage (training text: FineWeb-2 Mandarin, https://huggingface.co/datasets/HuggingFaceFW/fineweb-2,
ODC-By 1.0; mostly forums and blogs, which is close to how people chat):
  curl -sSL -o fw2.parquet https://huggingface.co/datasets/HuggingFaceFW/fineweb-2/resolve/main/data/cmn_Hani/train/000_00000.parquet
  pip install numpy opencc pyarrow
  tools/pinyin/lm/build_lm.py app/src/main/assets/pinyin/dict.tsv tools/pinyin/bench/bench.tsv \
      app/src/main/assets/pinyin/lm.bin fw2.parquet

Corpora are .parquet files with a `text` column (documents, split here at line breaks and
sentence ends) or text files with one sentence per line (an "id<TAB>" prefix is dropped, as in
Leipzig corpora). --docs N reads only the first N documents of each parquet file;
--min-count N drops word pairs seen fewer than N times (default MIN_COUNT), trading size for accuracy.

The vocabulary is the dictionary's: text is cut into runs of characters the dictionary has,
each run is split into dictionary words by their weights (the way the decoder would read it),
and word pairs are counted with <s> and </s> around each run. Traditional script is converted
to simplified first (OpenCC t2s); sentences that are still mostly characters the dictionary
lacks (other languages, rare characters) are skipped. Runs that are
benchmark clauses are left out, so the benchmark measures decoding rather than memory.

Smoothing is interpolated Kneser-Ney: a pair's discounted count, plus the rest spread over a
unigram that mixes how many words a word follows (continuation) with the dictionary's weight,
so words the corpus never saw keep their dictionary rank. Pairs seen fewer than MIN_COUNT
times are dropped; a backoff weight per context keeps each context's probabilities summing to 1.

File layout, big-endian (read by PinyinLanguageModel.kt):
  "ZNLM", int version, int words (V: distinct dictionary texts in dict.tsv order, the word ids),
  int dictionary rows, int pairs (B)
  short[V + 2] unigram ln P × 1000   (id V is <s>, V + 1 is </s>)
  short[V + 2] backoff ln α × 1000
  int[V + 3]   first pair of each context
  int[B]       pairs, per context sorted by word: word id << 14 | round(-ln P × 400)
"""
import math
import struct
import sys
import re
from multiprocessing import Pool

import numpy as np
import opencc

MIN_COUNT = 2
MAX_WORD_CHARS = 6
DICT_SHARE = 0.15  # weight of the dictionary in the unigram
MIN_KNOWN = 0.9    # share of a sentence's Han characters the dictionary must have
BENCH_MIN_CHARS = 5
CHUNK_LINES = 20_000
MERGE_EVERY = 40  # chunks between merges of the pair counts, which bounds memory
SENTENCE_END = re.compile(r"[\n\r。！？!?]+")
ID_BITS = 18
PROB_BITS = 14
PROB_SCALE = 400
SHORT_SCALE = 1000

_words = None
_logp = None
_chars = None
_skip = None
_t2s = None


def load_dictionary(path):
    ids, weights, rows = {}, [], 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            text, _, weight = line.rstrip("\n").split("\t")
            rows += 1
            if text not in ids:
                ids[text] = len(ids)
                weights.append(0)
            weights[ids[text]] += int(weight)
    return ids, np.array(weights, dtype=np.float64), rows


def init_worker(ids, weights, skip):
    global _words, _logp, _chars, _skip, _t2s
    _t2s = opencc.OpenCC("t2s.json")
    _words = ids
    total = weights.sum()
    _logp = {text: math.log(max(weights[i], 1) / total) for text, i in ids.items()}
    _chars = {text for text in ids if len(text) == 1}
    _skip = skip


def is_han(ch):
    return "一" <= ch <= "鿿" or "㐀" <= ch <= "䶿"


def segment(run):
    """Splits [run] into the dictionary words with the highest total log probability."""
    n = len(run)
    best = [(-math.inf, 0)] * (n + 1)
    best[0] = (0.0, 0)
    for i in range(1, n + 1):
        for j in range(max(0, i - MAX_WORD_CHARS), i):
            if best[j][0] == -math.inf:
                continue
            logp = _logp.get(run[j:i])
            if logp is not None and best[j][0] + logp > best[i][0]:
                best[i] = (best[j][0] + logp, j)
    words, i = [], n
    while i > 0:
        j = best[i][1]
        words.append(_words[run[j:i]])
        i = j
    words.reverse()
    return words


def count_chunk(lines):
    v = len(_words)
    start, end = v, v + 1
    pairs, unigrams = [], np.zeros(v + 2, dtype=np.int64)
    for line in lines:
        text = _t2s.convert(line)
        han = [ch for ch in text if is_han(ch)]
        if not han or sum(ch in _chars for ch in han) < MIN_KNOWN * len(han):
            continue
        run = []
        for ch in text + "\n":
            if ch in _chars:
                run.append(ch)
                continue
            if run:
                clause = "".join(run)
                run = []
                if clause in _skip:
                    continue
                ids = [start] + segment(clause) + [end]
                arr = np.array(ids, dtype=np.int64)
                pairs.append(arr[:-1] * (v + 2) + arr[1:])
                np.add.at(unigrams, arr[1:], 1)
    keys = np.concatenate(pairs) if pairs else np.zeros(0, dtype=np.int64)
    keys, counts = np.unique(keys, return_counts=True)
    return keys, counts, unigrams


def lines(path, docs):
    if path.endswith(".parquet"):
        import pyarrow.parquet as pq

        read = 0
        for batch in pq.ParquetFile(path).iter_batches(batch_size=2_000, columns=["text"]):
            for text in batch.column(0).to_pylist():
                yield from SENTENCE_END.split(text)
                read += 1
                if docs and read >= docs:
                    return
    else:
        with open(path, encoding="utf-8") as f:
            for line in f:
                yield line.rstrip("\n").split("\t", 1)[-1]


def chunks(paths, docs):
    batch = []
    for path in paths:
        for line in lines(path, docs):
            batch.append(line)
            if len(batch) >= CHUNK_LINES:
                yield batch
                batch = []
    if batch:
        yield batch


def merge(keys, counts):
    keys, counts = np.concatenate(keys), np.concatenate(counts)
    order = np.argsort(keys, kind="stable")
    keys, counts = keys[order], counts[order]
    keys, first = np.unique(keys, return_index=True)
    return keys, np.add.reduceat(counts, first)


def main(dict_path, bench_path, out_path, *corpora):
    def option(name, default):
        nonlocal corpora
        if name not in corpora:
            return default
        at = corpora.index(name)
        value = int(corpora[at + 1])
        corpora = corpora[:at] + corpora[at + 2:]
        return value

    docs = option("--docs", 0)
    min_count = option("--min-count", MIN_COUNT)
    ids, weights, dict_rows = load_dictionary(dict_path)
    v = len(ids)
    m = v + 2
    assert m < 1 << ID_BITS
    skip = set()
    with open(bench_path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) > 3 and not line.startswith("#") and len(parts[3]) >= BENCH_MIN_CHARS:
                skip.add(parts[3])

    all_keys, all_counts = [], []
    tokens = np.zeros(m, dtype=np.int64)
    with Pool(initializer=init_worker, initargs=(ids, weights, skip)) as pool:
        for n, (keys, counts, unigrams) in enumerate(pool.imap_unordered(count_chunk, chunks(corpora, docs)), 1):
            all_keys.append(keys)
            all_counts.append(counts)
            tokens += unigrams
            if n % MERGE_EVERY == 0:
                keys, counts = merge(all_keys, all_counts)
                all_keys, all_counts = [keys], [counts]
                print(f"  {n * CHUNK_LINES:,} lines, {len(keys):,} distinct pairs", flush=True)
    keys, counts = merge(all_keys, all_counts)
    left, right = keys // m, keys % m
    print(f"{int(tokens.sum()):,} words, {len(keys):,} distinct pairs")

    # Kneser-Ney discount from the counts of counts.
    n1, n2 = np.sum(counts == 1), np.sum(counts == 2)
    discount = n1 / (n1 + 2 * n2)
    context_total = np.bincount(left, weights=counts, minlength=m)
    context_types = np.bincount(left, minlength=m)
    continuation = np.bincount(right, minlength=m).astype(np.float64)
    continuation /= continuation.sum()
    prior = np.zeros(m)
    prior[:v] = weights / weights.sum()
    unigram = (1 - DICT_SHARE) * continuation + DICT_SHARE * prior
    unigram[v] = 0  # <s> is never predicted
    floor = unigram[unigram > 0].min()

    lam = np.where(context_total > 0, discount * context_types / np.maximum(context_total, 1), 1.0)
    prob = (np.maximum(counts - discount, 0) / context_total[left]) + lam[left] * unigram[right]

    keep = counts >= min_count
    left, right, prob = left[keep], right[keep], prob[keep]
    kept_prob = np.bincount(left, weights=prob, minlength=m)
    kept_uni = np.bincount(left, weights=unigram[right], minlength=m)
    backoff = (1 - kept_prob) / np.maximum(1 - kept_uni, 1e-12)
    backoff = np.clip(backoff, 1e-12, None)

    def shorts(values):
        return np.clip(np.round(values * SHORT_SCALE), -32768, 32767).astype(">i2")

    uni_log = np.log(np.maximum(unigram, floor))
    offsets = np.zeros(m + 1, dtype=">i4")
    offsets[1:] = np.cumsum(np.bincount(left, minlength=m))
    q = np.clip(np.round(-np.log(prob) * PROB_SCALE), 0, (1 << PROB_BITS) - 1).astype(np.int64)
    packed = ((right << PROB_BITS) | q).astype(">i4")
    with open(out_path, "wb") as out:
        out.write(b"ZNLM" + struct.pack(">iiii", 1, v, dict_rows, len(packed)))
        out.write(shorts(uni_log).tobytes())
        out.write(shorts(np.log(backoff)).tobytes())
        out.write(offsets.tobytes())
        out.write(packed.tobytes())
    print(f"discount {discount:.3f}; kept {len(packed):,} pairs seen {min_count}+ times -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
