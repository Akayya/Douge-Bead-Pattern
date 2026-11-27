# -*- coding: utf-8 -*-
"""生成爱发电主页封面。

尺寸策略：
  母版 1440×720（正好 2:1 = 手机档 800×400）
  桌面档 1440×360 = 母版正中横切（4:1）
  所以所有文字与手机截图都压在 y=180..540 的安全带里，两种比例都不会被切。

用法：
  python scripts/make-cover.py
输出：assets/marketing/cover/
"""

from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "assets", "marketing", "cover")

W, H = 1440, 720
BAND_TOP, BAND_BOTTOM = 180, 540          # 桌面档可见区（360 高）

BRAND = (108, 75, 244)                    # #6C4BF4，App 主色
DEEP = (26, 16, 56)                       # 深底

FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"
FONT_REG = "C:/Windows/Fonts/msyh.ttc"


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size, index=0)


def lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))  # type: ignore[return-value]


def background() -> Image.Image:
    """对角渐变 + 细网格 + 柔光。"""
    img = Image.new("RGB", (W, H), DEEP)
    px = img.load()
    for y in range(H):
        for x in range(0, W, 1):
            # 左上亮、右下深，再叠一点横向偏移
            t = (x / W * 0.55 + y / H * 0.45)
            px[x, y] = lerp(BRAND, DEEP, min(1.0, t * 1.15))

    # 右上角一团柔光，给手机截图当背光
    glow = Image.new("L", (W, H), 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse([760, -180, 1560, 620], fill=90)
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    img = Image.composite(Image.new("RGB", (W, H), (150, 120, 255)), img, glow)

    # 细网格（拼豆格子意象），只在深色区域可见
    grid = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gdr = ImageDraw.Draw(grid)
    step = 24
    for x in range(0, W, step):
        gdr.line([(x, 0), (x, H)], fill=(255, 255, 255, 14), width=1)
    for y in range(0, H, step):
        gdr.line([(0, y), (W, y)], fill=(255, 255, 255, 14), width=1)
    img = Image.alpha_composite(img.convert("RGBA"), grid)
    return img


def bead_dots(img: Image.Image) -> None:
    """在安全带之外撒几颗豆子，手机档更活泼；桌面档裁掉也无所谓。"""
    dots = [
        (60, 96, 14, (255, 255, 255, 46)),
        (128, 128, 9, (255, 205, 120, 70)),
        (1120, 80, 11, (255, 255, 255, 40)),
        (1300, 120, 16, (255, 150, 160, 60)),
        (1360, 620, 12, (255, 255, 255, 38)),
        (1180, 660, 18, (150, 220, 255, 45)),
        (90, 640, 15, (255, 255, 255, 34)),
        (760, 676, 10, (255, 220, 120, 55)),
    ]
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for cx, cy, r, color in dots:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
        d.ellipse([cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2],
                  outline=(255, 255, 255, min(90, color[3] + 20)), width=1)
    img.alpha_composite(layer)


def phone(path: str, height: int, radius_ratio: float = 0.075) -> Image.Image:
    """截图按比例缩放 + 圆角 + 白色描边 + 投影，返回带透明边的 RGBA。"""
    src = Image.open(path).convert("RGB")
    width = round(height * src.width / src.height)
    shot = src.resize((width, height), Image.LANCZOS)

    radius = round(width * radius_ratio)
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, height - 1], radius=radius, fill=255)

    border = 5
    card = Image.new("RGBA", (width + border * 2, height + border * 2), (0, 0, 0, 0))
    card.paste(shot, (border, border), mask)

    frame_mask = Image.new("L", card.size, 0)
    ImageDraw.Draw(frame_mask).rounded_rectangle(
        [0, 0, card.width - 1, card.height - 1], radius=radius + border, fill=255)
    card.putalpha(frame_mask)

    # 白色细边框
    ImageDraw.Draw(card).rounded_rectangle(
        [0, 0, card.width - 1, card.height - 1], radius=radius + border,
        outline=(255, 255, 255, 105), width=border)

    # 投影
    pad = 26
    shadow = Image.new("L", (card.width + pad * 2, card.height + pad * 2), 0)
    ImageDraw.Draw(shadow).rounded_rectangle(
        [pad, pad + 6, pad + card.width, pad + card.height + 6],
        radius=radius + border, fill=150)
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))

    out = Image.new("RGBA", shadow.size, (0, 0, 0, 0))
    out.paste(Image.new("RGBA", shadow.size, (10, 4, 30, 255)), (0, 0), shadow)
    out.alpha_composite(card, (pad, pad))
    return out


