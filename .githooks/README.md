# .githooks — forbidden-token scrub gate

Activated per clone with:

```
git config core.hooksPath .githooks
```

`pre-commit` and `pre-push` (same script, dispatches on `$0`) block any
staged/outgoing change that matches a pattern in an external, untracked
pattern file (default `~/.sharaku-scrub-patterns`, override with
`SHARAKU_SCRUB_PATTERNS`). The pattern file is deliberately NOT part of any
repo — see its own header comment for why. Fails closed (blocks) if the
pattern file is missing.

This file intentionally does not reproduce any of the patterns or example
tokens — doing so here would defeat the point. See the pattern file itself
(outside this repo) for what is actually matched, and its comments for the
false-positive-mitigation reasoning (word boundaries, exact case on the
shortest tokens).

If a hook fires on a genuine false positive, `git commit --no-verify` /
`git push --no-verify` bypasses it for that one operation — treat that as a
signal to double-check the line, not just a mechanical override.

CI runs the same pattern set server-side via a masked GitHub Actions secret
(`SCRUB_PATTERNS`) — never written into a workflow YAML file — as
defense-in-depth against a bypassed local hook.
