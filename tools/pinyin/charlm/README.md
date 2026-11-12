# Character model for AI 整句排序

A small GPT-style character model that reorders the decoder's whole-input readings (setting:
AI 整句排序, experimental, off by default). `assets/pinyin/charlm.onnx` is its INT8 export;
`charlm_vocab.txt` lists its characters (id = line + 3; 0 pad, 1 clause boundary, 2 unknown).

## Model

- 23 M parameters: 6 layers, width 512, 8 heads, feed-forward 2048, context 64, tied embeddings.
- Trained 8 hours on an M4 (MPS) over 200 M characters of FineWeb-2 Chinese (the shard in
  `tools/pinyin/lm`), clauses of dictionary characters only, benchmark clauses left out.
  Validation loss 3.54 nats per character.

## Ranking

`PinyinEngine` scores the decoder's best 8 readings and orders them by
`-cost + 0.5 × ln P + 3 × characters` (the model favours short readings), then keeps 3 as
before. Tuned on the benchmark's even Tatoeba ids, reported on the odd ones (top-1, points):

| input    | engine | reranked (INT8, top 8) |
|----------|--------|------------------------|
| full     | 72.7   | +4.4                   |
| initials | 7.1    | +1.4                   |
| mixed    | 28.9   | +6.5                   |
| typo     | 49.9   | +8.1                   |

On the emulator (M4 host) a keystroke takes 6.4 ms on average with 2 threads.

## Rebuild

```
python prep.py dict.tsv bench.tsv corpus.parquet out_dir 200000000
python train.py out_dir charlm.pt 480 large
python export_onnx.py charlm.pt onnx          # charlm_f32.onnx, charlm_int8.onnx
python eval_onnx.py onnx/charlm_int8.onnx out_dir/vocab.txt candidates.tsv ../bench/bench.tsv
```

`candidates.tsv` is the decoder's best 20 readings per benchmark case with their costs (one
`text:cost` list per case), as `rerank.py` and `eval_onnx.py` read it.
