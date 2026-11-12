"""prep.py dict.tsv bench.tsv corpus.parquet out_dir max_chars
Clauses of Han characters the dictionary has (traditional converted to simplified), as uint16
token ids with 1 between clauses; benchmark clauses are left out. vocab.txt lists the characters
(id = line + 3; 0 pad, 1 clause boundary, 2 unknown)."""
import sys, re, numpy as np, pyarrow.parquet as pq, opencc
dict_path, bench_path, corpus, out_dir, max_chars = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])
chars = set()
for line in open(dict_path, encoding="utf-8"):
    if line.startswith("#"): continue
    word = line.split("\t", 1)[0]
    chars.update(c for c in word if "一" <= c <= "鿿")
vocab = sorted(chars)
index = {c: i + 3 for i, c in enumerate(vocab)}
open(f"{out_dir}/vocab.txt", "w", encoding="utf-8").write("\n".join(vocab) + "\n")
skip = set()
for line in open(bench_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) > 3 and not line.startswith("#"): skip.add(p[3])
t2s = opencc.OpenCC("t2s")
cut = re.compile("[^" + "".join(sorted(chars)).replace("\\", "") + "]+")
buf = np.zeros(max_chars + 100_000, dtype=np.uint16)
n = clauses = skipped = 0
f = pq.ParquetFile(corpus)
for g in range(f.metadata.num_row_groups):
    for text in f.read_row_group(g, columns=["text"]).column(0).to_pylist():
        for run in cut.split(t2s.convert(text)):
            if len(run) < 2: continue
            if run in skip: skipped += 1; continue
            ids = [index[c] for c in run]
            buf[n:n + len(ids)] = ids; n += len(ids); buf[n] = 1; n += 1; clauses += 1
    if n >= max_chars: break
    if g % 50 == 0: print(f"row group {g}: {n:,} tokens", flush=True)
buf[:n].tofile(f"{out_dir}/tokens.u16")
print(f"vocab {len(vocab)}, {clauses:,} clauses, {n:,} tokens, {skipped} benchmark clauses left out")
