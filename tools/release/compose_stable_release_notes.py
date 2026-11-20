"""Validate stable, approved notes and compose their first published body."""

import re
import sys
from pathlib import Path

LIMIT = 32_768
SOURCE = b"<!-- cinematic-notes-source: "
MARKER = b"<!-- cinematic-internal-release:"
VERIFICATION = "\n## APK の検証情報\n\n".encode("utf-8")


def approved_prose(raw, tag, commit):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("approved notes are for stable tags only")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        raise ValueError("invalid notes source commit")
    if not raw or len(raw) > LIMIT:
        raise ValueError("missing or oversized stable release notes")
    binding, separator, prose = raw.partition(b"\n")
    if not separator or binding != SOURCE + commit.lower().encode("ascii") + b" -->":
        raise ValueError("stable release notes not bound to selected APK source")
    if not prose.endswith(b"\n") or any(c < 32 and c not in (9, 10) for c in prose):
        raise ValueError("stable release notes must be UTF-8 text with LF line endings")
    text = prose.decode("utf-8")
    heading = f"# Cinematic Camera {tag}\n\n"
    if not text.startswith(heading) or not text[len(heading):].strip():
        raise ValueError("stable release notes need a matching title and nonempty prose")
    if any(token in text.casefold() for token in (
        "<!--", "-->", "cinematic-internal-release", "cinematic-notes-source",
    )):
        raise ValueError("stable release notes contain reserved metadata text")
    return prose


def compose(raw, tag, commit, generated):
    prose = approved_prose(raw, tag, commit)
    if generated.count(MARKER) != 1:
        raise ValueError("generated verification block needs exactly one marker")
    return prose + VERIFICATION + generated


def main():
    tag, commit, *generated = sys.argv[1:]
    raw = sys.stdin.buffer.read(LIMIT + 1)
    try:
        if generated:
            sys.stdout.buffer.write(compose(raw, tag, commit, Path(generated[0]).read_bytes()))
        else:
            approved_prose(raw, tag, commit)
    except (OSError, ValueError) as exc:
        raise SystemExit(f"Release notes rejected: {exc}") from exc


if __name__ == "__main__":
    main()
