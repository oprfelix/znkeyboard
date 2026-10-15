# Typing accuracy in ZnKeyboard

Users said ZnKeyboard felt worse than Gboard and much worse than the iOS keyboard: they mistype more
and "can't feel" the typing. This document explains why, what the research says, and what changed.

## Why it felt worse

Reading the original touch pipeline in `ZnKeyboardView` against the touch literature turned up
five separate problems. The first one alone explains a large share of the mistakes.

1. **The key was chosen when the finger lifted, not where it landed.** `ACTION_MOVE` updated
   the current key whenever the contact point crossed a cell boundary, and the key under the finger at
   `ACTION_UP` was typed. During a tap, the finger rolls and its contact centroid drifts by a
   fraction of a millimetre to a few millimetres (Holz & Baudisch 2010, 2011). On a key about 6.5 mm wide,
   a tap that lands within about 2 mm of an edge often turns into the neighbouring key. No mainstream
   keyboard works this way.
2. **Every key was a plain rectangle centred on its drawing.** People do not hit where they aim. Touches
   are offset from target centres systematically, usually below the target, by an amount that depends
   on the person, the hand posture, and the position on screen (Henze et al. 2011; Azenkot & Zhai 2012).
   An offset that is identical on every tap is a correctable bias, not random noise. ZnKeyboard
   corrected none of it.
3. **There was no use of context.** iOS resizes key targets invisibly based on the likely next letter.
   Gboard combines touch position with a language model when decoding (Goodman et al. 2002; Gunawardana
   et al. 2010). ZnKeyboard had nothing to settle a tap that fell between two keys.
4. **There was no feedback.** There were no haptics, and the finger covers the key it presses
   (the occlusion problem; Vogel & Baudisch 2007), with no popup to make up for it. That is the
   "can't feel it" complaint, and it also makes users slow down and double-check.
5. **There were UX traps.** Shift cycled Off → One-shot → Caps-lock, so tapping Shift again to cancel
   it turned on caps lock. There was no auto-capitalisation, so every sentence needed an extra
   Shift tap, which is one more chance to mistype.

## What we built

### 1. Touch-down resolution with roll hysteresis
The key is decided at `ACTION_DOWN`. It changes only if the finger travels more than
55% of a key pitch, and from then on sliding to another key is treated as a deliberate correction.

### 2. A personal touch model per key, with hierarchical Bayesian shrinkage
For each letter, ZnKeyboard keeps sufficient statistics (n, Σdx, Σdy, Σdx², Σdy²), with exponential
forgetting, of where this user's touches land relative to the key's visual centre. The unit is dp, a
physical size, so the statistics stay valid when the keyboard height changes. The estimates are
shrunk in two stages, *key → region → neutral prior*. A region is one QWERTY row on the left or right
thumb's half. As a result, rare keys such as q, z and x inherit the bias the user shows on neighbouring
keys from the first tap, instead of waiting for hundreds of samples (this is the idea behind Weir et
al. 2012 and Findlater & Wobbrock 2012, made cheap enough to run on every touch). Learned means are
clamped to 45% of a pitch, so learning can never move a key onto its neighbour. Portrait and landscape
get separate models.

### 3. Repair-labelled learning (the part a person might not think of)
The best training labels are the user's own corrections. If someone types *thw*, deletes the *w* and
types *e* within a few seconds, and *e* is next to *w*, then that deleted touch was an attempt at *e*.
`TypingLearner` records it as a sample for *e*. Taps that survive 10 more keystrokes confirm
their own key. Replacements that are not neighbours (deleting *cat* to write *dog*) are treated as
edits, not labels. No calibration screen is needed: the keyboard learns from mistakes the user was
going to fix anyway.

### 4. An anchored, tempered letter prior (invisible key resizing)
Each tap is decoded as

    argmax over nearby letters k:  log N(touch; centre_k + μ_k, Σ_k)  +  0.7 · log P(k | previous two characters)

with two guarantees:
- **Anchor.** A touch inside the central 60% × 56% of a key's drawing always types that key. The
  model can only settle taps that are genuinely ambiguous, so the keyboard never fights a deliberate tap.
