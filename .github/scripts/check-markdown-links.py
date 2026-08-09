#!/usr/bin/env python3
"""Validate relative Markdown links and GitHub wiki links without dependencies."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote


MARKDOWN_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+['\"][^)]*['\"])?\)")
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


def resolve_target(source: Path, raw_target: str, wiki_root: Path | None) -> tuple[Path, str]:
    target, _, anchor = unquote(raw_target).partition("#")
    if not target:
        return source, anchor

    candidate = source.parent / target
    if candidate.exists():
        return candidate, anchor

    if wiki_root and source.is_relative_to(wiki_root) and not Path(target).suffix:
        candidate = source.parent / f"{target}.md"
    return candidate, anchor


def main() -> int:
    files = markdown_files(sys.argv[1:])
    wiki_root = next((Path(arg) for arg in sys.argv[1:] if Path(arg).is_dir()), None)
    failures: list[str] = []

    for source in files:
        text = without_fenced_code(source.read_text(encoding="utf-8"))
        links = [match.group(1).strip("<>") for match in MARKDOWN_LINK.finditer(text)]

        for match in WIKI_LINK.finditer(text):
            target = match.group(1).split("|", 1)[-1].strip().replace(" ", "-")
            links.append(target)

        for link in links:
            if link.startswith(EXTERNAL_SCHEMES):
                continue

            target, anchor = resolve_target(source, link, wiki_root)
            if not target.exists():
                failures.append(f"{source}: missing target {link}")
                continue

            if anchor and target.suffix.lower() == ".md":
                headings = {
                    github_slug(match.group(1))
                    for match in HEADING.finditer(target.read_text(encoding="utf-8"))
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
