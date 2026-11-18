#!/usr/bin/env python3
"""Counts the work of the Vietoris-Rips reductions: the columns each one reduces and the columns it skips, and its column
additions. A count run, never a timed one.

    count_work.py --data DATA --out DIR [--cases lattice32,equilateral60] [--fields 2] [--tools ripser.cpp-patched-edge,tda4j]
                  [--ties all|lexicographic,...] [--orders all|larger-index-first,...] [--threads 1] [--timeout 3600]

Each tool is run once per case and field, on the case's own input and threshold, with a build that counts:

  - a ripser.cpp-shaped code built with `-DCOUNT_WORK` (the fork's source files, `run.sh setup ripser`): per degree from 1,
    `simplices`, `apparent`, `cleared`, `columns`, `emergent`, `additions`, `apparent_additions` and more, on stderr.
  - a RedZeD fork instrumented by Bauer's `hybrid/profile.py`, or ripser-forest built with `-DCOUNT_WORK`: one line
    `WORK {json}` at exit, for the whole run.
  - TDA4j's block-parallel engine (`PaperBenchmarkDriver work=true`), once per edge order (`--ties`) and per block
    direction (`--orders`): per degree from 1, the same quantities.

Every row carries the tool's own counters unchanged (`raw`), and the fields they can be compared by, where the tool's
counter has that meaning (blank where it has not):

  - `simplices`  the simplices of the degree, each a column of its boundary or coboundary matrix.
  - `reduced`    the columns that go through the reduction: ripser.cpp's `columns`, TDA4j's `simplices - cleared - apparent`.
  - `skipped`    `simplices - reduced`: the columns that never are reduced (cleared by a pivot of the degree below, or
                 paired by an apparent pair).
  - `additions`  column additions: ripser.cpp's `additions + apparent_additions`, TDA4j's three phases summed.

Only ripser.cpp-shaped and TDA4j counters are comparable that way. RedZeD's hybrid counts different things (`cells`,
`lower_pairs`, `cleared_cells`, `xor_calls`, ...): its row is its counters, unchanged; ripser-forest's `columns` is its
`reduced`. The count builds are made by `run.sh setup` where a fork's source has the counters; the binaries are named
by `<variable>_COUNT_BIN` (`RIPSER_PATCHED_EDGE_BIN` -> `RIPSER_PATCHED_EDGE_COUNT_BIN`), and a tool without one has no row.

Output: `work.csv` (one row per case, field, tool, variant and degree, then a `total` row), `work.md` (the same as a
table, with the bar counts of the tools of a case set side by side: a count build that finds other bars than the rest is
not counting the same computation). A benchmark run never counts: the counting builds are slower, and TDA4j's work mode
is not timed. Environment: TDA4J_CP, and the variables `run.sh` exports for the forks.
"""
import argparse
import csv
import datetime
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bench  # noqa: E402

# TDA4j's `EdgeTies` and `BlockOrder` names, as the facades spell them.
EDGE_TIES = ["lexicographic", "reverse-lexicographic", "colexicographic", "reverse-colexicographic", "shuffled"]
BLOCK_ORDERS = ["larger-index-first", "smaller-index-first"]
DEFAULTS = ("lexicographic", "larger-index-first")
# The binary variable of a stock tool row of each kind, where its row names none.
STOCK_BINARY = {"ripser": ("RIPSER_BIN", "RIPSER_COEFF_BIN"), "redzed": ("REDZED_BIN", None)}
FIELDS = ["case", "task", "p", "tool", "variant", "degree", "status", "simplices", "reduced", "skipped", "additions",
          "raw", "bars", "threshold", "seconds", "note"]


def stamp(message):
    print(f"[{datetime.datetime.now():%H:%M:%S}] {message}", flush=True)


