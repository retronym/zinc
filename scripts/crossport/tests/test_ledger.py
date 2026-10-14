import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import analyze


def unit(source, repo, pr, date, part=None):
    return {"source": source, "repo": repo, "pr": pr, "part": part, "date": date, "shas": ["abcdef0123456789"], "refs": [], "subjects": set(), "title": "t",
            "id": f"{source}:{pr}" + (f"/{part[0]}" if part else "")}


def fixture():
    return {
        ("zinc-develop", 1601): unit("zinc-develop", "sbt/zinc", 1601, "2025-09-27"),
        ("scala2", 11137): unit("scala2", "scala/scala", 11137, "2025-09-26"),
        ("scala3", 26642): unit("scala3", "scala/scala3", 26642, "2026-07-29"),
    }


class LedgerLinks(unittest.TestCase):
    def test_ported_ref_merges_separate_groups_and_makes_an_edge(self):
        units = fixture()
        ledger = {"zinc-develop#1601": {"scala2": {"state": "ported", "ref": "scala/scala#11137"}}}
        links = list(analyze.ledger_links(units, ledger))
        groups = analyze.merge_groups([[k] for k in units], links)
        self.assertEqual(sorted(len(g) for g in groups), [1, 2])
        merged = next(g for g in groups if len(g) == 2)
        edges = analyze.chain_edges(merged, units, links)
        self.assertEqual(edges, [{"from": "scala2:11137", "to": "zinc-develop:1601", "kind": "ledger"}])

    def test_ref_to_a_pr_not_in_the_data_changes_nothing(self):
        units = fixture()
        ledger = {"zinc-develop#1601": {"scala2": {"state": "ported", "ref": "scala/scala#99999"}}}
        self.assertEqual(list(analyze.ledger_links(units, ledger)), [])

    def test_only_ported_states_create_links(self):
        units = fixture()
        ledger = {"zinc-develop#1601": {"scala2": {"state": "triage", "ref": "scala/scala#11137"}}}
        self.assertEqual(list(analyze.ledger_links(units, ledger)), [])

    def test_ref_prefers_the_unit_on_the_ported_line(self):
        units = fixture()
        units[("zinc-1.12.x", 1601)] = unit("zinc-1.12.x", "sbt/zinc", 1601, "2025-09-27")
        got = analyze.resolve_ref("sbt/zinc#1601", units, "zinc-1.12.x")
        self.assertEqual(got, [("zinc-1.12.x", 1601)])

    def test_split_pr_part_keys_resolve(self):
        units = {("scala2", 11287, 1): unit("scala2", "scala/scala", 11287, "2026-10-12", part=(2, 6))}
        self.assertEqual(analyze.unit_key("scala2#11287/2", units), ("scala2", 11287, 1))
        self.assertIsNone(analyze.unit_key("scala2#11287/9", units))


class Verdicts(unittest.TestCase):
    def test_verdict_keyed_by_any_unit_of_a_merged_group_is_found(self):
        units = fixture()
        ledger = {"scala3#26642": {"zinc-1.12.x": {"state": "ported", "ref": "sbt/zinc#1602"}}}
        self.assertEqual(analyze.group_verdicts(list(units), units, ledger)["zinc-1.12.x"]["ref"], "sbt/zinc#1602")

    def test_tracking_and_ref_are_kept_in_the_status(self):
        st = analyze.ledger_status({"state": "pending", "reason": "no hasInline", "tracking": "retronym/scala#135"})
        self.assertEqual((st["state"], st["tracking"], st["ref"], st["ledger"]), ("pending", "retronym/scala#135", None, True))


if __name__ == "__main__":
    unittest.main()
