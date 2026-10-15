import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import fetch_ci

HEREDOC_STYLE = """
      - name: Skip known failures
        run: |
          while IFS='|' read -r scenario reason; do
            rm -r "zinc/src/sbt-test/$scenario"
          done <<'EOF'
          source-dependencies/module-inheritance-extra-hash | Scala 3 bridge gap (zinc#1807): D is not recompiled
          source-dependencies/struct | Scala 2 only scenario: structural type
          EOF

      - name: Run zinc scripted tests
"""

COMMENT_STYLE = """
      - name: Skip known failures
        run: |
          # source-dependencies/compactify, compactify-nested: use -Xmax-classfile-name, which only exists in 2.12.
          # source-dependencies/module-inheritance-extra-hash: scala2-sbt-bridge doesn't report `object B` as a term
          #   (bridge side of sbt/zinc#1807). Delete from this list once ported.
          for t in compactify compactify-nested module-inheritance-extra-hash; do
            rm -r zinc/src/sbt-test/source-dependencies/$t
          done

      - name: Run zinc scripted tests
"""


class ParseSkips(unittest.TestCase):
    def test_scenario_and_reason_lines(self):
        got = fetch_ci.parse_skips(HEREDOC_STYLE)
        self.assertEqual([k["scenario"] for k in got], ["source-dependencies/module-inheritance-extra-hash", "source-dependencies/struct"])
        self.assertTrue(got[0]["reason"].startswith("Scala 3 bridge gap (zinc#1807)"))

    def test_comment_style_expands_shared_group_and_joins_continuations(self):
        got = {k["scenario"]: k["reason"] for k in fetch_ci.parse_skips(COMMENT_STYLE)}
        self.assertEqual(sorted(got), ["source-dependencies/compactify", "source-dependencies/compactify-nested", "source-dependencies/module-inheritance-extra-hash"])
        self.assertIn("only exists in 2.12", got["source-dependencies/compactify-nested"])
        self.assertIn("bridge side of sbt/zinc#1807", got["source-dependencies/module-inheritance-extra-hash"])

    def test_workflow_without_a_skip_step_has_no_skips(self):
        self.assertEqual(fetch_ci.parse_skips("name: x\njobs: {}\n"), [])


class Categories(unittest.TestCase):
    def test_reasons_map_to_categories(self):
        c = fetch_ci.categorise
        self.assertEqual(c("Scala 3 bridge gap: dependency is not recorded"), "gap")
        self.assertEqual(c("scala2-sbt-bridge doesn't report `object B` as a term"), "gap")
        self.assertEqual(c("Not triaged: `run` finds no main class"), "triage")
        self.assertEqual(c("Pending in Zinc, passes on Scala 3"), "pending-in-zinc")
        self.assertEqual(c("Scala 2 only scenario: `with` in a type"), "label")


if __name__ == "__main__":
    unittest.main()
