#!/usr/bin/env python3
"""Monte Carlo study of soft-keyboard tap decoding for ZnKeyboard.

This is a model, not a user study. It answers "which decoder change removes how
much error, for a plausible two-thumb typist?" so engineering effort goes to the
right place. Typist parameters are order-of-magnitude values from the touch
literature (see docs/typing-accuracy.md) and are deliberately conservative.

Decoders compared (each builds on the previous one):
  A  current ZnKeyboard: key under the finger at *release*, rectangular cells
  B  key under the finger at *touch-down* (roll/drift hysteresis)
  C  B + Gaussian spatial model (nearest-centre cells that respect row stagger)
  D  C + personal per-key offsets learned online (hierarchical Bayesian shrinkage)
  E  D + letter-trigram prior, tempered and anchored (invisible key resizing)

Usage: python3 touch_simulation.py /path/to/count_1w.txt [en_char_trigram.bin]
"""
import math
import random
import sys
from pathlib import Path

DP_PER_MM = 1 / 0.15875  # 160 dp per inch
WIDTH_DP = 411.0
PAD = 6.0
GAP = 6.0
ROW_PITCH = 54.0
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]

# ---- keyboard geometry -------------------------------------------------------
PITCH_X = (WIDTH_DP - 2 * PAD + GAP) / 10.0
KEY_W = PITCH_X - GAP
KEYS = {}
for r, row in enumerate(ROWS):
    if r == 0:
        left = PAD
    elif r == 1:
        left = PAD + PITCH_X / 2
    else:
        left = PAD + 1.5 * PITCH_X  # shift + 7 letters + delete, 1.5-unit side keys
    for i, ch in enumerate(row):
        cx = left + i * PITCH_X + KEY_W / 2
        cy = ROW_PITCH * (r + 0.5)
        KEYS[ch] = (cx, cy, r)
LETTERS = list(KEYS)


