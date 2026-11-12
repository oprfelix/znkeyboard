"""eval_onnx.py model.onnx vocab.txt candidates.tsv bench.tsv: rerank@8 with the ONNX scorer, plus timing."""
import sys, itertools, time
import numpy as np, onnxruntime as ort
model_path, vocab_path, cand_path, bench_path = sys.argv[1:5]
vocab = [l.rstrip("\n") for l in open(vocab_path, encoding="utf-8")]
index = {c: i + 3 for i, c in enumerate(vocab)}
source = {}
for line in open(bench_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) > 5 and not line.startswith("#") and p[0] != "set": source[(p[0], p[2])] = int(p[5])
cases = []
for line in open(cand_path, encoding="utf-8").read().splitlines()[1:]:
    st, bucket, typed, expected, engine, readings = line.split("\t")
    rows = [r.rsplit(":", 1) for r in readings.split("|")] if readings else []
    cases.append((st, typed, expected, engine.split("|")[0] if engine else "", [t for t, _ in rows][:8], [float(c) for _, c in rows][:8]))
opts = ort.SessionOptions(); opts.intra_op_num_threads = int(sys.argv[5]) if len(sys.argv) > 5 else 4
sess = ort.InferenceSession(model_path, opts, providers=["CPUExecutionProvider"])
def batch(texts):
    t = max(len(x) for x in texts) + 1
    ids = np.zeros((len(texts), t), dtype=np.int64); ids[:, 0] = 1
    for i, x in enumerate(texts): ids[i, 1:len(x) + 1] = [index.get(c, 2) for c in x[:63]]
    return ids, np.array([min(len(x), 63) for x in texts], dtype=np.int64)
scores, times = {}, []
for c in cases:
    texts = c[4]
    if not texts: continue
    ids, lens = batch(texts)
    s = time.perf_counter(); out = sess.run(None, {"ids": ids, "lengths": lens})[0]; times.append(time.perf_counter() - s)
    for x, v in zip(texts, out): scores[x] = float(v)
def acc(w, b, parity):
    t = {}
    for st, typed, exp, first, texts, costs in cases:
        if source[(st, typed)] % 2 != parity: continue
        r = t.setdefault(st, [0, 0, 0]); r[0] += 1; r[1] += first == exp
        if texts:
            i = max(range(len(texts)), key=lambda i: -costs[i] + w * scores[texts[i]] + b * len(texts[i])); r[2] += texts[i] == exp
    return t
W = [0.3, 0.4, 0.5, 0.6, 0.8]; B = [1, 2, 3, 4, 5]
g = max(itertools.product(W, B), key=lambda wb: sum(r[2] for r in acc(*wb, 0).values()))
print(f"K=8 w={g[0]} b={g[1]}  " + "  ".join(f"{s} {100*(r[2]-r[1])/r[0]:+.1f}" for s, r in acc(*g, 1).items()))
times.sort(); print(f"per keystroke (8 readings): median {1000*times[len(times)//2]:.1f} ms, p90 {1000*times[int(len(times)*.9)]:.1f} ms, {opts.intra_op_num_threads} threads")
