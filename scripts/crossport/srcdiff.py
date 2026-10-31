#!/usr/bin/env python3
"""Diff zinc's own Scala 2 bridge against scala2-sbt-bridge, file by file, after stripping
comments, package and import lines, and blank lines. A remaining hunk is either a real
port gap or a known, intended difference (Scala 2.11/2.12 compat, AnalysisCallback4 until
the scala2-sbt-bridge port lands, compat shims for older zinc).

This is a ground-truth check for analyze.py: it finds gaps whatever the commit history
says, and shows when a commit-level "pending" is already ported under another title.

usage: srcdiff.py ZINC_CHECKOUT SCALA_CHECKOUT
  e.g. srcdiff.py ~/code/zinc ~/code/scala   (zinc on 1.12.x or develop, scala on 2.13.x)
"""
import difflib, re, sys
from pathlib import Path

FILES = ["API", "Analyzer", "CallbackGlobal", "ClassName", "CompilerBridge", "Dependency",
         "ExtractAPI", "ExtractUsedNames", "GlobalHelpers", "JavaUtils", "LocalToNonLocalClass",
         "LocateClassFile"]


def norm(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    lines = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("//") or s.startswith("package ") or s.startswith("import "):
            continue
        lines.append(re.sub(r"\s+", " ", s))
    return lines


def main(zinc, scala):
    zdir = Path(zinc) / "internal/compiler-bridge/src/main/scala/xsbt"
    sdir = Path(scala) / "src/sbt-bridge/scala/tools/xsbt"
    for f in FILES:
        z, s = zdir / f"{f}.scala", sdir / f"{f}.scala"
        if not (z.exists() and s.exists()):
            print(f"## {f}: missing on one side")
            continue
        d = list(difflib.unified_diff(norm(z.read_text()), norm(s.read_text()),
                                      f"zinc/{f}.scala", f"scala2-sbt-bridge/{f}.scala", n=1, lineterm=""))
        print(f"## {f}: {sum(1 for l in d if l[:1] in '+-' and l[:3] not in ('+++', '---'))} changed lines")
        print("\n".join(d))


if __name__ == "__main__":
    main(*sys.argv[1:3])
