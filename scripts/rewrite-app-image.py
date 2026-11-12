"""Replace only app.image in the existing deployment override; no YAML dependency."""
import argparse
from pathlib import Path
import re


def rewrite(text, image):
    section = re.search(r"(?ms)^  app:[ \t]*\n.*?(?=^  [\w-]+:[ \t]*\n|\Z)", text)
    if not section:
        raise ValueError("Expected a two-space-indented app service in the override")
    block, count = re.subn(r"(?m)^    image:[^\n]*$", "    image: " + image, section.group())
    if count != 1:
        raise ValueError("Expected exactly one app.image; refusing to rewrite this override")
    return text[:section.start()] + block + text[section.end():]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("override", type=Path)
    parser.add_argument("image")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9._:/-]+", args.image):
        raise SystemExit("Invalid image reference")
    args.override.write_text(rewrite(args.override.read_text(encoding="utf-8"), args.image), encoding="utf-8")