def chip(text: str, f: ImageFont.FreeTypeFont) -> Image.Image:
    """渲染一枚半透明胶囊，返回独立小图（调用方自己 composite，避免变实心白块）。"""
    pad_x, pad_y = 20, 10
    box = f.getbbox(text)
    tw, th = box[2] - box[0], box[3] - box[1]
    w, h = tw + pad_x * 2, th + pad_y * 2
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.rounded_rectangle([0, 0, w - 1, h - 1], radius=h // 2,
                         fill=(255, 255, 255, 30), outline=(255, 255, 255, 105), width=2)
    ld.text((pad_x, pad_y - box[1]), text, font=f, fill=(255, 255, 255, 240))
    return layer


def build() -> Image.Image:
    img = background().convert("RGBA")
    bead_dots(img)

    # 所有文字/胶囊先画在独立图层，最后一次性合成，才能得到真正的半透明
    overlay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    x = 96

    # 顶部小标签
    d.text((x, 186), "开源  ·  纯离线  ·  不做账号", font=font(FONT_REG, 25),
           fill=(255, 255, 255, 185))

    # 主标题 + 英文并排（英文基线对齐大字底部）
    f_cn = font(FONT_BOLD, 96)
    d.text((x - 3, 212), "豆格", font=f_cn, fill=(255, 255, 255, 255))
    cn_box = f_cn.getbbox("豆格")
    d.text((x + cn_box[2] + 18, 262), "Douge", font=font(FONT_REG, 36),
           fill=(255, 255, 255, 160))

    # 副标题两行（避开右侧截图区）
    f_sub = font(FONT_REG, 35)
    d.text((x, 320), "把照片变成拼豆图纸", font=f_sub, fill=(255, 255, 255, 235))
    d.text((x, 362), "自动数色号、算成本、按 29/58 板施工", font=f_sub,
           fill=(255, 255, 255, 235))

    # 底部一行事实
    d.text((x, 470), "Android 11+   ·   无广告   ·   不联网   ·   MIT 开源",
           font=font(FONT_REG, 24), fill=(255, 255, 255, 170))

    img.alpha_composite(overlay)

    # 卖点胶囊（单独合成，保证半透明）
    f_chip = font(FONT_REG, 26)
    cx = x
    for text in ("照片 → 图纸", "数豆 · 算成本", "29/58 板施工"):
        c = chip(text, f_chip)
        img.alpha_composite(c, (cx, 412))
        cx += c.width + 12

    # 右侧三张截图：含投影的总高必须 ≤ 260，才能完全落在 180..540 安全带内
    shots = [
        ("screenshots/m2/m2-grid.png", 262),
        ("screenshots/m9/m9-bycolor.png", 282),
        ("screenshots/m3/m3-editor-picked.png", 262),
    ]
    cards = [phone(os.path.join(ROOT, p), h) for p, h in shots]
    gap = 18
    total = sum(c.width for c in cards) + gap * (len(cards) - 1)
    right_edge = W - 88
    px = right_edge - total
    for i, c in enumerate(cards):
        dy = -8 if i == 1 else 6
        top = (H - c.height) // 2 + dy
        img.alpha_composite(c, (px, top))
        px += c.width + gap

    return img


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    master = build()

    full = master.convert("RGB")
    full.save(os.path.join(OUT_DIR, "cover-1440x720.png"), optimize=True)

    desktop = full.crop((0, BAND_TOP, W, BAND_BOTTOM))
    desktop.save(os.path.join(OUT_DIR, "cover-desktop-1440x360.png"), optimize=True)

    mobile = full.resize((800, 400), Image.LANCZOS)
    mobile.save(os.path.join(OUT_DIR, "cover-mobile-800x400.png"), optimize=True)

    for name in sorted(os.listdir(OUT_DIR)):
        p = os.path.join(OUT_DIR, name)
        print(f"{name:34s} {Image.open(p).size}  {os.path.getsize(p) // 1024} KB")


if __name__ == "__main__":
    main()
