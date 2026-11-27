#!/usr/bin/env python3
"""Collect the latest runs of the "Zinc scripted tests vs. <bridge>" CI jobs and their skip tables.

For each source this reads the newest completed run of the workflow, parses the scripted
summary from its log (cached per run id under $CROSSPORT_CACHE), and parses the
"Skip known failures" step of the workflow file at that run's commit into scenario and reason.
It also lists the scenarios Zinc itself has marked `disabled` or `pending`.
Output: <out>/ci.json.

usage: fetch_ci.py [--out DIR]
"""
import argparse, base64, json, os, re, subprocess
from datetime import datetime, timezone
from pathlib import Path

CACHE = Path(os.environ.get("CROSSPORT_CACHE", Path.home() / ".cache" / "crossport"))
SOURCES = [
    {"id": "scala2", "label": "scala/scala 2.13.x, label 2.13.y", "repo": "retronym/scala", "branch": "ci/zinc-scripted-vs-bridge",
     "workflow": "zinc-scripted.yml", "pr": "https://github.com/retronym/scala/pull/139"},
    {"id": "scala3", "label": "scala/scala3, label 3.x", "repo": "retronym/scala3", "branch": "ci/zinc-scripted-vs-scala3-bridge",
     "workflow": "zinc-scripted.yml", "pr": "https://github.com/retronym/scala3/pull/26"},
]
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]|\^\[\[[0-9;]*[A-Za-z]")


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def categorise(reason):
    if re.search(r"bridge gap|doesn.t report|not ported", reason, re.I):
        return "gap"
    if re.match(r"not triaged", reason, re.I):
        return "triage"
    if re.match(r"pending in zinc", reason, re.I):
        return "pending-in-zinc"
    return "label"


def parse_skips(workflow_text):
    step = re.search(r"- name: Skip known failures(.*?)(?=\n      - name:)", workflow_text, re.S)
    if not step:
        return []
    body, skips = step.group(1), []
    for m in re.finditer(r"^\s+((?:[\w.-]+)/[\w.-]+) \| (.+)$", body, re.M):
        skips.append({"scenario": m.group(1), "reason": m.group(2).strip()})
    if skips:
        return skips
    comments = re.findall(r"^\s*#\s?(.*)$", body, re.M)
    cur = None
    for c in comments:
        m = re.match(r"([\w.-]+)/([\w.-]+(?:, [\w.-]+)*): (.*)", c)
        if m:
            cur = {"group": m.group(1), "names": m.group(2).split(", "), "reason": m.group(3).strip()}
            skips += [{"scenario": f"{cur['group']}/{n}", "reason": cur["reason"]} for n in cur["names"]]
        elif cur and c.startswith("  "):
            for s in skips[-len(cur["names"]):]:
                s["reason"] += " " + c.strip()
    return skips


def parse_log(run_id, repo):
    p = CACHE / "ci" / f"{repo.replace('/', '_')}-{run_id}.json"
    if p.exists():
        return json.loads(p.read_text())
    text = ANSI.sub("", gh("run", "view", str(run_id), "-R", repo, "--log"))
    m = re.search(r"Testing zinc (\w+) against Scala (\S+)", text)
    res = {"zinc_sha": m.group(1) if m else None, "scala_version": m.group(2) if m else None,
           "passed": len(re.findall(r"^\S.*?\t.*?\t\S+Z \[info\] \+ ", text, re.M)) or len(re.findall(r"^\[info\] \+ ", text, re.M)),
           "pending": len(re.findall(r"Pending cause", text)),
           "failed": len(re.findall(r"\[error\] x ", text))}
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(res))
    return res


def zinc_markers():
    sha = json.loads(gh("api", "repos/sbt/zinc/branches/develop"))["commit"]["sha"]
    tree = json.loads(gh("api", f"repos/sbt/zinc/git/trees/{sha}?recursive=1"))["tree"]
    out = {"sha": sha, "disabled": [], "pending": []}
    for e in tree:
        m = re.search(r"sbt-test/([^/]+/[^/]+)/(disabled|pending)$", e["path"])
        if m:
            out[m.group(2)].append(m.group(1))
    out["disabled"].sort(), out["pending"].sort()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="scripts/crossport/out")
    a = ap.parse_args()
    result = {"fetched_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "sources": []}
    for s in SOURCES:
        runs = json.loads(gh("run", "list", "-R", s["repo"], "--workflow", s["workflow"], "-b", s["branch"], "-L", "20",
                             "--json", "databaseId,conclusion,status,createdAt,updatedAt,headSha,url,displayTitle,event"))
        done = [r for r in runs if r["status"] == "completed" and r["conclusion"] in ("success", "failure")]
        src = {k: s[k] for k in ("id", "label", "repo", "branch", "pr")}
        src["recent"] = [{"id": r["databaseId"], "conclusion": r["conclusion"], "created": r["createdAt"], "url": r["url"], "title": r["displayTitle"]} for r in done[:5]]
        def failed_step(r):
            jobs = json.loads(gh("run", "view", str(r["databaseId"]), "-R", s["repo"], "--json", "jobs"))["jobs"]
            return next((st["name"] for j in jobs for st in j["steps"] if st["conclusion"] == "failure"), None)

        if done:
            src["latest"] = {"id": done[0]["databaseId"], "url": done[0]["url"], "conclusion": done[0]["conclusion"], "created": done[0]["createdAt"],
                             "title": done[0]["displayTitle"], "failed_step": failed_step(done[0]) if done[0]["conclusion"] == "failure" else None}
            for r in done:
                log = parse_log(r["databaseId"], s["repo"])
                if log["scala_version"]:
                    wf = base64.b64decode(json.loads(gh("api", f"repos/{s['repo']}/contents/.github/workflows/{s['workflow']}?ref={r['headSha']}"))["content"]).decode()
                    src["run"] = {"id": r["databaseId"], "url": r["url"], "conclusion": r["conclusion"], "created": r["createdAt"], "updated": r["updatedAt"],
                                  "head_sha": r["headSha"], **log}
                    src["skips"] = [dict(k, category=categorise(k["reason"])) for k in parse_skips(wf)]
                    break
        result["sources"].append(src)
    result["zinc"] = zinc_markers()
    Path(a.out).mkdir(parents=True, exist_ok=True)
    (Path(a.out) / "ci.json").write_text(json.dumps(result, indent=1))
    for s in result["sources"]:
        r = s.get("run", {})
        print(s["id"], r.get("conclusion"), f"passed={r.get('passed')} pending={r.get('pending')}", f"skips={len(s.get('skips', []))}", r.get("scala_version"))
    print("zinc disabled:", len(result["zinc"]["disabled"]), "pending:", len(result["zinc"]["pending"]))


if __name__ == "__main__":
    main()