# ---------------------------------------------------------------------------------------------------------
# what the tools print
# ---------------------------------------------------------------------------------------------------------
def parse_ripser_work(text):
    """{degree: {counter: n}} from ripser.cpp's `work in dim 2: simplices 11568 apparent 11447 ...` lines (stderr)."""
    degrees = {}
    for line in text.splitlines():
        m = re.match(r"work in dim (\d+):(.*)$", line.strip())
        if m:
            tokens = m.group(2).split()
            degrees[int(m.group(1))] = {k: int(v) for k, v in zip(tokens[::2], tokens[1::2])}
    return degrees


def parse_work_line(text):
    """The counters of the last `WORK {json}` line a code printed at exit (ripser-forest, the hybrid builds), or None."""
    found = None
    for line in text.splitlines():
        if line.startswith("WORK {"):
            try:
                found = json.loads(line[len("WORK "):])
            except json.JSONDecodeError:
                pass
    return found


def normalized_ripser(counters):
    """The comparable fields of one degree of ripser.cpp's counters. `columns` is the columns it reduced; a simplex that
    is cleared or has an apparent cofacet is among the `simplices` and not among them."""
    return {"simplices": counters["simplices"], "reduced": counters["columns"],
            "skipped": counters["simplices"] - counters["columns"],
            "additions": counters["additions"] + counters["apparent_additions"]}


def normalized_tda4j(counters):
    """The comparable fields of one degree of the driver's `work=true` line: `cleared` (pivots of the degree below) and
    `apparent` (columns with an apparent cofacet) are both skipped."""
    return {"simplices": counters["simplices"], "reduced": counters["reduced"],
            "skipped": counters["cleared"] + counters["apparent"], "additions": counters["additions"]}


def total(per_degree):
    """Sums the comparable fields over degrees; a field one degree lacks is blank in the total."""
    out = {}
    for k in ("simplices", "reduced", "skipped", "additions"):
        values = [d.get(k) for d in per_degree.values()]
        out[k] = sum(values) if values and all(v is not None for v in values) else ""
    return out


# ---------------------------------------------------------------------------------------------------------
# rows
# ---------------------------------------------------------------------------------------------------------
def row(case, p, tool, variant="", degree="total", status="ok", fields=None, raw=None, bars=None, threshold=None,
        seconds=0.0, note=""):
    fields = fields or {}
    return {"case": case.name, "task": case.task, "p": p, "tool": tool, "variant": variant, "degree": degree,
            "status": status, **{k: fields.get(k, "") for k in ("simplices", "reduced", "skipped", "additions")},
            "raw": json.dumps(raw, sort_keys=True) if raw else "", "bars": json.dumps(bars) if bars else "",
            "threshold": "" if threshold is None else repr(threshold), "seconds": f"{seconds:.2f}", "note": note}


def rows_of(case, p, tool, variant, per_degree, raw_per_degree, raw_total, bars, threshold, seconds, note=""):
    """One row per degree, then the total: `per_degree` the comparable fields by degree, `raw_per_degree` the counters
    by degree (or nothing), `raw_total` the whole run's counters where the code prints no degrees."""
    rows = [row(case, p, tool, variant, str(k), fields=per_degree[k], raw=raw_per_degree.get(k),
                threshold=threshold) for k in sorted(per_degree)]
    rows.append(row(case, p, tool, variant, "total", fields=total(per_degree), raw=raw_total, bars=bars,
                    threshold=threshold, seconds=seconds, note=note))
    return rows


def count_variable(variable):
    """`RIPSER_PATCHED_EDGE_BIN` -> `RIPSER_PATCHED_EDGE_COUNT_BIN`: where run.sh puts the counting build of a binary."""
    return variable[:-len("_BIN")] + "_COUNT_BIN"


def counting_options(kind, opts):
    """`opts` with the binary variables of the counting builds in place of the timed ones, so `bench.command` makes the
    command of the same tool on the same input."""
    binary, coeff = STOCK_BINARY[kind]
    out = dict(opts)
    out["binary"] = count_variable(opts.get("binary", binary))
    if kind == "ripser":
        out["coeff_binary"] = count_variable(opts.get("coeff_binary", coeff))
    return out