def rect_hit(x, y):
    r = int(y // ROW_PITCH)
    if r < 0 or r > 2:
        return None
    row = ROWS[r]
    best = None
    for i, ch in enumerate(row):
        cx = KEYS[ch][0]
        lo = -1e9 if i == 0 else (KEYS[row[i - 1]][0] + cx) / 2
        hi = 1e9 if i == len(row) - 1 else (KEYS[row[i + 1]][0] + cx) / 2
        if lo <= x < hi:
            best = ch
    if r == 2 and (x < KEYS['z'][0] - PITCH_X / 2 or x > KEYS['m'][0] + PITCH_X / 2):
        return None  # shift / delete
    return best


# ---- language model ----------------------------------------------------------
class Trigram:
    def __init__(self, path):
        self.d = Path(path).read_bytes() if path else None

    @staticmethod
    def i(ch):
        return ord(ch) - 96 if 'a' <= ch <= 'z' else 0

    def logp(self, a, b, c):
        if not self.d:
            return 0.0
        q = self.d[self.i(a) * 729 + self.i(b) * 27 + self.i(c)]
        return -q / 16.0 * math.log(2)


# ---- personal touch model (mirror of TouchModel.kt) ---------------------------
PRIOR_SIGMA = 10.0
N0 = 15.0
M0 = 30.0
DECAY = 0.997
MAX_SIGMA = 20.0
MIN_SIGMA = 5.0


def region_of(ch):
    cx, _, r = KEYS[ch]
    return r * 2 + (0 if cx < WIDTH_DP / 2 else 1)


class TouchModel:
    def __init__(self):
        self.s = {ch: [0.0] * 5 for ch in LETTERS}  # n, sx, sy, sxx, syy

    def learn(self, ch, dx, dy):
        if abs(dx) > PITCH_X or abs(dy) > ROW_PITCH:
            return
        s = self.s[ch]
        for k in range(5):
            s[k] *= DECAY
        s[0] += 1; s[1] += dx; s[2] += dy; s[3] += dx * dx; s[4] += dy * dy

    def params(self, ch):
        reg = region_of(ch)
        R = [0.0] * 5
        for k2, s2 in self.s.items():
            if region_of(k2) == reg:
                for k in range(5):
                    R[k] += s2[k]
        # region level: shrink toward zero offset / prior sigma
        rmx = R[1] / (M0 + R[0]); rmy = R[2] / (M0 + R[0])
        rvx = (M0 * (PRIOR_SIGMA ** 2) + R[3]) / (M0 + R[0]) - rmx * rmx * (R[0] / (M0 + R[0]))
        rvy = (M0 * (PRIOR_SIGMA ** 2) + R[4]) / (M0 + R[0]) - rmy * rmy * (R[0] / (M0 + R[0]))
        s = self.s[ch]
        mx = (N0 * rmx + s[1]) / (N0 + s[0]); my = (N0 * rmy + s[2]) / (N0 + s[0])
        vx = (N0 * (rvx + rmx * rmx) + s[3]) / (N0 + s[0]) - mx * mx
        vy = (N0 * (rvy + rmy * rmy) + s[4]) / (N0 + s[0]) - my * my
        mx = max(-0.45 * PITCH_X, min(0.45 * PITCH_X, mx))
        my = max(-0.45 * ROW_PITCH, min(0.45 * ROW_PITCH, my))
        vx = max(MIN_SIGMA ** 2, min(MAX_SIGMA ** 2, vx))
        vy = max(MIN_SIGMA ** 2, min(MAX_SIGMA ** 2, vy))
        return mx, my, vx, vy


def decode(x, y, model, lm, ctx, lam, use_model=True):
    g = rect_hit(x, y)
    if g is None:
        return None
    gx, gy, _ = KEYS[g]
    if abs(x - gx) <= 0.30 * PITCH_X and abs(y - gy) <= 0.28 * ROW_PITCH:
        return g  # anchor: the visual centre of a key always types that key
    best, bests = g, -1e18
    for ch in LETTERS:
        cx, cy, _ = KEYS[ch]
        if abs(x - cx) > 1.6 * PITCH_X or abs(y - cy) > 1.6 * ROW_PITCH:
            continue
        if use_model:
            mx, my, vx, vy = model.params(ch)
        else:
            mx, my, vx, vy = 0.0, 0.0, PRIOR_SIGMA ** 2, PRIOR_SIGMA ** 2
        dx = x - cx - mx; dy = y - cy - my
        sc = -0.5 * (dx * dx / vx + dy * dy / vy) - 0.5 * math.log(vx * vy)
        if lam:
            sc += lam * max(lm.logp(ctx[-2], ctx[-1], ch), math.log(1e-4))
        if sc > bests:
            best, bests = ch, sc
    return best


# ---- simulated typist ----------------------------------------------------------
class Typist:
    def __init__(self, rng):
        mm = DP_PER_MM
        self.rng = rng
        self.noise = rng.uniform(1.0, 1.5) * mm          # per-axis tap scatter SD
        y_bias = rng.gauss(1.0, 0.5) * mm                 # people land below centre
        side = [rng.gauss(0, 0.9) * mm, rng.gauss(0, 0.9) * mm]  # left/right thumb bias
        self.off = {}
        for ch in LETTERS:
            cx, _, r = KEYS[ch]
            s = side[0 if cx < WIDTH_DP / 2 else 1]
            # thumbs over-reach toward the far edge on the outermost columns
            reach = (cx - WIDTH_DP / 2) / (WIDTH_DP / 2) * rng.gauss(0.4, 0.2) * mm
            self.off[ch] = (s + reach + rng.gauss(0, 0.4) * mm,
                            y_bias + (2 - r) * 0.15 * mm + rng.gauss(0, 0.4) * mm)
        self.drift = (rng.gauss(0, 0.2) * mm, rng.gauss(0.3, 0.2) * mm)
        self.drift_sd = 0.55 * mm

    def tap(self, ch):
        cx, cy, _ = KEYS[ch]
        ox, oy = self.off[ch]
        x = cx + ox + self.rng.gauss(0, self.noise)
        y = cy + oy + self.rng.gauss(0, self.noise)
        ux = x + self.drift[0] + self.rng.gauss(0, self.drift_sd)
        uy = y + self.drift[1] + self.rng.gauss(0, self.drift_sd)
        return (x, y), (ux, uy)


def load_words(path, n=20000):
    words, weights = [], []
    with open(path) as fh:
        for i, line in enumerate(fh):
            if i >= n:
                break
            w, c = line.split("\t")
            if w.isalpha():
                words.append(w.lower()); weights.append(float(c))
    return words, weights


def run(words, weights, lm, users=40, letters=4000, lam=0.6, seed=7):
    names = ["A release+rect", "B down+rect", "C down+gauss", "D +personal", "E +LM prior"]
    errs = {k: [0, 0] for k in names}
    late = {k: [0, 0] for k in names}
    for u in range(users):
        rng = random.Random(seed * 1000 + u)
        t = Typist(rng)
        models = {"D +personal": TouchModel(), "E +LM prior": TouchModel()}
        typed = 0
        ctx = "  "
        while typed < letters:
            w = rng.choices(words, weights)[0]
            for ch in w:
                down, up = t.tap(ch)
                res = {
                    "A release+rect": rect_hit(*up),
                    "B down+rect": rect_hit(*down),
                    "C down+gauss": decode(*down, None, lm, ctx, 0.0, use_model=False),
                    "D +personal": decode(*down, models["D +personal"], lm, ctx, 0.0),
                    "E +LM prior": decode(*down, models["E +LM prior"], lm, ctx, lam),
                }
                for k, v in res.items():
                    bad = v != ch
                    errs[k][0] += bad; errs[k][1] += 1
                    if typed >= letters * 3 // 4:
                        late[k][0] += bad; late[k][1] += 1
                cx, cy, _ = KEYS[ch]
                for k, m in models.items():
                    # Correct taps confirm; wrong taps get repaired (backspace + retype),
                    # which labels the original touch with the intended key.
                    m.learn(ch, down[0] - cx, down[1] - cy)
                ctx = ctx[-1] + ch
                typed += 1
            ctx = ctx[-1] + " "
    print(f"key pitch {PITCH_X:.1f} x {ROW_PITCH:.1f} dp; {users} simulated typists x {letters} letters")
    print(f"{'decoder':18} {'all taps':>9} {'after learning':>15}")
    base = late[names[0]][0] / late[names[0]][1]
    for k in names:
        a = errs[k][0] / errs[k][1]; l = late[k][0] / late[k][1]
        print(f"{k:18} {a*100:8.2f}% {l*100:14.2f}%   ({(1 - l / base) * 100:+.0f}% vs A)")


if __name__ == "__main__":
    words, weights = load_words(sys.argv[1])
    lm = Trigram(sys.argv[2] if len(sys.argv) > 2 else None)
    lam = float(sys.argv[3]) if len(sys.argv) > 3 else 0.6
    run(words, weights, lm, lam=lam)

