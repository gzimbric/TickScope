#!/usr/bin/env python3
"""Validate relative Markdown links and GitHub wiki links without dependencies."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote


MARKDOWN_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+['\"][^)]*['\"])?\)")
# A badge is a link wrapped in a link: [![alt](image)](target). MARKDOWN_LINK consumes the
# inner image link and never sees the outer target, so it is matched separately.
NESTED_LINK = re.compile(r"\]\([^)\s]+\)\]\(([^)\s]+)\)")
# README.md places its images with HTML so it can size and align them.
IMG_SRC = re.compile(r"""<img[^>]*\ssrc=["']([^"']+)["']""")
WIKI_LINK = re.compile(r"\[\[([^\]]+)\]\]")
HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$", re.MULTILINE)
EXTERNAL_SCHEMES = ("http://", "https://", "mailto:", "tel:")


def github_slug(value: str) -> str:
    value = re.sub(r"<[^>]+>", "", value)
    value = re.sub(r"[`*_~]", "", value).strip().lower()
    value = re.sub(r"[^\w\- ]", "", value, flags=re.UNICODE)
    return re.sub(r"[ -]+", "-", value).strip("-")


def markdown_files(arguments: list[str]) -> list[Path]:
    files: set[Path] = set()
    for argument in arguments:
        path = Path(argument)
        if path.is_dir():
            files.update(path.rglob("*.md"))
        elif path.suffix.lower() == ".md" and path.exists():
            files.add(path)
    return sorted(files)


def without_fenced_code(text: str) -> str:
    return re.sub(r"^```.*?^```\s*$", "", text, flags=re.MULTILINE | re.DOTALL)


def resolve_target(source: Path, raw_target: str, roots: list[Path]) -> tuple[Path, str]:
    target, _, anchor = unquote(raw_target).partition("#")
    if not target:
        return source, anchor

    candidate = source.parent / target
    if candidate.exists():
        return candidate, anchor

    # GitHub wiki pages link to each other by page name, without the .md suffix. Any
    # directory passed on the command line may be a wiki clone; a docs page never links
    # without a suffix, so the fallback is harmless there.
    if any(source.is_relative_to(root) for root in roots) and not Path(target).suffix:
        candidate = source.parent / f"{target}.md"
    return candidate, anchor


def main() -> int:
    files = markdown_files(sys.argv[1:])
    roots = [Path(arg) for arg in sys.argv[1:] if Path(arg).is_dir()]
    failures: list[str] = []

    for source in files:
        text = without_fenced_code(source.read_text(encoding="utf-8"))
        links = [match.group(1).strip("<>") for match in MARKDOWN_LINK.finditer(text)]
        links += [match.group(1).strip("<>") for match in NESTED_LINK.finditer(text)]
        links += [match.group(1) for match in IMG_SRC.finditer(text)]

        for match in WIKI_LINK.finditer(text):
            target = match.group(1).split("|", 1)[-1].strip().replace(" ", "-")
            links.append(target)

        for link in links:
            if link.startswith(EXTERNAL_SCHEMES):
                continue

            target, anchor = resolve_target(source, link, roots)
            if not target.exists():
                failures.append(f"{source}: missing target {link}")
                continue

            if anchor and target.suffix.lower() == ".md":
                # A "# comment" inside a fenced example is not a heading GitHub would anchor.
                headings = {
                    github_slug(match.group(1))
                    for match in HEADING.finditer(without_fenced_code(target.read_text(encoding="utf-8")))
                }
                if anchor.lower() not in headings:
                    failures.append(f"{source}: missing anchor #{anchor} in {target}")

    if failures:
        print("Relative Markdown link failures:")
        for failure in failures:
            print(f"  BROKEN {failure}")
        return 1

    print(f"All relative links resolve across {len(files)} Markdown files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
