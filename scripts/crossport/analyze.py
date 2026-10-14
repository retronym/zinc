#!/usr/bin/env python3
"""Group bridge commits into logical fixes (chains) and compute, per fix, which lines still lack it.

Reads out/commits.json (fetch.py) and verdicts.json (local ledger, never published upstream).
Writes out/report.json and out/report.md.

usage: analyze.py [--since YYYY-MM-DD] [--out DIR] [--verdicts FILE]
"""
import argparse, json, re
from pathlib import Path

LINES = ["scala2", "scala3", "zinc-develop", "zinc-1.12.x"]
REPO_LINE = {"scala/scala": "scala2", "scala/scala3": "scala3"}
SEMANTIC = re.compile(r"/(ExtractAPI|ExtractUsedNames|ExtractDependencies|Dependency|ClassName|API|LocalToNonLocalClass|GlobalHelpers|CallbackGlobal|ThunkHolder|APIUtils|ShowAPI)\.scala$")
TEST = re.compile(r"(/test/|/src/test|compiler-bridge-test|sbt-test/)")
OLD_SCALA_DIR = re.compile(r"/(scala_2\.10|scala-2\.11|scala-2\.12|scala_2\.11-12)/")
SCALA3_ONLY = re.compile(r"(ConsoleInterface|CompilerBridgeDriver|Fallback\w+File|PositionBridge|ProgressCallback|OldIncrementalCallback|CachedCompilerImpl|DottydocRunner)")
MECHANICAL = re.compile(r"bump|update scala|scalafmt|reformat|typo|copyright|header|unsafe ?null|\.nn\b|redundant|warning|syntax|rename|encapsulat|dead code|NoAbstractFile|deprecat|chaining|migrate|uninitialized|source ?file content|catching|io package|encapsulation|capabilit|nullable|flexible|scaladoc|bug url", re.I)


def norm_title(s):
    return re.sub(r"^\[[^\]]*\]\s*|\s*\(#\d+\)$", "", s).strip().lower()


def norm_subject(s):
    s = re.sub(r"\s*\((?:sbt/zinc|scala/scala3?)?[#@][^)]*\)\s*$", "", s)
    s = re.sub(r"^\[[^\]]*\]\s*|\s*\(#\d+\)$", "", s).lower()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def shared_subject(u1, u2):
    return any(len(x) > 25 and len(x.split()) >= 4 for x in u1["subjects"] & u2["subjects"])


def refs_in(text, own_repo):
    out = set()
    for m in re.finditer(r"(?:(sbt/zinc|scala/scala3?|zinc|scala3|scala)\s?)?#(\d+)", text):
        q = m.group(1)
        repo = {"zinc": "sbt/zinc", "scala3": "scala/scala3", "scala": "scala/scala"}.get(q, q) or own_repo
        out.add((repo, int(m.group(2))))
    return out


SUBJECT_REF = re.compile(r"#\d+|@[0-9a-f]{7,40}")


def classify(title, paths):
    non_test = {p: n for p, n in paths.items() if not TEST.search(p)}
    sem = {p: n for p, n in non_test.items() if SEMANTIC.search(p)}
    changed = sum(sem.values())
    if sem:
        incidental = len(paths) > 12 and changed * 10 < sum(non_test.values())
        kind = "mechanical" if MECHANICAL.search(title) or incidental else "semantic"
    elif non_test:
        kind = "plumbing"
    else:
        kind = "test"
    return kind, sorted(p.split("/")[-1] for p in sem)


def build_open_units(open_prs):
    out = {}
    for p in open_prs:
        files = {f["path"]: f["additions"] + f["deletions"] for f in p["files"]}
        kind, sem = classify(p["title"], files)
        text = p["title"] + "\n" + p["body"]
        u = {"source": p["source"], "repo": p["repo"], "pr": p["number"], "part": None, "title": p["title"], "date": p["updated"],
             "files": files, "text": text, "shas": [], "url": p["url"], "kind": kind, "semantic_files": sem,
             "subjects": {norm_subject(p["title"])}, "authors": {p["author"]}, "open": True, "draft": p["draft"],
             "id": f"{p['source']}:{p['repo']}#{p['number']}"}
        u["refs"] = sorted(r for r in refs_in(text, p["repo"]) if not (r[0] == p["repo"] and r[1] == p["number"]))
        out[(p["source"], "open", p["repo"], p["number"])] = u
    return out


