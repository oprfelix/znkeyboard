# Pinyin language model

`app/src/main/assets/pinyin/lm.bin` is a word bigram model (16 MB) that the sentence decoder
uses to rank whole readings by context: 下午两点开会 over 下午亮点开会, 收到了 over 受到了.

```bash
# Rebuild it (about 3 minutes and 3 GB of memory on an M-series Mac); see build_lm.py for downloads
tools/pinyin/lm/build_lm.py app/src/main/assets/pinyin/dict.tsv tools/pinyin/bench/bench.tsv \
    app/src/main/assets/pinyin/lm.bin fw2.parquet --docs 400000 --min-count 8
```

**Rebuild it whenever `dict.tsv` changes.** Word ids are the dictionary's distinct texts in
order, so the keyboard ignores a model built for another dictionary (sentences then rank as in
1.9.2, by word frequency alone).

## How it works

- **Training text:** the first 400,000 documents of FineWeb-2 Mandarin, shard `000_00000`
  (forums and blogs, 313 M words after cutting at characters the dictionary lacks). Traditional
  script is converted with OpenCC. Benchmark clauses are left out.
- **Words:** each run of text is split into dictionary words by their weights, the way the
  decoder would read it, so the model's words are exactly the dictionary's.
- **Smoothing:** interpolated Kneser-Ney, backing off to a unigram that mixes continuation counts
  with the dictionary's weights. Pairs seen fewer than 8 times are dropped (9.4 M of 42 M kept).
- **Decoding:** a beam of 6 readings per position, one per last word, trying the best 8 words for
  each run of syllables. Each word costs `1 − 0.6 · ln P(word | previous)` on top of its slip and
  简拼 costs. A word that can't make the beam is skipped before the model is asked.
- **On the phone** the model is memory-mapped from the APK (stored uncompressed), so it doesn't
  sit on the heap.

## Choosing the data and size

Scored on a separate dev set of 1,000 Tatoeba clauses (not the benchmark), share read right by
one press of space:

| training text | size | full | 简拼 | mixed | typo | all |
|---|---:|---:|---:|---:|---:|---:|
| none (1.9.2) | — | 53% | 3% | 13% | 23% | 23% |
| Leipzig news + Wikipedia, 2 M sentences | 15 MB | 54% | 4% | 13% | 26% | 24% |
| FineWeb-2 test split (33 k docs) | 9.9 MB | 64% | 6% | 23% | 31% | 31% |
| FineWeb-2, 400 k docs, pairs seen 3+ times | 39 MB | 69% | 9% | 27% | 35% | 35% |
| **FineWeb-2, 400 k docs, 8+ times** | **16 MB** | **67%** | **8%** | **27%** | **33%** | **34%** |
| FineWeb-2, 400 k docs, 15+ times | 9.7 MB | 65% | 7% | 25% | 32% | 32% |
| FineWeb-2, whole shard (1.97 M docs), 30+ times | 18 MB | 69% | 7% | 26% | 34% | 34% |

What mattered: the kind of text far more than the amount. News and encyclopedia text taught it
我市, 香山区 and that sentences never end in 啊 or 吗; forum text fixed that. Mixing the dictionary's
own frequency back into each word's cost made every set worse, so the decoder doesn't.

## Licenses

FineWeb-2 is ODC-By 1.0 (built from Common Crawl, whose terms of use also apply); only
aggregate word-pair statistics are shipped, not text.
