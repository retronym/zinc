#!/usr/bin/env python3
"""Generates IncBench edits for a real build from its class hierarchy.

Reads the tab-separated output of `sbt.internal.inc.bench.Hierarchy` (class, kind, descendants,
direct descendants, clients, file) and the build's sources, and writes IncBench's edits format:
name, file, text to find, replacement, with `\\n` for a newline. Lines with the same name are
one edit.

Targets are the traits and abstract classes with the most descendants, plus the concrete leaf
classes with the most clients. Each gets four edits:

  add-member  an unused concrete member, `def zincBenchMember: Int = 1`
  overload    an overload of a method the class declares, taking a new top-level class
  body        a statement added to a method body (or the constructor, if no method has a block)
  serializable  `java.io.Serializable` added to the parents

The edits are textual, over the source with comments and strings blanked out. Targets whose
declaration can't be found unambiguously, or whose edit would need text IncBench can't express,
are reported on stderr and skipped. Whether an edit compiles is left to the run: an edit that fails
both incrementally and clean is listed as not compiling.

  bin/catalyst-edits.py HIERARCHY.tsv BUILD_DIR --hierarchy-targets 25 --leaf-targets 10 > edits.tsv
"""
import argparse
import re
import sys


def mask(src):
    """Returns src with comments and string and char literals replaced by spaces."""
    out = list(src)
    i, n = 0, len(src)

    def blank(a, b):
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif src.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            blank(i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            while j < n and src[j] == '"':
                j += 1
            blank(i, j)
            i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"' and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            blank(i, j + 1)
            i = j + 1
        elif c == "'":
            m = re.match(r"'(\\u[0-9a-fA-F]{4}|\\.|[^\\'\n])'", src[i:])
            if m:
                blank(i, i + m.end())
                i += m.end()
            else:
                i += 1
        else:
            i += 1
    return "".join(out)


def matching(m, i):
    """The index of the bracket that closes the one at i, in masked text m."""
    pairs = {"{": "}", "(": ")", "[": "]"}
    stack = []
    for j in range(i, len(m)):
        if m[j] in pairs:
            stack.append(pairs[m[j]])
        elif m[j] in ")]}":
            if not stack or stack.pop() != m[j]:
                return None
            if not stack:
                return j
    return None


class Decl:
    """A class or trait declaration: [start, open) is its header, [open, close] its body."""

    def __init__(self, src, masked, simple):
        self.ok = False
        ms = [x for x in re.finditer(r"\b(class|trait)\s+" + re.escape(simple) + r"\b", masked)]
        if len(ms) != 1:
            self.why = f"{len(ms)} declarations of {simple}"
            return
        self.start = ms[0].start()
        j = ms[0].end()
        while j < len(masked):
            c = masked[j]
            if c in "([":
                j = matching(masked, j)
                if j is None:
                    self.why = "unbalanced header"
                    return
                j += 1
            elif c == "{":
                rest = masked[j + 1 :]
                if re.match(r"\s*(case\b|\w+\s*=>)", rest) is None and re.search(
                    r"\b(extends|with)\s*$", masked[self.start : j]
                ):
                    self.why = "early initializer"
                    return
                break
            elif c == "\n":
                nxt = re.match(r"\n\s*(\w+)", masked[j:])
                if nxt is None or nxt.group(1) not in ("extends", "with"):
                    self.why = "no body"
                    return
                j += 1
            else:
                j += 1
        else:
            self.why = "no body"
            return
        self.open = j
        self.close = matching(masked, j)
        if self.close is None:
            self.why = "unbalanced body"
            return
        self.header = masked[self.start : self.open]
        selfType = re.match(r"\s*(\w+|this)\s*(:[^{}=]*)?=>", masked[self.open + 1 : self.close])
        self.insert = self.open + 1 + (selfType.end() if selfType else 0)
        self.ok = True

    def members(self, masked):
        """(start, end) of each line of the body at depth one."""
        depth, line_start, out = 0, None, []
        for k in range(self.open + 1, self.close):
            c = masked[k]
            if c == "\n":
                if line_start is not None:
                    out.append((line_start, k))
                line_start = k + 1 if depth == 0 else None
            elif c in "{([":
                depth += 1
            elif c in "})]":
                depth -= 1
        return out


def unique(src, a, b, why):
    """The shortest text src[a':b] (a' <= a) that occurs once in src, or raises."""
    k = a
    while k >= 0:
        t = src[k:b]
        if src.count(t) == 1:
            return t
        k = src.rfind("\n", 0, k - 1) + 1 if k > 0 else -1
    raise ValueError(why)


def tsv(name, file, find, replace):
    for t in (find, replace):
        if "\t" in t or "\\n" in t:
            raise ValueError("text IncBench can't express")
    esc = lambda t: t.replace("\n", "\\n")
    return f"{name}\t{file}\t{esc(find)}\t{esc(replace)}"


def insertion(src, decl, text, why):
    find = unique(src, decl.start, decl.insert, why)
    return find, find + text


def edits_for(cls, file, src, idx):
    masked = mask(src)
    simple = re.split(r"[.$]", cls)[-1]
    decl = Decl(src, masked, simple)
    if not decl.ok:
        raise ValueError(decl.why)
    tag = f"{idx:03d}-{simple}"
    out = []

    find, rep = insertion(src, decl, "\n  def zincBenchMember: Int = 1\n", "add-member")
    out.append(tsv(f"{tag}-add-member", file, find, rep))

    members = decl.members(masked)
    defs = []
    for a, b in members:
        m = re.match(r"\s*((?:override|final|protected(?:\[\w+\])?|implicit|@\w+)\s+)*def\s+([a-zA-Z_]\w*)\b", masked[a:b])
        if m and not re.search(r"\b(private|implicit)\b", masked[a:b].split("def")[0]):
            defs.append((a, b, m.group(2)))
    overloadable = [d for d in defs if d[2] not in ("apply", "unapply", "this", "copy", "equals")]
    pkgs = list(re.finditer(r"^package\s+[\w.]+[ \t]*$", masked, re.M))
    if overloadable and pkgs:
        name = overloadable[0][2]
        fresh = f"ZincBenchFresh{idx:03d}"
        p = pkgs[-1]
        pline = src[p.start() : p.end()]
        if src.count(pline) != 1:
            raise ValueError("package clause not unique")
        find, rep = insertion(
            src, decl, f"\n  def {name}(zincBench: {fresh}): Int = 1\n", "overload"
        )
        out.append(tsv(f"{tag}-overload-{name}", file, pline, pline + f"\n\nfinal class {fresh}\n"))
        out.append(tsv(f"{tag}-overload-{name}", file, find, rep))
    else:
        print(f"skip {cls} overload: no public method or package clause", file=sys.stderr)

    stmt = '\n    if (System.nanoTime() == 42L) println("zinc")'
    body = None
    for a, b, _ in defs:
        if re.search(r"=\s*\{\s*$", masked[a:b]):
            after = masked[b + 1 : decl.close].lstrip()
            if not after.startswith("case"):
                body = (a, b)
                break
    if body:
        a, b = body
        find = unique(src, a, b, "body")
        out.append(tsv(f"{tag}-body", file, find, find + stmt))
    else:
        find, rep = insertion(src, decl, "\n  if (System.nanoTime() == 42L) println(\"zinc\")\n", "body")
        out.append(tsv(f"{tag}-body", file, find, rep))

    h = decl.header
    if re.search(r"\b(Serializable|AnyVal)\b", h):
        print(f"skip {cls} serializable: already in header", file=sys.stderr)
    else:
        end = decl.start + len(h.rstrip())
        add = " with java.io.Serializable" if re.search(r"\bextends\b", h) else " extends java.io.Serializable"
        find = unique(src, decl.start, end, "serializable")
        out.append(tsv(f"{tag}-serializable", file, find, find + add))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("hierarchy")
    ap.add_argument("build")
    ap.add_argument("--hierarchy-targets", type=int, default=25)
    ap.add_argument("--leaf-targets", type=int, default=10)
    a = ap.parse_args()
    rows = []
    for line in open(a.hierarchy):
        cls, kind, desc, direct, clients, file = line.rstrip("\n").split("\t")
        rows.append((cls, kind, int(desc), int(direct), int(clients), file))
    ancestors = sorted(
        (r for r in rows if r[1] in ("trait", "abstract") and r[5].endswith(".scala")),
        key=lambda r: -r[2],
    )
    leaves = sorted(
        (r for r in rows if r[1] == "class" and r[2] == 0 and r[5].endswith(".scala")),
        key=lambda r: -r[4],
    )
    idx = 0
    for pool, want in ((ancestors, a.hierarchy_targets), (leaves, a.leaf_targets)):
        taken = 0
        for cls, kind, desc, _, clients, file in pool:
            if taken == want:
                break
            src = open(f"{a.build}/{file}").read()
            try:
                lines = edits_for(cls, file, src, idx)
            except ValueError as e:
                print(f"skip {cls}: {e}", file=sys.stderr)
                continue
            print(f"# {cls} {kind} descendants={desc} clients={clients}")
            print("\n".join(lines))
            idx += 1
            taken += 1


if __name__ == "__main__":
    main()