def counting_binary(kind, opts, p):
    """The counting build this row would run over Z/p, or None."""
    variable = counting_options(kind, opts)["binary" if p == 2 or kind == "redzed" else "coeff_binary"]
    path = os.environ.get(variable, "")
    return (variable, path) if path and Path(path).exists() else None


def count_binary_row(case, data, p, threshold, name, kind, opts, args, out):
    tag = f"{case.name}__p{p}__{name}"
    cmd = bench.pinned(bench.command(kind, name, counting_options(kind, opts), case, data, p, threshold, 0, 1,
                                     out / "bars" / f"{tag}.tsv", args), args)
    if cmd is None:
        return [row(case, p, name, status="missing", note="no counting build over this field")]
    proc = bench.run_proc(cmd, args.timeout, out / "logs" / tag)
    if proc.status != "ok":
        return [row(case, p, name, status=proc.status, seconds=proc.wall, threshold=threshold, note=proc.reason)]
    stderr = Path(str(out / "logs" / tag) + ".err").read_text(errors="replace")
    try:
        bars = bench.parse_binary_output(kind, proc, out / "bars" / f"{tag}.tsv", case)
    except (OSError, ValueError) as e:
        bars = None
        stderr += f"\n(no bars read: {e})"
    per_degree = parse_ripser_work(stderr)
    if per_degree:
        return rows_of(case, p, name, "", {k: normalized_ripser(c) for k, c in per_degree.items()}, per_degree, None,
                       bars, threshold, proc.wall)
    counters = parse_work_line(stderr)
    if counters is None:
        return [row(case, p, name, status="error(output)", bars=bars, seconds=proc.wall, threshold=threshold,
                    note="the counting build printed no counters (expected `work in dim` lines or a `WORK {...}` line)")]
    if "columns" in counters and "streams" in counters:  # ripser-forest: its `columns` are the ones it reduces
        fields, note = {"reduced": counters["columns"]}, "reduced only: the other counters are of its own kind"
    else:  # the hybrid's counters count cells, pairs and symmetric differences: not columns, and not comparable
        fields, note = {}, "counters of the hybrid's own kind: raw only"
    return [row(case, p, name, fields=fields, raw=counters, bars=bars, threshold=threshold, seconds=proc.wall,
                note=note)]


def tda4j_rows(case, data, p, threshold, args, out, variants):
    """One run of `PaperBenchmarkDriver work=true` per (edge order, block direction) in `variants`."""
    rows = []
    cp = Path(os.environ["TDA4J_CP"]).read_text().strip()
    for ties, order in variants:
        variant = f"ties={ties} order={order}"
        tag = f"{case.name}__p{p}__tda4j-{ties}-{order}"
        cmd = bench.pinned(
            ["java"] + bench.jvm_flags(args.jvm_heap, args.jvm_opts, getattr(args, "jvm_fixed_heap", False)) +
            ["-cp", cp, "org.appliedtopology.tda4j.PaperBenchmarkDriver", "task=vr", f"input={data / case.file}",
             f"dim={case.dim}", f"p={p}", "work=true", f"ties={ties}", f"order={order}", f"threads={args.threads}"] +
            ([f"format={case.format}"] if case.format in ("points", "distance") else []) +
            ([f"threshold={threshold!r}"] if threshold is not None else []), args)
        proc = bench.run_proc(cmd, args.timeout, out / "logs" / tag)
        j = bench.last_json(proc.text) if proc.status == "ok" else None
        if j is None:
            status = proc.status if proc.status != "ok" else "error(output)"
            rows.append(row(case, p, "tda4j", variant, status=status, seconds=proc.wall, threshold=threshold,
                            note=proc.reason or "the driver printed no JSON line"))
            continue
        raw = {int(k): v for k, v in j["degrees"].items()}
        rows += rows_of(case, p, "tda4j", variant, {k: normalized_tda4j(c) for k, c in raw.items()}, raw, None,
                        j["bars"], threshold, proc.wall, note=f"{j['threads']} thread(s)")
    return rows


