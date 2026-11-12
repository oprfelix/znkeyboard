"""rerank_k.py model.pt vocab.txt candidates.tsv bench.tsv: the one global setting, reranking only the engine's top K."""
import sys, itertools
sys.argv = sys.argv[:5]
import rerank as R
cases, scores, source = R.cases, R.scores, R.source
def acc(w, b, parity, k):
    t = {}
    for st, typed, exp, first, texts, costs in cases:
        if source[(st, typed)] % 2 != parity: continue
        r = t.setdefault(st, [0, 0, 0]); r[0] += 1; r[1] += first == exp
        tx, cs = texts[:k], costs[:k]
        if tx:
            i = max(range(len(tx)), key=lambda i: -cs[i] + w * scores[tx[i]] + b * len(tx[i]))
            r[2] += tx[i] == exp
    return t
W = [0.2, 0.3, 0.4, 0.5, 0.6, 0.8]; B = [0, 1, 2, 3, 4, 5]
for k in (3, 5, 8, 12, 20):
    g = max(itertools.product(W, B), key=lambda wb: sum(r[2] for r in acc(*wb, 0, k).values()))
    res = acc(*g, 1, k)
    print(f"K={k:2d} w={g[0]} b={g[1]}  " + "  ".join(f"{s} {100*(r[2]-r[1])/r[0]:+.1f}" for s, r in res.items()), flush=True)
