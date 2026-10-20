#!/usr/bin/env python3
"""Render out/report.json as a single self-contained out/report.html (swim lanes, port edges).

usage: render.py [--since YYYY-MM-DD] [--out DIR]
"""
import argparse, json, re
from pathlib import Path

TEMPLATE = r'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bridge Cross-Port Gaps</title>
<style>
:root{--bg:#f6f7f9;--panel:#fff;--fg:#1c2230;--fg2:#4f5a6b;--fg3:#7d8797;--line:#dde2ea;
--l0:#2a78d6;--l1:#d9531e;--l2:#14916a;--l3:#7b55c9;
--crit:#c0392b;--crit-bg:#fde8e6;--warn:#9a6700;--warn-bg:#fff1c9;--mute:#7d8797;--mute-bg:#eef1f5;--good:#1a7f37;--good-bg:#e6f4ea;--rev:#6b46c1;--rev-bg:#efe9fb;
--sans:system-ui,-apple-system,"Segoe UI",sans-serif;--mono:ui-monospace,"SF Mono",Menlo,monospace}
@media(prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#0f1419;--panel:#171d25;--fg:#e8ecf2;--fg2:#aab4c2;--fg3:#7b8694;--line:#2a3340;
--l0:#5a9ae8;--l1:#f0885a;--l2:#3cc79a;--l3:#a58be6;--crit:#f0776a;--crit-bg:#3a1a17;--warn:#e3b341;--warn-bg:#33290f;--mute:#7b8694;--mute-bg:#202835;--good:#4cc26a;--good-bg:#14301d;--rev:#b79df0;--rev-bg:#2a2140;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#0f1419;--panel:#171d25;--fg:#e8ecf2;--fg2:#aab4c2;--fg3:#7b8694;--line:#2a3340;
--l0:#5a9ae8;--l1:#f0885a;--l2:#3cc79a;--l3:#a58be6;--crit:#f0776a;--crit-bg:#3a1a17;--warn:#e3b341;--warn-bg:#33290f;--mute:#7b8694;--mute-bg:#202835;--good:#4cc26a;--good-bg:#14301d;--rev:#b79df0;--rev-bg:#2a2140;color-scheme:dark}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 var(--sans);padding-inline:16px;padding-block:0 48px}
.wrap{max-width:1120px;margin:0 auto}h1{font-size:1.5rem;margin:28px 0 4px}h2{font-size:1.05rem;margin:28px 0 8px}
p{margin:0 0 8px;color:var(--fg2);max-width:78ch}code{font:.85em var(--mono);background:var(--mute-bg);padding:1px 5px;border-radius:4px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:16px 0}
.tile{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:10px 14px}.tile b{display:block;font-size:1.6rem;line-height:1.1}.tile span{color:var(--fg2);font-size:.85rem}
.legend{display:flex;flex-wrap:wrap;gap:6px 18px;font-size:.85rem;color:var(--fg2);margin:10px 0}.legend i{display:inline-block;width:14px;height:12px;border-radius:3px;vertical-align:-1px;margin-right:6px;border:1.5px solid}
.wrap{min-width:1040px}.wip{display:inline-block;font:600 .7rem var(--sans);letter-spacing:.08em;background:var(--warn-bg);color:var(--warn);border:1px solid var(--warn);border-radius:999px;padding:1px 9px;vertical-align:middle;margin-left:8px}
@media(max-width:1100px){.wrap{min-width:0}.panel{overflow-x:auto}.lanehead{position:static}}.panel{background:var(--panel);border:1px solid var(--line);border-radius:8px}
svg{display:block;min-width:1040px;width:100%;height:auto}
.lanehead{position:sticky;top:env(safe-area-inset-top,0px);z-index:5;background:var(--panel);border-bottom:1px solid var(--line);border-radius:8px 8px 0 0;box-shadow:0 2px 6px rgba(0,0,0,.08)}svg text{font-family:var(--sans)}
a:focus-visible{outline:2px solid var(--l0)}
.ledger{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:12px 14px;max-height:420px;overflow:auto;font:12px/1.5 var(--mono);margin:0;white-space:pre}
footer{margin-top:28px;border-top:1px solid var(--line);padding-top:12px;color:var(--fg3);font-size:.85rem}
.ci{display:grid;gap:14px}.card{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:14px 16px;min-width:0}
.card h3{margin:0 0 4px;font-size:1rem}.card .meta{color:var(--fg2);font-size:.85rem;margin:0 0 8px}.card p{max-width:80ch}
.agent{border-left:3px solid var(--l0)}.agent .stamp{font:.75rem var(--mono);color:var(--fg3);margin-bottom:6px}
.pill{display:inline-block;font:600 .7rem var(--sans);letter-spacing:.04em;text-transform:uppercase;padding:1px 8px;border-radius:999px;border:1px solid var(--line);white-space:nowrap}
.pill.good{background:var(--good-bg);color:var(--good);border-color:var(--good)}.pill.crit{background:var(--crit-bg);color:var(--crit);border-color:var(--crit)}
.pill.warn{background:var(--warn-bg);color:var(--warn);border-color:var(--warn)}.pill.rev{background:var(--rev-bg);color:var(--rev);border-color:var(--rev)}.pill.mute{background:var(--mute-bg);color:var(--mute)}
.tblw{overflow-x:auto}table.skips{border-collapse:collapse;width:100%;font-size:.85rem}table.skips th,table.skips td{text-align:left;padding:5px 8px;border-bottom:1px solid var(--line);vertical-align:top}
table.skips th{font:600 .7rem var(--sans);letter-spacing:.06em;text-transform:uppercase;color:var(--fg3)}table.skips td.sc{font-family:var(--mono);font-size:.78rem;white-space:nowrap}
.chips{display:flex;flex-wrap:wrap;gap:6px;margin:6px 0}.chips code{background:var(--mute-bg)}

.lnk:hover{text-decoration:underline}
.tip{pointer-events:none;max-height:70vh;overflow:auto;position:fixed;z-index:30;max-width:440px;background:var(--panel);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:10px 12px;box-shadow:0 6px 24px rgba(0,0,0,.18);font-size:.85rem;line-height:1.4}
.tip[hidden]{display:none}.tip.pinned{pointer-events:auto;border-color:var(--l0);padding-right:34px}.tip .x{position:absolute;top:6px;right:8px;border:0;background:none;color:var(--fg2);font-size:1.2rem;line-height:1;cursor:pointer;padding:2px 6px}.tip .hint{margin-top:8px;font-size:.72rem;color:var(--fg3)}.tip .h{font-weight:600;margin-bottom:4px}.tip .l{margin:1px 0}.tip .u+.u{border-top:1px solid var(--line);margin-top:8px;padding-top:8px}.tip .m{color:var(--fg2);font-size:.8rem;margin-top:2px}.tip a{color:var(--l0)}.tip code{font-size:.78rem}details summary{cursor:pointer;font-weight:600;margin:24px 0 8px}
</style></head><body><div class="wrap">
<h1>Bridge cross-port gaps<span class="wip">WIP</span></h1>
<p>Fixes that landed on the compiler bridges of Scala 2.13, Scala 3 and Zinc (<code>develop</code>, <code>1.12.x</code>), one row per logical fix. Solid dots are where the fix landed; arrows point in port direction (earlier to later); dashed arrows go to a line that does not have it yet. Changes since __SINCE__, generated __GEN__.</p>
<div class="tiles" id="tiles"></div>
<div class="legend">
<span><i style="background:var(--crit-bg);border-color:var(--crit)"></i>pending: should exist, missing</span>
<span><i style="background:var(--warn-bg);border-color:var(--warn)"></i>triage: separate implementation, decide if the symptom applies</span>
<span><i style="background:var(--mute-bg);border-color:var(--mute);border-style:dashed"></i>n/a? looks mechanical, confirm</span>
<span><i style="background:var(--rev-bg);border-color:var(--rev)"></i>open PR: a submitted port exists, avoid duplicate work</span>
<span><i style="background:transparent;border-color:transparent"></i>n/a: excluded by rule</span>
</div>
<h2>Behaviour changes</h2><div class="panel" id="sem"></div>
<details><summary>Touch the extraction code but look mechanical</summary><div class="panel" id="mech"></div></details>
__CI__
<h2>Verdicts</h2>
<p>The local ledger the report was generated with (<a href="__VERDICTS_URL__" target="_blank" rel="noopener"><code>verdicts.json</code></a>), read-only. It overrides the computed state of a line; a <code>ported</code> verdict with a <code>ref</code> links two fixes into one row.</p>
<pre class="ledger" tabindex="0" aria-label="verdicts.json, read-only">__VERDICTS__</pre>
<footer>Generated by <a href="__PR_URL__" target="_blank" rel="noopener">__PR_LABEL__</a> (<code>scripts/crossport</code>) on __GENAT__.</footer>
</div>
<script>
const DATA = __DATA__;
const LINES = [["scala2","scala/scala 2.13.x","--l0"],["scala3","scala/scala3 main","--l1"],["zinc-develop","sbt/zinc develop","--l2"],["zinc-1.12.x","sbt/zinc 1.12.x","--l3"]];
const NS = "http://www.w3.org/2000/svg";
const LABEL_W = 340, COL_W = 175, ROW_H = 58, HEAD_H = 4, W = LABEL_W + COL_W * 4 + 10;
const cx = i => LABEL_W + COL_W * i + COL_W / 2;
const el = (n, a = {}, kids = []) => { const e = document.createElementNS(NS, n); for (const k in a) e.setAttribute(k, a[k]); for (const c of kids) e.append(c); return e; };
const txt = (s, a) => { const t = el("text", a); t.textContent = s; return t; };
const clip = (s, n) => s.length > n ? s.slice(0, n - 1) + "…" : s;
const STATE = {
  pending: {fill: "var(--crit-bg)", stroke: "var(--crit)", ink: "var(--crit)", label: "pending", dash: ""},
  triage: {fill: "var(--warn-bg)", stroke: "var(--warn)", ink: "var(--warn)", label: "triage", dash: ""},
  "probably-n/a": {fill: "var(--mute-bg)", stroke: "var(--mute)", ink: "var(--mute)", label: "n/a?", dash: "3 3"},
  "n/a": {fill: "none", stroke: "none", ink: "var(--fg3)", label: "n/a", dash: ""},
  ported: {fill: "var(--good-bg)", stroke: "var(--good)", ink: "var(--good)", label: "ported", dash: ""},
  "in-review": {fill: "var(--rev-bg)", stroke: "var(--rev)", ink: "var(--rev)", label: "open PR", dash: ""},
  wontfix: {fill: "none", stroke: "none", ink: "var(--fg3)", label: "won't port", dash: ""}
};
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]));
const tipEl = document.createElement("div"); tipEl.className = "tip"; tipEl.hidden = true; tipEl.setAttribute("role", "dialog"); document.body.append(tipEl);
let pinned = false, pinnedBy = null;
const place = (x, y) => { const r = tipEl.getBoundingClientRect(); tipEl.style.left = Math.max(8, Math.min(x + 12, innerWidth - r.width - 8)) + "px"; tipEl.style.top = Math.max(8, Math.min(y + 12, innerHeight - r.height - 8)) + "px"; };
const unpin = () => { pinned = false; pinnedBy = null; tipEl.classList.remove("pinned"); tipEl.hidden = true; };
const show = (html, x, y, pin) => {
  tipEl.innerHTML = html + (pin ? '<button class="x" type="button" aria-label="Close">×</button>' : '<div class="hint">click to pin; links in a pinned card are clickable</div>');
  tipEl.classList.toggle("pinned", pin); tipEl.hidden = false; place(x, y);
};
tipEl.addEventListener("click", e => { if (e.target.closest(".x")) unpin(); });
document.addEventListener("click", e => { if (pinned && !tipEl.contains(e.target) && !(pinnedBy && pinnedBy.contains(e.target))) unpin(); });
document.addEventListener("keydown", e => { if (e.key === "Escape") unpin(); });
const withTip = (node, html) => {
  node.setAttribute("tabindex", "0"); node.setAttribute("role", "button");
  node.addEventListener("mouseenter", e => { if (!pinned) show(html(), e.clientX, e.clientY, false); });
  node.addEventListener("mousemove", e => { if (!pinned) place(e.clientX, e.clientY); });
  node.addEventListener("mouseleave", () => { if (!pinned) tipEl.hidden = true; });
  const pin = (e, x, y) => { pinned = true; pinnedBy = node; show(html(), x, y, true); };
  node.addEventListener("click", e => { if (e.metaKey || e.ctrlKey || e.shiftKey) return; e.preventDefault(); e.stopPropagation(); pin(e, e.clientX, e.clientY); });
  node.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); const r = node.getBoundingClientRect(); pin(e, r.left, r.bottom); } });
  return node;
};
const unitLabel = u => "#" + (u.pr || u.id.split(":")[1]) + (u.part ? `.${u.part[0]}/${u.part[1]}` : "");
const unitHtml = u => {
  const lane = LINES.find(l => l[0] === u.source)[1];
  const commits = u.shas.slice(0, 6).map(h => `<a href="https://github.com/${u.repo}/commit/${h}" target="_blank" rel="noopener">${h.slice(0, 7)}</a>`).join(" ") + (u.shas.length > 6 ? ` +${u.shas.length - 6} more` : "");
  const cites = (u.refs || []).map(r => `<a href="https://github.com/${r[0]}/issues/${r[1]}" target="_blank" rel="noopener">${esc(r[0].split("/")[1])}#${r[1]}</a>`).join(" ");
  return `<div class="u"><b>${u.url ? `<a href="${esc(u.url)}" target="_blank" rel="noopener">${esc(unitLabel(u))}</a>` : esc(unitLabel(u))}</b> ${esc(u.title)}
    <div class="m">${u.state === "merged" ? "merged" : "<b>" + esc(u.state) + " PR</b>, updated"} ${esc(u.date)} · ${esc(lane)} · ${esc(u.authors.join(", "))}${u.files.length ? "<br>extraction files: " + esc(u.files.join(", ")) : ""}<br>commits: ${commits}${cites ? "<br>cites: " + cites : ""}</div></div>`;
};
const unitLine = u => `<div class="l">${u.url ? `<a href="${esc(u.url)}" target="_blank" rel="noopener">${esc(unitLabel(u))}</a>` : esc(unitLabel(u))} <span class="m">${esc(LINES.find(l => l[0] === u.source)[1])} · ${u.state === "merged" ? esc(u.date) : esc(u.state) + " PR"}</span></div>`;
const refLink = r => { const m = /^([\w.-]+\/[\w.-]+)#(\d+)$/.exec(r); const href = m ? `https://github.com/${m[1]}/issues/${m[2]}` : /^https:\/\//.test(r) ? r : null; return href ? `<a href="${esc(href)}" target="_blank" rel="noopener">${esc(r)}</a>` : esc(r); };
const linkText = (node, url) => { if (!url) return node; const a = el("a", {href: url, target: "_blank", rel: "noopener"}); a.append(node); return a; };
function draw(host, rows, ghostArrows) {
  const H = HEAD_H + rows.length * ROW_H + 8;
  const svg = el("svg", {viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": "Cross-port swim lanes"});
  const defs = el("defs");
  const marker = (id, color) => defs.append(el("marker", {id, viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse"}, [el("path", {d: "M0 0L10 5L0 10z", fill: color})]));
  marker("a-solid", "var(--fg2)"); marker("a-crit", "var(--crit)"); marker("a-warn", "var(--warn)"); marker("a-good", "var(--good)");
  svg.append(defs);
  const hs = el("svg", {viewBox: `0 0 ${W} 30`, role: "presentation"});
  LINES.forEach(([id, name, col], i) => {
    hs.append(el("rect", {x: LABEL_W + COL_W * i + 4, y: 3, width: COL_W - 8, height: 24, rx: 5, fill: `var(${col})`}));
    hs.append(txt(name, {x: cx(i), y: 19, "text-anchor": "middle", "font-size": 12, "font-weight": 600, fill: "#fff"}));
    svg.append(el("line", {x1: cx(i), y1: 0, x2: cx(i), y2: H, stroke: `var(${col})`, "stroke-opacity": .18, "stroke-width": 2}));
  });
  hs.append(txt("fix", {x: 12, y: 19, "font-size": 12, "font-weight": 600, fill: "var(--fg3)"}));
  rows.forEach((r, ri) => {
    const y0 = HEAD_H + ri * ROW_H, cy = y0 + 22;
    svg.append(el("line", {x1: 0, y1: y0, x2: W, y2: y0, stroke: "var(--line)"}));
    const lead = r.units.find(u => u.state === "merged") || r.units[0];
    const title = txt(clip(r.title, 52), {x: 12, y: cy - 2, "font-size": 13, "font-weight": 600, fill: "var(--fg)", class: "lnk"});
    svg.append(withTip(linkText(title, lead.url), () => `<div class="h">${esc(r.title)}</div>${r.units.map(unitLine).join("")}<div class="m">hover a dot for commits and files</div>`));
    const keyUnit = r.units[0];
    svg.append(txt(`${r.first.slice(0, 10)} · ${r.key}`, {x: 12, y: cy + 15, "font-size": 11, fill: "var(--fg3)", "font-family": "var(--mono)"}));
    const present = {};
    r.units.filter(u => u.state === "merged").forEach(u => { (present[u.source] = present[u.source] || []).push(u); });
    const lane = s => LINES.findIndex(l => l[0] === s);
    const edges = new Map();
    r.edges.forEach(e => { const a = lane(e.from.split(":")[0]), b = lane(e.to.split(":")[0]); if (a === b) return; const k = a + ">" + b; (edges.get(k) || edges.set(k, []).get(k)).push(e); });
    const uById = Object.fromEntries(r.units.map(u => [u.id, u]));
    const arc = (a, b, color, marker, dash, tipHtml, ghost) => {
      const dir = b > a ? 1 : -1, x1 = cx(a) + dir * 11, x2 = cx(b) - dir * (ghost ? 42 : 14), span = Math.abs(b - a);
      const bulge = span === 1 ? 0 : -(10 + span * 5), mx = (x1 + x2) / 2;
      const d = `M${x1} ${cy} Q${mx} ${cy + 2 * bulge} ${x2} ${cy}`;
      svg.append(el("path", {d, fill: "none", stroke: color, "stroke-width": 1.6, "marker-end": `url(#${marker})`, "stroke-dasharray": dash}));
      svg.append(withTip(el("path", {d, fill: "none", stroke: "transparent", "stroke-width": 12, "pointer-events": "stroke", style: "cursor:help"}), () => tipHtml));
    };
    edges.forEach((es, k) => { const [a, b] = k.split(">").map(Number);
      const led = es.every(e => e.kind === "ledger"); arc(a, b, led ? "var(--good)" : "var(--fg2)", led ? "a-good" : "a-solid", "", `<div class="h">port ${esc(LINES[a][1])} → ${esc(LINES[b][1])}</div>` + es.map(e => `<div class="m">${esc(unitLabel(uById[e.from]))} → ${esc(unitLabel(uById[e.to]))} <i>(${esc(e.kind)})</i></div>`).join(""), !present[LINES[b][0]]); });
    if (ghostArrows) {
      const origin = lane(lead.source);
      LINES.forEach(([id], li) => { const st = r.lines[id].state;
        if (present[id] || (st !== "pending" && st !== "triage")) return;
        arc(origin, li, st === "pending" ? "var(--crit)" : "var(--warn)", st === "pending" ? "a-crit" : "a-warn", "5 4", `<div class="h">${esc(st)}: ${esc(lead.source)} → ${esc(id)}</div><div class="m">${esc(r.lines[id].reason || "")}</div>`, true); });
    }
    LINES.forEach(([id], li) => {
      const st = r.lines[id];
      if (present[id]) return;
      const s = STATE[st.state] || STATE["n/a"];
      const g = el("g", {style: "cursor:help"});
      g.append(el("rect", {x: cx(li) - 38, y: cy - 13, width: 76, height: 26, rx: 6, fill: "var(--panel)"}));
      g.append(el("rect", {x: cx(li) - 36, y: cy - 11, width: 72, height: 22, rx: 5, fill: s.stroke === "none" ? "transparent" : s.fill, stroke: s.stroke, "stroke-width": 1.4, "stroke-dasharray": s.dash}));
      g.append(txt(st.state === "in-review" && st.prs.every(p => p.draft) ? "draft PR" : s.label, {x: cx(li), y: cy + 4, "text-anchor": "middle", "font-size": 11, "font-weight": 600, fill: s.ink}));
      const prs = st.prs || [];
      const prHtml = prs.map(p => `<div class="l"><a href="${esc(p.url)}" target="_blank" rel="noopener">${esc(p.repo)}#${p.pr}</a> ${esc(p.title)}<div class="m">${p.draft ? "draft" : "open"} · ${esc(p.author)} · updated ${esc(p.updated)}</div></div>`).join("");
      const ghostNode = prs.length ? linkText(g, prs[0].url) : g;
      svg.append(withTip(ghostNode, () => prHtml + `<div class="h">${esc(id)}: ${esc(st.state)}</div><div class="m">${esc(st.reason || "")}${st.ledger ? "<br>from the local ledger" : ""}${st.ref ? "<br>ported as " + refLink(st.ref) : ""}${st.tracking ? "<br>tracking: " + refLink(st.tracking) : ""}</div>
        <div class="m">Origin: ${esc(lead.source)} ${esc(unitLabel(lead))}<br>Record a verdict under <code>${esc(r.key)}.${esc(id)}</code> in <code>verdicts.json</code>.</div>`));
    });
    LINES.forEach(([id, , col], li) => {
      const us = present[id]; if (!us) return;
      const dot = el("circle", {cx: cx(li), cy, r: 8, fill: `var(${col})`, stroke: "var(--panel)", "stroke-width": 2, style: "cursor:pointer"});
      svg.append(withTip(linkText(dot, us.find(u => u.url)?.url), () => us.map(unitHtml).join("")));
      const labels = us.map(unitLabel), cw = 6.7, gap = 8, total = labels.reduce((n, l) => n + l.length * cw, 0) + gap * (labels.length - 1);
      let x = cx(li) - total / 2;
      us.forEach((u, i) => {
        const w = labels[i].length * cw;
        const t = txt(labels[i], {x: x + w / 2, y: cy + 24, "text-anchor": "middle", "font-size": 11, fill: "var(--fg2)", "font-family": "var(--mono)", class: "lnk"});
        svg.append(withTip(linkText(t, u.url), () => unitHtml(u)));
        x += w + gap;
      });
    });
  });
  const head = document.createElement("div"); head.className = "lanehead"; head.append(hs);
  host.replaceChildren(head, svg);
}
const sem = DATA.filter(r => r.kind === "semantic"), mech = DATA.filter(r => r.kind === "mechanical");
const count = s => sem.reduce((n, r) => n + Object.values(r.lines).filter(l => l.state === s).length, 0);
document.getElementById("tiles").innerHTML = [[sem.length, "behaviour changes tracked"], [count("pending"), "pending ports"], [count("triage"), "cells to triage"], [count("in-review"), "ports in review (open or draft PRs)"], [mech.length, "look mechanical, confirm"]]
  .map(([n, l]) => `<div class="tile"><b>${n}</b><span>${l}</span></div>`).join("");
draw(document.getElementById("sem"), sem, true);
draw(document.getElementById("mech"), mech, false);
</script></body></html>'''


def ci_panel(ci, summary):
    import html as H
    esc = H.escape
    def link(text):
        t = esc(text)
        t = re.sub(r"\b(?:sbt/)?zinc#(\d+)", r'<a href="https://github.com/sbt/zinc/pull/\1" target="_blank" rel="noopener">zinc#\1</a>', t)
        t = re.sub(r"scala/(scala3?)#(\d+)", r'<a href="https://github.com/scala/\1/pull/\2" target="_blank" rel="noopener">scala/\1#\2</a>', t)
        return t
    cat = {"gap": ("crit", "bridge gap"), "triage": ("warn", "not triaged"), "pending-in-zinc": ("rev", "pending in Zinc"), "label": ("mute", "Scala 2 only")}
    order = ["gap", "triage", "pending-in-zinc", "label"]
    out = ['<h2>CI: Zinc\'s scripted suite against each bridge</h2><div class="ci">']
    if summary:
        paras = "".join(f"<p>{link(p)}</p>" for p in summary["paragraphs"])
        out.append(f'<div class="card agent"><h3>Summary</h3><div class="stamp">written by Claude, {esc(summary["generated_at"])}, from the runs below; the reasons in the skip tables are readings of failure output, not confirmed root causes</div>{paras}</div>')
    for src in ci["sources"]:
        run, latest = src.get("run"), src.get("latest")
        h = [f'<div class="card"><h3>{esc(src["label"])}</h3>']
        meta = [f'<a href="{esc(src["pr"])}" target="_blank" rel="noopener">{esc(src["repo"])} PR</a>']
        if run:
            ok = run["conclusion"] == "success"
            meta.append(f'<span class="pill {"good" if ok else "crit"}">{esc(run["conclusion"])}</span> <a href="{esc(run["url"])}" target="_blank" rel="noopener">run {run["id"]}</a> {esc(run["created"][:16].replace("T", " "))} UTC')
            meta.append(f'{run["passed"]} passed, {run["pending"]} pending, {len(src["skips"])} skipped; Zinc <code>{esc((run["zinc_sha"] or "")[:8])}</code> on Scala <code>{esc(run["scala_version"] or "?")}</code>')
        h.append(f'<div class="meta">{" · ".join(meta)}</div>')
        if latest and run and latest["id"] != run["id"]:
            h.append(f'<p class="meta"><span class="pill {"good" if latest["conclusion"] == "success" else "crit"}">newest run: {esc(latest["conclusion"])}</span> <a href="{esc(latest["url"])}" target="_blank" rel="noopener">{latest["id"]}</a> {esc(latest["created"][:16].replace("T", " "))} UTC'
                     + (f', stopped at step "{esc(latest["failed_step"])}" before the tests ran' if latest.get("failed_step") else "") + ".</p>")
        skips = sorted(src.get("skips", []), key=lambda k: (order.index(k["category"]), k["scenario"]))
        if skips:
            counts = {c: sum(1 for k in skips if k["category"] == c) for c in order}
            h.append('<div class="chips">' + "".join(f'<span class="pill {cat[c][0]}">{counts[c]} {cat[c][1]}</span>' for c in order if counts[c]) + "</div>")
            rows = "".join(f'<tr><td class="sc">{esc(k["scenario"])}</td><td><span class="pill {cat[k["category"]][0]}">{cat[k["category"]][1]}</span></td><td>{link(k["reason"])}</td></tr>' for k in skips)
            h.append(f'<div class="tblw"><table class="skips"><thead><tr><th>skipped scenario</th><th>kind</th><th>reason</th></tr></thead><tbody>{rows}</tbody></table></div>')
        out.append("".join(h) + "</div>")
    z = ci["zinc"]
    base = "https://github.com/sbt/zinc/tree/" + z["sha"] + "/zinc/src/sbt-test/"
    chips = lambda xs: '<div class="chips">' + "".join(f'<a href="{base}{esc(x)}" target="_blank" rel="noopener"><code>{esc(x)}</code></a>' for x in xs) + "</div>"
    out.append(f'<div class="card"><h3>Zinc\'s own markers (sbt/zinc develop <code>{esc(z["sha"][:8])}</code>)</h3><p class="meta">No reason is recorded in the repository for any of these. A disabled scenario is skipped by every run above, so a port gap it would show goes unnoticed.</p>'
               f'<div><span class="pill crit">{len(z["disabled"])} disabled</span>{chips(z["disabled"])}</div><div><span class="pill rev">{len(z["pending"])} pending</span>{chips(z["pending"])}</div></div>')
    out.append(f'</div><p class="meta">CI data fetched {esc(ci["fetched_at"])}.</p>')
    return "".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="2025-01-01")
    ap.add_argument("--out", default="scripts/crossport/out")
    ap.add_argument("--verdicts", default="scripts/crossport/verdicts.json")
    ap.add_argument("--pr", default="https://github.com/retronym/zinc/pull/65", help="link shown in the generated-by footer")
    ap.add_argument("--ci", default="scripts/crossport/out/ci.json")
    ap.add_argument("--ci-summary", default="scripts/crossport/ci_summary.json")
    ap.add_argument("--artifact", action="store_true", help="also write report.artifact.html, a page fragment for publishing")
    a = ap.parse_args()
    out = Path(a.out)
    rows = [r for r in json.load(open(out / "report.json")) if r["latest"][:10] >= a.since and r["kind"] in ("semantic", "mechanical")]
    from datetime import date
    import html as htmllib
    from datetime import datetime, timezone
    vpath = Path(a.verdicts)
    ledger = json.dumps(json.load(open(vpath)), indent=1) if vpath.exists() else "{}"
    pr_label = "/".join(a.pr.rstrip("/").split("/")[-4:]).replace("/pull/", "#") if "/pull/" in a.pr else a.pr
    ci_path, sum_path = Path(a.ci), Path(a.ci_summary)
    ci_html = ci_panel(json.load(open(ci_path)), json.load(open(sum_path)) if sum_path.exists() else None) if ci_path.exists() else ""
    html = TEMPLATE.replace("__CI__", ci_html).replace("__VERDICTS__", htmllib.escape(ledger)).replace("__VERDICTS_URL__", "https://github.com/retronym/zinc/blob/cross-port-gap-analysis/scripts/crossport/verdicts.json").replace("__PR_URL__", a.pr).replace("__PR_LABEL__", htmllib.escape(pr_label)).replace("__GENAT__", datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")).replace("__DATA__", json.dumps(rows)).replace("__SINCE__", a.since).replace("__GEN__", date.today().isoformat())
    (out / "report.html").write_text(html)
    if a.artifact:
        frag = html[html.index("<title>"):].replace("<title>Bridge Cross-Port Gaps</title>", "<title>Bridge Port Gaps (WIP)</title>").replace("</head><body>", "").replace("</body></html>", "")
        (out / "report.artifact.html").write_text(frag)
    print(f"wrote {out / 'report.html'} ({len(rows)} rows)")


main()
