#!/bin/sh
# Step 2: full-quality renders into tools/art/build/. BLENDER is the path to a Blender 4.5 binary.
cd "$(dirname "$0")" || exit 1
for shot in "hero 2000 128" "ads 1600 96" "phone 1600 96" "remote 1600 96" "logo 1024 128" "banner 1280 128"; do
  set -- $shot
  nice "${BLENDER:-blender}" -b -P scene.py -- "$1" "$2" "$3" "$PWD/build/render-$1.png" > "build/render-$1.log" 2>&1 || exit 1
done
