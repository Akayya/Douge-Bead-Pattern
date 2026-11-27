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


PLAN_SIZE = 1024


def heart_cells(n: int) -> list[tuple[int, int]]:
    """在 n×n 网格上求心形占位格：隐函数 (x²+y²−1)³ − x²y³ ≤ 0，y 轴向上。

    y 上限要留到 1.25——两个凸起比中央凹口高，只到 1.0 会把顶部压平。
    """
    cells: list[tuple[int, int]] = []
    for gy in range(n):
        for gx in range(n):
            x = (gx + 0.5) / n * 2.30 - 1.15
            y = 1.25 - (gy + 0.5) / n * 2.60
            if (x * x + y * y - 1) ** 3 - x * x * y ** 3 <= 0:
                cells.append((gx, gy))
    return cells


def bead(layer: Image.Image, cx: float, cy: float, r: float, color: tuple[int, int, int]) -> None:
    """画一颗拼豆：实心盘 + 中孔 + 左上高光。

    别加内圈亮边——那会让珠子看起来像垫圈，缩略图尺寸下尤其脏。
    """
    d = ImageDraw.Draw(layer)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    hl = Image.new("RGBA", (int(r * 2) + 2, int(r * 2) + 2), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.ellipse([r * 0.30, r * 0.30, r * 1.10, r * 1.10], fill=(255, 255, 255, 46))
    layer.alpha_composite(hl, (int(cx - r), int(cy - r)))
    hr = r * 0.28
    d.ellipse([cx - hr, cy - hr, cx + hr, cy + hr], fill=(252, 249, 255))


def build_plan_cover() -> Image.Image:
    """赞助方案封面：1024×1024 方形。

    不放任何文字——档位封面在爱发电界面里只有一百多像素，字必然糊。
    主体用 App 自己的产物：拼豆板上一颗颗拼豆拼成的心。
    """
    n = PLAN_SIZE
    img = Image.new("RGB", (n, n), (255, 250, 245))
    px = img.load()
    for y in range(n):
        t = y / n
        c = lerp((255, 249, 242), (243, 235, 250), t)
        for x in range(n):
            px[x, y] = c

    # 拼豆板：圆角白卡 + 淡投影
    pad = 88
    board = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    bd = ImageDraw.Draw(board)
    bd.rounded_rectangle([pad, pad + 8, n - pad, n - pad + 8], radius=58, fill=(120, 90, 160, 26))
    board = board.filter(ImageFilter.GaussianBlur(16))
    img = Image.alpha_composite(img.convert("RGBA"), board)

    card = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    cd = ImageDraw.Draw(card)
    cd.rounded_rectangle([pad, pad, n - pad, n - pad], radius=58, fill=(255, 255, 255, 255))
    img.alpha_composite(card)

    # 板上的定位柱（淡淡的点阵），裁在卡片内
    pegs = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    pdw = ImageDraw.Draw(pegs)
    step = 40
    for y in range(pad + step, n - pad, step):
        for x in range(pad + step, n - pad, step):
            pdw.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(243, 238, 250, 255))
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle([pad, pad, n - pad, n - pad], radius=58, fill=255)
    pegs.putalpha(Image.composite(pegs.getchannel("A"), Image.new("L", (n, n), 0), mask))
    img.alpha_composite(pegs)

    # 心形拼豆
    grid = 15
    cells = heart_cells(grid)
    if not cells:
        raise RuntimeError("心形网格为空")

    xs = [c[0] for c in cells]
    ys = [c[1] for c in cells]
    gw = max(xs) - min(xs) + 1
    gh = max(ys) - min(ys) + 1

    # 按实际包围盒居中，别让心形自己飘在上面
    area_w = (n - pad * 2) * 0.84
    cell = min(area_w / gw, area_w / gh)
    ox = (n - gw * cell) / 2 - min(xs) * cell
    oy = (n - gh * cell) / 2 - min(ys) * cell

    occupied = set(cells)
    beads = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    r = cell * 0.45
    outline_col = (201, 58, 71)
    fill_col = (241, 148, 164)
    for gx, gy in cells:
        cx = ox + (gx + 0.5) * cell
        cy = oy + (gy + 0.5) * cell
        edge = any((gx + dx, gy + dy) not in occupied
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        bead(beads, cx, cy, r, outline_col if edge else fill_col)

    # 整片豆子的投影
    shadow = beads.getchannel("A").filter(ImageFilter.GaussianBlur(9))
    shade = Image.new("RGBA", (n, n), (60, 30, 60, 60))
    shade.putalpha(shadow.point(lambda v: int(v * 0.30)))
    under = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    under.alpha_composite(shade, (2, 8))
    img.alpha_composite(under)
    img.alpha_composite(beads)

    return img.convert("RGB")


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    master = build()

    full = master.convert("RGB")
    full.save(os.path.join(OUT_DIR, "cover-1440x720.png"), optimize=True)

    desktop = full.crop((0, BAND_TOP, W, BAND_BOTTOM))
    desktop.save(os.path.join(OUT_DIR, "cover-desktop-1440x360.png"), optimize=True)

    mobile = full.resize((800, 400), Image.LANCZOS)
    mobile.save(os.path.join(OUT_DIR, "cover-mobile-800x400.png"), optimize=True)

    build_plan_cover().save(os.path.join(OUT_DIR, "plan-cover-1024x1024.png"), optimize=True)

    for name in sorted(os.listdir(OUT_DIR)):
        p = os.path.join(OUT_DIR, name)
        print(f"{name:34s} {Image.open(p).size}  {os.path.getsize(p) // 1024} KB")


if __name__ == "__main__":
    main()
