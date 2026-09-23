#!/usr/bin/env python3
"""Plot mismatch% vs round from qa/round-*-report.json. Pillow only, no matplotlib."""
from __future__ import annotations

import json
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / "qa"
OUT = QA / "mismatch-curve.png"

W, H = 1100, 560
PAD_L, PAD_R, PAD_T, PAD_B = 70, 40, 50, 60
PLOT_W = W - PAD_L - PAD_R
PLOT_H = H - PAD_T - PAD_B
BG = (247, 249, 252)
AXIS = (45, 55, 72)
GRID = (220, 226, 234)
LINE = (43, 108, 176)
DOT = (214, 158, 46)
TEXT = (26, 32, 44)


def load_series() -> list[tuple[int, float]]:
    rows = []
    for p in QA.glob("round-*-report.json"):
        m = re.search(r"round-(\d+)-report", p.name)
        if not m:
            continue
        data = json.loads(p.read_text(encoding="utf-8"))
        pct = (data.get("overall") or {}).get("mismatch_pct")
        if pct is None:
            continue
        rows.append((int(m.group(1)), float(pct)))
    rows.sort()
    return rows


def pick_milestones(rows: list[tuple[int, float]]) -> list[tuple[int, float]]:
    if not rows:
        return []
    picked = {rows[0][0], rows[-1][0]}
    picked.add(min(rows, key=lambda x: x[1])[0])
    prev = rows[0][1]
    for rnd, pct in rows[1:]:
        if prev - pct >= 1.5:
            picked.add(rnd)
        prev = pct
    return [(r, p) for r, p in rows if r in picked]


def font(size: int):
    for name in ("msyh.ttc", "msyh.ttf", "arial.ttf", "seguiemj.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def xy(rnd: float, pct: float, xmin: float, xmax: float, ymin: float, ymax: float) -> tuple[int, int]:
    tx = 0 if xmax == xmin else (rnd - xmin) / (xmax - xmin)
    ty = 0 if ymax == ymin else (pct - ymin) / (ymax - ymin)
    x = PAD_L + tx * PLOT_W
    y = PAD_T + (1 - ty) * PLOT_H
    return int(x), int(y)


def main() -> None:
    rows = load_series()
    if len(rows) < 2:
        raise SystemExit("need at least 2 round-*-report.json files")
    xs = [r for r, _ in rows]
    ys = [p for _, p in rows]
    xmin, xmax = min(xs), max(xs)
    ymin, ymax = 0.0, max(ys) * 1.12
    marks = pick_milestones(rows)

    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    title_f, axis_f, label_f = font(22), font(14), font(12)

    d.text((PAD_L, 14), "Agent Loop 差异率", fill=TEXT, font=title_f)

    # grid + y ticks (bottom = ymin)
    for i in range(5):
        frac = i / 4
        y = PAD_T + (1 - frac) * PLOT_H
        tick = ymin + (ymax - ymin) * frac
        d.line([(PAD_L, y), (PAD_L + PLOT_W, y)], fill=GRID, width=1)
        d.text((10, y - 8), f"{tick:.1f}%", fill=AXIS, font=axis_f)

    # x ticks
    xticks = sorted({xs[0], xs[len(xs) // 2], xs[-1]})
    for rnd in xticks:
        x, _ = xy(rnd, ymin, xmin, xmax, ymin, ymax)
        d.line([(x, PAD_T + PLOT_H), (x, PAD_T + PLOT_H + 5)], fill=AXIS, width=1)
        d.text((x - 10, PAD_T + PLOT_H + 10), str(rnd), fill=AXIS, font=axis_f)

    d.rectangle([PAD_L, PAD_T, PAD_L + PLOT_W, PAD_T + PLOT_H], outline=AXIS, width=1)
    d.text((W // 2 - 20, H - 28), "轮数", fill=AXIS, font=axis_f)

    pts = [xy(r, p, xmin, xmax, ymin, ymax) for r, p in rows]
    d.line(pts, fill=LINE, width=3)
    for r, p in marks:
        x, y = xy(r, p, xmin, xmax, ymin, ymax)
        d.ellipse([x - 6, y - 6, x + 6, y + 6], fill=DOT, outline=TEXT)
        d.text((x + 8, y - 22), f"R{r}  {p:.2f}%", fill=TEXT, font=label_f)

    QA.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"wrote {OUT}  points={len(rows)}  best={min(ys):.4f}%")


if __name__ == "__main__":
    main()
