# Bridge cross-port gap report

WIP. Reports which fixes to the compiler bridges have not yet been ported between Scala 2.13 (`scala/scala` 2.13.x), Scala 3 (`scala/scala3` main), and Zinc (`develop` and `1.12.x`).

```bash
python3 scripts/crossport/fetch.py      # gh api; cached per commit under ~/.cache/crossport
python3 scripts/crossport/analyze.py    # group commits into fixes, compute gaps -> out/report.json, out/report.md
python3 scripts/crossport/render.py --since 2023-06-01   # out/report.html; --artifact also writes a publishable page fragment
```

- `fetch.py` lists commits touching the bridge paths of each line and caches commit detail and PR metadata, so reruns only fetch new commits.
- `analyze.py` groups commits and PRs into one fix by PR number, title, shared commit subject, and explicit `#N` or `repo#N` citations, then marks each line `has`, `pending`, `triage`, `probably-n/a` or `n/a`. A PR that bundles several fixes (such as scala/scala#11287) is split per cited fix.
- Verdicts are local. `verdicts.json` (seeded from review on 2026-10-14; state is one of pending, ported, triage, wontfix) maps a fix key to per-line states, for ports the script cannot infer, and overrides the computed state. Nothing is written to the upstream repositories.
- `render.py` writes a self-contained page with one lane per line, port-direction arrows, and links and hover details for every PR and commit.
