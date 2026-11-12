"""rerank.py model.pt vocab.txt candidates.tsv bench.tsv
Scores each candidate reading with the character model and picks by
    -engine cost + weight × ln P(reading)
with the weight chosen on the clauses with even Tatoeba ids and reported on the odd ones."""
import sys
import numpy as np
import torch
import torch.nn.functional as F
from train import load, CONTEXT

model_path, vocab_path, cand_path, bench_path = sys.argv[1:5]
vocab = [l.rstrip("\n") for l in open(vocab_path, encoding="utf-8")]
index = {c: i + 3 for i, c in enumerate(vocab)}
device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
model = load(model_path, device)

source = {}
for line in open(bench_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) > 5 and not line.startswith("#") and p[0] != "set":
        source[(p[0], p[2])] = int(p[5])

cases = []
for line in open(cand_path, encoding="utf-8").read().splitlines()[1:]:
    st, bucket, typed, expected, engine, readings = line.split("\t")
    rows = [r.rsplit(":", 1) for r in readings.split("|")] if readings else []
    cases.append((st, typed, expected, engine.split("|")[0] if engine else "", [t for t, _ in rows], [float(c) for _, c in rows]))


@torch.no_grad()
def log_probs(texts):
    """ln P(text, then a clause boundary | clause boundary) for each text."""
    out = {}
    unique = sorted(set(texts), key=len)
    for start in range(0, len(unique), 512):
        chunk = unique[start:start + 512]
        ids = [[1] + [index.get(c, 2) for c in t][:CONTEXT - 1] for t in chunk]
        width = max(len(i) for i in ids) + 1
        x = torch.zeros(len(ids), width, dtype=torch.long)
        y = torch.full((len(ids), width), -1, dtype=torch.long)
        for row, seq in enumerate(ids):
            full = seq + [1]
            x[row, :len(seq)] = torch.tensor(seq)
            y[row, :len(seq)] = torch.tensor(full[1:])
        logits = model(x.to(device)[:, :width - 1])
        target = y.to(device)[:, :width - 1]
        lp = -F.cross_entropy(logits.transpose(1, 2), target.clamp(min=0), reduction="none")
        lp = (lp * (target >= 0)).sum(1).cpu().numpy()
        for t, v in zip(chunk, lp):
            out[t] = float(v)
    return out


scores = log_probs([t for c in cases for t in c[4]])


def accuracy(weight, parity):
    tally = {}
    for st, typed, expected, engine_first, texts, costs in cases:
        if source[(st, typed)] % 2 != parity:
            continue
        r = tally.setdefault(st, [0, 0, 0])
        r[0] += 1
        r[1] += engine_first == expected
        if texts:
            best = max(range(len(texts)), key=lambda i: -costs[i] + weight * scores[texts[i]])
            r[2] += texts[best] == expected
    return tally


weights = [0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 3.0]
dev = {w: accuracy(w, 0) for w in weights}
for w in weights:
    print(f"dev weight {w:4.1f}: " + "  ".join(f"{s} {100 * r[2] / r[0]:.1f}" for s, r in dev[w].items()))
best = max(weights, key=lambda w: sum(r[2] for r in dev[w].values()))
print(f"\nweight {best} on test (odd ids): engine top-1 -> reranked top-1")
for s, r in accuracy(best, 1).items():
    print(f"  {s:9s} {100 * r[1] / r[0]:5.1f}% -> {100 * r[2] / r[0]:5.1f}%  ({100 * (r[2] - r[1]) / r[0]:+.1f})")