def count_case(case, data, p, args, out, variants):
    threshold = bench.case_threshold(case, data)
    rows = []
    for name, kind, task, opts in bench.TOOLS:
        if task != case.task or kind not in STOCK_BINARY or not bench.tool_selected(name, args.tools):
            continue
        if opts.get("experiment") and not bench.experiment_wanted(name, args.tools):
            continue
        if bench.skip_reason(opts, case, 0, threshold, p) or not counting_binary(kind, opts, p):
            continue  # a tool with no counting build has no row; `report_missing` says which, once
        rows += count_binary_row(case, data, p, threshold, name, kind, opts, args, out)
    if os.environ.get("TDA4J_CP") and Path(os.environ["TDA4J_CP"]).exists() and bench.tool_selected("tda4j", args.tools):
        rows += tda4j_rows(case, data, p, threshold, args, out, variants)
    return rows


def report_missing(args, fields):
    """One line per tool row that was asked for and has no counting build, so a missing row is not mistaken for a zero."""
    for name, kind, task, opts in bench.TOOLS:
        if task != "vr" or kind not in STOCK_BINARY or not bench.tool_selected(name, args.tools):
            continue
        if opts.get("experiment") and not bench.experiment_wanted(name, args.tools):
            continue
        if bench.available(kind, opts, args.python):
            for p in fields:
                if bench.skip_reason(opts, bench.Case("-", "vr", "-", "-", 0, None, ()), 0, None, p) is None \
                        and not counting_binary(kind, opts, p):
                    variable = counting_options(kind, opts)["binary" if p == 2 or kind == "redzed" else "coeff_binary"]
                    stamp(f"{name} (Z/{p}): no counting build ({variable} is not set; `run.sh setup` makes one where the "
                          "fork's source has the counters)")


# ---------------------------------------------------------------------------------------------------------
# output
# ---------------------------------------------------------------------------------------------------------
def label(r):
    return r["tool"] if not r["variant"] else f"{r['tool']} ({r['variant']})"


def thousands(v):
    return f"{int(v):,}" if v not in ("", None) else ""


