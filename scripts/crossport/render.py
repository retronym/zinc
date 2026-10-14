#!/usr/bin/env python3
"""Render out/report.json as a single self-contained out/report.html (swim lanes, port edges).

usage: render.py [--since YYYY-MM-DD] [--out DIR]
"""
import argparse, json
from pathlib import Path

TEMPLATE = r'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bridge Cross-Port Gaps</title>
<style>
:root{--bg:#f6f7f9;--panel:#fff;--fg:#1c2230;--fg2:#4f5a6b;--fg3:#7d8797;--line:#dde2ea;
--l0:#2a78d6;--l1:#d9531e;--l2:#14916a;--l3:#7b55c9;
--crit:#c0392b;--crit-bg:#fde8e6;--warn:#9a6700;--warn-bg:#fff1c9;--mute:#7d8797;--mute-bg:#eef1f5;--good:#1a7f37;--good-bg:#e6f4ea;
--sans:system-ui,-apple-system,"Segoe UI",sans-serif;--mono:ui-monospace,"SF Mono",Menlo,monospace}
@media(prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#0f1419;--panel:#171d25;--fg:#e8ecf2;--fg2:#aab4c2;--fg3:#7b8694;--line:#2a3340;
--l0:#5a9ae8;--l1:#f0885a;--l2:#3cc79a;--l3:#a58be6;--crit:#f0776a;--crit-bg:#3a1a17;--warn:#e3b341;--warn-bg:#33290f;--mute:#7b8694;--mute-bg:#202835;--good:#4cc26a;--good-bg:#14301d}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 var(--sans);padding:0 16px 48px}
.wrap{max-width:1120px;margin:0 auto}h1{font-size:1.5rem;margin:28px 0 4px}h2{font-size:1.05rem;margin:28px 0 8px}
p{margin:0 0 8px;color:var(--fg2);max-width:78ch}code{font:.85em var(--mono);background:var(--mute-bg);padding:1px 5px;border-radius:4px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:16px 0}
.tile{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:10px 14px}.tile b{display:block;font-size:1.6rem;line-height:1.1}.tile span{color:var(--fg2);font-size:.85rem}
.legend{display:flex;flex-wrap:wrap;gap:6px 18px;font-size:.85rem;color:var(--fg2);margin:10px 0}.legend i{display:inline-block;width:14px;height:12px;border-radius:3px;vertical-align:-1px;margin-right:6px;border:1.5px solid}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:6px 0;overflow-x:auto}
svg{display:block;min-width:1040px;width:100%;height:auto}svg text{font-family:var(--sans)}
a:focus-visible{outline:2px solid var(--l0)}details summary{cursor:pointer;font-weight:600;margin:24px 0 8px}
</style></head><body><div class="wrap">
<h1>Bridge cross-port gaps</h1>
<p>Fixes that landed on the compiler bridges of Scala 2.13, Scala 3 and Zinc (<code>develop</code>, <code>1.12.x</code>), one row per logical fix. Solid dots are where the fix landed; arrows point in port direction (earlier to later); dashed arrows go to a line that does not have it yet. Changes since __SINCE__, generated __GEN__.</p>
<div class="tiles" id="tiles"></div>
<div class="legend">
<span><i style="background:var(--crit-bg);border-color:var(--crit)"></i>pending: should exist, missing</span>
<span><i style="background:var(--warn-bg);border-color:var(--warn)"></i>triage: separate implementation, decide if the symptom applies</span>
<span><i style="background:var(--mute-bg);border-color:var(--mute);border-style:dashed"></i>n/a? looks mechanical, confirm</span>
<span><i style="background:transparent;border-color:transparent"></i>n/a: excluded by rule</span>
</div>
<h2>Behaviour changes</h2><div class="panel" id="sem"></div>
<details><summary>Touch the extraction code but look mechanical</summary><div class="panel" id="mech"></div></details>
</div>
<script>
const DATA = __DATA__;
const LINES = [["scala2","scala/scala 2.13.x","--l0"],["scala3","scala/scala3 main","--l1"],["zinc-develop","sbt/zinc develop","--l2"],["zinc-1.12.x","sbt/zinc 1.12.x","--l3"]];
const NS = "http://www.w3.org/2000/svg";
const LABEL_W = 340, COL_W = 175, ROW_H = 58, HEAD_H = 34, W = LABEL_W + COL_W * 4 + 10;
const cx = i => LABEL_W + COL_W * i + COL_W / 2;
const el = (n, a = {}, kids = []) => { const e = document.createElementNS(NS, n); for (const k in a) e.setAttribute(k, a[k]); for (const c of kids) e.append(c); return e; };
const txt = (s, a) => { const t = el("text", a); t.textContent = s; return t; };
const clip = (s, n) => s.length > n ? s.slice(0, n - 1) + "…" : s;
const STATE = {
  pending: {fill: "var(--crit-bg)", stroke: "var(--crit)", ink: "var(--crit)", label: "pending", dash: ""},
  triage: {fill: "var(--warn-bg)", stroke: "var(--warn)", ink: "var(--warn)", label: "triage", dash: ""},
  "probably-n/a": {fill: "var(--mute-bg)", stroke: "var(--mute)", ink: "var(--mute)", label: "n/a?", dash: "3 3"},
  "n/a": {fill: "none", stroke: "none", ink: "var(--fg3)", label: "n/a", dash: ""},
  ported: {fill: "var(--good-bg)", stroke: "var(--good)", ink: "var(--good)", label: "ported", dash: ""}
};
function draw(host, rows, ghostArrows) {
  const H = HEAD_H + rows.length * ROW_H + 8;
  const svg = el("svg", {viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": "Cross-port swim lanes"});
  const defs = el("defs");
  const marker = (id, color) => defs.append(el("marker", {id, viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse"}, [el("path", {d: "M0 0L10 5L0 10z", fill: color})]));
  marker("a-solid", "var(--fg2)"); marker("a-crit", "var(--crit)"); marker("a-warn", "var(--warn)");
  svg.append(defs);
  LINES.forEach(([id, name, col], i) => {
    svg.append(el("rect", {x: LABEL_W + COL_W * i + 4, y: 4, width: COL_W - 8, height: 24, rx: 5, fill: `var(${col})`}));
    svg.append(txt(name, {x: cx(i), y: 20, "text-anchor": "middle", "font-size": 12, "font-weight": 600, fill: "#fff"}));
    svg.append(el("line", {x1: cx(i), y1: HEAD_H, x2: cx(i), y2: H - 4, stroke: `var(${col})`, "stroke-opacity": .18, "stroke-width": 2}));
  });
  rows.forEach((r, ri) => {
    const y0 = HEAD_H + ri * ROW_H, cy = y0 + 22;
    svg.append(el("line", {x1: 0, y1: y0, x2: W, y2: y0, stroke: "var(--line)"}));
    svg.append(txt(clip(r.title, 52), {x: 12, y: cy - 2, "font-size": 13, "font-weight": 600, fill: "var(--fg)"}, ));
    svg.lastChild.append(el("title", {}, [document.createTextNode(r.title)]));
    svg.append(txt(`${r.first.slice(0, 10)} · ${r.key}`, {x: 12, y: cy + 15, "font-size": 11, fill: "var(--fg3)", "font-family": "var(--mono)"}));
    const present = {};
    r.units.forEach(u => { (present[u.source] = present[u.source] || []).push(u); });
    const lane = s => LINES.findIndex(l => l[0] === s);
    const edges = new Map();
    r.edges.forEach(e => { const a = lane(e.from.split(":")[0]), b = lane(e.to.split(":")[0]); if (a !== b) edges.set(a + ">" + b, e.kind); });
    const arc = (a, b, color, marker, dash, tip, ghost) => {
      const dir = b > a ? 1 : -1, x1 = cx(a) + dir * 11, x2 = cx(b) - dir * (ghost ? 42 : 14), span = Math.abs(b - a);
      const bulge = span === 1 ? 0 : -(10 + span * 5), mx = (x1 + x2) / 2;
      const p = el("path", {d: `M${x1} ${cy} Q${mx} ${cy + 2 * bulge} ${x2} ${cy}`, fill: "none", stroke: color, "stroke-width": 1.6, "marker-end": `url(#${marker})`, "stroke-dasharray": dash});
      p.append(el("title", {}, [document.createTextNode(tip)])); svg.append(p);
    };
    LINES.forEach(([id], li) => {
      const st = r.lines[id];
      if (present[id]) return;
      const s = STATE[st.state] || STATE["n/a"];
      const g = el("g");
      if (s.stroke !== "none") g.append(el("rect", {x: cx(li) - 36, y: cy - 11, width: 72, height: 22, rx: 5, fill: s.fill, stroke: s.stroke, "stroke-width": 1.4, "stroke-dasharray": s.dash}));
      g.append(txt(s.label, {x: cx(li), y: cy + 4, "text-anchor": "middle", "font-size": 11, "font-weight": 600, fill: s.ink}));
      g.append(el("title", {}, [document.createTextNode(`${id}: ${st.state}${st.reason ? " (" + st.reason + ")" : ""}`)]));
      svg.append(g);
    });
    edges.forEach((kind, k) => { const [a, b] = k.split(">").map(Number); arc(a, b, "var(--fg2)", "a-solid", "", `port (${kind})`); });
    if (ghostArrows) {
      const origin = lane(r.units[0].source);
      LINES.forEach(([id], li) => { const st = r.lines[id].state;
        if (present[id] || (st !== "pending" && st !== "triage")) return;
        arc(origin, li, st === "pending" ? "var(--crit)" : "var(--warn)", st === "pending" ? "a-crit" : "a-warn", "5 4", `${st}: ${r.units[0].source} → ${id}`, true); });
    }
    LINES.forEach(([id, , col], li) => {
      const us = present[id]; if (!us) return;
      svg.append(el("circle", {cx: cx(li), cy, r: 8, fill: `var(${col})`, stroke: "var(--panel)", "stroke-width": 2}));
      const label = us.map(u => "#" + (u.pr || u.id.split(":")[1]) + (u.part ? `.${u.part[0]}` : "")).join(" ");
      const t = txt(clip(label, 24), {x: cx(li), y: cy + 24, "text-anchor": "middle", "font-size": 11, fill: "var(--fg2)", "font-family": "var(--mono)"});
      t.append(el("title", {}, [document.createTextNode(us.map(u => `${u.date} ${u.title}`).join("\n"))]));
      const url = us.find(u => u.url);
      if (url) { const a = el("a", {href: url.url, target: "_blank", rel: "noopener"}); a.append(t); svg.append(a); } else svg.append(t);
    });
  });
  host.replaceChildren(svg);
}
const sem = DATA.filter(r => r.kind === "semantic"), mech = DATA.filter(r => r.kind === "mechanical");
const count = s => sem.reduce((n, r) => n + Object.values(r.lines).filter(l => l.state === s).length, 0);
document.getElementById("tiles").innerHTML = [[sem.length, "behaviour changes tracked"], [count("pending"), "pending ports"], [count("triage"), "cells to triage"], [mech.length, "look mechanical, confirm"]]
  .map(([n, l]) => `<div class="tile"><b>${n}</b><span>${l}</span></div>`).join("");
draw(document.getElementById("sem"), sem, true);
draw(document.getElementById("mech"), mech, false);
</script></body></html>'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="2025-01-01")
    ap.add_argument("--out", default="scripts/crossport/out")
    a = ap.parse_args()
    out = Path(a.out)
    rows = [r for r in json.load(open(out / "report.json")) if r["latest"][:10] >= a.since and r["kind"] in ("semantic", "mechanical")]
    from datetime import date
    html = TEMPLATE.replace("__DATA__", json.dumps(rows)).replace("__SINCE__", a.since).replace("__GEN__", date.today().isoformat())
    (out / "report.html").write_text(html)
    print(f"wrote {out / 'report.html'} ({len(rows)} rows)")


main()
