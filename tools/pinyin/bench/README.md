# Pinyin benchmark

A fixed set of 8,000 typed clauses for measuring the pinyin engine, so changes to ranking,
correction or language modelling can be compared by numbers instead of by feel.

```bash
# Score the engine (about 2 minutes; JDK 17+ in JAVA_HOME, Gradle build run once first)
tools/pinyin/bench/run_bench.sh
# Fuzzy sounds off, and every case written out for diffing against another run
tools/pinyin/bench/run_bench.sh --strict --cases /tmp/cases.tsv
```

## What is in `bench.tsv`

2,000 clauses from [Tatoeba](https://tatoeba.org) Mandarin sentences (CC BY 2.0 FR; the `source`
column is the sentence id), split at punctuation the way people type them, each typed four ways:

| set | typed as | example |
|---|---|---|
| `full` | whole pinyin, no separators | `womenyiqiqukandianying` |
| `initials` | 简拼, first letter of each syllable | `wmyqqkdy` |
| `mixed` | each syllable whole or initial, at random | `womenyqqukdy` |
| `typo` | whole pinyin with touch slips | `wozaizhuomianshangjianleyigekuaijiefangdhi` |

Buckets by length: `short` 2–4 characters (500), `medium` 5–9 (1,000), `long` 10–20 (500).

- Only clauses whose every character is in the dictionary are used, which drops traditional
  characters, digits and Latin.
- A clause is kept only when its reading from the dictionary's words agrees with pypinyin's, so
  a wrong label never counts against the engine (about 16% of clauses were dropped for this).
- Slips use the keyboard geometry of `tools/typing/touch_simulation.py`: taps scatter around the
  key centre (σ 7.5 × 8.5 dp, landing 4 dp low), 1% of letters are dropped and 0.5% of neighbours
  swapped. That is about 3.4% of letters wrong. Every `typo` case has at least one slip, so the
  set measures correction rather than average typing.
- Tatoeba favours short everyday sentences and translates Tom and Mary as 汤姆 and 玛丽 a lot.
  Read the numbers as everyday chat, not as news or technical text.
- Keep these sentences out of any language-model training data, or the score measures memory.

Rebuild it (only when the method changes, since scores are only comparable on the same file):
see the usage in `build_bench.py`.

## Metrics

Each case is converted with an empty user history, as on a fresh install.

- **top1**: the first candidate is the expected text and reads all of the input (one press of space).
- **top1~**: top1, counting 他 / 她 / 它 as the same; nothing inside the clause can tell them apart.
- **top3**: the expected text is among the first three candidates and reads all of the input.
- **CER**: character edit distance from the expected text to the first candidate that reads all
  of the input, over the expected characters (no such candidate counts as all wrong).
- **none**: no candidate reads all of the input.
- **per keystroke**: time to convert each prefix of every 10th case, after warm-up. These are
  JVM numbers on a Mac; a phone is slower, so treat them as relative.

## Baseline (1.9.2, default fuzzy sounds)

| set | top1 | top1~ | top3 | CER |
|---|---:|---:|---:|---:|
| full | 53.4% | 57.7% | 54.2% | 12.8% |
| initials | 2.9% | 3.0% | 4.5% | 77.6% |
| mixed | 12.8% | 13.1% | 14.1% | 44.5% |
| typo | 22.7% | 24.4% | 23.0% | 26.1% |
| **all** | **22.9%** | **24.5%** | **23.9%** | **40.3%** |

By length, `full` goes from 60.8% (short) to 54.2% (medium) and 44.2% (long); `initials` from
6.8% to 2.3% and 0.0%. Fuzzy sounds off: `full` 55.1%, all 23.6%. Per keystroke on an M-series
Mac: mean 3.7 ms, median 0.4 ms, p95 20 ms, max 127 ms (long inputs).

What the failures show: words are ranked by frequency alone, so context-dependent homophones go
wrong (上床把, 我再次薯条, 告诉锅里, 好像级); 简拼 of a whole clause almost never reads right; and
one slip takes a clause from about 53% to 23%.
