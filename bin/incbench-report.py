#!/usr/bin/env python3
"""Render IncBench results (JSON lines) as one self-contained HTML page comparing two checkouts.

    bin/incbench-report.py --a baseline=r-baseline-class.jsonl --b poc=r-poc-class.jsonl \
        [--a ... --b ... for more trees] [--note TEXT] -o report.html

Each --a/--b pair is one tree (class root, trait root, ...); the label comes from the results.
Steps are summarised by their median over repetitions; reverts are left out.
"""
import argparse, html, json, statistics, collections

METRICS = [
    ('recompiled', 'classes recompiled', lambda v: f'{v:.0f}'),
    ('rounds', 'rounds', lambda v: f'{v:.0f}'),
    ('wallMillis', 'wall time', lambda v: f'{v / 1000:.2f} s'),
]
STORED = [
    ('inherited', 'inherited definitions'),
    ('declared', 'declared definitions'),
    ('nameHashes', 'name hashes'),
    ('analysisBytes', 'analysis bytes (full API)'),
    ('minimizedBytes', 'analysis bytes (minimized)'),
]


def load(path):
    steps = collections.defaultdict(list)
    for line in open(path):
        r = json.loads(line)
        if not r['step'].endswith('-revert'):
            steps[r['step']].append(r)
    return steps


def med(rs, key):
    return statistics.median(r[key] for r in rs)


def bars(a, b, fmt):
    top = max(a, b) or 1
    def bar(v, cls):
        return (f'<div class="bar {cls}"><span class="fill" style="width:{100 * v / top:.1f}%"></span>'
                f'<span class="val">{fmt(v)}</span></div>')
    return bar(a, 'a') + bar(b, 'b')


def delta(a, b):
    if a == 0:
        return '<span class="delta same">–</span>'
    d = (b - a) / a
    cls = 'better' if d < -0.005 else 'worse' if d > 0.005 else 'same'
    return f'<span class="delta {cls}">{d:+.0%}</span>'


def tree_section(a_path, b_path):
    a, b = load(a_path), load(b_path)
    first = a['clean'][0]
    la, lb = first['label'], b['clean'][0]['label']
    shape = (f"{first['classes']} classes over {first['modules']} modules, depth {first['depth']}, "
             f"fan-out {first['fanOut']}, {'trait' if first['trait'] else 'abstract class'} root")
    rows = []
    ref_a = med(a['clean-build'], 'wallMillis') if 'clean-build' in a else None
    ref_b = med(b['clean-build'], 'wallMillis') if 'clean-build' in b else None
    order = [s for s in a if s not in ('clean', 'clean-build')] + (['clean-build'] if 'clean-build' in a else [])
    for step in order:
        if step not in b:
            continue
        cells = []
        for key, _, fmt in METRICS:
            va, vb = med(a[step], key), med(b[step], key)
            cells.append(f'<td><div class="pair">{bars(va, vb, fmt)}</div>{delta(va, vb)}</td>')
        per_a = '/'.join(str(m['recompiled']) for m in a[step][-1]['modules'])
        per_b = '/'.join(str(m['recompiled']) for m in b[step][-1]['modules'])
        frac = ''
        if ref_a and ref_b and step != 'clean-build':
            fa, fb = med(a[step], 'wallMillis') / ref_a, med(b[step], 'wallMillis') / ref_b
            frac = f'<div class="per">of a clean build: {fa:.0%} → {fb:.0%}</div>'
        cls = ' class="ref"' if step == 'clean-build' else ''
        rows.append(f'<tr{cls}><th scope="row"><code>{html.escape(step)}</code>'
                    f'<div class="per">per module: {per_a} → {per_b}</div>{frac}</th>{"".join(cells)}</tr>')
    stored = []
    ca, cb = a['clean'][0], b['clean'][0]
    for key, label in STORED:
        if key in ca and key in cb:
            stored.append(f'<tr><th scope="row">{label}</th><td class="num">{ca[key]:,}</td>'
                          f'<td class="num">{cb[key]:,}</td><td class="num">{delta(ca[key], cb[key])}</td></tr>')
    head = ''.join(f'<th scope="col">{label}</th>' for _, label, _ in METRICS)
    return f'''
<section class="tree">
  <h2>{html.escape(shape)}</h2>
  <p class="legend"><span class="key a"></span>{html.escape(la)} <span class="key b"></span>{html.escape(lb)}
    <span class="hint">medians; Δ is B relative to A</span></p>
  <div class="scroll"><table class="steps">
    <thead><tr><th scope="col">edit</th>{head}</tr></thead>
    <tbody>{"".join(rows)}</tbody>
  </table></div>
  <h3>Stored after a clean build</h3>
  <div class="scroll"><table class="stored">
    <thead><tr><th scope="col"></th><th scope="col">{html.escape(la)}</th><th scope="col">{html.escape(lb)}</th><th scope="col">Δ</th></tr></thead>
    <tbody>{"".join(stored)}</tbody>
  </table></div>
</section>'''


