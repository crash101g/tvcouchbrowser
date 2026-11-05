#!/usr/bin/env python3
"""Step 3: glow, grain and lettering on the renders, then export everything to where it belongs.
Needs: pip install pillow numpy playwright. Set CHROME as for capture.py."""
import base64, io, os
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from playwright.sync_api import sync_playwright

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
BUILD = HERE / "build"
IMAGES = ROOT / "docs/images"
RES = ROOT / "app/src/main/res"
IMAGES.mkdir(exist_ok=True)

def grade(name, bloom=.55, grain=.012, vignette=.35):
    """Lens glow around bright things, darker corners, a little film grain."""
    im = Image.open(BUILD / f"render-{name}.png").convert("RGB")
    a = np.asarray(im, dtype=np.float32) / 255
    w, h = im.size
    bright = np.clip((a - .55) / .45, 0, 1) * a
    src = Image.fromarray((bright * 255).astype(np.uint8))
    glow = sum(np.asarray(src.filter(ImageFilter.GaussianBlur(w * r)), dtype=np.float32) * g
               for r, g in ((.004, .5), (.014, .35), (.05, .3))) / 255
    a = 1 - (1 - a) * (1 - np.clip(glow * bloom * 1.6, 0, 1))      # screen blend
    y, x = np.mgrid[0:h, 0:w]
    d = np.hypot((x - w/2) / (w/2), (y - h/2) / (h/2)) / 1.414
    a *= (1 - vignette * d ** 2.4)[..., None]
    rng = np.random.default_rng(1)
    a += rng.normal(0, grain, (h, w, 1)).astype(np.float32)
    return Image.fromarray((np.clip(a, 0, 1) * 255 + .5).astype(np.uint8))

def uri(im):
    b = io.BytesIO(); im.save(b, "PNG"); return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()

CSS = """<!doctype html><meta charset=utf-8><style>
*{margin:0;box-sizing:border-box} body{position:relative;overflow:hidden;background:#000;font-family:"Fira Sans",sans-serif;color:#fff}
img{position:absolute;inset:0;width:100%;height:100%} .fade{position:absolute;inset:0}
.t{position:absolute} .n{font-weight:800;letter-spacing:-.02em;line-height:.98}
.n b{font-weight:800;background:linear-gradient(95deg,#7fdcff,#a48bff 55%,#ff6fc4);-webkit-background-clip:text;color:transparent}
.s{font-weight:500;color:#cfdcec}</style>"""

def letter(pw, im, html, w, h):
    pg = pw.new_page(viewport={"width": w, "height": h})
    pg.set_content(f'{CSS}<body style="width:{w}px;height:{h}px"><img src="{uri(im)}">{html}')
    pg.evaluate("document.fonts.ready")
    data = pg.screenshot(); pg.close()
    return Image.open(io.BytesIO(data)).convert("RGB")

def rounded(im, radius):
    s = 4; m = Image.new("L", (im.width*s, im.height*s), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, im.width*s-1, im.height*s-1), radius*s, fill=255)
    out = im.convert("RGBA"); out.putalpha(m.resize(im.size, Image.LANCZOS)); return out

def device_shot():
    """The real phone page, in a simple phone outline, on a transparent background."""
    page = Image.open(BUILD / "phone-top.png").convert("RGB")
    page = page.resize((585, 1266), Image.LANCZOS)
    pad, W, H = 18, 585 + 36, 1266 + 36
    out = Image.new("RGBA", (W + 60, H + 60), (0, 0, 0, 0))
    shadow = Image.new("RGBA", out.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((30, 30, W + 30, H + 30), 66, fill=(46, 120, 255, 150))
    out = Image.alpha_composite(out, shadow.filter(ImageFilter.GaussianBlur(14)))
    ImageDraw.Draw(out).rounded_rectangle((30, 30, W + 30, H + 30), 66, fill=(16, 19, 24, 255), outline=(70, 86, 110, 255), width=4)
    out.alpha_composite(rounded(page, 50), (30 + pad, 30 + pad))
    return out

with sync_playwright() as p:
    b = p.chromium.launch(executable_path=os.environ.get("CHROME") or None)

    hero = grade("hero")
    fade = '<div class="fade" style="background:linear-gradient(100deg,#04060cf8 0,#04060ce0 30%,#04060c00 52%)"></div>'
    text = lambda W: (f'{fade}<div class="t n" style="left:{W*.045}px;top:{W*.13}px;font-size:{W*.075}px">TVCouch<br><b>Browser</b></div>'
                      f'<div class="t s" style="left:{W*.047}px;top:{W*.3}px;font-size:{W*.0235}px;line-height:1.35">The ad-blocking browser<br>for your Google TV couch</div>')
    letter(b, hero.resize((1600, 800), Image.LANCZOS), text(1600), 1600, 800).save(IMAGES / "hero.jpg", quality=88)
    letter(b, hero.resize((1280, 640), Image.LANCZOS), text(1280), 1280, 640).save(IMAGES / "social-preview.jpg", quality=88)

    for name, cap, glow in (("ads", "ad-blocking", .14), ("phone", "phone-trackpad", .55), ("remote", "remote", .55)):
        grade(name, bloom=glow).resize((1200, 675), Image.LANCZOS).save(IMAGES / f"{cap}.jpg", quality=86)
    device_shot().save(IMAGES / "phone-page.png", optimize=True)

    # App banner, 320x180 dp at xhdpi. It's seen from the couch, so the name is big. Rendered at 2x and scaled down.
    banner = letter(b, grade("banner", grain=.006, vignette=.2),
                    '<div class="t n" style="left:690px;top:206px;font-size:128px">TVCouch<br><b>Browser</b></div>', 1280, 720)
    banner.resize((640, 360), Image.LANCZOS).save(RES / "drawable-xhdpi/banner.webp", quality=88, method=6)
    banner.save(IMAGES / "banner.jpg", quality=90)

    # Launcher icon: the couch and neon outline, cropped tight, with rounded corners.
    logo = grade("logo", grain=.004, vignette=.15)
    icon = rounded(logo.crop((64, 112, 960, 1008)).resize((384, 384), Image.LANCZOS), 84)
    icon.save(IMAGES / "icon.png", optimize=True)
    for folder, size in (("mipmap-xhdpi", 96), ("mipmap-xxxhdpi", 192)):
        (RES / folder).mkdir(exist_ok=True)
        icon.resize((size, size), Image.LANCZOS).save(RES / folder / "ic_launcher.webp", quality=90, method=6)
    b.close()

for f in sorted(IMAGES.iterdir()) + sorted(RES.glob("*/*.webp")):
    print(f"{f.stat().st_size/1024:8.1f} KB  {f.relative_to(ROOT)}")
