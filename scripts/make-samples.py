# -*- coding: utf-8 -*-
"""生成两张**自造**测试素材，替换掉早期放在 assets/samples/ 里的第三方截图。

背景：原来的 ref-dog.jpg / ref-wangzhaojun.jpg 是别的拼豆 App 的截图，页面标注
CC-BY-NC-ND（不可二改、禁商用、需署名），其中小狗那张还是「线条小狗」的二创，
不能随仓库公开。这两张图只用于验证两件事，自造完全够用：

  ref-pixel-art.png  规整像素画 → 网格识别应给出「每格 12px」且格数与实际一致
  ref-photo.png      平滑渐变的伪照片 → 网格识别应判失败，退回长边格数模式

用法：python scripts/make-samples.py
"""

from __future__ import annotations

import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets", "samples")

# 6 色小色板：暗描边 + 5 个色块，全部自定义，不取自任何现有作品
INK = (38, 34, 46)
PETAL = (244, 132, 158)
PETAL_DARK = (214, 92, 124)
LEAF = (96, 176, 128)
CORE = (255, 206, 112)
BG = (250, 246, 242)


def pixel_art(cols: int = 28, rows: int = 28, cell: int = 12) -> Image.Image:
    """画一朵块状小花：每格纯色 + 1px 描边，用来测网格周期识别。

    图案必须够「粗」——细线条在 28×28 网格里会碎成点，既不像花也不方便目视核对。
    """
    grid = [[BG for _ in range(cols)] for _ in range(rows)]

    def put(x: int, y: int, c: tuple[int, int, int]) -> None:
        if 0 <= x < cols and 0 <= y < rows:
            grid[y][x] = c

    def disc(cx: float, cy: float, r: float, c: tuple[int, int, int]) -> None:
        for y in range(rows):
            for x in range(cols):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    grid[y][x] = c

    cx, cy = cols / 2 - 0.5, 10.0
    # 五片花瓣（每个是半径 3 的圆），错开角度避免对称到呆板
    import math

    for i in range(5):
        a = math.pi * 2 * i / 5 - math.pi / 2
        disc(cx + math.cos(a) * 5.2, cy + math.sin(a) * 5.2, 3.4, PETAL)
    # 花心压在上面
    disc(cx, cy, 3.0, CORE)
    # 茎：两格宽，从花心往下
    for y in range(int(cy) + 3, rows - 1):
        put(round(cx), y, LEAF)
        put(round(cx) + 1, y, LEAF)
    # 一片叶子
    disc(round(cx) + 4, rows - 8.0, 3.0, LEAF)

    img = Image.new("RGB", (cols * cell, rows * cell), BG)
    d = ImageDraw.Draw(img)
    for y in range(rows):
        for x in range(cols):
            x0, y0 = x * cell, y * cell
            d.rectangle([x0, y0, x0 + cell - 1, y0 + cell - 1], fill=grid[y][x])
            # 细描边：让相邻同色格之间也有边界，网格识别靠它找周期
            d.rectangle([x0, y0, x0 + cell - 1, y0 + cell - 1], outline=INK)
    return img


def fake_photo(w: int = 900, h: int = 1200) -> Image.Image:
    """平滑渐变的伪照片：没有任何等距边界，网格识别必须干净地判失败。"""
    img = Image.new("RGB", (w, h), (232, 226, 236))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w) * 0.6 + (y / h) * 0.4
            px[x, y] = (
                int(226 - 60 * t),
                int(206 - 40 * t),
                int(232 - 30 * t),
            )
    rnd = random.Random(20261127)
    layer = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(14):
        cx, cy = rnd.randrange(w), rnd.randrange(h)
        r = rnd.randrange(90, 260)
        c = (rnd.randrange(180, 250), rnd.randrange(150, 240), rnd.randrange(160, 250))
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)
    layer = layer.filter(ImageFilter.GaussianBlur(70))
    return Image.blend(img, layer, 0.45).filter(ImageFilter.GaussianBlur(6))


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    a = os.path.join(OUT, "ref-pixel-art.png")
    b = os.path.join(OUT, "ref-photo.png")
    pixel_art().save(a, optimize=True)
    fake_photo().save(b, optimize=True)
    for p in (a, b):
        print(f"{os.path.basename(p):22s} {Image.open(p).size}  {os.path.getsize(p) // 1024} KB")


if __name__ == "__main__":
    main()
