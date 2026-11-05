# Artwork

The app icon, the TV banner and the pictures in `docs/images/` are made here. Nothing is hand-painted or
downloaded: the 3D scenes are modelled in `scene.py`, and the screens on the TV and phone are HTML.

    pip install playwright pillow numpy
    export CHROME=/path/to/chromium       # optional: playwright finds its own if it has one
    export BLENDER=/path/to/blender       # Blender 4.5; about 10 minutes on a 6-core CPU

    python tools/art/capture.py           # 1. screens for the TV and phone
    tools/art/render.sh                   # 2. 3D renders (Cycles)
    python tools/art/finish.py            # 3. glow, lettering, export to docs/images/ and app/src/main/res/

| File | What it is |
|---|---|
| `screens/tv-start.html` | The app's start screen, redrawn from the sizes and colours in `BrowserActivity.kt` |
| `screens/tv-split.html` | A made-up news site, half covered in ads and half clean |
| `screens/ad-cards.html` | Pretend ads for the 3D scene to shatter |
| `capture.py` | Screenshots those, and the real `phone.html` |
| `scene.py` | The living room, couch, remote and phone, and the camera for each picture |
| `finish.py` | Post-processing and export |

Keep the app's images small: they go in the APK. The icon and banner are WebP for that reason.
Lettering uses Fira Sans (SIL Open Font License), rendered into the images.