STYLE = '''
/* Layout: one reading column; each tree is a section with a step table (paired bars) and a stored-size table. */
:root {
  --bg: #f6f7f9; --panel: #ffffff; --fg: #1b2230; --muted: #5c6678; --rule: #dfe3ea;
  --a: #8a94a6; --b: #2f6fd6; --good: #1d7a46; --bad: #b3362b;
  --font-body: "IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif;
  --font-mono: "IBM Plex Mono", ui-monospace, Menlo, monospace;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg: #11151c; --panel: #171c25; --fg: #e4e8ef; --muted: #98a2b3; --rule: #2a313d;
  --a: #6d7789; --b: #6ea2ff; --good: #5cc98a; --bad: #ff7b6e; color-scheme: dark; } }
:root[data-theme="dark"] {
  --bg: #11151c; --panel: #171c25; --fg: #e4e8ef; --muted: #98a2b3; --rule: #2a313d;
  --a: #6d7789; --b: #6ea2ff; --good: #5cc98a; --bad: #ff7b6e; color-scheme: dark; }
body { background: var(--bg); color: var(--fg); font: 15px/1.55 var(--font-body); margin: 0; }
main { max-width: 1040px; margin: 0 auto; padding-inline: 20px; padding-block: 32px 64px; display: grid; gap: 28px; }
h1 { font-size: 26px; margin: 0; text-wrap: balance; }
h2 { font-size: 17px; margin: 0 0 6px; text-wrap: balance; }
h3 { font-size: 13px; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); margin: 22px 0 8px; }
p { margin: 0; max-width: 72ch; }
code { font-family: var(--font-mono); font-size: 13px; }
.intro { display: grid; gap: 8px; }
.note { color: var(--muted); }
.tree { background: var(--panel); border: 1px solid var(--rule); border-radius: 8px; padding: 20px; min-width: 0; }
.legend { color: var(--muted); font-size: 13px; display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; margin-bottom: 12px; }
.key { display: inline-block; width: 12px; height: 12px; border-radius: 2px; }
.key.a, .bar.a .fill { background: var(--a); }
.key.b, .bar.b .fill { background: var(--b); }
.hint { margin-left: auto; }
.scroll { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--rule); vertical-align: middle; }
thead th { font-size: 12px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; }
.steps td { min-width: 180px; }
.per { font-size: 12px; color: var(--muted); font-family: var(--font-mono); }
.pair { display: grid; gap: 3px; }
.bar { position: relative; height: 18px; background: color-mix(in srgb, var(--rule) 45%, transparent); border-radius: 3px; overflow: hidden; }
.bar .fill { position: absolute; inset: 0 auto 0 0; border-radius: 3px; }
.bar .val { position: relative; font-size: 12px; padding-left: 6px; line-height: 18px; font-family: var(--font-mono); color: var(--fg); }
.delta { font-size: 12px; font-family: var(--font-mono); font-weight: 600; }
.delta.better { color: var(--good); } .delta.worse { color: var(--bad); } .delta.same { color: var(--muted); }
tr.ref th, tr.ref td { border-top: 2px solid var(--rule); }
tr.ref code::after { content: " (reference)"; color: var(--muted); font-family: var(--font-body); }
.num { text-align: right; font-family: var(--font-mono); font-size: 13px; }
.stored th[scope="col"]:not(:first-child) { text-align: right; }
'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--a', action='append', required=True)
    ap.add_argument('--b', action='append', required=True)
    ap.add_argument('--note', default='')
    ap.add_argument('--title', default='IncBench Merkle A/B')
    ap.add_argument('-o', '--out', required=True)
    args = ap.parse_args()
    sections = ''.join(tree_section(a.split('=', 1)[-1], b.split('=', 1)[-1]) for a, b in zip(args.a, args.b))
    page = f'''<title>{html.escape(args.title)}</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;600&family=IBM+Plex+Sans:wght@400;600&display=swap">
<style>{STYLE}</style>
<main>
  <header class="intro">
    <h1>{html.escape(args.title)}</h1>
    <p>Incremental compiles of generated multi-module builds. For each edit: classes recompiled, compiler rounds and wall time,
    then what each side stores after a clean build.</p>
    {f'<p class="note">{html.escape(args.note)}</p>' if args.note else ''}
  </header>
  {sections}
</main>
'''
    open(args.out, 'w').write(page)


if __name__ == '__main__':
    main()
