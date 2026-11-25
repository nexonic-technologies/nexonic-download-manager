# Project Rules

## Authorship & Attribution Policy (Strict)

This repository enforces **human-only authorship and attribution**:

1. **No AI Co-Authors**: NEVER include any AI assistant, LLM, bot, or automated coding agent (such as Codex, Antigravity, Copilot, Cursor, Gemini, ChatGPT, or similar AI tools) in git `Co-Authored-By:` / `Co-authored-by:` trailers or commit author/committer fields.
2. **No AI Contributors or Credits**: NEVER add or mention any AI tool, model, or coding agent as a contributor, author, or code generator in source files, comments, docstrings, README, CHANGELOG, manifests, notices, or pull request descriptions.
3. **CI Enforcement**: `scripts/check-attribution.py` runs in CI to reject AI co-author trailers and forbidden AI attribution markers. Run `python3 scripts/check-attribution.py` before pushing.
