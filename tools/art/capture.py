#!/usr/bin/env python3
"""Step 1: render the screen images that go on the 3D TV and phone, into tools/art/build/.
Needs: pip install playwright. Set CHROME to a Chromium binary, or let playwright find its own."""
import os
from pathlib import Path
from playwright.sync_api import sync_playwright

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
OUT = HERE / "build"
OUT.mkdir(exist_ok=True)

# phone.html needs the TV behind it: pretend the trackpad socket connected, so the page shows its real connected state.
FAKE_SOCKET = """
window.WebSocket = class { constructor(){ this.readyState = 1; setTimeout(() => this.onopen && this.onopen({}), 0) }
  send(){} close(){} addEventListener(t, f){ if (t === 'open') setTimeout(f, 0) } };
"""

with sync_playwright() as p:
    b = p.chromium.launch(executable_path=os.environ.get("CHROME") or None)
    for name, w, h in (("tv-start", 1920, 1080), ("tv-split", 1920, 1080), ("ad-cards", 1600, 1200)):
        pg = b.new_page(viewport={"width": w, "height": h})
        pg.goto((HERE / "screens" / f"{name}.html").as_uri())
        pg.evaluate("document.fonts.ready")
        pg.screenshot(path=OUT / f"{name}.png")
    ctx = b.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=3, is_mobile=True, has_touch=True)
    ctx.add_init_script(FAKE_SOCKET)
    pg = ctx.new_page()
    pg.goto((ROOT / "app/src/main/assets/phone.html").as_uri() + "?t=demo")
    pg.wait_for_timeout(400)
    pg.screenshot(path=OUT / "phone-top.png")     # one iPhone screen: what the 3D phone shows
    pg.screenshot(path=OUT / "phone-full.png", full_page=True)
    b.close()