def write_outputs(out, rows):
    with open(out / "work.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)
    lines = ["# Work of the Vietoris-Rips reductions", "",
             "`reduced` are the columns that go through the reduction, `skipped` the ones that never do (cleared by a pivot "
             "of the degree below, or paired by an apparent pair), `additions` the column additions; `simplices` = "
             "`reduced` + `skipped`. The three are comparable between ripser.cpp-shaped builds and TDA4j; a blank is a "
             "counter that tool does not have in that sense (its own counters are in `work.csv`, column `raw`).", ""]
    groups = {}
    for r in rows:
        groups.setdefault((r["case"], r["p"]), []).append(r)
    for (case, p), group in groups.items():
        lines += [f"## {case}, Z/{p}", "",
                  "| tool | simplices | reduced | skipped | skipped % | additions | reduced by degree | bars | note |",
                  "|---|---|---|---|---|---|---|---|---|"]
        bars_seen = {}
        by_tool = {}
        for r in group:
            by_tool.setdefault((r["tool"], r["variant"]), []).append(r)
        for (tool, variant), rs in by_tool.items():
            t = next((r for r in rs if r["degree"] == "total"), rs[0])
            if t["status"] != "ok":
                lines.append(f"| {label(t)} | | | | | | | | {t['status']}: {t['note']} |")
                continue
            share = (f"{100 * int(t['skipped']) / int(t['simplices']):.1f}"
                     if t["simplices"] not in ("", 0) and t["skipped"] != "" else "")
            by_degree = " / ".join(thousands(r["reduced"]) or "-" for r in rs if r["degree"] != "total")
            bars = json.loads(t["bars"]) if t["bars"] else None
            if bars:
                bars_seen[label(t)] = bars
            lines.append(f"| {label(t)} | {thousands(t['simplices'])} | {thousands(t['reduced'])} | "
                         f"{thousands(t['skipped'])} | {share} | {thousands(t['additions'])} | {by_degree} | "
                         f"{' / '.join(str(bars[k]) for k in sorted(bars, key=int)) if bars else ''} | {t['note']} |")
        distinct = {json.dumps(b, sort_keys=True) for b in bars_seen.values()}
        if len(distinct) > 1:
            lines += ["", "**The bar counts by degree differ between these rows, so they did not count the same "
                      "computation:** " + "; ".join(f"{k}: {v}" for k, v in bars_seen.items())]
        lines.append("")
    (out / "work.md").write_text("\n".join(lines) + "\n")


def selected_cases(spec):
    """The Vietoris-Rips cases to count: the `experiment` ones without `--cases`, else those it names (patterns allowed)."""
    return [c for c in bench.CASES if c.task == "vr" and (bench.tool_selected(c.name, spec) if spec else c.experiment)]


def choose(spec, names, what):
    wanted = names if spec in ("", "all") else [s.strip() for s in spec.split(",") if s.strip()]
    unknown = [s for s in wanted if s not in names]
    if unknown:
        raise SystemExit(f"unknown {what}: {', '.join(unknown)} (one of {', '.join(names)})")
    return wanted


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, help="the directory `bench.py prepare` filled")
    ap.add_argument("--out", required=True, help="where work.md and work.csv go")
    ap.add_argument("--cases", default="", help="comma-separated case names or patterns (default: the tie-heavy "
                    "`experiment` cases, lattice32 and equilateral60)")
    ap.add_argument("--fields", default="2", help="comma-separated primes (default 2)")
    ap.add_argument("--tools", default="", help="comma-separated tool names or patterns, `tda4j` for the TDA4j rows (default: all)")
    ap.add_argument("--ties", default="all", help="TDA4j's edge orders: all or a comma-separated list of " + ", ".join(EDGE_TIES))
    ap.add_argument("--orders", default="all", help="TDA4j's block directions: all or a comma-separated list of " + ", ".join(BLOCK_ORDERS))
    ap.add_argument("--threads", type=int, default=1, help="threads of TDA4j's engine (the additions can depend on how the units split)")
    ap.add_argument("--timeout", type=float, default=3600, help="seconds per run")
    ap.add_argument("--jvm-heap", default="16g")
    ap.add_argument("--jvm-opts", default="")
    ap.add_argument("--python", default=sys.executable)
    args = ap.parse_args()
    data, out = Path(args.data), Path(args.out)
    (out / "bars").mkdir(parents=True, exist_ok=True)
    (out / "logs").mkdir(parents=True, exist_ok=True)
    variants = [(t, o) for t in choose(args.ties, EDGE_TIES, "edge order") for o in choose(args.orders, BLOCK_ORDERS, "block direction")]
    fields = [int(p) for p in args.fields.split(",")]
    # No --cases: the tie-heavy `experiment` cases, where the order of equal edges changes the work; any other case by name.
    cases = selected_cases(args.cases)
    report_missing(args, fields)
    rows = []
    for case in cases:
        if not (data / case.file).exists():
            stamp(f"{case.name}: {data / case.file} not found: run bench.py prepare --data {data}")
            continue
        for p in fields:
            stamp(f"{case.name} Z/{p}")
            found = count_case(case, data, p, args, out, variants)
            rows += found
            for r in found:
                if r["degree"] == "total":
                    stamp(f"    {label(r):52s} {r['status']:10s} reduced={r['reduced']} skipped={r['skipped']} "
                          f"additions={r['additions']} {r['note'][:60]}")
            write_outputs(out, rows)  # after every case: a killed run keeps what it counted
    write_outputs(out, rows)
    print((out / "work.md").read_text())


if __name__ == "__main__":
    main()
