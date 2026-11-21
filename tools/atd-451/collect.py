"""Stage only the fixed #451 evidence from a fresh connected-test run."""
import argparse
from io import BytesIO
from pathlib import Path
from PIL import Image

STEM = "360x480-font2-"
PASS_IMAGES = (STEM + "initial.png", STEM + "after-scroll.png", STEM + "comparison.png")
FAILURE_IMAGE = STEM + "failure.png"
METRICS = STEM + "checks.txt"
EXPECTED = (b"viewportDp=360x480\nfontScale=2.0\nouterScrollReachedSave=true\n"
            b"comparisonPixels=blue-to-gray\nsaveCallbacks=1\nvisualReview=pending\n")


def collect(root, stage, failed):
    root = root.resolve(strict=True)
    names = (FAILURE_IMAGE,) if failed else (*PASS_IMAGES, METRICS)
    gathered = {}
    for name in names:
        candidates = [p for p in root.rglob(name)
                      if p.parent.name == "develop-451" and p.is_file()
                      and not p.is_symlink() and p.resolve().is_relative_to(root)]
        if failed and not candidates:
            continue  # Failure can precede fixture creation; the job remains failed.
        if len(candidates) != 1:
            raise ValueError(f"Expected exactly one candidate for {name}")
        selected = candidates[0]
        if selected.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("Oversized #451 evidence")
        data = selected.read_bytes()
        if name == METRICS:
            if data != EXPECTED:
                raise ValueError("Unexpected #451 check text")
        else:
            with Image.open(BytesIO(data), formats=("PNG",)) as image:
                if not all(320 <= d <= 4096 for d in image.size):
                    raise ValueError("Unexpected #451 image dimensions")
                clean = Image.new("RGB", image.size)
                clean.paste(image.convert("RGB"))
                out = BytesIO()
                clean.save(out, format="PNG")
                data = out.getvalue()
        gathered[name] = data
    if gathered:
        stage.mkdir(parents=True, exist_ok=False)
        for name, data in gathered.items():
            (stage / name).write_bytes(data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("stage", type=Path)
    parser.add_argument("--failed", action="store_true")
    args = parser.parse_args()
    if args.failed and not args.root.is_dir():
        parser.exit()  # Gradle may fail before producing any additional output.
    collect(args.root, args.stage, args.failed)