def presence(us, line):
    merged = [u for u in us if u["source"] == line and not u.get("open")]
    if merged:
        return "has", merged
    opens = [u for u in us if u["source"] == line and u.get("open")]
    return ("in-review", opens) if opens else (None, [])


def segment(cs, num):
    marks = [bool([m for m in SUBJECT_REF.findall(c["message"].split("\n")[0]) if m != f"#{num}"]) for c in cs]
    if sum(marks) < 2:
        return [cs]
    segs = []
    for c, m in zip(cs, marks):
        if m or not segs:
            segs.append([c])
        else:
            segs[-1].append(c)
    return segs


def build_units(commits):
    by = {}
    groups = {}
    for c in commits:
        pr = c["prs"][0] if c["prs"] else None
        groups.setdefault((c["source"], pr["number"] if pr else c["sha"][:10]), []).append(c)
    for (source, num), cs in groups.items():
        pr = cs[0]["prs"][0] if cs[0]["prs"] else None
        segs = segment(cs, num) if pr else [cs]
        for i, seg in enumerate(segs):
            part = (i + 1, len(segs)) if len(segs) > 1 else None
            key = (source, num, i) if part else (source, num)
            u = by.setdefault(key, {"source": source, "repo": cs[0]["repo"], "pr": num if pr else None, "part": part,
                                     "title": seg[0]["message"].split("\n")[0] if part else (pr["title"] if pr else seg[0]["message"].split("\n")[0]),
                                     "date": seg[0]["date"], "files": {}, "text": "", "shas": [], "url": pr["url"] if pr else None})
            u.setdefault("subjects", set())
            u.setdefault("authors", set())
            for c in seg:
                u["authors"].add(c["author"])
                u["subjects"].add(norm_subject(c["message"].split("\n")[0]))
                u["date"] = max(u["date"], c["date"])
                u["text"] += "\n" + c["message"]
                u["shas"].append(c["sha"])
                for f in c["files"]:
                    u["files"][f["path"]] = u["files"].get(f["path"], 0) + f["additions"] + f["deletions"]
            if pr and not part:
                u["text"] += "\n" + pr["body"]
    sha_owner = {sha: u for u in by.values() for sha in u["shas"]}
    for u in by.values():
        u["kind"], u["semantic_files"] = classify(u["title"], u["files"])
        refs = refs_in(u["title"] + "\n" + u["text"], u["repo"])
        for m in re.finditer(r"@([0-9a-f]{7,40})", u["text"]):
            for sha, o in sha_owner.items():
                if sha.startswith(m.group(1)) and o is not u and o["pr"]:
                    refs.add((o["repo"], o["pr"]))
        u["refs"] = sorted(r for r in refs if not (r[0] == u["repo"] and r[1] == u["pr"]))
        u["id"] = f"{u['source']}:{u['pr'] or u['shas'][0][:10]}" + (f"/{u['part'][0]}" if u["part"] else "")
    return by


def chains(units):
    parent = {k: k for k in units}

    def find(k):
        while parent[k] != k:
            parent[k] = parent[parent[k]]
            k = parent[k]
        return k

    def union(a, b):
        parent[find(a)] = find(b)

    index = {}
    for k, u in units.items():
        if u["pr"] and not u["part"]:
            index.setdefault((u["repo"], u["pr"]), []).append(k)
    for ks in index.values():
        for k in ks[1:]:
            union(ks[0], k)
    titles = {}
    for k, u in units.items():
        if u["repo"] == "sbt/zinc":
            titles.setdefault(norm_title(u["title"]), []).append(k)
    for ks in titles.values():
        for k in ks[1:]:
            union(ks[0], k)
    byprnum = {}
    for k, u in units.items():
        if u["pr"]:
            byprnum.setdefault((u["repo"], u["pr"]), []).append(k)
    for k, u in units.items():
        for r in u["refs"]:
            for t in byprnum.get(r, []):
                if units[t]["part"]:
                    continue
                if units[t]["source"] != u["source"] or units[t]["repo"] != u["repo"]:
                    union(k, t)
                elif u["repo"] == "sbt/zinc":
                    union(k, t)
    keys = list(units)
    for i, k1 in enumerate(keys):
        for k2 in keys[i + 1:]:
            if units[k1]["source"] != units[k2]["source"] and shared_subject(units[k1], units[k2]):
                union(k1, k2)
    groups = {}
    for k in units:
        groups.setdefault(find(k), []).append(k)
    return list(groups.values())


