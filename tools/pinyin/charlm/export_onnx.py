"""export_onnx.py model.pt out_dir
Exports the character model as a scorer for the keyboard: ids [B, T] (clause boundary first, then
the reading's characters, 0-padded) and lengths [B] in, ln P(reading, then a clause boundary) [B]
out, the sum rerank.py uses. Writes charlm_f32.onnx and charlm_int8.onnx (weights INT8, dynamic
activation quantization) and checks both against PyTorch."""
import sys, os
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from train import load, CONTEXT

BOUNDARY = 1


class Scorer(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, ids, lengths):
        # ids: [B, T] with ids[:, 0] the boundary and ids[b, 1:lengths[b]+1] the characters.
        b, t = ids.shape
        logits = self.model(ids)                                  # [B, T, V]
        logp = F.log_softmax(logits, dim=-1)
        # Target at step i is ids[i + 1], and the boundary right after the last character.
        nxt = torch.cat([ids[:, 1:], torch.zeros_like(ids[:, :1])], dim=1)
        pos = torch.arange(t, device=ids.device).unsqueeze(0)     # [1, T]
        last = lengths.unsqueeze(1)                               # [B, 1]
        target = torch.where(pos == last, torch.full_like(nxt, BOUNDARY), nxt)
        picked = logp.gather(2, target.unsqueeze(2)).squeeze(2)   # [B, T]
        mask = (pos <= last).to(picked.dtype)
        return (picked * mask).sum(1)


def main(model_path, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    model = load(model_path, torch.device("cpu"))
    scorer = Scorer(model).eval()
    ids = torch.tensor([[1, 5, 6, 7, 0], [1, 8, 9, 0, 0]], dtype=torch.long)
    lengths = torch.tensor([3, 2], dtype=torch.long)
    f32 = f"{out_dir}/charlm_f32.onnx"
    torch.onnx.export(
        scorer, (ids, lengths), f32, input_names=["ids", "lengths"], output_names=["logp"],
        dynamic_axes={"ids": {0: "batch", 1: "time"}, "lengths": {0: "batch"}, "logp": {0: "batch"}},
        opset_version=17, dynamo=False,
    )
    from onnxruntime.quantization import quantize_dynamic, QuantType
    int8 = f"{out_dir}/charlm_int8.onnx"
    quantize_dynamic(f32, int8, weight_type=QuantType.QInt8, per_channel=True)
    import onnxruntime as ort
    rng = np.random.default_rng(0)
    test = np.zeros((8, 12), dtype=np.int64)
    test[:, 0] = BOUNDARY
    lens = rng.integers(2, 11, size=8)
    for i, n in enumerate(lens):
        test[i, 1:n + 1] = rng.integers(3, 3000, size=n)
    with torch.no_grad():
        ref = scorer(torch.from_numpy(test), torch.from_numpy(lens)).numpy()
    for path in (f32, int8):
        session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        out = session.run(None, {"ids": test, "lengths": lens})[0]
        print(f"{os.path.basename(path)}: {os.path.getsize(path) / 1e6:.1f} MB, max |diff| vs torch {np.abs(out - ref).max():.3f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
