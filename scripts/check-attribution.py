#!/usr/bin/env python3
"""Verify no AI co-authors, AI contributors, or outside attribution strings appear in commits or files."""
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Check files (excluding .git, build outputs, caches, and .github/scripts policy docs)
OUTSIDE_NAMES = re.compile(r"(?:cl[a]ude|anthr[o]pic)", re.IGNORECASE)
COAUTHOR_AI = re.compile(
    r"^(?:co-authored-by|authored-by|signed-off-by)\s*:.*?\b("
    r"codex|antigravity|anti-gravity|copilot|cursor|chatgpt|openai|gemini|ai\b|bot\b|cl[a]ude|anthr[o]pic"
    r")\b",
    re.IGNORECASE | re.MULTILINE,
)
GENERATED_AI = re.compile(
    r"(?:generated|authored|written|created|assisted)\s+(?:with|by)\s+(?:"
    r"codex|antigravity|anti-gravity|copilot|cursor|chatgpt|openai|gemini|cl[a]ude|anthr[o]pic"
    r")\b",
    re.IGNORECASE,
)

SKIP_DIRS = {".git", ".github", ".gradle", ".native-cache", ".ffmpeg-cache", ".tools-cache", "build", "out", "node_modules"}


def check_files():
    errors = []
    for root, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            p = Path(root) / name
            if p == Path(__file__).resolve():
                continue
            try:
                data = p.read_bytes()
            except OSError:
                continue
            if b"\x00" in data[:4096]:
                continue
            text = data.decode("utf-8", errors="ignore")
            rel = p.relative_to(ROOT)
            if OUTSIDE_NAMES.search(text):
                errors.append(f"{rel}: contains forbidden outside AI name")
            if p.name != "AGENTS.md" and (COAUTHOR_AI.search(text) or GENERATED_AI.search(text)):
                errors.append(f"{rel}: contains forbidden AI attribution or co-author line")
    return errors


def check_commits():
    errors = []
    base = os.environ.get("GITHUB_BASE_REF")
    rev_range = f"origin/{base}..HEAD" if base else None
    if not rev_range:
        # Check the unpushed or latest commits safely without failing on historical tags unless checked explicitly.
        rev_range = "origin/main..HEAD"
    try:
        res = subprocess.run(
            ["git", "-C", str(ROOT), "log", rev_range, "--format=%H%x00%an <%ae>%n%B%x1e"],
            capture_output=True, text=True, check=False,
        )
    except OSError:
        return errors
    if res.returncode != 0 or not res.stdout.strip():
        return errors
    for rec in res.stdout.split("\x1e"):
        rec = rec.strip()
        if not rec or "\x00" not in rec:
            continue
        sha, body = rec.split("\x00", 1)
        if COAUTHOR_AI.search(body) or GENERATED_AI.search(body) or OUTSIDE_NAMES.search(body):
            errors.append(f"commit {sha[:8]}: contains forbidden AI co-author or attribution")
    return errors


def main():
    errors = check_files() + check_commits()
    if errors:
        for e in errors:
            print(f"Attribution check failed: {e}", file=sys.stderr)
        sys.exit(1)
    print("Attribution check passed (human authorship only).")


if __name__ == "__main__":
    main()
