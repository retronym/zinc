#!/usr/bin/env python3
"""Fetch commits touching the compiler-bridge code on the Scala 2.13, Scala 3 and Zinc branches.

Everything fetched is cached under $CROSSPORT_CACHE (default ~/.cache/crossport), keyed by commit
sha, so later runs only fetch commits they have not seen. Output: <out>/commits.json.

usage: fetch.py [--since YYYY-MM-DD] [--out DIR] [--refresh-lists]
"""
import argparse, json, os, re, subprocess, sys
from pathlib import Path

SOURCES = [
    {"id": "scala2", "repo": "scala/scala", "branch": "2.13.x",
     "paths": ["src/sbt-bridge", "test/junit/scala/tools/xsbt"]},
    {"id": "scala3", "repo": "scala/scala3", "branch": "main",
     "paths": ["sbt-bridge", "compiler/src/dotty/tools/dotc/sbt", "sbt-test/source-dependencies"]},
    {"id": "zinc-develop", "repo": "sbt/zinc", "branch": "develop",
     "paths": ["internal/compiler-bridge", "internal/compiler-bridge-test"]},
    {"id": "zinc-1.12.x", "repo": "sbt/zinc", "branch": "1.12.x",
     "paths": ["internal/compiler-bridge", "internal/compiler-bridge-test"]},
]

OPEN_PR_REPOS = [
    ("scala/scala", "2.13.x", "scala2"), ("scala/scala3", "main", "scala3"),
    ("sbt/zinc", "develop", "zinc-develop"), ("sbt/zinc", "1.12.x", "zinc-1.12.x"),
    ("retronym/scala", "2.13.x", "scala2"), ("retronym/scala3", "main", "scala3"),
    ("retronym/zinc", "develop", "zinc-develop"), ("retronym/zinc", "1.12.x", "zinc-1.12.x"),
]
OPEN_PR_QUERY = """query($owner:String!,$name:String!,$base:String!,$after:String){repository(owner:$owner,name:$name){
pullRequests(states:OPEN,baseRefName:$base,first:50,after:$after){pageInfo{hasNextPage endCursor}
nodes{number title body isDraft url createdAt updatedAt author{login} files(first:100){nodes{path additions deletions}}}}}}"""

CACHE = Path(os.environ.get("CROSSPORT_CACHE", Path.home() / ".cache" / "crossport"))


def gh(endpoint, paginate=False):
    cmd = ["gh", "api", endpoint] + (["--paginate"] if paginate else [])
    out = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout
    if paginate:
        out = out.replace("][", ",")
    return json.loads(out)


def cached(name, producer, refresh=False):
    p = CACHE / name
    if p.exists() and not refresh:
        return json.loads(p.read_text())
    v = producer()
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(v))
    return v


def commit_detail(repo, sha):
    def produce():
        d = gh(f"repos/{repo}/commits/{sha}")
        return {
            "sha": d["sha"], "message": d["commit"]["message"],
            "date": d["commit"]["committer"]["date"],
            "author": (d.get("author") or {}).get("login") or d["commit"]["author"]["name"],
            "parents": [p["sha"] for p in d["parents"]],
            "files": [{"path": f["filename"], "status": f["status"],
                       "additions": f["additions"], "deletions": f["deletions"]} for f in d.get("files", [])],
        }
    return cached(f"commits/{repo.replace('/', '_')}/{sha}.json", produce)


def commit_prs(repo, sha):
    def produce():
        ps = gh(f"repos/{repo}/commits/{sha}/pulls")
        return [{"number": p["number"], "title": p["title"], "body": p.get("body") or "",
                 "merged_at": p.get("merged_at"), "base": p["base"]["ref"],
                 "user": p["user"]["login"], "url": p["html_url"]} for p in ps]
    return cached(f"prs/{repo.replace('/', '_')}/{sha}.json", produce)


def open_prs(source, repo, base, paths):
    out, after = [], None
    owner, name = repo.split("/")
    while True:
        cmd = ["gh", "api", "graphql", "-f", f"query={OPEN_PR_QUERY}", "-f", f"owner={owner}", "-f", f"name={name}", "-f", f"base={base}"]
        if after:
            cmd += ["-f", f"after={after}"]
        d = json.loads(subprocess.run(cmd, check=True, capture_output=True, text=True).stdout)["data"]["repository"]["pullRequests"]
        for n in d["nodes"]:
            files = [{"path": f["path"], "additions": f["additions"], "deletions": f["deletions"]} for f in n["files"]["nodes"]]
            if any(f["path"].startswith(p + "/") or f["path"] == p for f in files for p in paths):
                out.append({"source": source, "repo": repo, "base": base, "number": n["number"], "title": n["title"], "body": n["body"] or "",
                            "draft": n["isDraft"], "url": n["url"], "created": n["createdAt"], "updated": n["updatedAt"],
                            "author": (n["author"] or {}).get("login", ""), "files": files})
        if not d["pageInfo"]["hasNextPage"]:
            return out
        after = d["pageInfo"]["endCursor"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="2023-06-01")
    ap.add_argument("--out", default="scripts/crossport/out")
    ap.add_argument("--refresh-lists", action="store_true")
    ap.add_argument("--no-open", action="store_true", help="skip fetching open PRs")
    a = ap.parse_args()
    out = []
    for s in SOURCES:
        shas = {}
        for path in s["paths"]:
            ep = f"repos/{s['repo']}/commits?sha={s['branch']}&path={path}&since={a.since}T00:00:00Z&per_page=100"
            lst = gh(ep, paginate=True)
            for c in lst:
                shas.setdefault(c["sha"], c["commit"]["committer"]["date"])
        print(f"{s['id']}: {len(shas)} commits", file=sys.stderr)
        for sha in sorted(shas, key=shas.get):
            d = commit_detail(s["repo"], sha)
            if len(d["parents"]) > 1:
                continue
            prs = commit_prs(s["repo"], sha)
            out.append({**d, "source": s["id"], "repo": s["repo"], "branch": s["branch"], "prs": prs})
    Path(a.out).mkdir(parents=True, exist_ok=True)
    if not a.no_open:
        paths = {s["id"]: s["paths"] for s in SOURCES}
        prs = []
        for repo, base, source in OPEN_PR_REPOS:
            found = open_prs(source, repo, base, paths["zinc-develop" if source.startswith("zinc") else source])
            print(f"open PRs {repo} {base}: {len(found)}", file=sys.stderr)
            prs += found
        (Path(a.out) / "open_prs.json").write_text(json.dumps(prs, indent=1))
    (Path(a.out) / "commits.json").write_text(json.dumps(out, indent=1))
    print(f"wrote {len(out)} commits", file=sys.stderr)


main()
