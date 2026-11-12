"""train.py data_dir out.pt minutes [size]
A causal character model (GPT-style) over tokens.u16 from prep.py, trained on the Mac's GPU (MPS)
for a fixed time; the last 1% of the tokens are held out for validation. [size] names a shape in
SIZES; the shape is saved in the checkpoint so rerank.py can rebuild the model."""
import math, sys, time
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

CONTEXT = 64
SIZES = {  # dim, layers, heads, ff
    "small": (256, 4, 4, 1024),
    "medium": (384, 6, 6, 1536),
    "large": (512, 6, 8, 2048),
}
BATCH, LR, WARMUP = 256, 1e-3, 500


class Block(nn.Module):
    def __init__(self, dim, heads, ff):
        super().__init__()
        self.dim, self.heads = dim, heads
        self.norm1, self.norm2 = nn.LayerNorm(dim), nn.LayerNorm(dim)
        self.qkv, self.proj = nn.Linear(dim, 3 * dim), nn.Linear(dim, dim)
        self.ff = nn.Sequential(nn.Linear(dim, ff), nn.GELU(), nn.Linear(ff, dim))

    def forward(self, x):
        b, t, _ = x.shape
        q, k, v = self.qkv(self.norm1(x)).split(self.dim, dim=2)
        q, k, v = (z.view(b, t, self.heads, self.dim // self.heads).transpose(1, 2) for z in (q, k, v))
        y = F.scaled_dot_product_attention(q, k, v, is_causal=True)
        x = x + self.proj(y.transpose(1, 2).reshape(b, t, self.dim))
        return x + self.ff(self.norm2(x))


class CharLM(nn.Module):
    def __init__(self, vocab, shape=SIZES["small"]):
        super().__init__()
        dim, layers, heads, ff = shape
        self.embed = nn.Embedding(vocab, dim)
        self.position = nn.Embedding(CONTEXT, dim)
        self.blocks = nn.ModuleList(Block(dim, heads, ff) for _ in range(layers))
        self.norm = nn.LayerNorm(dim)
        # Small embeddings: with the tied output, N(0, 1) would start the logits at a spread of √dim.
        nn.init.normal_(self.embed.weight, std=0.02)
        nn.init.normal_(self.position.weight, std=0.02)

    def forward(self, ids):
        x = self.embed(ids) + self.position(torch.arange(ids.shape[1], device=ids.device))
        for block in self.blocks:
            x = block(x)
        return self.norm(x) @ self.embed.weight.T  # tied output


def load(path, device):
    saved = torch.load(path, map_location=device)
    model = CharLM(saved["vocab"], tuple(saved.get("shape", SIZES["small"]))).to(device)
    model.load_state_dict(saved["model"])
    return model.eval()


def main(data_dir, out_path, minutes, size="small"):
    shape = SIZES[size]
    tokens = np.fromfile(f"{data_dir}/tokens.u16", dtype=np.uint16)
    vocab = sum(1 for _ in open(f"{data_dir}/vocab.txt", encoding="utf-8")) + 3
    split = int(len(tokens) * 0.99)
    train, val = tokens[:split], tokens[split:]
    device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    model = CharLM(vocab, shape).to(device)
    print(f"{size} {shape}: {sum(p.numel() for p in model.parameters()) / 1e6:.2f} M parameters, vocab {vocab}, "
          f"{len(tokens):,} tokens, {device}", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=0.1, betas=(0.9, 0.95))
    span = np.arange(CONTEXT + 1)

    def batch(source):
        at = np.random.randint(0, len(source) - CONTEXT - 1, BATCH)
        rows = torch.from_numpy(source[at[:, None] + span].astype(np.int64)).to(device)
        return rows[:, :-1], rows[:, 1:]

    @torch.no_grad()
    def validate():
        model.eval()
        state = np.random.get_state()
        np.random.seed(0)  # the same validation batches every time
        losses = [F.cross_entropy(model(x).flatten(0, 1), y.flatten()).item() for x, y in (batch(val) for _ in range(40))]
        np.random.set_state(state)
        model.train()
        return sum(losses) / len(losses)

    def save():
        torch.save({"model": model.state_dict(), "vocab": vocab, "shape": shape}, out_path)

    budget = minutes * 60
    started = time.time()
    step = 0
    while time.time() - started < budget:
        progress = (time.time() - started) / budget
        lr = LR * min(1, (step + 1) / WARMUP) * (0.1 + 0.9 * 0.5 * (1 + math.cos(math.pi * progress)))
        for group in opt.param_groups:
            group["lr"] = lr
        x, y = batch(train)
        loss = F.cross_entropy(model(x).flatten(0, 1), y.flatten())
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        step += 1
        if step % 1000 == 0:
            elapsed = time.time() - started
            print(f"step {step}  {elapsed / 60:.1f} min  train {loss.item():.3f}  val {validate():.3f}  "
                  f"{step * BATCH * CONTEXT / elapsed / 1000:.0f}k tokens/s  "
                  f"{step * BATCH * CONTEXT / len(train):.2f} passes", flush=True)
            save()
    save()
    print(f"done: {step} steps, val {validate():.3f}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], float(sys.argv[3]), *sys.argv[4:5])
