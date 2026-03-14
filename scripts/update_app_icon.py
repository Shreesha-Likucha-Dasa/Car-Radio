#!/usr/bin/env python3
"""Generate Android launcher icons for this project.

Usage:
  python scripts/update_app_icon.py /path/to/source.png

This script will resize the provided source image into the standard Android mipmap
launcher icon sizes and overwrite the existing icon assets in
`app/src/main/res/mipmap-*`.

By default it will generate both `ic_launcher.webp` and `ic_launcher_round.webp`.

Requirements:
  pip install pillow
"""

import argparse
import os
from pathlib import Path

try:
    from PIL import Image
except ImportError as exc:
    raise SystemExit(
        "Pillow is required to run this script. Install it with `pip install pillow`."
    ) from exc

# The icon sizes for launcher icons (standard Android density buckets)
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

ICON_FILENAMES = ["ic_launcher.webp", "ic_launcher_round.webp"]


def remove_white_background(img: Image.Image) -> Image.Image:
    """Remove white/near-white backgrounds from the image, making them transparent."""
    img = img.convert("RGBA")
    data = img.getdata()
    new_data = []
    for item in data:
        # If pixel is close to white (all RGB > 240), make it transparent
        if item[0] > 240 and item[1] > 240 and item[2] > 240:
            new_data.append((255, 255, 255, 0))  # Transparent
        else:
            new_data.append(item)
    img.putdata(new_data)
    return img


def crop_to_content(img: Image.Image) -> Image.Image:
    """Crop the image to the bounding box of non-transparent pixels."""
    img = img.convert("RGBA")
    bbox = img.getbbox()  # Bounding box of non-transparent pixels
    if bbox:
        return img.crop(bbox)
    return img  # If no bbox, return as is


def resize_and_save(img: Image.Image, dest_path: Path, size: int):
    """Resize the source image and save it as a WebP file."""
    target = remove_white_background(img)
    target = crop_to_content(target)
    target = target.resize((size, size), Image.LANCZOS)
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    target.save(dest_path, format="WEBP", quality=100)


def main():
    parser = argparse.ArgumentParser(description="Generate Android launcher icons.")
    parser.add_argument(
        "source",
        type=str,
        help="Path to the source PNG/JPG/SVG (raster) image to use as the app icon.",
    )
    parser.add_argument(
        "--res-dir",
        type=str,
        default="app/src/main/res",
        help="Android res directory (default: app/src/main/res).",
    )

    args = parser.parse_args()

    src_path = Path(args.source)
    if not src_path.exists():
        raise SystemExit(f"Source icon not found: {src_path}")

    res_dir = Path(args.res_dir)
    if not res_dir.exists():
        raise SystemExit(f"Resource directory not found: {res_dir}")

    try:
        src_img = Image.open(src_path)
    except Exception as e:
        raise SystemExit(f"Failed to open source image: {e}")

    for density, size in DENSITIES.items():
        mipmap_dir = res_dir / f"mipmap-{density}"
        if not mipmap_dir.exists():
            print(f"Warning: {mipmap_dir} does not exist, creating it.")
            mipmap_dir.mkdir(parents=True, exist_ok=True)

        for icon_filename in ICON_FILENAMES:
            dest = mipmap_dir / icon_filename
            resize_and_save(src_img, dest, size)
            print(f"Wrote {dest} ({size}x{size})")

    print("\nDone. Rebuild your Android app to see the new launcher icon.")


if __name__ == "__main__":
    main()
