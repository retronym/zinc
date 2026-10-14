# Cross-port gap report: plan

## Problem

The report decides per fix and per line whether a port exists, is missing, or does not apply. It does this from commit metadata alone (PR numbers, titles, citations). That misses ports made independently or under another title, and it says nothing about fixes outside the bridge paths. Reviews of the first reports found false positives, one confirmed real gap, and a blind spot (zinc-core). The refinements below each fix one observed miss.

## Constraints

- Each step is small, keeps the report generating, and is committed on its own.
- Each step that changes classification comes with unit tests that encode the miss it fixes, using the real titles and shapes seen so far as fixtures.
- Verdicts stay local (`verdicts.json`). Nothing is written to the upstream repositories.

## Groundwork

TODO. `analyze.py` is one `main()` plus helpers that read files. Tests need the classification and linking logic callable on plain data. Split into pure functions over unit dicts (`classify`, `link`, `status_for_line`) and keep IO in `main()`. Tests use the stdlib `unittest` under `scripts/crossport/tests/`, so there is nothing to install: `python3 -m unittest discover scripts/crossport/tests`.

## Steps

Suggested order is cheapest and most certain first.

### 4. Extend the mechanical regex

TODO. Add `tailrec`, `lint`, `-Xlint` and `warn` to `MECHANICAL`, covering zinc#1209 and scala/scala#8806. Test: both titles classify as mechanical, and a behaviour fix whose title contains none of these words does not.

### 7. Ledger first

PARTLY DONE. `verdicts.json` is read and overrides computed states, and unknown keys are reported. Remaining: have the report show only rows with no verdict by default (`--all` to see the rest), so each rerun surfaces only new rows. Test: a fix whose lines all have verdicts is hidden by default and shown with `--all`; a new fix is shown.

### 1. Fuzzy subject match

TODO. `shared_subject` needs exactly equal normalised subjects, which missed zinc#1288 against scala/scala#10617 (an `Incremental compilation:` prefix). Strip a leading `<words>:` prefix, then compare token sets with Jaccard of at least 0.8 and at least 4 shared tokens. Tests: the #1288/#10617 pair links; two unrelated subjects that share a few common words do not; generic short subjects such as "Apply Scalafmt format" still do not.

### 3. Disabled-test check

TODO. Flag a fix whose scripted test directory holds `disabled` or `pending` in place of `test`, since no CI verifies it (zinc#1310). Needs the test file listing per fix, which `fetch.py` already has for added files. Test: a fix touching `source-dependencies/inline` with a `disabled` file is flagged; one with `test` is not.

### 5. Customer lens

TODO. `--lines scala2,zinc-1.12.x` on `analyze.py` and `render.py`, restricting lanes, rows (drop rows with no unit on a chosen line) and counts. Test: with two lanes selected, Scala 3-only rows drop out and tile counts change accordingly.

### 2. Source-diff ground truth

TODO. Use `srcdiff.py` to check the Scala 2 bridge pair against the source, not only the history. Mark a scala2 `pending` as ported when the files the fix touched show no hunk containing the fix's added lines; confirm a `pending` when they do. This would have cleared the five false positives found by hand and confirmed zinc#1310. Needs local checkouts, so it stays an opt-in step with paths passed in, and its result is stored in the report as evidence next to the state. Test: refactor `srcdiff.py` into a function over two text blobs, then test it on small before/after snippets, including a renamed-but-equivalent line and a missing `hasInline` check.

### 6. Cover zinc-core

TODO. A `zinc-core` lane for `internal/zinc-core`, `internal/zinc-apiinfo` and `internal/zinc-classfile`, comparing only develop with 1.12.x, so core fixes such as #1841, #1843, #1845 and #1846 appear. Needs a separate source entry in `fetch.py` and a rule that these fixes have no Scala 2 or Scala 3 counterpart. Test: a core-only fix present on develop and missing on 1.12.x is pending on 1.12.x and `n/a` on the Scala lanes.

## Decisions to make

- Whether step 2 is worth the checkout requirement, or the ledger should carry the source-diff evidence by hand.
- Whether the zinc-core lane belongs in the same report or its own.
