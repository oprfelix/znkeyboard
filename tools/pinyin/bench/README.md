# Pinyin benchmark

A fixed set of 8,000 typed clauses for measuring the pinyin engine, so changes to ranking,
correction or language modelling can be compared by numbers instead of by feel.

```bash
# Score the engine (about 2 minutes; JDK 17+ in JAVA_HOME, Gradle build run once first)
tools/pinyin/bench/run_bench.sh
# As the keyboard ships: the language model (tools/pinyin/lm/README.md) and each letter's tap
tools/pinyin/bench/run_bench.sh --lm app/src/main/assets/pinyin/lm.bin --taps
# The same clauses typed on the 9-key keyboard (full, initials and mixed; typo is QWERTY slips)
tools/pinyin/bench/run_bench.sh --nine --lm app/src/main/assets/pinyin/lm.bin
# On the connected phone; this computer only compiles it (see the script for its options)
tools/pinyin/bench/run_bench_phone.sh /tmp/cases.tsv --taps --latency-every 0
# One set typed in order by a user who fixes every mistake, learning as they go
tools/pinyin/bench/run_bench.sh --lm app/src/main/assets/pinyin/lm.bin --taps --learn --set full
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
- The last column holds a tap (x, y in dp) for every typed letter. `typo` taps are the ones that
  made the slips; the other sets get taps that scatter the same way but land on the intended key,
  so `--taps` also shows whether clean input gets "corrected". `--taps` scores them with the
  keyboard's own `TouchDecoder` and an untrained touch model, as on a fresh install.
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

## With the language model (`--lm app/src/main/assets/pinyin/lm.bin`)

| set | top1 | top1~ | top3 | CER |
|---|---:|---:|---:|---:|
| full | 67.2% (+13.8) | 72.8% | 67.5% | 7.8% |
| initials | 6.0% (+3.1) | 6.5% | 7.4% | 68.3% |
| mixed | 25.0% (+12.2) | 26.8% | 25.8% | 32.0% |
| typo | 30.3% (+7.6) | 32.9% | 30.6% | 21.8% |
| **all** | **32.1% (+9.2)** | **34.7%** | **32.8%** | **32.5%** |

By length, `full` is 74.2% short, 67.3% medium, 59.8% long. Per keystroke on the same Mac: mean
3.0 ms, median 0.4 ms, p95 14 ms, max 129 ms, no slower than without the model. The model was
tuned on a separate dev set, not on this file.

On a Pixel 9 Pro XL, run through `app_process` (no ahead-of-time compilation, so far slower than
the installed keyboard and only useful for comparing): mean 34.7 ms, p95 171 ms with the model,
against 38.0 ms, p95 185 ms for 1.9.2 without it.

## With trade words (`--lm … --taps --trade`, as the keyboard ships)

`assets/pinyin/trade.tsv` holds foreign-trade terms, and the decoder gives them a bonus of 2.5 in
ln P. Against the table in the next section: full 67.2% (±0), initials 6.0% (−0.2), mixed 24.9%
(−0.1), typo 39.3% (−0.1), all **34.3%** (−0.1). This set is everyday chat, so it can only show the
cost. The gain is on `tools/pinyin/trade/verify_trade.sh`, 60 trade messages: 32 right before the
terms, 45 with them, 47 with the bonus.

## With taps (`--lm … --taps`, as the keyboard ships)

| set | top1 | top1~ | top3 | CER |
|---|---:|---:|---:|---:|
| full | 67.2% (±0) | 72.8% | 67.5% | 7.8% |
| initials | 6.2% (+0.2) | 6.6% | 7.4% | 67.6% |
| mixed | 25.0% (±0) | 26.8% | 25.8% | 32.0% |
| typo | 39.4% (+9.1) | 42.8% | 39.6% | 18.1% |
| **all** | **34.4% (+2.3)** | **37.2%** | **35.1%** | **31.4%** |

A typed letter replaced by another key costs 2 plus how much worse its tap fits that key
(−ln of the touch likelihood), instead of a flat 7 for any QWERTY neighbour; keys the tap fits
more than 4 worse are not tried, and with a tap two-letter pieces may be corrected too (wl → wo).
Clean input is unchanged, and keystrokes got faster: mean 1.8 ms, p95 7.6 ms, max 78 ms, since
the capped alternatives are fewer than the neighbour table. What `typo` still misses is mostly
dropped letters, which leave no tap to go on, and slips that type another valid syllable.

## 9-key (`--nine`)

Each clause's letters become 9-key digits (`nihao` → 64426) and go through `NineKeyEngine`.
Until 1.9.7 the 9-key sentence ignored the language model, so the model changed nothing here and
5422468 read 家抽 (the commoner characters) where 26-key reads 家丑. It now ranks like 26-key:

| set | top1, 1.9.6 (model or not) | top1, with the model | CER |
|---|---:|---:|---:|
| full | 34.3% | 53.0% (+18.7) | 24.2% → 14.7% |
| initials | 0.1% | 0.2% | 95.8% → 94.6% |
| mixed | 0.3% | 0.3% | 82.7% → 81.0% |

Up to 16 words per run of digits are tried (8 scored 52.3%, 4 scored 51.3%), since one code
spells many words. Per keystroke stays near 0.1 ms. 9-key 简拼 and mixes stay near zero: a digit
does not say whether it starts a syllable or is one, and those cases need more than ranking.

## Other readings (the candidates after the first)

The decoder used to offer one reading of the whole input, so top3 barely passed top1: of the
`full` clauses it got wrong, 98% had no right reading among the candidates, and half of those were
one character off. It now offers up to three. The beam keeps, per reading, the worse ways to the
same last word, and swapping one in gives another reading at a known extra cost. Others follow the
words that read all the input, and must cost less than 6 more than the best.

| set | top3, 26-key before | top3, 26-key | top3, 9-key before | top3, 9-key |
|---|---:|---:|---:|---:|
| full | 67.5% | 82.6% | 53.8% | 69.0% |
| initials | 7.4% | 10.3% | 0.5% | 0.8% |
| mixed | 25.8% | 35.6% | 1.4% | 1.4% |
| typo | 39.6% | 50.2% | | |

top1 and CER don't change. Five readings, a margin of 10 and 6 kept ways reach 86.3% within five
candidates on `full`, but a long reading fills the bar, so more of them would only push the words
off screen. On a Pixel 9 Pro XL through `app_process`, a keystroke takes 11.18 ms on average
against 10.93 ms with one reading (p95 39.9 against 39.5 ms). Many of the gains are 他 / 她 / 它.

## Learning (`--learn --set S`)

The cases of one set are typed in order; after each, the learner is taught the right words as the
user would pick them (by hand when the first candidate was wrong). Only one set at a time, since
the same clause is in all four. The file runs short to long, so compare like halves:

| set | second half, no learning | second half, learning |
|---|---:|---:|
| full | 64.8% | 65.4% |
| mixed | 19.5% | 21.1% |
| typo | 38.1% | 39.1% |

On the dev set the full-pinyin gain was larger (64.6% → 67.8%). Most fixes are names and phrases
typed before (玛丽 rather than 马力); a few recently picked characters now win where they
shouldn't (住在 → 助在).

Until this change, learning added 4 · ln(1 + picks) to a word's score with no limit. With the language
model's small per-word cost, much-picked characters (你, 是, 狗) then cost less than nothing, so
splitting input into them always won: in this simulation the second half of `full` fell to 3%.
Picks and the pairs the user typed are now a personal model mixed into each word's probability
(5% picks, 10% pairs after the previous word), which can never make a word free.

## Words typed by initials (1.9.24)

With `--lm --taps --trade`, a word read from every letter (时候 for `sh`, 解决方案 for `jjfa`) now
competes in sentence search as one word's worth of 简拼, and the likeliest one sits right behind the
top candidate from three letters on. `tools/pinyin/trade/verify_initials.sh`, by word frequency:

| word | first | top 3 | top 8 |
|---:|---:|---:|---:|
| 2 characters | 11.7 → 12.5% | 40.1 → 40.3% | 74.0 → 73.9% |
| 3 characters | 46.0 → 51.3% | 91.7 → 94.3% | 99.9 → 99.6% |
| 4 characters | 78.3 → 86.3% | 95.2 → 99.9% | 100% |

Two characters are near the ceiling of ranking by frequency alone (18.3%, 41.9%, 74.6%). In this
file `full`, `mixed` and `typo` do not move; `initials` goes 6.0 → 6.3% top1 and 10.1 → 9.8% top3,
since whole clauses typed by initials now meet more single words.

## Slips (1.9.25)

With `--lm --taps --trade`: `typo` 39.3 → 46.0% top1, 50.1 → 58.3% top3, CER 18.2 → 15.7%, and its
long clauses 33.4 → 40.4%. A vowel that lost its y or w (`ue`, `iqie`) and two letters with a vowel
that are no syllable (`ho`) are now corrected, and a missing letter costs 3.5 instead of 5. `full`
goes 67.2 → 67.1%, `initials` 6.3 → 6.1%. One slip per word keeps the search small: 0.92 ms per
keystroke on an M-series Mac, p95 3 ms, down from 1.7 and 6.6 ms; allowing a second one scored
46.6% at 4.3 and 25 ms.