- **Personal language.** The letter-trigram prior is Dirichlet-smoothed toward the user's own
  typing. The bundled English statistics (aggregate trigram probabilities only, 19 KB) give way to the
  user's patterns, such as Romanized Hindi/Urdu, names, or shell commands, as they type.

### 5. Feel and confidence
- `HapticFeedbackConstants.KEYBOARD_TAP` fires at touch-down, the moment the key is
  decided. Space-bar cursor drag and swipe-delete give a light tick per step.
- A popup above the finger shows the decoded character, so the user sees exactly what the decoder chose.
- Shift: a tap toggles, a double tap locks caps (Gboard/iOS behaviour).
- Auto-capitalisation through `InputConnection.getCursorCapsMode`, so it follows each
  app's own `TYPE_TEXT_FLAG_CAP_*` request. Double-space inserts a period.
- Bottom letter row realigned to the 10-unit grid, so z through m are the same width as other letters.

## Evidence

`tools/typing/touch_simulation.py` models two-thumb typists using literature-scale
parameters: per-axis tap scatter of 1.0–1.5 mm, a downward bias of about 1 mm, per-thumb sideways bias,
over-reach on outer columns, and about 0.55 mm of down-to-up drift. It then compares decoders on text
sampled by word frequency. These are assumed values, not measurements of ZnKeyboard users. The goal is
to rank the fixes, not to predict exact field error rates.

| Decoder (cumulative) | Letter error, after learning |
| --- | --- |
| A. Old: key under finger at release, rectangles | 9.1% |
| B. Key at touch-down | 5.7% (−37%) |
| C. + Gaussian cells that respect row stagger | 5.5% (−39%) |
| D. + personal per-key offsets | 2.1% (−76%) |
| E. + anchored letter prior (shipped) | 1.6% (−83%) |

`tools/typing/verify_engine.sh` runs the **actual Kotlin engine**, including delayed
confirmation and backspace-repair labelling, against the same simulated typists. The old decoder makes
8.3% errors; the shipped engine makes **1.3%**. The LM weight plateaus between 0.6 and 1.0. ZnKeyboard
ships 0.7 because real text contains names, code and code-switching that an English prior gets wrong.

## Privacy

- Learning is on-device only. The model is stored in `noBackupFilesDir/typing/`, which is
  excluded from cloud backup and device transfer; it leaves the device only inside ZnKeyboard's
  password-encrypted export, so a restore carries your learned typing to a new phone.
- Only aggregates are stored: per-key touch statistics and letter-trigram counts. No text is kept.
- Nothing is learned in password fields or in fields that set
  `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` (for example, incognito tabs).
- Settings → Typing shows what was learned (the touch map) and can reset it.

## References

- Goodman, Venolia, Steury, Parker. *Language modeling for soft keyboards.* IUI 2002.
- Vogel, Baudisch. *Shift: a technique for operating pen-based interfaces using touch.* CHI 2007.
- Holz, Baudisch. *The generalized perceived input point model and how to double touch accuracy by compensating for it.* CHI 2010.
- Gunawardana, Paek, Meek. *Usability guided key-target resizing for soft keyboards.* IUI 2010.
- Holz, Baudisch. *Understanding touch.* CHI 2011.
- Henze, Rukzio, Boll. *100,000,000 taps: analysis and improvement of touch performance in the large.* MobileHCI 2011.
- Azenkot, Zhai. *Touch behavior with different postures on soft smartphone keyboards.* MobileHCI 2012.
- Findlater, Wobbrock. *Personalized input: improving ten-finger touchscreen typing through automatic adaptation.* CHI 2012.
- Weir, Rogers, Murray-Smith, Löchtefeld. *A user-specific machine learning approach for improving touch accuracy on mobile devices.* UIST 2012.
- Android: [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING), [HapticFeedbackConstants](https://developer.android.com/reference/android/view/HapticFeedbackConstants), [InputConnection.getCursorCapsMode](https://developer.android.com/reference/android/view/inputmethod/InputConnection#getCursorCapsMode(int)).
- Letter statistics derived from Peter Norvig's [count_1w.txt](https://norvig.com/ngrams/) word frequencies.