def unit_key_str(u):
    return f"{u['source']}#{u['pr'] or u['shas'][0][:10]}" + (f"/{u['part'][0]}" if u["part"] else "")


def unit_key(key, units):
    m = re.fullmatch(r"([\w.\-]+)#(\w+)(?:/(\d+))?", key)
    if not m:
        return None
    cand = (m.group(1), int(m.group(2)) if m.group(2).isdigit() else m.group(2))
    if m.group(3):
        cand += (int(m.group(3)) - 1,)
    return cand if cand in units else None


def resolve_ref(ref, units, line=None):
    m = re.fullmatch(r"([\w.\-]+/[\w.\-]+)#(\d+)", ref or "")
    if not m:
        return []
    ks = [k for k, u in units.items() if u["repo"] == m.group(1) and u["pr"] == int(m.group(2))]
    return [k for k in ks if units[k]["source"] == line] or ks


def ledger_links(units, ledger):
    for key, lines in ledger.items():
        k = unit_key(key, units)
        if not k:
            continue
        for line, v in lines.items():
            if v.get("state") == "ported":
                for t in resolve_ref(v.get("ref"), units, line):
                    if t != k:
                        yield k, t


def merge_groups(groups, links):
    where = {k: i for i, g in enumerate(groups) for k in g}
    parent = list(range(len(groups)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for a, b in links:
        parent[find(where[a])] = find(where[b])
    merged = {}
    for i, g in enumerate(groups):
        merged.setdefault(find(i), []).extend(g)
    return list(merged.values())


def ledger_status(v):
    return {"state": v["state"], "reason": v.get("reason", ""), "ref": v.get("ref"), "tracking": v.get("tracking"), "ledger": True}


def group_verdicts(g, units, ledger):
    out = {}
    for k in sorted(g, key=lambda k: units[k]["date"]):
        for line, v in ledger.get(unit_key_str(units[k]), {}).items():
            out[line] = v
    return out


def chain_edges(g, units, extra=()):
    ids = {k: units[k]["id"] for k in g}
    out = {}
    for k1, k2 in extra:
        if k1 in ids and k2 in ids:
            a, b = sorted([k1, k2], key=lambda k: (units[k]["date"], LINES.index(units[k]["source"]) if units[k]["source"].startswith("zinc") else 9))
            out[(ids[a], ids[b])] = "ledger"
    for i, k1 in enumerate(g):
        for k2 in g[i + 1:]:
            u1, u2 = units[k1], units[k2]
            if u1["source"] == u2["source"]:
                continue
            kind = None
            if u1["repo"] == u2["repo"] and u1["pr"] and u1["pr"] == u2["pr"] and not (u1["part"] or u2["part"]):
                kind = "same PR"
            elif u1["repo"] == "sbt/zinc" == u2["repo"] and norm_title(u1["title"]) == norm_title(u2["title"]):
                kind = "same title"
            elif (not u2["part"] and (u2["repo"], u2["pr"]) in [tuple(r) for r in u1["refs"]]) or (not u1["part"] and (u1["repo"], u1["pr"]) in [tuple(r) for r in u2["refs"]]):
                kind = "cited"
            elif shared_subject(u1, u2):
                kind = "same subject"
            if not kind:
                continue
            a, b = sorted([k1, k2], key=lambda k: (units[k]["date"], LINES.index(units[k]["source"]) if units[k]["source"].startswith("zinc") else 9))
            out.setdefault((ids[a], ids[b]), kind)
    return [{"from": f, "to": t, "kind": k} for (f, t), k in out.items()]


SCALA2_BRIDGE_MOVED = "2023-07-12"


def exclusions(group_units, target):
    why = []
    if target == "scala2" and max(u["date"] for u in group_units) < SCALA2_BRIDGE_MOVED:
        why.append("predates the bridge move to scala/scala (2023-07)")
    paths = [p for u in group_units for p in u["files"]]
    if target in ("scala2", "scala3") and paths and all(OLD_SCALA_DIR.search(p) for p in paths if SEMANTIC.search(p) and not TEST.search(p)) and any(SEMANTIC.search(p) for p in paths):
        why.append("only old-Scala-version sources")
    if any(SCALA3_ONLY.search(p) for p in paths) and target != "scala3":
        why.append("Scala 3 specific")
    return why


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="2025-01-01")
    ap.add_argument("--out", default="scripts/crossport/out")
    ap.add_argument("--verdicts", default="scripts/crossport/verdicts.json")
    a = ap.parse_args()
    out = Path(a.out)
    units = build_units(json.load(open(out / "commits.json")))
    if (out / "open_prs.json").exists():
        units.update(build_open_units(json.load(open(out / "open_prs.json"))))
    ledger = json.load(open(a.verdicts)) if Path(a.verdicts).exists() else {}
    report = []
    links = list(ledger_links(units, ledger))
    for g in merge_groups(chains(units), links):
        us = [units[k] for k in g]
        present = {u["source"] for u in us if not u.get("open")}
        kinds = {u["kind"] for u in us}
        kind = "semantic" if "semantic" in kinds else "mechanical" if "mechanical" in kinds else "plumbing" if "plumbing" in kinds else "test"
        lead = min(us, key=lambda u: u["date"])
        key = unit_key_str(lead)
        latest = max(u["date"] for u in us)
        status = {}
        verdicts = group_verdicts(g, units, ledger)
        for line in LINES:
            state, there = presence(us, line)
            if state == "has":
                status[line] = {"state": "has", "refs": [u["pr"] for u in there]}
            elif state == "in-review":
                status[line] = {"state": "in-review", "prs": [{"repo": u["repo"], "pr": u["pr"], "url": u["url"], "draft": u["draft"], "title": u["title"], "author": sorted(u["authors"])[0], "updated": u["date"][:10]} for u in there]}
            elif kind in ("test", "plumbing"):
                status[line] = {"state": "n/a", "reason": f"{kind} only"}
            else:
                ex = exclusions(us, line)
                if ex:
                    status[line] = {"state": "n/a", "reason": "; ".join(ex)}
                elif kind == "mechanical":
                    status[line] = {"state": "probably-n/a", "reason": "mechanical change"}
                elif line == "scala3" or (line.startswith("zinc") and not any(p.startswith("zinc") for p in present)):
                    status[line] = {"state": "triage", "reason": "separate implementation; check whether the symptom applies"}
                elif line.startswith("zinc"):
                    status[line] = {"state": "pending", "reason": "missing on this zinc line"}
                else:
                    status[line] = {"state": "pending" if present & {"zinc-develop", "zinc-1.12.x"} else "triage", "reason": "no linked change found"}
            v = verdicts.get(line)
            if v:
                status[line] = ledger_status(v)
        report.append({"key": key, "title": lead["title"], "kind": kind, "latest": latest, "first": lead["date"], "lines": status,
                       "edges": chain_edges(g, units, links), "units": [{"id": u["id"], "part": u["part"], "repo": u["repo"], "state": ("draft" if u["draft"] else "open") if u.get("open") else "merged", "shas": u["shas"], "authors": sorted(u["authors"]), "refs": u["refs"], "source": u["source"], "pr": u["pr"], "title": u["title"], "date": u["date"][:10], "url": u["url"], "files": u["semantic_files"]} for u in sorted(us, key=lambda u: u["date"])]})
    unmatched = sorted(set(ledger) - {unit_key_str(u) for u in units.values()})
    if unmatched:
        print("ledger keys matching no fix:", ", ".join(unmatched))
    report.sort(key=lambda r: r["latest"], reverse=True)
    (out / "report.json").write_text(json.dumps(report, indent=1))
    recent = [r for r in report if r["latest"][:10] >= a.since]
    sym = {"has": "✓", "pending": "**pending**", "triage": "triage", "probably-n/a": "n/a?", "n/a": "n/a", "ported": "ported", "wontfix": "n/a", "in-review": "open PR"}
    row = lambda r: f"| {r['first'][:10]} {r['title'][:75].replace('|', '/')} (`{r['key']}`) | " + " | ".join(sym.get(r['lines'][l]['state'], r['lines'][l]['state']) for l in LINES) + " |"
    head = ["| fix | " + " | ".join(LINES) + " |", "|---|" + "---|" * len(LINES)]
    md = [f"# Bridge cross-port gaps (chains with activity since {a.since})", "", "## Behaviour changes", ""] + head
    md += [row(r) for r in recent if r["kind"] == "semantic"]
    md += ["", "## Touch the extraction code but look mechanical (confirm)", ""] + head + [row(r) for r in recent if r["kind"] == "mechanical"]
    (out / "report.md").write_text("\n".join(md) + "\n")
    from collections import Counter
    print(Counter(r["kind"] for r in report), Counter(s["state"] for r in recent for s in r["lines"].values()))


if __name__ == "__main__":
    main()
