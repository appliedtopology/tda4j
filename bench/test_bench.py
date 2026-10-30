#!/usr/bin/env python3
"""Tests of the benchmark harness itself, on synthetic processes and synthetic results: nothing here needs the tools
being benchmarked (no sbt, GUDHI, ripser or Julia), except `PythonWorkerTests`, which runs the Python worker's adapters
and skips what is not installed. Run from the repository root:

    python3 bench/test_bench.py        (or: python3 -m unittest discover -s bench -p 'test_*.py' -v)

CI does not run these (it has no Python step); they are what makes a change to bench.py checkable on a laptop.
"""
import csv
import importlib.util
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import struct
import time
import unittest
from unittest import mock
from argparse import Namespace
from pathlib import Path

import numpy as np

# By file, not by name: from the repository root `import bench` finds the directory, not bench.py.
_spec = importlib.util.spec_from_file_location("bench_py", Path(__file__).resolve().parent / "bench.py")
bench = importlib.util.module_from_spec(_spec)
sys.modules["bench_py"] = bench
_spec.loader.exec_module(bench)
sys.path.insert(0, str(Path(__file__).resolve().parent))
import sweep_alpha  # noqa: E402  (imports bench.py by name: a second copy of the module, which is fine here)
import sweep_threads  # noqa: E402  (likewise)
import count_cells  # noqa: E402
import dipha_io  # noqa: E402
import phat_io  # noqa: E402
import redzed_io  # noqa: E402


HERE_BENCH = Path(__file__).resolve().parent


def child(code):
    return [sys.executable, "-c", code]


def stub_bottleneck(a, b):
    """The largest difference between sorted diagrams of equal size (enough for these tests), else inf."""
    if len(a) != len(b):
        return math.inf
    return float(np.abs(np.sort(a, axis=0) - np.sort(b, axis=0)).max()) if len(a) else 0.0


class ProcessTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.log = Path(self.dir.name) / "log"

    def test_rss_is_the_childs_not_the_harness(self):
        """ru_maxrss would report the harness's own 250 MB: the kernel folds the parent's high-water mark into the
        child at exec. The sampled VmHWM is the child's."""
        ballast = bytearray(250 * 1024 * 1024)
        ballast[::4096] = b"x" * len(ballast[::4096])  # touch every page
        proc = bench.run_proc(child("import time; x = bytearray(40*1024*1024); x[::4096] = b'y'*(len(x)//4096); "
                                    "time.sleep(0.4)"), 30, self.log)
        self.assertEqual(proc.status, "ok")
        self.assertEqual(proc.rss_src, "proc")
        self.assertGreater(proc.rss_mb, 40)
        self.assertLess(proc.rss_mb, 150, "the harness's 250 MB leaked into the child's peak")
        del ballast

    def test_vm_hwm_of_this_process(self):
        self.assertGreater(bench.vm_hwm_mb(os.getpid()), 1)
        self.assertIsNone(bench.vm_hwm_mb(2 ** 22 + 1))

    def test_timeout_kills_the_group_and_says_so(self):
        started = time.perf_counter()
        proc = bench.run_proc(child("import time; time.sleep(30)"), 0.5, self.log)
        self.assertEqual(proc.status, "timeout")
        self.assertIn("killed after 0.5 s", proc.reason)
        self.assertLess(time.perf_counter() - started, 10)

    def test_exit_codes_and_stderr(self):
        self.assertEqual(bench.run_proc(child("pass"), 30, self.log).status, "ok")
        unsupported = bench.run_proc(child("import sys; print('no', file=sys.stderr); sys.exit(3)"), 30, self.log)
        self.assertEqual((unsupported.status, unsupported.reason), ("unsupported", "no"))
        failed = bench.run_proc(child("import sys; print('boom', file=sys.stderr); sys.exit(1)"), 30, self.log)
        self.assertEqual((failed.status, failed.reason), ("error(1)", "boom"))

    def test_json_line_is_returned(self):
        proc = bench.run_proc(child("print('noise'); print('{\"times_s\": [0.5], \"bars\": {\"0\": 2}}')"), 30, self.log)
        self.assertEqual(bench.last_json(proc.text)["bars"], {"0": 2})


class ClassifyTests(unittest.TestCase):
    RIPSER = ("terminate called after throwing an instance of 'std::overflow_error'\n"
              "  what():  simplex index 260385417812487500 in filtration is larger than maximum index "
              "36028797018963967\n")

    def test_ripsers_index_range_is_a_limit(self):
        status, reason = bench.classify(-6, self.RIPSER)
        self.assertEqual(status, "limit")
        self.assertIn("larger than maximum index 36028797018963967", reason)

    def test_out_of_memory_is_oom(self):
        for text in ["terminate called after throwing an instance of 'std::bad_alloc'",
                     "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space",
                     "FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory",
                     "MemoryError"]:
            self.assertEqual(bench.classify(1, text)[0], "oom", text)

    def test_other_failures_keep_their_code_and_last_line(self):
        self.assertEqual(bench.classify(-6, "a\n\nlast line\n"), ("error(-6)", "last line"))
        self.assertEqual(bench.classify(0, "warnings only"), ("ok", ""))
        self.assertEqual(bench.classify(3, "needs p = 2"), ("unsupported", "needs p = 2"))
        self.assertIn("out-of-memory killer", bench.classify(-9, "")[1])

    def test_a_limit_is_not_a_missing_tool(self):
        self.assertNotEqual(bench.classify(-6, self.RIPSER)[0], "missing")


class CaseTests(unittest.TestCase):
    def test_names_are_unique_and_degenerate_cases_have_a_general_position_twin(self):
        names = [c.name for c in bench.CASES]
        self.assertEqual(len(names), len(set(names)))
        for c in bench.CASES:
            if c.degenerate:
                self.assertIn(c.name + "_gp", names)

    def test_every_tool_and_every_case_runs_a_task_with_a_reference(self):
        for name, kind, task, opts in bench.TOOLS:
            self.assertIn(task, bench.REFERENCE, name)
        for case in bench.CASES:
            self.assertIn(case.task, bench.REFERENCE, case.name)

    def test_alpha_radius_has_about_k_neighbours(self):
        x = np.random.default_rng(1).random((1500, 3))
        k = 30
        r = bench.alpha_radius(x, k)
        counts = (bench.pairwise_distances(x, np.arange(0, 1500, 7)) <= 2 * r).sum(axis=1) - 1
        self.assertLess(abs(np.median(counts) - k), 0.2 * k)
        self.assertEqual(r, bench.alpha_radius(x, k), "same data, same radius")

    def test_jitter_breaks_cospherical_points_and_is_reproducible(self):
        rng = np.random.default_rng(2)
        sphere = rng.normal(size=(200, 4))
        sphere /= np.linalg.norm(sphere, axis=1)[:, None]
        moved = bench.jitter(sphere, np.random.default_rng(3))
        self.assertGreater(np.ptp(np.linalg.norm(moved, axis=1)), 1e-3)
        self.assertLess(np.abs(moved - sphere).max(), 0.05)
        np.testing.assert_array_equal(moved, bench.jitter(sphere, np.random.default_rng(3)))

    def test_skip_reasons(self):
        alpha = bench.Case("c", "alpha", "c.txt", "points", 2)
        vr = bench.Case("v", "vr", "v.txt", "points", 2)
        self.assertIn("ambient dimension 5", bench.skip_reason({"max_ambient": 4}, alpha, 5, None))
        self.assertIsNone(bench.skip_reason({"max_ambient": 4}, alpha, 4, None))
        self.assertIn("radius", bench.skip_reason({"needs_radius": True}, alpha, 2, None))
        self.assertIsNone(bench.skip_reason({"needs_radius": True}, alpha, 2, 1.0))
        self.assertIn("no radius", bench.skip_reason({"no_radius": True}, alpha, 2, 1.0))
        self.assertIsNone(bench.skip_reason({"no_radius": True}, vr, 2, 1.0), "a Rips threshold is not a radius")
        self.assertIsNone(bench.skip_reason({}, alpha, 10, 1.0))

    def test_dqp_and_bowyer_watson_are_configured_as_documented(self):
        tools = {name: opts for name, _, task, opts in bench.TOOLS if task == "alpha"}
        self.assertEqual(tools["tda4j-bw"]["max_ambient"], 4)
        self.assertTrue(tools["tda4j-dqp"]["needs_radius"])
        self.assertTrue(tools["tda4j-fastalpha"]["no_radius"])
        self.assertEqual(bench.jvm_twin("tda4j-js-dqp"), "tda4j-dqp")

    def test_dionysus_and_oat_are_vietoris_rips_comparators_and_oat_is_characteristic_zero(self):
        rows = {name: (task, opts) for name, kind, task, opts in bench.TOOLS if name.startswith(("dionysus", "oat"))}
        self.assertEqual(set(rows), {"dionysus", "dionysus-homology", "dionysus-cocycles", "oat", "oat-cycles"})
        self.assertEqual({task for task, _ in rows.values()}, {"vr"}, "neither has a cubical or an alpha complex to run")
        self.assertEqual(bench.CHARACTERISTIC_ZERO, {"oat", "oat-cycles"})
        names = {name for name, _, _, _ in bench.TOOLS}
        for name, (_, opts) in rows.items():
            self.assertIn(opts.get("slower_than", "dionysus" if name.startswith("dionysus") else "oat"), names)
        for name in ("dionysus", "oat"):
            self.assertNotIn("slower_than", rows[name][1], "the fast variant is the one the others wait for")
        # the module the harness imports to test for the tool is not always the tool's name
        for pytool in ("dionysus", "oat"):
            self.assertIsInstance(bench.available("py", {"pytool": pytool}, sys.executable), bool)

    def test_the_default_policy_follows_the_live_data(self):
        flags = bench.jvm_flags("16g", "-Xss8m")
        self.assertEqual(flags[:2], ["-Xmx16g", "-XX:+UseSerialGC"])
        self.assertFalse([f for f in flags if f.startswith("-Xms")], "no pinned heap: RSS would be allocation")
        self.assertEqual(flags[-1], "-Xss8m")
        self.assertEqual(bench.jvm_flags("4g", "-Xss8m -ea")[-2:], ["-Xss8m", "-ea"])

    def test_the_fixed_heap_policy_is_pinned_with_the_parallel_collector(self):
        self.assertEqual(bench.jvm_flags("16g", fixed_heap=True), ["-Xmx16g", "-Xms16g", "-XX:+UseParallelGC"])


class AgreementTests(unittest.TestCase):
    def test_equal_barcodes_agree_exactly(self):
        bars = {0: [(0.0, 1.0), (0.0, math.inf)], 1: [(0.5, 0.9)]}
        self.assertEqual(bench.agreement(bars, bars, 1, stub_bottleneck), (0.0, ""))

    def test_denormal_distances_are_zero(self):
        bars = {0: [(0.0, 1.0)]}
        self.assertEqual(bench.agreement(bars, bars, 0, lambda a, b: 9.49e-309), (0.0, ""))

    def test_essential_count_mismatch_names_the_degree_and_the_counts(self):
        a = {0: [(0.0, math.inf)], 1: [(0.2, math.inf)]}
        b = {0: [(0.0, math.inf)], 1: [(0.2, math.inf), (0.3, math.inf)]}
        dist, note = bench.agreement(a, b, 1, stub_bottleneck)
        self.assertTrue(math.isinf(dist))
        self.assertEqual(note, "H1 has 1 essential bars against the reference's 2")

    def test_significant_counts_drop_near_zero_bars_but_keep_essential_ones(self):
        bars = {0: [(0.0, math.inf), (0.0, 1e-7)], 1: [(0.1, 0.5), (0.2, 0.2 + 1e-9)], 2: [(0.3, 0.3 + 1e-8)]}
        self.assertEqual(bench.significant_counts(bars, 1e-5), {"0": 1, "1": 1})

    def test_tolerances(self):
        self.assertAlmostEqual(bench.tolerance("", "gudhi", 1000), 1e-5)
        self.assertAlmostEqual(bench.tolerance("1.8", "ripser.cpp", 1000), 1.8e-5)
        self.assertAlmostEqual(bench.tolerance("1.8", "javaplex", 1000), 1.8e-3, places=9)


def write_runs(directory, rows):
    with open(Path(directory) / "runs.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=bench.RUNS_COLUMNS)
        w.writeheader()
        for r in rows:
            w.writerow(r)


def run_row(case, tool, mode, status, wall="", median="", rss="100", reason="", task="vr", p="17", **more):
    return {"case": case, "task": task, "p": p, "tool": tool, "mode": mode, "status": status, "wall_s": wall,
            "median_s": median, "maxrss_mb": rss, "reason": reason, "rss_src": "proc", "threshold": "1.0",
            "multithreaded": "False", **more}


class SummaryTests(unittest.TestCase):
    """A synthetic run of two Vietoris-Rips cases: on the first every tool finishes; on the second ripser.cpp hits its
    index range, so the 'best other' baseline silently becomes a slower tool."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        out = Path(self.dir.name)
        (out / "bars").mkdir()
        bars = "0\t0.0\tinf\n1\t0.5\t0.9\n1\t0.4\t0.40000001\n"  # the last one is near zero
        rows = []

        def tool(case, name, warm, status="ok", bars_text=bars, **more):
            if status == "ok":
                rows.append(run_row(case, name, "cold", "ok", wall=str(warm + 1), median=str(warm + 1),
                                    bars=json.dumps({"0": 1, "1": 2}), **more))
                rows.append(run_row(case, name, "warm", "ok", wall=str(warm), median=str(warm), **more))
                (out / "bars" / f"{case}__p17__{name}.tsv").write_text(bars_text)
            else:
                rows.append(run_row(case, name, "cold", status, wall="20", reason=more.pop("reason", ""), **more))

        for name, warm in [("ripser.cpp", 1.0), ("gudhi", 2.0), ("tda4j", 5.0), ("tda4j-cocycles", 3.0)]:
            tool("small", name, warm, heap_peak_mb="800" if name.startswith("tda4j") else "")
        tool("big", "ripser.cpp", 0, "limit",
             reason="what():  simplex index 260385417812487500 in filtration is larger than maximum index 36028797018963967")
        tool("big", "gudhi", 20.0)
        tool("big", "tda4j", 15.0)
        tool("big", "tda4j-cocycles", 10.0, bars_text="0\t0.0\tinf\n0\t0.1\tinf\n1\t0.5\t0.9\n")
        rows.append(run_row("small", "ripser.py", "-", "missing"))
        write_runs(out, rows)
        bench.summarize(out, bottleneck=stub_bottleneck)
        self.out = out
        self.md = (out / "summary.md").read_text()
        with open(out / "summary.csv") as f:
            self.csv = list(csv.DictReader(f))

    def row(self, case, tool):
        return next(r for r in self.csv if r["case"] == case and r["tool"] == tool)

    def test_a_failure_is_explained_not_just_numbered(self):
        self.assertIn("## Failures and limits", self.md)
        self.assertIn("| big | 17 | ripser.cpp | limit | what():  simplex index 260385417812487500", self.md)
        self.assertEqual(self.row("big", "ripser.cpp")["status"], "limit")
        self.assertIn("Not installed: ripser.py.", self.md)

    def test_the_reference_against_itself_is_exactly_zero(self):
        self.assertEqual(self.row("small", "ripser.cpp")["vs_reference"], "0")
        self.assertNotIn("e-30", self.md)

    def test_near_zero_bars_do_not_make_agreeing_rows_differ(self):
        self.assertEqual(json.loads(self.row("small", "tda4j")["bars_sig"]), {"0": 1, "1": 1})
        self.assertEqual(self.row("small", "tda4j")["agrees"], "True")

    def test_an_essential_mismatch_says_where(self):
        self.assertEqual(self.row("big", "tda4j-cocycles")["note"], "")  # no reference bars on 'big': nothing compared
        # compare against gudhi instead on 'small': give tda4j-cocycles two essential bars
        (self.out / "bars" / "small__p17__tda4j-cocycles.tsv").write_text("0\t0.0\tinf\n0\t0.1\tinf\n1\t0.5\t0.9\n")
        bench.summarize(self.out, bottleneck=stub_bottleneck)
        with open(self.out / "summary.csv") as f:
            again = {(r["case"], r["tool"]): r for r in csv.DictReader(f)}
        row = again[("small", "tda4j-cocycles")]
        self.assertEqual((row["vs_reference"], row["agrees"]), ("inf", "False"))
        self.assertIn("H0 has 2 essential bars against the reference's 1", row["note"])
        self.assertIn("H0 has 2 essential bars against the reference's 1", (self.out / "summary.md").read_text())

    def test_a_row_without_a_finished_reference_is_marked_and_left_out_of_the_means(self):
        self.assertIn("| big | 17 | tda4j | gudhi † |", self.md)
        self.assertNotIn("| big | 17 | tda4j | gudhi |", self.md)
        # only 'small' enters the means: tda4j is 5.0 / 1.0 = 5 times the reference, over one case, finished 2 of 2
        self.assertIn("vr, tda4j, p=17: 5.00x the reference (median 5.00, range 5.00 to 5.00) and 5.00x the best "
                      "other tool, over 1 cases; finished 2 of 2", self.md)

    def test_peak_memory_and_heap_columns(self):
        self.assertEqual(self.row("small", "tda4j")["heap_peak_mb"], "800")
        self.assertEqual(self.row("small", "ripser.cpp")["heap_peak_mb"], "")
        self.assertEqual(self.row("small", "tda4j")["rss_src"], "proc")

    def test_rusage_rss_is_marked_as_an_upper_bound(self):
        out = Path(self.dir.name)
        with open(out / "runs.csv") as f:
            rows = list(csv.DictReader(f))
        for r in rows:
            if r["case"] == "small" and r["tool"] == "gudhi":
                r["rss_src"] = "rusage"
        write_runs(out, rows)
        bench.summarize(out, bottleneck=stub_bottleneck)
        self.assertRegex((out / "summary.md").read_text(), r"\| small \| 17 \| gudhi \| ok \|.*\| ≤100 \|")


class OldResultsTests(unittest.TestCase):
    def test_a_runs_csv_from_before_the_reason_and_heap_columns_still_summarizes(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "bars").mkdir()
            old = ["case", "task", "p", "tool", "mode", "status", "wall_s", "times_s", "median_s", "maxrss_mb", "bars",
                   "threshold", "multithreaded", "info"]
            with open(out / "runs.csv", "w", newline="") as f:
                w = csv.writer(f)
                w.writerow(old)
                for tool, status in [("ripser.cpp", "ok"), ("tda4j", "ok"), ("gudhi", "error(-6)")]:
                    w.writerow(["c", "vr", "17", tool, "cold", status, "3.0", "[2.0]", "2.0", "130.0", "{}", "1.0",
                                "False", ""])
                    if status == "ok":
                        w.writerow(["c", "vr", "17", tool, "warm", status, "2.0", "[1.5]", "1.5", "130.0", "{}", "1.0",
                                    "False", ""])
                        (out / "bars" / f"c__p17__{tool}.tsv").write_text("0\t0.0\tinf\n")
            bench.summarize(out, bottleneck=stub_bottleneck)
            md = (out / "summary.md").read_text()
            self.assertIn("| c | 17 | gudhi | error(-6) |", md)
            self.assertIn("## Failures and limits", md)


class DegenerateTests(unittest.TestCase):
    def test_a_disagreement_on_a_degenerate_case_is_dagger_marked(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "bars").mkdir()
            rows = []
            for name, bars in [("gudhi", "0\t0.0\tinf\n1\t0.5\t0.9\n"), ("tda4j", "0\t0.0\tinf\n1\t0.5\t0.9\n1\t0.2\t0.6\n")]:
                rows.append(run_row("sphere3_192_alpha", name, "cold", "ok", wall="2", median="2", task="alpha",
                                    threshold=""))
                rows.append(run_row("sphere3_192_alpha", name, "warm", "ok", wall="1", median="1", task="alpha",
                                    threshold=""))
                (out / "bars" / f"sphere3_192_alpha__p17__{name}.tsv").write_text(bars)
            write_runs(out, rows)
            bench.summarize(out, bottleneck=stub_bottleneck)
            md = (out / "summary.md").read_text()
            self.assertIn("| False† |", md)


class CharacteristicZeroTests(unittest.TestCase):
    """OAT computes over the rationals whatever the row's p: a disagreement with the F_p reference is noted as such."""

    def summarize(self, oat_bars):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        out = Path(d.name)
        (out / "bars").mkdir()
        rows = []
        for name, bars in [("ripser.cpp", "0\t0.0\tinf\n1\t0.5\tinf\n"), ("oat", oat_bars)]:
            rows.append(run_row("torsion", name, "cold", "ok", wall="2", median="2", p="2"))
            rows.append(run_row("torsion", name, "warm", "ok", wall="1", median="1", p="2"))
            (out / "bars" / f"torsion__p2__{name}.tsv").write_text(bars)
        write_runs(out, rows)
        bench.summarize(out, bottleneck=stub_bottleneck)
        with open(out / "summary.csv") as f:
            return (out / "summary.md").read_text(), {r["tool"]: r for r in csv.DictReader(f)}

    def test_a_difference_is_noted_as_the_field_not_hidden(self):
        md, rows = self.summarize("0\t0.0\tinf\n")  # no essential H1 class over Q
        self.assertEqual(rows["oat"]["agrees"], "False")
        self.assertIn("H1 has 0 essential bars against the reference's 1; computed over the rationals, the reference "
                      "over F_2", rows["oat"]["note"])
        self.assertIn("oat compute over the rationals whatever the p of the row", md)
        self.assertEqual(rows["ripser.cpp"]["note"], "")

    def test_agreement_needs_no_note(self):
        _, rows = self.summarize("0\t0.0\tinf\n1\t0.5\tinf\n")
        self.assertEqual((rows["oat"]["agrees"], rows["oat"]["note"]), ("True", ""))


class RunLoopTests(unittest.TestCase):
    """The loop around run_one: skipping what cannot run, and not repeating a timeout at the next field."""

    def run_loop(self, statuses, rerun=False, tools=None):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            data, out = Path(d) / "data", Path(d) / "out"
            data.mkdir()
            np.savetxt(data / "pts.txt", np.random.default_rng(0).random((20, 5)))
            case = bench.Case("five", "alpha", "pts.txt", "points", 1, radius_k=3)
            saved = bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env
            bench.CASES = [case]
            bench.TOOLS = tools or [("slow", "py", "alpha", {}), ("bw", "py", "alpha", {"max_ambient": 4}),
                                    ("fast", "py", "alpha", {"no_radius": True})]
            bench.available = lambda kind, opts, python: True
            bench.write_env = lambda out, args: None

            def fake(w, f, data, out, case, p, threshold, name, kind, opts, args):
                calls.append((name, p, threshold))
                return statuses.get((name, p), "ok")
            bench.run_one = fake
            try:
                args = Namespace(data=str(data), out=str(out), cases="", tasks="", tools="", fields="2,17",
                                 python="python3", rerun_timeouts=rerun)
                bench.run(args)
                with open(out / "runs.csv") as f:
                    rows = list(csv.DictReader(f))
            finally:
                bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env = saved
        return calls, rows

    def test_unsupported_combinations_are_recorded_without_running(self):
        calls, rows = self.run_loop({})
        self.assertEqual([c[0] for c in calls], ["slow", "slow"])  # bw: ambient 5 > 4; fast: the case has a radius
        reasons = {(r["tool"], r["p"]): (r["status"], r["reason"]) for r in rows}
        self.assertEqual(reasons[("bw", "2")][0], "unsupported")
        self.assertIn("ambient dimension 5", reasons[("bw", "2")][1])
        self.assertIn("no radius", reasons[("fast", "17")][1])
        self.assertTrue(all(c[2] is not None and c[2] > 0 for c in calls), "the radius comes from radius_k")

    def test_a_timeout_is_not_repeated_at_the_next_field(self):
        calls, rows = self.run_loop({("slow", 2): "timeout"})
        self.assertEqual(calls, [("slow", 2, calls[0][2])])
        again = next(r for r in rows if r["tool"] == "slow" and r["p"] == "17")
        self.assertEqual(again["status"], "timeout")
        self.assertIn("timed out at p=2", again["reason"])

    def test_rerun_timeouts_opts_out(self):
        calls, _ = self.run_loop({("slow", 2): "timeout"}, rerun=True)
        self.assertEqual([(c[0], c[1]) for c in calls], [("slow", 2), ("slow", 17)])

    def test_an_error_at_one_field_does_not_skip_the_other(self):
        calls, _ = self.run_loop({("slow", 2): "error(1)"})
        self.assertEqual([(c[0], c[1]) for c in calls], [("slow", 2), ("slow", 17)])


class SlowerVariantTests(unittest.TestCase):
    """A variant that does more work than a tool which timed out is not run, so its timeout is not spent again."""

    def run_loop(self, statuses, rerun=False):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            data, out = Path(d) / "data", Path(d) / "out"
            data.mkdir()
            np.savetxt(data / "pts.txt", np.random.default_rng(0).random((20, 3)))
            case = bench.Case("pts", "vr", "pts.txt", "points", 1)
            saved = bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env
            bench.CASES = [case]
            bench.TOOLS = [("base", "py", "vr", {}), ("extras", "py", "vr", {"slower_than": "base"}),
                           ("other", "py", "vr", {})]
            bench.available = lambda kind, opts, python: True
            bench.write_env = lambda out, args: None

            def fake(w, f, data, out, case, p, threshold, name, kind, opts, args):
                calls.append((name, p))
                return statuses.get(name, "ok")
            bench.run_one = fake
            try:
                args = Namespace(data=str(data), out=str(out), cases="", tasks="", tools="", fields="2,17",
                                 python="python3", rerun_timeouts=rerun)
                bench.run(args)
                with open(out / "runs.csv") as f:
                    rows = list(csv.DictReader(f))
            finally:
                bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env = saved
        return calls, rows

    def test_a_timeout_of_the_faster_variant_skips_the_slower_one_at_every_field(self):
        calls, rows = self.run_loop({"base": "timeout"})
        self.assertEqual(calls, [("base", 2), ("other", 2), ("other", 17)])
        skipped = [r for r in rows if r["tool"] == "extras"]
        self.assertEqual([(r["p"], r["status"]) for r in skipped], [("2", "timeout"), ("17", "timeout")])
        self.assertIn("base, the faster variant, timed out on this case", skipped[0]["reason"])

    def test_running_out_of_memory_is_passed_on_as_oom(self):
        _, rows = self.run_loop({"base": "oom"})
        extras = next(r for r in rows if r["tool"] == "extras")
        self.assertEqual(extras["status"], "oom")
        self.assertIn("ran out of memory", extras["reason"])

    def test_an_error_or_a_finished_run_does_not_skip_it(self):
        for status in ("ok", "error(1)", "limit"):
            calls, _ = self.run_loop({"base": status})
            self.assertIn(("extras", 2), calls, status)

    def test_rerun_timeouts_runs_it_anyway(self):
        calls, _ = self.run_loop({"base": "timeout"}, rerun=True)
        self.assertIn(("extras", 2), calls)


class AddressSpaceTests(unittest.TestCase):
    def test_a_runaway_allocation_under_a_cap_is_oom_not_a_crash_of_the_machine(self):
        with tempfile.TemporaryDirectory() as d:
            proc = bench.run_proc(child("x = bytearray(2 * 1024**3); print(len(x))"), 60, Path(d) / "log",
                                  address_space_mb=600)
        self.assertEqual(proc.status, "oom")
        self.assertIn("MemoryError", proc.reason)

    def test_no_cap_by_default(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(bench.run_proc(child("x = bytearray(300 * 1024**2)"), 60, Path(d) / "log").status, "ok")


class TopDegreeTests(unittest.TestCase):
    def test_dim_top_is_one_less_than_the_ambient_dimension(self):
        self.assertEqual([sweep_alpha.degree_for("top", d) for d in (3, 8)], [2, 7])
        self.assertEqual(sweep_alpha.degree_for("2", 8), 2)

    def test_a_number_never_asks_for_more_than_the_top_degree(self):
        self.assertEqual([sweep_alpha.degree_for("4", d) for d in (2, 3, 5, 9)], [1, 2, 4, 4])

    def test_the_plane_path_is_one_series_over_n_in_two_dimensions(self):
        preset = sweep_alpha.PATHS["plane"]
        args = Namespace(shapes=preset["shapes"], points=preset["points"], dims=preset["dims"], grid=preset["grid"],
                         extra="")
        cells = sweep_alpha.plan(args)
        self.assertEqual({(shape, d) for shape, _, d, _ in cells}, {("interior", 2)})
        self.assertEqual([n for _, n, _, _ in cells], sorted(n for _, n, _, _ in cells))
        self.assertTrue(all(series[0] == "n" for _, _, _, series in cells))

    def test_a_top_only_barcode_is_compared_in_its_degree_only(self):
        a = {2: [(0.1, 0.5)]}
        b = {0: [(0.0, math.inf)], 1: [(0.2, 0.4)], 2: [(0.1, 0.5)]}
        bottleneck = lambda x, y: float(abs(len(x) - len(y)))
        self.assertGreater(bench.agreement(a, b, 2, bottleneck)[0], 0)
        self.assertEqual(bench.agreement(a, b, 2, bottleneck, [2])[0], 0)
        a[2] = [(0.1, 0.5), (0.2, 0.3)]
        self.assertGreater(bench.agreement(a, b, 2, bottleneck, [2])[0], 0)


class CubePointsTests(unittest.TestCase):
    def test_interior_points_are_inside_the_cube_and_deterministic(self):
        x = sweep_alpha.points("interior", 40, 7, 0)
        self.assertEqual(x.shape, (40, 7))
        self.assertTrue(((x > 0) & (x < 1)).all())
        np.testing.assert_array_equal(x, sweep_alpha.points("interior", 40, 7, 0))
        self.assertFalse(np.array_equal(x, sweep_alpha.points("interior", 40, 7, 1)))
        self.assertFalse(np.array_equal(x, sweep_alpha.points("surface", 40, 7, 0)))

    def test_surface_points_have_exactly_one_coordinate_on_a_facet(self):
        x = sweep_alpha.points("surface", 400, 6, 3)
        self.assertTrue(((x >= 0) & (x <= 1)).all())
        self.assertTrue((((x == 0) | (x == 1)).sum(axis=1) == 1).all())

    def test_surface_points_are_uniform_over_the_facets(self):
        x = sweep_alpha.points("surface", 20000, 4, 0)
        counts = [int((x[:, i] == v).sum()) for i in range(4) for v in (0.0, 1.0)]
        self.assertEqual(sum(counts), 20000)
        self.assertLess(max(counts) - min(counts), 0.1 * 20000 / 8)

    def test_d_plus_one_points_on_a_facet_are_not_in_general_position(self):
        x = np.random.default_rng(0).random((12, 5))
        self.assertEqual(sweep_alpha.densest_facet(x), 0)
        x[:6, 2] = 1.0  # six points in the hyperplane x_2 = 1 of R^5: d + 1 = 6
        self.assertEqual(sweep_alpha.densest_facet(x), 6)
        self.assertGreaterEqual(sweep_alpha.densest_facet(x), 5 + 1)

    def test_extra_cells(self):
        self.assertEqual(sweep_alpha.parse_extra("800:11:interior, 60:8:surface"), [(800, 11, "interior"), (60, 8, "surface")])
        self.assertEqual(sweep_alpha.parse_extra(""), [])


class PlanTests(unittest.TestCase):
    def test_a_draw_in_the_interior_is_in_general_position(self):
        self.assertFalse(sweep_alpha.is_degenerate(sweep_alpha.points("interior", 40, 11, 0)))
        self.assertFalse(sweep_alpha.is_degenerate(sweep_alpha.points("surface", 40, 12, 0)))

    def test_there_is_no_sphere_to_sample(self):
        with self.assertRaises(Exception):
            sweep_alpha.parse_extra("40:11:sphere")

    def test_grid_groups(self):
        self.assertEqual(sweep_alpha.parse_grid("11:40,100;14:40,75,125"), [(11, [40, 100]), (14, [40, 75, 125])])
        self.assertEqual(sweep_alpha.parse_grid(""), [])

    def test_the_plan_runs_each_series_in_order_and_the_extra_cells_on_their_own(self):
        args = Namespace(shapes="interior", points="40,60", dims="5,6", grid="11:40,100;14:40", extra="800:11:interior")
        cells = sweep_alpha.plan(args)
        self.assertEqual([(c[0], c[1], c[2]) for c in cells if c[3] == ("d", "interior", 40)],
                         [("interior", 40, 5), ("interior", 40, 6)])
        self.assertEqual([(c[1], c[2]) for c in cells if c[3] == ("n", "interior", 11)], [(40, 11), (100, 11)])
        self.assertEqual([c for c in cells if c[3] is None], [("interior", 800, 11, None)])
        self.assertNotIn("surface", {c[0] for c in cells})

    def test_no_other_papers_numbers_are_in_the_script(self):
        self.assertFalse(hasattr(sweep_alpha, "THESIS_HELIX_SECONDS"))

    def test_the_cech_tool_runs_the_cech_task_and_the_others_the_alpha_task(self):
        seen = []
        saved = sweep_alpha.bench.command, sweep_alpha.bench.run_proc
        sweep_alpha.bench.command = lambda kind, name, opts, case, data, p, thr, w, t, out, args: (
            seen.append((name, case.task)) or ["x"])
        sweep_alpha.bench.run_proc = lambda cmd, timeout, log, **kw: sweep_alpha.bench.Proc(
            "ok", 1.0, 100.0, '{"times_s": [1.0], "bars": {"0": 1}}')
        try:
            with tempfile.TemporaryDirectory() as d:
                (Path(d) / "sweepalpha").mkdir()
                (Path(d) / "bars").mkdir()
                args = Namespace(data=d, dim=2, field=17, py_mem_gb=0, timeout=10, warm_cap=0, trials=0)
                rows = [sweep_alpha.run_cell(name, "tda4j", {}, "interior", 10, 5, 0, "d", args, None, Path(d))
                        for name in ("tda4j-cech", "tda4j-dqp")]
        finally:
            sweep_alpha.bench.command, sweep_alpha.bench.run_proc = saved
        self.assertEqual(seen, [("tda4j-cech", "cech"), ("tda4j-dqp", "alpha")])
        self.assertEqual([r["status"] for r in rows], ["ok", "ok"])
        self.assertEqual(rows[0]["series"], "d")


class CyclooctaneTests(unittest.TestCase):
    def test_the_vietoris_rips_and_alpha_cases_share_the_data_and_the_radii_match(self):
        cases = {c.name: c for c in bench.CASES}
        vr, alpha = cases["cyclooctane"], cases["cyclooctane_alpha"]
        self.assertEqual((vr.task, vr.threshold, vr.dim, vr.file), ("vr", 0.5, 2, "cyclooctane.txt"))
        self.assertEqual((alpha.task, alpha.threshold, alpha.dim, alpha.file), ("alpha", 0.25, 2, "cyclooctane.txt"))
        self.assertEqual(alpha.threshold * 2, vr.threshold, "two balls of radius r meet at distance 2r")

    def test_a_changed_file_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            saved = bench.fetch
            bench.fetch = lambda url, dest: Path(dest).write_bytes(b"not the file")
            try:
                with self.assertRaises(SystemExit) as caught:
                    bench.cyclooctane_points(Path(d))
            finally:
                bench.fetch = saved
        self.assertIn("SHA-256", str(caught.exception))

    @unittest.skipUnless(importlib.util.find_spec("scipy"), "scipy is not installed")
    def test_the_mat_file_becomes_a_text_file_of_6040_points_in_24_dimensions(self):
        import scipy.io
        x = np.random.default_rng(0).standard_normal((6040, 24))
        with tempfile.TemporaryDirectory() as d:
            mat = Path(d) / "pointsCycloOctane.mat"
            scipy.io.savemat(str(mat), {"pointsCycloOctane": x})
            saved = bench.fetch, bench.CYCLOOCTANE_SHA256
            bench.fetch, bench.CYCLOOCTANE_SHA256 = (lambda url, dest: None), bench.sha256_of(mat)
            try:
                bench.cyclooctane_points(Path(d))
            finally:
                bench.fetch, bench.CYCLOOCTANE_SHA256 = saved
            np.testing.assert_array_equal(np.loadtxt(Path(d) / "cyclooctane.txt"), x)

    def test_the_summary_reports_the_bars_alive_at_the_threshold_per_field(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "bars").mkdir()
            rows = []
            for p, bars in [(2, "0\t0.0\tinf\n1\t0.3\tinf\n2\t0.2\tinf\n2\t0.21\tinf\n2\t0.1\t0.15\n"),
                            (17, "0\t0.0\tinf\n1\t0.3\tinf\n1\t0.31\tinf\n")]:
                for mode in ("cold", "warm"):
                    rows.append(run_row("cyclooctane_alpha", "tda4j-dqp", mode, "ok", wall="2", median="2", task="alpha",
                                        p=str(p), threshold="0.25"))
                (out / "bars" / f"cyclooctane_alpha__p{p}__tda4j-dqp.tsv").write_text(bars)
            write_runs(out, rows)
            bench.summarize(out, bottleneck=stub_bottleneck)
            with open(out / "summary.csv") as f:
                found = {r["p"]: r["betti"] for r in csv.DictReader(f)}
            md = (out / "summary.md").read_text()
        self.assertEqual(found, {"2": "1,1,2", "17": "1,2,0"})
        self.assertNotIn("Known Betti", md, "no number from a paper is compared with")
        self.assertEqual(bench.essential_counts({0: [(0.0, math.inf)], 2: [(0.1, 0.2)]}, 2), (1, 0, 0))


class SweepLoopTests(unittest.TestCase):
    def run_sweep(self, fake, argv_extra=()):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            saved = sweep_alpha.run_cell, sweep_alpha.bench.available, sys.argv
            sweep_alpha.run_cell = lambda name, kind, opts, shape, n, dd, seed, series, args, bench_args, out: (
                calls.append((name, shape, n, dd, seed)) or fake(name, shape, n, dd, seed))
            sweep_alpha.bench.available = lambda kind, opts, python: True
            sys.argv = ["sweep_alpha.py", "--data", d, "--out", str(Path(d) / "out"), "--dims", "5,6,7,8", "--points", "40",
                        "--shapes", "interior", "--seeds", "2", "--tools", "tda4j-helix,tda4j-dqp", *argv_extra]
            try:
                sweep_alpha.main()
                with open(Path(d) / "out" / "sweep.csv") as f:
                    rows = list(csv.DictReader(f))
                md = (Path(d) / "out" / "sweep.md").read_text()
            finally:
                sweep_alpha.run_cell, sweep_alpha.bench.available, sys.argv = saved
        return calls, rows, md

    @staticmethod
    def row(name, shape, n, d, seed, status="ok", seconds="1.5", **more):
        base = {c: "" for c in sweep_alpha.COLUMNS}
        base.update({"shape": shape, "n": n, "d": d, "seed": seed, "tool": name, "status": status, "warm_median_s": seconds if status == "ok" else "",
                     "rss_mb": "500", "rss_src": "proc", "degenerate": "False"})
        base.update(more)
        return base

    def test_a_series_stops_at_its_first_timeout_and_the_other_tools_go_on(self):
        def fake(name, shape, n, d, seed):
            return self.row(name, shape, n, d, seed, "timeout" if name == "tda4j-helix" and d >= 7 else "ok")
        calls, rows, md = self.run_sweep(fake)
        self.assertEqual([c for c in calls if c[0] == "tda4j-helix" and c[3] >= 7], [("tda4j-helix", "interior", 40, 7, 0)],
                         "the second seed and the larger dimensions are not run")
        self.assertEqual(len([c for c in calls if c[0] == "tda4j-dqp"]), 8, "4 dimensions x 2 seeds")
        self.assertIn("timeout", md)


    def test_a_grid_series_stops_along_n_and_its_tables_are_by_d(self):
        def fake(name, shape, n, d, seed):
            return self.row(name, shape, n, d, seed, "timeout" if name == "tda4j-helix" and n >= 100 else "ok",
                            series="n")
        calls, rows, md = self.run_sweep(fake, ["--dims", "", "--points", "", "--grid", "11:40,100,300;14:40"])
        self.assertEqual([c[2:4] for c in calls if c[0] == "tda4j-helix" and c[3] == 11], [(40, 11), (40, 11), (100, 11)])
        self.assertEqual([c[2] for c in calls if c[0] == "tda4j-helix" and c[3] == 14], [40, 40])
        self.assertEqual(len([c for c in calls if c[0] == "tda4j-dqp" and c[3] == 11]), 6, "3 sizes x 2 seeds")
        self.assertIn("### interior points of the unit cube, d = 11", md)
        self.assertIn("### interior points of the unit cube, d = 14", md)
        self.assertRegex(md, r"\| n = 100 \|[^\n]*timeout")
        self.assertNotIn("thesis", md.lower())

    def test_extra_cells_ignore_the_stop(self):
        def fake(name, shape, n, d, seed):
            return self.row(name, shape, n, d, seed, "timeout" if name == "tda4j-helix" else "ok")
        calls, _, _ = self.run_sweep(fake, ["--extra", "800:11:interior"])
        self.assertIn(("tda4j-helix", "interior", 800, 11, 0), calls)

    def test_tables_name_the_reference_and_mark_failures(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "bars").mkdir()
            rows = []
            same, other = "0\t0.0\tinf\n1\t0.5\t0.9\n", "0\t0.0\tinf\n1\t0.5\t0.9\n1\t0.2\t0.6\n"
            for dd, gudhi_ok in [(5, True), (6, False)]:
                for seed in (0, 1):
                    for tool, ok, text in [("gudhi", gudhi_ok, same), ("tda4j-dqp", True, same),
                                           ("tda4j-helix", True, same if seed == 0 else other)]:
                        rows.append(self.row(tool, "surface", 40, dd, seed, "ok" if ok else "oom",
                                             heap_peak_mb="700" if tool.startswith("tda4j") else "",
                                             degenerate=str(dd == 5 and seed == 1)))
                        if ok:
                            (out / "bars" / f"surface_40_{dd}_{seed}__{tool}.tsv").write_text(text)
            with open(out / "sweep.csv", "w", newline="") as f:
                w = csv.DictWriter(f, fieldnames=sweep_alpha.COLUMNS)
                w.writeheader()
                w.writerows(rows)
            sweep_alpha.summarize(out, stub_bottleneck)
            md = (out / "sweep.md").read_text()
        self.assertIn("| d = 5† |", md, "a draw with d + 1 points on a facet marks its dimension")
        self.assertIn("| d = 6 |", md)
        self.assertRegex(md, r"\| d = 6 \|[^\n]*oom")
        self.assertIn("(gudhi)", md)                    # d = 5: GUDHI finished, so it is the reference
        self.assertIn("(tda4j-dqp)", md)                # d = 6: GUDHI ran out of memory, the dual QP is the reference
        self.assertIn("| 500 (700) |", md.split("## Peak memory")[1].split("## Largest")[0])
        self.assertIn("inf† (gudhi)", md)


class CellCountTests(unittest.TestCase):
    """bench/count_cells.py: the size of each case, by dimension. The clique counter is checked against enumeration and
    against GUDHI, the grid formula against cell-by-cell enumeration."""

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.tmp = Path(cls.dir.name)
        import shutil
        cls.counter = count_cells.build_counter(cls.tmp / "build") if shutil.which(os.environ.get("CXX", "g++")) else None

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def cliques(self, matrix, fmt, threshold, k):
        path = self.tmp / f"in-{fmt}.txt"
        np.savetxt(path, matrix, fmt="%.17g")
        r = subprocess.run([str(self.counter), str(path), fmt, repr(threshold), str(k)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return [int(c) for c in json.loads(r.stdout)["counts"]]

    @staticmethod
    def enumerate_cliques(adjacent, k):
        """Cliques of sizes 1..k of a graph by plain recursion: nothing shared with the C++ method."""
        found = [0] * (k + 1)

        def grow(clique, candidates):
            found[len(clique)] += 1
            if len(clique) < k:
                for i, c in enumerate(candidates):
                    grow(clique + [c], [d for d in candidates[i + 1:] if adjacent[c][d]])
        n = len(adjacent)
        for v in range(n):
            grow([v], [u for u in range(v + 1, n) if adjacent[v][u]])
        return found[1:]

    def test_the_grid_formula_is_cell_by_cell_enumeration(self):
        for shape in [(5,), (2, 3), (2, 2, 2), (1, 4, 2), (3, 1)]:
            # a cell is a coordinate in 0..2n per axis: even is a vertex of that axis, odd an edge; its dimension is the
            # number of odd coordinates
            brute = {}
            for cell in __import__("itertools").product(*[range(2 * n + 1) for n in shape]):
                d = sum(c % 2 for c in cell)
                brute[d] = brute.get(d, 0) + 1
            self.assertEqual(count_cells.grid_cells(shape, len(shape)), brute, shape)
        self.assertEqual(count_cells.grid_cells((4, 4), 1), {0: 25, 1: 40}, "cut at dimension 1")
        self.assertEqual(count_cells.grid_cells((4, 4), 5), {0: 25, 1: 40, 2: 16}, "no dimension above the grid's own")

    def test_every_subset_bound(self):
        self.assertEqual(count_cells.every_subset_bound(5, 3), 5 + 10 + 10)

    @unittest.skipIf(shutil.which("g++") is None and "CXX" not in os.environ, "no C++ compiler")
    def test_cliques_of_random_points_equal_plain_enumeration(self):
        rng = np.random.default_rng(1)
        for n, d, threshold, k in [(40, 3, 0.5, 6), (60, 2, 0.3, 8), (35, 5, 0.9, 5)]:
            x = rng.random((n, d))
            dist = np.sqrt(((x[:, None] - x[None]) ** 2).sum(-1))
            adjacent = (dist <= threshold) & ~np.eye(n, dtype=bool)
            self.assertEqual(self.cliques(x, "points", threshold, k), self.enumerate_cliques(adjacent, k), (n, d))

    @unittest.skipIf(shutil.which("g++") is None and "CXX" not in os.environ, "no C++ compiler")
    def test_a_distance_matrix_is_read_from_its_lower_triangle(self):
        x = np.random.default_rng(2).random((30, 3))
        dist = np.sqrt(((x[:, None] - x[None]) ** 2).sum(-1))
        skewed = dist.copy()
        skewed[np.triu_indices(30, 1)] = 99.0  # the upper triangle is ignored
        want = self.enumerate_cliques((dist <= 0.5) & ~np.eye(30, dtype=bool), 5)
        self.assertEqual(self.cliques(skewed, "distance", 0.5, 5), want)

    @unittest.skipIf(shutil.which("g++") is None and "CXX" not in os.environ, "no C++ compiler")
    def test_an_edge_exactly_at_the_threshold_is_in(self):
        lattice = np.array([(i, j) for i in range(3) for j in range(3)], dtype=float)
        squared = ((lattice[:, None] - lattice[None]) ** 2).sum(-1)  # integers: exact, so the ties are real
        unit = (squared <= 1) & ~np.eye(9, dtype=bool)
        king = (squared <= 2) & ~np.eye(9, dtype=bool)
        self.assertEqual(self.cliques(lattice, "points", 1.0, 3), [9, 12, 0])
        self.assertEqual(self.cliques(lattice, "points", 1.0, 3), self.enumerate_cliques(unit, 3))
        # 12 unit edges and 8 diagonals of length sqrt(2): the king's graph, whose 2 x 2 blocks are four K4s
        self.assertEqual(self.cliques(lattice, "points", math.sqrt(2), 4), self.enumerate_cliques(king, 4))
        self.assertEqual(self.cliques(lattice, "points", math.sqrt(2), 4), [9, 20, 16, 4])

    @unittest.skipUnless(importlib.util.find_spec("gudhi"), "gudhi is not installed")
    @unittest.skipIf(shutil.which("g++") is None and "CXX" not in os.environ, "no C++ compiler")
    def test_cliques_equal_gudhis_rips_complex_by_dimension(self):
        import gudhi
        x = np.random.default_rng(3).random((70, 3))
        threshold = 0.35
        tree = gudhi.RipsComplex(points=x, max_edge_length=threshold).create_simplex_tree(max_dimension=3)
        by_dim = [0, 0, 0, 0]
        for simplex, _ in tree.get_simplices():
            by_dim[len(simplex) - 1] += 1
        self.assertEqual(self.cliques(x, "points", threshold, 4), by_dim)

    @unittest.skipIf(shutil.which("g++") is None and "CXX" not in os.environ, "no C++ compiler")
    def test_a_count_that_times_out_still_reports_the_graph_and_the_bound(self):
        fake = self.tmp / "fake_counter"
        fake.write_text("#!/bin/sh\necho 'graph: n=100 edges=500 degeneracy=9' >&2\nsleep 30\n")
        fake.chmod(0o755)
        (self.tmp / "logs").mkdir(exist_ok=True)
        case = bench.Case("slow", "vr", "x.txt", "points", 2)
        r = count_cells.count_cliques(case, self.tmp / "x.txt", 1.0, fake, Namespace(timeout=1), self.tmp)
        self.assertEqual((r["status"], r["points"], r["edges"]), ("timeout", 100, 500))
        self.assertIn("at most", r["note"])
        self.assertIn(f"{count_cells.every_subset_bound(100, 4):.3g}", r["note"])
        self.assertEqual(r["total"], "", "no cell count is made up")

    def test_the_canonical_row_is_the_independent_source_and_the_check_says_what_the_other_found(self):
        case = bench.Case("c", "alpha", "c.txt", "points", 1)
        gudhi = count_cells.row(case, "gudhi", "ok", {0: 5, 1: 9, 2: 5})
        tda4j = count_cells.row(case, "tda4j", "ok", {0: 5, 1: 9, 2: 5})
        chosen, check = count_cells.canonical([tda4j, gudhi])
        self.assertEqual((chosen["source"], check), ("gudhi", "tda4j agrees"))
        off = count_cells.row(case, "tda4j", "ok", {0: 5, 1: 9, 2: 6})
        chosen, check = count_cells.canonical([gudhi, off])
        self.assertIn("tda4j DIFFERS (dim 2: gudhi 5 / tda4j 6)", check)
        chosen, check = count_cells.canonical([count_cells.row(case, "gudhi", "timeout"), tda4j])
        self.assertEqual((chosen["source"], check), ("tda4j", "single source (TDA4j's own count)"))
        chosen, check = count_cells.canonical([count_cells.row(case, "gudhi", "timeout")])
        self.assertEqual(check, "no count")

    def test_missing_data_is_a_row_not_a_crash(self):
        case = bench.Case("gone", "vr", "gone.txt", "points", 1)
        args = Namespace(cross_check=False, no_jvm=True, gudhi_python_ok=False)
        rows = count_cells.count_case(case, args, self.tmp, self.tmp, None)
        self.assertEqual([(r["status"], r["source"]) for r in rows], [("missing", "-")])
        self.assertIn("bench.py prepare", rows[0]["note"])

    def test_outputs_and_the_join_with_a_summary(self):
        out = self.tmp / "outputs"
        out.mkdir()
        cubical = bench.Case("img", "cubical", "img.npy", "npy", 1)
        vr = bench.Case("cloud", "vr", "cloud.txt", "points", 2)
        cases_rows = [(cubical, [count_cells.row(cubical, "grid", "ok", count_cells.grid_cells((2, 2), 2), points="2x2")]),
                      (vr, [count_cells.row(vr, "cliques", "timeout", note="killed after 1 s", points=100, edges=500)])]
        flat = count_cells.write_outputs(out, cases_rows)
        with open(out / "cells.csv") as f:
            rows = {r["case"]: r for r in csv.DictReader(f)}
        self.assertEqual((rows["img"]["dim0"], rows["img"]["dim1"], rows["img"]["dim2"], rows["img"]["total"]), ("9", "12", "4", "25"))
        self.assertEqual((rows["cloud"]["status"], rows["cloud"]["total"], rows["cloud"]["dim0"]), ("timeout", "", ""))
        md = (out / "cells.md").read_text()
        self.assertIn("| img | cubical | 2x2 |", md)
        self.assertIn("timeout: killed after 1 s", md)
        results = self.tmp / "results"
        results.mkdir()
        with open(results / "summary.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["case", "tool", "status"])
            w.writeheader()
            w.writerows([{"case": "img", "tool": "gudhi", "status": "ok"}, {"case": "other", "tool": "gudhi", "status": "ok"}])
        with open(count_cells.join_summary(results, flat)) as f:
            joined = {r["case"]: r["cells_total"] for r in csv.DictReader(f)}
        self.assertEqual(joined, {"img": "25", "other": ""})

    def test_the_reference_file_keeps_a_good_count_over_a_failed_one_and_the_order_of_the_cases(self):
        path = self.tmp / "reference.csv"
        path.unlink(missing_ok=True)
        names = [c.name for c in bench.CASES]
        a, b = bench.Case(names[3], "vr", "a.txt", "points", 2), bench.Case(names[0], "vr", "b.txt", "points", 2)
        good = count_cells.write_outputs(self.tmp / "outputs", [(a, [count_cells.row(a, "cliques", "ok", {0: 4, 1: 6}, points=4)])])
        count_cells.update_reference(path, good, {a.name: "abc"})
        more = count_cells.write_outputs(self.tmp / "outputs", [(b, [count_cells.row(b, "cliques", "ok", {0: 2, 1: 1}, points=2)])])
        self.assertEqual(count_cells.update_reference(path, more, {b.name: "def"}), 2)
        failed = count_cells.write_outputs(self.tmp / "outputs", [(a, [count_cells.row(a, "cliques", "timeout")])])
        count_cells.update_reference(path, failed, {})
        with open(path) as f:
            rows = list(csv.DictReader(f))
        self.assertEqual([r["case"] for r in rows], [names[0], names[3]], "in the order of bench.CASES")
        self.assertEqual((rows[1]["total"], rows[1]["data_sha256"]), ("10", "abc"), "a timeout does not replace a count")
        self.assertEqual((rows[0]["total"], rows[0]["dim1"]), ("3", "1"))

    def test_verify_notices_a_changed_or_missing_data_file(self):
        data = self.tmp / "verify-data"
        data.mkdir(exist_ok=True)
        case = bench.CASES[0]
        (data / case.file).write_text("1 2 3\n")
        reference = self.tmp / "verify.csv"
        reference.write_text(f"case,data_sha256\n{case.name},{bench.sha256_of(data / case.file)}\nnot_a_case,\n")
        self.assertEqual([c for c, _ in count_cells.verify_reference(reference, data)], ["not_a_case"])
        (data / case.file).write_text("1 2 4\n")
        problems = dict(count_cells.verify_reference(reference, data))
        self.assertIn("SHA-256 differs", problems[case.name])
        (data / case.file).unlink()
        self.assertIn("not found", dict(count_cells.verify_reference(reference, data))[case.name])

    def test_summarize_joins_the_reference_counts_and_a_run_never_counts(self):
        reference = self.tmp / "joined.csv"
        reference.write_text("case,total\nsmall,12345\n")
        saved = bench.CELLS_REFERENCE
        bench.CELLS_REFERENCE = reference
        try:
            out = self.tmp / "joined-run"
            (out / "bars").mkdir(parents=True, exist_ok=True)
            rows = []
            for case in ("small", "other"):
                rows.append(run_row(case, "gudhi", "cold", "ok", wall="2", median="2"))
                rows.append(run_row(case, "gudhi", "warm", "ok", wall="1", median="1"))
            write_runs(out, rows)
            bench.summarize(out, bottleneck=stub_bottleneck)
            with open(out / "summary.csv") as f:
                cells = {r["case"]: r["cells"] for r in csv.DictReader(f)}
            md = (out / "summary.md").read_text()
        finally:
            bench.CELLS_REFERENCE = saved
        self.assertEqual(cells, {"small": "12345", "other": ""})
        self.assertIn("agrees | cells |", md)
        self.assertRegex(md, r"\| small \| 17 \| gudhi \| ok \|.*\| 12,345 \|")
        self.assertNotIn("count_cells", Path(bench.__file__).read_text().split("def run(args)")[1].split("def run_one")[0],
                         "the benchmark loop does not count cells")

    def test_every_case_is_countable_by_some_source(self):
        # vr by the clique counter, cubical by the formula, alpha by GUDHI and TDA4j, the others by TDA4j's stream.
        for case in bench.CASES:
            self.assertIn(case.task, ("vr", "cubical", "alpha", "cech", "sparse", "dtm", "witness", "dowker"), case.name)


class DiphaPhatEireneTests(unittest.TestCase):
    """DIPHA, PHAT and Eirene: the rows, their Z/2 limit, DIPHA's and PHAT's files, and (with the tools installed) each tool's
    barcode against GUDHI's."""

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.tmp = Path(cls.dir.name)

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def test_the_rows_are_vietoris_rips_and_z2_only_and_the_phat_rows_are_kernels(self):
        rows = {name: (kind, task, opts) for name, kind, task, opts in bench.TOOLS
                if name.startswith(("dipha", "phat", "eirene"))}
        self.assertEqual(set(rows), {"dipha", "dipha-mt", "phat", "phat-dual", "eirene", "eirene-cycles"})
        for name, (kind, task, opts) in rows.items():
            self.assertEqual((task, opts.get("only_p")), ("vr", 2), name)
        self.assertEqual(bench.KERNEL_TOOLS, {"phat", "phat-dual"})
        self.assertTrue(rows["dipha-mt"][2]["multithreaded"])
        self.assertEqual(rows["eirene-cycles"][2]["slower_than"], "eirene")
        for name in ("eirene", "eirene-cycles"):
            self.assertIn(rows[name][2]["julia_tool"], bench.JULIA_TOOLS)
        for binary_kind in ("dipha", "phat"):
            self.assertIn(binary_kind, bench.BINARY_KINDS)

    def test_a_z2_only_tool_is_not_run_at_another_prime(self):
        case = bench.Case("c", "vr", "c.txt", "points", 2)
        self.assertIn("Z/2 coefficients only", bench.skip_reason({"only_p": 2}, case, 3, 1.0, 17))
        self.assertIsNone(bench.skip_reason({"only_p": 2}, case, 3, 1.0, 2))
        self.assertIsNone(bench.skip_reason({"only_p": 2}, case, 3, 1.0), "no field given: nothing to refuse")
        self.assertIsNone(bench.skip_reason({}, case, 3, 1.0, 17))

    def test_the_sparse_distance_matrix_lists_both_ends_of_every_edge_in_dipha_layout(self):
        path = self.tmp / "tiny.dipha"
        dipha_io.write_sparse_distance_matrix(path, 4, np.array([0, 0, 2]), np.array([1, 2, 3]), np.array([1.0, 2.0, 0.5]))
        raw = path.read_bytes()
        magic, kind, n = struct.unpack("<qqq", raw[:24])
        self.assertEqual((magic, kind, n), (8067171840, 8, 4))
        counts = np.frombuffer(raw[24:24 + 32], dtype="<i8")
        self.assertEqual(counts.tolist(), [2, 1, 2, 1])
        m = int(counts.sum())
        columns = np.frombuffer(raw[56:56 + 8 * m], dtype="<i8")
        self.assertEqual(columns.tolist(), [1, 2, 0, 0, 3, 2])
        distances = np.frombuffer(raw[56 + 8 * m:], dtype="<f8")
        self.assertEqual(distances.tolist(), [1.0, 2.0, 1.0, 2.0, 0.5, 0.5])

    @unittest.skipUnless(importlib.util.find_spec("scipy"), "scipy is not installed")
    def test_an_edge_exactly_at_the_threshold_is_in(self):
        lattice = np.array([(i, j) for i in range(3) for j in range(3)], dtype=float)
        i, j, d = dipha_io.edges_within(lattice, math.sqrt(2))
        self.assertEqual(len(i), 20, "12 unit edges and 8 diagonals")
        self.assertAlmostEqual(float(d.max()), math.sqrt(2))

    def test_a_distance_matrix_is_read_from_its_lower_triangle(self):
        matrix = np.array([[0, 99, 99], [1, 0, 99], [3, 2, 0]], dtype=float)  # upper triangle junk
        i, j, d = dipha_io.matrix_edges_within(matrix, 2.0)
        self.assertEqual(sorted(zip(i.tolist(), j.tolist(), d.tolist())), [(0, 1, 1.0), (1, 2, 2.0)])

    def test_a_diagram_is_read_with_essential_classes_decoded_and_its_values_doubled(self):
        path = self.tmp / "tiny.diagram"
        with open(path, "wb") as f:
            f.write(struct.pack("<qqq", 8067171840, 2, 4))
            for dim, birth, death in [(0, 0.0, 0.5), (-1, 0.0, 9.0), (1, 0.25, 0.75), (2, 0.5, 0.75)]:
                f.write(struct.pack("<qdd", dim, birth, death))
        self.assertEqual(dipha_io.read_diagram(path), [(0, 0.0, 0.5), (0, 0.0, math.inf), (1, 0.25, 0.75), (2, 0.5, 0.75)])
        bars = self.tmp / "tiny.tsv"
        counts = dipha_io.diagram_to_bars(path, bars, 1)
        self.assertEqual(counts, {"0": 2, "1": 1})
        self.assertEqual(bench.read_bars(bars), {0: [(0.0, 1.0), (0.0, math.inf)], 1: [(0.5, 1.5)]})
        (self.tmp / "bad.diagram").write_bytes(struct.pack("<qqq", 1, 2, 0))
        with self.assertRaises(ValueError):
            dipha_io.read_diagram(self.tmp / "bad.diagram")

    def test_phat_pairs_become_bars_unpaired_columns_are_essential_and_equal_values_are_no_bar(self):
        matrix = self.tmp / "m.phat"
        np.array([0.0, 0.0, 0.5, 0.5, 0.75, 0.75], dtype="<f8").tofile(f"{matrix}.values")
        np.array([0, 0, 1, 1, 2, 2], dtype="<i8").tofile(f"{matrix}.dims")
        pairs = self.tmp / "m.pairs"
        with open(pairs, "wb") as f:
            f.write(struct.pack("<q", 2))
            f.write(struct.pack("<qq", 1, 2))  # a vertex born at 0 dies at the edge of value 0.5
            f.write(struct.pack("<qq", 3, 4))  # an edge and a triangle of value 0.5 and 0.75: a bar
        bars = self.tmp / "m.tsv"
        counts = phat_io.pairs_to_bars(pairs, matrix, bars, 1)
        self.assertEqual(counts, {"0": 2, "1": 1})
        self.assertEqual(bench.read_bars(bars), {0: [(0.0, 1.0), (0.0, math.inf)], 1: [(1.0, 1.5)]})
        # a dimension above max_dim is left out, even essential: the triangle 5 (dimension 2) is in no pair
        self.assertNotIn(2, bench.read_bars(bars))

    def test_mpiexec_gets_the_flags_its_flavour_needs(self):
        def answers(text):
            return mock.patch.object(bench.subprocess, "run", return_value=mock.Mock(stdout=text))
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MPIEXEC_FLAGS", None)
            with answers("mpiexec (OpenRTE) 4.1.6"):
                self.assertIn("--oversubscribe", bench.mpiexec_flags())
            with answers("HYDRA build details: Version: 4.0"):
                self.assertEqual(bench.mpiexec_flags(), [])
        with mock.patch.dict(os.environ, {"MPIEXEC_FLAGS": "--bind-to none"}):
            self.assertEqual(bench.mpiexec_flags(), ["--bind-to", "none"])

    def test_the_memory_of_a_job_of_several_processes_is_summed_over_the_tree(self):
        code = ("import subprocess, sys, time\n"
                "c = subprocess.Popen([sys.executable, '-c', 'x = bytearray(200 * 1024 * 1024)\\nimport time; time.sleep(4)'])\n"
                "time.sleep(4)\n")
        proc = subprocess.Popen(child(code))
        try:
            time.sleep(2)
            alone, together = bench.vm_hwm_mb(proc.pid), bench.tree_hwm_mb(proc.pid)
        finally:
            proc.kill()
            proc.wait()
        self.assertGreater(together, alone + 150, "the child's 200 MB is in the sum and not in the parent's own figure")

    def test_a_kernel_row_is_never_the_baseline_and_the_table_says_so(self):
        out = self.tmp / "kernel-run"
        (out / "bars").mkdir(parents=True, exist_ok=True)
        bars = "0\t0.0\tinf\n1\t0.5\t0.9\n"
        rows = []
        for name, warm in [("ripser.cpp", 1.0), ("gudhi", 2.0), ("phat", 0.1), ("tda4j", 5.0)]:
            rows.append(run_row("k", name, "cold", "ok", wall=str(warm), median=str(warm)))
            rows.append(run_row("k", name, "warm", "ok", wall=str(warm), median=str(warm)))
            (out / "bars" / f"k__p17__{name}.tsv").write_text(bars)
        write_runs(out, rows)
        bench.summarize(out, bottleneck=stub_bottleneck)
        md = (out / "summary.md").read_text()
        self.assertIn("| k | 17 | tda4j | ripser.cpp |", md, "the best other single-threaded tool is ripser.cpp, not phat")
        self.assertIn("phat time the reduction of a boundary matrix built beforehand", md)

    def test_a_case_too_big_for_an_explicit_matrix_is_not_run_by_phat(self):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            data, out = Path(d) / "data", Path(d) / "out"
            data.mkdir()
            np.savetxt(data / "pts.txt", np.random.default_rng(0).random((20, 3)))
            case = bench.Case("big", "vr", "pts.txt", "points", 1)
            saved = bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env, bench.cells_reference
            bench.CASES = [case]
            bench.TOOLS = [("phat", "phat", "vr", {"only_p": 2, "kernel": True})]
            bench.available = lambda kind, opts, python: True
            bench.write_env = lambda out, args: None
            bench.cells_reference = lambda: {"big": "500000000"}
            bench.run_one = lambda *a, **k: calls.append(a) or "ok"
            try:
                args = Namespace(data=str(data), out=str(out), cases="", tasks="", tools="", fields="2",
                                 python="python3", rerun_timeouts=False, explicit_max_cells=1e8)
                bench.run(args)
                with open(out / "runs.csv") as f:
                    rows = list(csv.DictReader(f))
            finally:
                bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env, bench.cells_reference = saved
        self.assertEqual(calls, [])
        self.assertEqual(rows[0]["status"], "unsupported")
        self.assertIn("500,000,000 cells", rows[0]["reason"])

    # --- with the tools installed (bench/run.sh setup dipha phat eirene; the variables run.sh exports) ---------------------

    def tiny_case(self):
        data = self.tmp / "tiny-data"
        data.mkdir(exist_ok=True)
        points = np.random.default_rng(5).random((45, 3))
        np.savetxt(data / "tiny.txt", points, fmt="%.17g")
        d = np.sqrt(((points[:, None] - points[None]) ** 2).sum(-1))
        case = bench.Case("tiny", "vr", "tiny.txt", "points", 2)
        return case, data, float(d.max(axis=1).min())

    def gudhi_bars(self, case, data, threshold):
        import gudhi
        points = np.loadtxt(data / case.file)
        tree = gudhi.RipsComplex(points=points, max_edge_length=threshold).create_simplex_tree(max_dimension=case.dim + 1)
        tree.compute_persistence(homology_coeff_field=2, min_persistence=0)
        return {k: [(float(b), math.inf if d == math.inf else float(d)) for b, d in tree.persistence_intervals_in_dimension(k)]
                for k in range(case.dim + 1)}

    def run_binary(self, kind, name, opts, case, data, threshold):
        out = self.tmp / f"{name}.tsv"
        args = Namespace(trials=1)
        cmd = bench.command(kind, name, opts, case, data, 2, threshold, 0, 1, out, args)
        r = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        matrix = cmd[-2] if kind == "phat" else None
        bench.parse_binary_output(kind, bench.Proc("ok", 0, 0, r.stdout), out, case, matrix)
        return bench.read_bars(out)

    @unittest.skipUnless(os.environ.get("DIPHA_BIN") and importlib.util.find_spec("gudhi") and importlib.util.find_spec("scipy"),
                         "DIPHA_BIN, gudhi or scipy missing")
    def test_dipha_has_gudhis_barcode_on_one_rank_and_on_three(self):
        case, data, threshold = self.tiny_case()
        reference = self.gudhi_bars(case, data, threshold)
        for opts in ({}, {"ranks": "3"}):
            bars = self.run_binary("dipha", "dipha", opts, case, data, threshold)
            self.assertLess(bench.agreement(bars, reference, case.dim)[0], 1e-9, opts)

    @unittest.skipUnless(os.environ.get("PHAT_BIN") and os.environ.get("MAKE_PHAT_BIN") and importlib.util.find_spec("gudhi")
                         and importlib.util.find_spec("scipy"), "PHAT_BIN, MAKE_PHAT_BIN, gudhi or scipy missing")
    def test_phat_has_gudhis_barcode_twist_and_dualized(self):
        case, data, threshold = self.tiny_case()
        reference = self.gudhi_bars(case, data, threshold)
        for opts in ({}, {"dualize": True}):
            bars = self.run_binary("phat", "phat", opts, case, data, threshold)
            self.assertLess(bench.agreement(bars, reference, case.dim)[0], 1e-9, opts)

    @unittest.skipUnless(os.environ.get("JULIA") and os.environ.get("EIRENE_PROJECT") and importlib.util.find_spec("gudhi"),
                         "JULIA, EIRENE_PROJECT or gudhi missing")
    def test_eirene_has_gudhis_barcode_in_both_variants_and_refuses_another_prime(self):
        case, data, threshold = self.tiny_case()
        reference = self.gudhi_bars(case, data, threshold)
        worker = str(HERE_BENCH / "workers" / "eirene_worker.jl")
        for variant in ("bars", "cycles"):
            out = self.tmp / f"eirene-{variant}.tsv"
            cmd = [os.environ["JULIA"], f"--project={os.environ['EIRENE_PROJECT']}", worker, f"variant={variant}", "task=vr",
                   f"input={data / case.file}", "format=points", f"dim={case.dim}", "p=2", f"threshold={threshold!r}",
                   "warmup=0", "trials=1", f"out={out}"]
            r = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertLess(bench.agreement(bench.read_bars(out), reference, case.dim)[0], 1e-9, variant)
        r = subprocess.run(cmd[:-5] + ["p=17"] + cmd[-3:], capture_output=True, text=True)
        self.assertEqual(r.returncode, 3, "exit 3 is 'unsupported'")


class RedzedTests(unittest.TestCase):
    """RedZeD: the row, its binary input (the lower triangle as float32), its BAR output, and (with the tool installed,
    bench/run.sh setup redzed) its barcode against GUDHI's."""

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.tmp = Path(cls.dir.name)

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def test_the_row_is_vietoris_rips_z2_only_and_one_thread(self):
        rows = [(kind, task, opts) for name, kind, task, opts in bench.TOOLS if name == "redzed"]
        self.assertEqual(rows, [("redzed", "vr", {"only_p": 2})])
        self.assertIn("redzed", bench.BINARY_KINDS)
        self.assertNotIn("redzed", bench.KERNEL_TOOLS)
        case = bench.Case("c", "vr", "c.txt", "points", 2)
        self.assertIn("Z/2 coefficients only", bench.skip_reason(rows[0][2], case, 3, 1.0, 17))
        self.assertIsNone(bench.skip_reason(rows[0][2], case, 3, 1.0, 2))

    def test_the_input_is_the_lower_triangle_row_by_row_in_float32_whatever_the_block_size(self):
        points = np.random.default_rng(3).random((9, 3))
        d = np.sqrt(((points[:, None] - points[None]) ** 2).sum(-1))
        expected = d[np.tril_indices(9, -1)].astype("<f4").tobytes()
        self.assertEqual(len(expected), 4 * 9 * 8 // 2)
        for block in (1, 17, 10 ** 6):  # a block of one row, of a few, of all of them
            path = redzed_io.prepare_input(lambda: points, "points", self.tmp / f"points-{block}.lower", block)
            self.assertEqual(path.read_bytes(), expected, block)
        self.assertEqual([p.name for p in self.tmp.glob("points-*.part")], [], "the temporary file is renamed away")

    def test_a_distance_matrix_is_read_from_its_lower_triangle_only(self):
        matrix = np.array([[0, 99, 99, 99], [1, 0, 99, 99], [3, 2, 0, 99], [6, 5, 4, 0]], dtype=float)  # upper triangle junk
        for block in (1, 2, 10 ** 6):
            path = redzed_io.prepare_input(lambda: matrix, "distance", self.tmp / f"matrix-{block}.lower", block)
            self.assertEqual(np.fromfile(path, dtype="<f4").tolist(), [1, 3, 2, 6, 5, 4], block)

    def test_an_input_that_exists_is_kept_and_not_loaded_again(self):
        path = self.tmp / "kept.lower"
        redzed_io.prepare_input(lambda: np.array([[0.0], [1.0]]), "points", path)

        def refuse():
            raise AssertionError("the file exists: nothing to load")

        self.assertEqual(redzed_io.prepare_input(refuse, "points", path), path)
        self.assertEqual(np.fromfile(path, dtype="<f4").tolist(), [1.0])

    def test_the_command_reads_the_lower_triangle_and_prints_the_bars_up_to_the_case_dimension(self):
        data = self.tmp / "cmd-data"
        data.mkdir()
        np.savetxt(data / "p.txt", np.random.default_rng(1).random((6, 2)))
        case = bench.Case("cmd", "vr", "p.txt", "points", 2)
        with mock.patch.dict(os.environ, {"REDZED_BIN": "/tools/redzed"}):
            cmd = bench.command("redzed", "redzed", {"only_p": 2}, case, data, 2, 0.75, 0, 1, self.tmp / "o.tsv", Namespace())
            plain = bench.command("redzed", "redzed", {"only_p": 2}, case, data, 2, None, 0, 1, self.tmp / "o.tsv", Namespace())
        self.assertEqual(cmd, ["/tools/redzed", str(data / "redzed" / "cmd.lower"), "2", "--lower", "--barcode",
                               "--threshold", "0.75"])
        self.assertEqual(plain, cmd[:-2], "no threshold: RedZeD's own default, the enclosing radius")
        self.assertEqual((data / "redzed" / "cmd.lower").stat().st_size, 4 * 6 * 5 // 2)

    def test_the_bars_are_the_bar_lines_and_the_summary_lines_are_ignored(self):
        text = ("H0 3 1 2.5000000000\nH1 1 0 4.0000000000\n"
                "BAR 0 0 0.5\nBAR 0 0 1.25\nBAR 0 0 inf\nBAR 1 0.5 0.875\n")
        out = self.tmp / "redzed.tsv"
        counts = bench.parse_binary_output("redzed", bench.Proc("ok", 0, 0, text), out, bench.Case("c", "vr", "c", "points", 1))
        self.assertEqual(counts, {"0": 3, "1": 1})
        self.assertEqual(bench.read_bars(out), {0: [(0.0, 0.5), (0.0, 1.25), (0.0, math.inf)], 1: [(0.5, 0.875)]})

    def test_it_is_available_when_its_binary_is_named(self):
        with mock.patch.dict(os.environ, {"REDZED_BIN": "/tools/redzed"}):
            self.assertTrue(bench.available("redzed", {}, "python"))
        with mock.patch.dict(os.environ):
            os.environ.pop("REDZED_BIN", None)
            self.assertFalse(bench.available("redzed", {}, "python"))

    @unittest.skipUnless(os.environ.get("REDZED_BIN") and importlib.util.find_spec("gudhi"), "REDZED_BIN or gudhi missing")
    def test_redzed_has_gudhis_barcode_on_points_and_on_a_distance_matrix(self):
        import gudhi
        data = self.tmp / "tool-data"
        data.mkdir()
        points = np.random.default_rng(5).random((45, 3))
        np.savetxt(data / "tiny.txt", points, fmt="%.17g")
        d = np.sqrt(((points[:, None] - points[None]) ** 2).sum(-1))
        np.savetxt(data / "tiny-d.txt", d, fmt="%.17g")
        threshold = float(d.max(axis=1).min())
        tree = gudhi.RipsComplex(points=points, max_edge_length=threshold).create_simplex_tree(max_dimension=3)
        tree.compute_persistence(homology_coeff_field=2, min_persistence=0)
        reference = {k: [(float(b), math.inf if e == math.inf else float(e)) for b, e in tree.persistence_intervals_in_dimension(k)]
                     for k in range(3)}
        for fname, fmt in (("tiny.txt", "points"), ("tiny-d.txt", "distance")):
            case = bench.Case("tiny", "vr", fname, fmt, 2)
            out = self.tmp / f"redzed-{fmt}.tsv"
            cmd = bench.command("redzed", "redzed", {"only_p": 2}, case, data, 2, threshold, 0, 1, out, Namespace())
            r = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            bench.parse_binary_output("redzed", bench.Proc("ok", 0, 0, r.stdout), out, case)
            self.assertLess(bench.agreement(bench.read_bars(out), reference, 2)[0], 1e-6, fmt)


def have(module):
    return importlib.util.find_spec(module) is not None


class PythonWorkerTests(unittest.TestCase):
    """The adapters of workers/py_worker.py against GUDHI on a random cloud. What is not installed is skipped."""

    WORKER = Path(__file__).resolve().parent / "workers" / "py_worker.py"

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.points = Path(cls.dir.name) / "pts.txt"
        x = np.random.default_rng(7).random((60, 3))
        np.savetxt(cls.points, x)
        d = np.sqrt(((x[:, None, :] - x[None, :, :]) ** 2).sum(-1))
        cls.distances = Path(cls.dir.name) / "dist.txt"
        np.savetxt(cls.distances, d)
        cls.threshold = float(np.min(d.max(axis=1)))
        cls.image = Path(cls.dir.name) / "image.npy"
        np.save(cls.image, np.random.default_rng(8).random((8, 8)))

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def worker(self, tool, task="vr", p=2, fmt="points", variant=None):
        tag = f"{tool}-{variant}-{p}-{fmt}"
        out = Path(self.dir.name) / f"{tag}.tsv"
        source = self.image if task == "cubical" else self.points if fmt == "points" else self.distances
        cmd = [sys.executable, str(self.WORKER), f"tool={tool}", f"task={task}", f"input={source}", f"format={fmt}",
               "dim=2", f"p={p}", f"threshold={self.threshold!r}", "warmup=0", "trials=1", f"out={out}"]
        if variant:
            cmd.append(f"variant={variant}")
        r = subprocess.run(cmd, capture_output=True, text=True)
        return r, (bench.read_bars(out) if r.returncode == 0 else None)

    @unittest.skipUnless(all(map(have, ("gudhi", "dionysus", "oat_python"))), "gudhi, dionysus or oat_python missing")
    def test_every_variant_has_the_barcode_of_gudhi_at_both_primes(self):
        for p in (2, 17):
            _, reference = self.worker("gudhi", p=p)
            self.assertEqual(len(reference[0]), 60)
            for tool, variant in [("dionysus", None), ("dionysus", "homology"), ("dionysus", "cocycles"), ("oat", None),
                                  ("oat", "cycles")]:
                r, bars = self.worker(tool, p=p, variant=variant)
                self.assertEqual(r.returncode, 0, r.stderr)
                dist, note = bench.agreement(bars, reference, 2)
                self.assertLess(dist, 1e-5, f"{tool} {variant} p={p}: {note}")
                self.assertEqual(note, "")
                self.assertEqual(json.loads(r.stdout.strip().splitlines()[-1])["bars"],
                                 {str(k): len(bars[k]) for k in sorted(bars)})

    @unittest.skipUnless(all(map(have, ("gudhi", "dionysus", "oat_python"))), "gudhi, dionysus or oat_python missing")
    def test_a_distance_matrix_is_the_same_input_as_the_points(self):
        for tool in ("dionysus", "oat"):
            _, a = self.worker(tool, fmt="points")
            _, b = self.worker(tool, fmt="distance")
            dist, note = bench.agreement(a, b, 2)  # Dionysus rounds its distances to float32 (about 3e-8 here)
            self.assertLess(dist, 1e-6, tool)
            self.assertEqual(note, "")

    @unittest.skipUnless(all(map(have, ("dionysus", "oat_python"))), "dionysus or oat_python missing")
    def test_neither_runs_a_cubical_or_an_alpha_task(self):
        for tool in ("dionysus", "oat"):
            for task in ("cubical", "alpha"):
                r, _ = self.worker(tool, task=task)
                self.assertEqual(r.returncode, 3, f"{tool} {task}: exit 3 is 'unsupported'")


class RevisionTests(unittest.TestCase):
    """Which TDA4j a run measured: the commit of the checkout the JVM classes were built in, the tracked files changed
    since, and whether the classes are older than the sources."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.repo = Path(self.dir.name) / "checkout"
        (self.repo / "src" / "main" / "scala").mkdir(parents=True)
        (self.repo / "src" / "main" / "scala" / "A.scala").write_text("object A")
        (self.repo / "notes.txt").write_text("notes")
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "first")
        self.head = self.git("rev-parse", "HEAD")
        self.classes = self.repo / "target" / "out" / "jvm" / "scala-3.9.0" / "tda4j" / "classes"
        self.classes.mkdir(parents=True)
        (self.classes / "A.class").write_text("class")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repo), *args], capture_output=True, text=True, check=True).stdout.strip()

    def age(self, path, seconds_ago):
        t = time.time() - seconds_ago
        os.utime(path, (t, t))

    def test_a_clean_checkout_is_its_head_and_nothing_else(self):
        rev = bench.revision(self.classes)
        self.assertEqual((rev["tda4j_git"], rev["tda4j_dirty"]), (self.head, []))

    def test_the_checkout_is_the_one_the_classes_were_built_in_not_the_harness_s(self):
        elsewhere = Path(self.dir.name) / "other"
        elsewhere.mkdir()
        # no checkout above these classes: the fallback is the harness's own, here a directory that is no repository
        rev = bench.revision(elsewhere, default_repo=elsewhere)
        self.assertEqual((rev["tda4j_git"], rev["tda4j_dirty"]), ("unavailable", []))
        self.assertEqual(bench.revision(self.classes, default_repo=elsewhere)["tda4j_git"], self.head)

    def test_changed_tracked_files_are_listed_staged_or_not_and_untracked_ones_are_not(self):
        (self.repo / "notes.txt").write_text("changed")
        (self.repo / "src" / "main" / "scala" / "A.scala").write_text("object A { val x = 1 }")
        self.git("add", "src/main/scala/A.scala")
        (self.repo / "scratch.txt").write_text("untracked")
        self.assertEqual(bench.revision(self.classes)["tda4j_dirty"], ["notes.txt", "src/main/scala/A.scala"])

    def test_classes_older_than_a_source_are_stale_and_newer_ones_are_not(self):
        source, compiled = self.repo / "src" / "main" / "scala" / "A.scala", self.classes / "A.class"
        self.age(source, 100)
        self.age(compiled, 10)
        self.assertIs(bench.revision(self.classes)["tda4j_classes_stale"], False)
        self.age(compiled, 1000)
        self.assertIs(bench.revision(self.classes)["tda4j_classes_stale"], True)

    def test_without_classes_staleness_is_unknown(self):
        self.assertIsNone(bench.revision(None, default_repo=self.repo)["tda4j_classes_stale"])
        self.assertIsNone(bench.revision(self.repo / "no-such-dir", default_repo=self.repo)["tda4j_classes_stale"])

    def test_the_first_entry_of_the_classpath_file_is_the_classes_directory(self):
        cp = Path(self.dir.name) / "tda4j.classpath"
        cp.write_text(os.pathsep.join([str(self.classes), "/x/scala3-library.jar"]))
        with mock.patch.dict(os.environ, {"TDA4J_CP": str(cp)}):
            self.assertEqual(bench.classes_directory(), self.classes)
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(bench.classes_directory())

    def test_the_text_names_the_commit_and_says_when_it_is_not_the_whole_story(self):
        env = {"tda4j_git": "0123456789abcdef0123", "tda4j_dirty": [], "tda4j_classes_stale": False}
        self.assertEqual(bench.revision_text(env), "TDA4j `0123456789ab`.")
        self.assertEqual(bench.revision_text({**env, "tda4j_dirty": ["a", "b"]}),
                         "TDA4j `0123456789ab` with 2 changed files (a, b).")
        self.assertEqual(bench.revision_text({**env, "tda4j_dirty": ["a"]}), "TDA4j `0123456789ab` with 1 changed file (a).")
        self.assertEqual(bench.revision_text({**env, "tda4j_classes_stale": True}),
                         "TDA4j `0123456789ab`; the classes the JVM rows ran are older than the sources.")
        self.assertEqual(bench.revision_text({"tda4j_git": "0123456789abcdef0123"}), "TDA4j `0123456789ab`.")
        self.assertEqual(bench.revision_text({"tda4j_git": "unavailable"}), "")
        self.assertEqual(bench.revision_text({}), "")

    def test_many_changed_files_are_counted_and_the_first_few_named(self):
        text = bench.revision_text({"tda4j_git": "0123456789abcdef", "tda4j_dirty": [f"f{i}" for i in range(12)]})
        self.assertEqual(text, "TDA4j `0123456789ab` with 12 changed files (f0, f1, f2, f3, f4, ...).")

    def test_both_summaries_say_which_commit_they_measured(self):
        env = {"tda4j_git": self.head, "tda4j_dirty": ["notes.txt"], "cpu": "Test CPU", "cores": 4,
               "args": {"baselines": "ripser.cpp"}}
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "env.json").write_text(json.dumps(env))
            (out / "bars").mkdir()
            write_runs(out, [run_row("small", "tda4j", "warm", "ok", wall="1", median="1")])
            bench.summarize(out, bottleneck=stub_bottleneck)
            self.assertIn(f"TDA4j `{self.head[:12]}` with 1 changed file (notes.txt).", (out / "summary.md").read_text())
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "env.json").write_text(json.dumps(env))
            (out / "bars").mkdir()
            with open(out / "sweep.csv", "w", newline="") as f:
                w = csv.DictWriter(f, fieldnames=sweep_threads.COLUMNS)
                w.writeheader()
                w.writerow(run_row("small", "tda4j-parallel-mt", "warm", "ok", median="1", threads="1", pinned="False",
                                   multithreaded="True"))
            sweep_threads.summarize(out, bottleneck=stub_bottleneck)
            self.assertIn(f"TDA4j `{self.head[:12]}` with 1 changed file (notes.txt).", (out / "sweep.md").read_text())

    def note(self, head, *dirty):
        path = Path(self.dir.name) / "tda4j-js.git"
        path.write_text("\n".join([head, *dirty]) + "\n")
        return path

    def test_the_note_gives_the_commit_and_the_files_that_differed(self):
        rev = bench.js_revision(self.note(self.head, "src/main/scala/A.scala"), self.repo)
        self.assertEqual((rev["tda4j_js_git"], rev["tda4j_js_dirty"]), (self.head, ["src/main/scala/A.scala"]))

    def test_the_package_is_stale_once_src_main_differs_from_the_commit_it_was_built_from(self):
        note = self.note(self.head)
        self.assertIs(bench.js_revision(note, self.repo)["tda4j_js_stale"], False)
        (self.repo / "notes.txt").write_text("not a source")
        self.assertIs(bench.js_revision(note, self.repo)["tda4j_js_stale"], False)
        (self.repo / "src" / "main" / "scala" / "A.scala").write_text("object A { val x = 2 }")
        self.assertIs(bench.js_revision(note, self.repo)["tda4j_js_stale"], True)

    def test_a_commit_git_does_not_know_leaves_staleness_unknown(self):
        self.assertIsNone(bench.js_revision(self.note("f" * 40), self.repo)["tda4j_js_stale"])

    def test_no_note_is_no_entry(self):
        self.assertEqual(bench.js_revision(None, self.repo), {})
        self.assertEqual(bench.js_revision(Path(self.dir.name) / "none", self.repo), {})
        self.assertEqual(bench.js_revision(self.note(""), self.repo), {})

    def test_the_summary_names_the_package_commit_only_where_it_differs_or_is_not_clean(self):
        env = {"tda4j_git": "0123456789abcdef", "tda4j_js_git": "0123456789abcdef", "tda4j_js_dirty": []}
        self.assertEqual(bench.revision_text(env), "TDA4j `0123456789ab`.")
        self.assertIn("tda4j-js rows ran the npm package built from `fedcba987654`.",
                      bench.revision_text({**env, "tda4j_js_git": "fedcba9876543210"}))
        self.assertIn("with 2 changed files (a, b)", bench.revision_text({**env, "tda4j_js_dirty": ["a", "b"]}))
        self.assertIn("src/main has changed since", bench.revision_text({**env, "tda4j_js_stale": True}))
        self.assertEqual(bench.revision_text({"tda4j_git": "unavailable", "tda4j_js_git": "0123456789ab"}), "")


class ThreadSweepTests(unittest.TestCase):
    """bench/sweep_threads.py: which counts run, how a tool is given its count and pinned, where a series stops, and the
    barcode checks of the summary."""

    def test_the_default_counts_are_the_powers_of_two_up_to_the_cpus_and_the_cpus_most_first(self):
        self.assertEqual(sweep_threads.thread_counts("", 32), [32, 16, 8, 4, 2, 1])
        self.assertEqual(sweep_threads.thread_counts("", 6), [6, 4, 2, 1])
        self.assertEqual(sweep_threads.thread_counts("", 1), [1])
        self.assertEqual(sweep_threads.thread_counts("1, 4,2", 32), [4, 2, 1])

    def test_the_generated_case_is_written_once_and_the_same_every_time(self):
        case = next(c for c in sweep_threads.SWEEP_CASES if c.name == "cube10_40")
        self.assertEqual((case.task, case.dim, case.threshold), ("vr", 10, 1.6))
        self.assertGreater(int(sweep_threads.GENERATED_CELLS["cube10_40"]), 1e8, "above the explicit tools' cap")
        self.assertIn("cube10_40", sweep_threads.DEFAULT_CASES.split(","))
        self.assertNotIn("cube10_40", {c.name for c in bench.CASES}, "not a case of the main run")
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            sweep_threads.generate(case, Path(a))
            sweep_threads.generate(case, Path(b))
            x = np.loadtxt(Path(a) / case.file)
            self.assertEqual(x.shape, (40, 10))
            self.assertTrue(((x >= 0) & (x < 1)).all())
            np.testing.assert_array_equal(x, np.loadtxt(Path(b) / case.file))
            (Path(a) / case.file).write_text("1 2\n")
            sweep_threads.generate(case, Path(a))
            self.assertEqual((Path(a) / case.file).read_text(), "1 2\n", "an existing file is kept")

    def test_a_tool_gets_its_count_as_threads_or_as_mpi_ranks_and_a_pinned_run_gets_as_many_cpus(self):
        self.assertEqual(sweep_threads.with_threads({"engine": "parallel", "threads": "ALL"}, 8)["threads"], "8")
        self.assertEqual(sweep_threads.with_threads({"only_p": 2, "ranks": "ALL"}, 8), {"only_p": 2, "ranks": "8"})
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "cp").write_text("x.jar")
            case = bench.Case("c", "vr", "pts.txt", "points", 2)
            args = Namespace(jvm_heap="1g", jvm_opts="")
            with mock.patch.dict(os.environ, {"TDA4J_CP": str(Path(d) / "cp")}):
                cmd = bench.command("tda4j", "tda4j-parallel-mt@8", sweep_threads.with_threads(
                    {"engine": "parallel", "reps": "none", "threads": "ALL"}, 8), case, Path(d), 2, 1.0, 0, 1, "o", args)
        self.assertIn("threads=8", cmd)
        self.assertIn("-Djava.util.concurrent.ForkJoinPool.common.parallelism=8", cmd)
        self.assertEqual(bench.pinned(cmd, Namespace(pin_cpus=4))[:3], ["taskset", "-c", "0-3"])
        self.assertEqual(bench.pinned(cmd, Namespace(pin_cpus=0)), cmd)
        self.assertEqual(bench.pinned(cmd, Namespace()), cmd)
        self.assertIsNone(bench.pinned(None, Namespace(pin_cpus=4)))

    def sweep(self, statuses, cells=None, argv=()):
        """Runs the sweep's loop with run_one faked: `statuses` maps "tool@threads" to a status (default ok)."""
        calls = []
        with tempfile.TemporaryDirectory() as d:
            data, out = Path(d) / "data", Path(d) / "out"
            data.mkdir()
            np.savetxt(data / "pts.txt", np.random.default_rng(0).random((12, 3)))
            b = sweep_threads.bench
            saved = b.CASES, b.TOOLS, b.available, b.run_one, b.write_env, b.cells_reference
            b.CASES = [b.Case("heavy", "vr", "pts.txt", "points", 1)]
            b.TOOLS = [("one", "ripser", "vr", {}),
                       ("par", "tda4j", "vr", {"engine": "parallel", "threads": "ALL", "multithreaded": True}),
                       ("blk", "tda4j", "vr", {"engine": "blocks", "threads": "ALL", "multithreaded": True,
                                               "explicit": True}),
                       ("mpi", "dipha", "vr", {"only_p": 2, "ranks": "ALL", "multithreaded": True}),
                       ("par-1t", "tda4j", "vr", {"engine": "parallel", "threads": "1"})]
            b.available = lambda kind, opts, python: True
            b.write_env = lambda out, args: None
            b.cells_reference = lambda: cells or {}

            def fake(w, f, data, out, case, p, threshold, name, kind, opts, args):
                calls.append((name, opts.get("threads") or opts.get("ranks"), args.pin_cpus))
                return statuses.get(name, "ok")
            b.run_one = fake
            args = sweep_threads.parser().parse_args(
                ["--data", str(data), "--out", str(out), "--cases", "heavy", "--threads", "1,2,4", "--baselines", "one",
                 *argv])
            try:
                with mock.patch.dict(os.environ, {"MPIEXEC_FLAGS": "--oversubscribe"}):
                    sweep_threads.sweep(args, out, 4, pin=not args.no_pin)
                with open(out / "sweep.csv") as f:
                    rows = list(csv.DictReader(f))
            finally:
                b.CASES, b.TOOLS, b.available, b.run_one, b.write_env, b.cells_reference = saved
        return calls, rows

    def test_the_baseline_runs_once_and_each_multithreaded_tool_from_the_most_threads_down_pinned(self):
        calls, rows = self.sweep({})
        self.assertEqual(calls, [("one@1", None, 1),
                                 ("par@4", "4", 4), ("par@2", "2", 2), ("par@1", "1", 1),
                                 ("blk@4", "4", 4), ("blk@2", "2", 2), ("blk@1", "1", 1),
                                 ("mpi@4", "4", 4), ("mpi@2", "2", 2), ("mpi@1", "1", 1)])
        self.assertEqual(rows, [], "the run rows are run_one's to write")

    def test_without_pinning_nothing_is_confined(self):
        calls, _ = self.sweep({}, argv=("--no-pin",))
        self.assertEqual({c[2] for c in calls}, {0})

    def test_a_series_stops_at_a_timeout_or_an_error_and_goes_on_after_running_out_of_memory(self):
        calls, rows = self.sweep({"par@2": "timeout", "blk@4": "error(1)", "mpi@4": "oom"})
        self.assertEqual([c[0] for c in calls], ["one@1", "par@4", "par@2", "blk@4", "mpi@4", "mpi@2", "mpi@1"])
        skipped = {(r["tool"], r["threads"]): (r["status"], r["reason"]) for r in rows}
        self.assertEqual(skipped[("par", "1")], ("timeout", "not run: timeout at 2 threads"))
        self.assertEqual(skipped[("blk", "1")], ("error(1)", "not run: error(1) at 4 threads"))
        self.assertEqual({r["pinned"] for r in rows}, {"True"})

    def test_the_explicit_tool_is_not_run_on_a_complex_above_the_cap(self):
        calls, rows = self.sweep({}, cells={"heavy": "500000000"})
        self.assertNotIn("blk", {c[0].split("@")[0] for c in calls})
        blk = [r for r in rows if r["tool"] == "blk"]
        self.assertEqual(len(blk), 1)
        self.assertEqual(blk[0]["status"], "unsupported")
        self.assertIn("500,000,000 cells", blk[0]["reason"])

    def test_the_mpi_ranks_are_not_bound_by_open_mpi_when_the_runs_are_pinned(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MPIEXEC_FLAGS", None)
            with mock.patch.object(sweep_threads.bench, "mpiexec_flags", lambda: ["--oversubscribe"]):
                self.assertEqual(sweep_threads.pinned_mpi_flags(), "--oversubscribe --bind-to none")
            with mock.patch.object(sweep_threads.bench, "mpiexec_flags", lambda: []):
                self.assertIsNone(sweep_threads.pinned_mpi_flags(), "another MPI: left alone")
            os.environ["MPIEXEC_FLAGS"] = "--mine"
            self.assertIsNone(sweep_threads.pinned_mpi_flags(), "the user's flags win")

    def test_the_summary_gives_speedups_and_flags_a_barcode_that_changes_with_the_count(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "bars").mkdir()
            (out / "env.json").write_text(json.dumps({"cpu": "Test CPU", "cores": 4, "args": {"baselines": "ripser.cpp"}}))
            good, bad = "0\t0.0\tinf\n1\t0.5\t0.75\n", "0\t0.0\tinf\n1\t0.5\t0.8\n"
            rows = [run_row("heavy", "ripser.cpp", "warm", "ok", median="2.0", p="2", threads="1", pinned="True")]
            (out / "bars" / "heavy__p2__ripser.cpp@1.tsv").write_text(good)
            for tool, times, bars in [("tda4j-parallel-mt", {1: "8.0", 2: "4.0", 4: "2.5"}, {1: good, 2: good, 4: good}),
                                      ("giotto-ph-mt", {1: "6.0", 2: "3.0", 4: ""}, {1: good, 2: bad})]:
                for t, median in times.items():
                    status = "ok" if median else "timeout"
                    rows.append(run_row("heavy", tool, "cold" if not median else "warm", status, median=median, p="2",
                                        threads=str(t), pinned="True", multithreaded="True"))
                    if t in bars:
                        (out / "bars" / f"heavy__p2__{tool}@{t}.tsv").write_text(bars[t])
            with open(out / "sweep.csv", "w", newline="") as f:
                w = csv.DictWriter(f, fieldnames=sweep_threads.COLUMNS)
                w.writeheader()
                for r in rows:
                    w.writerow(r)
            sweep_threads.summarize(out, bottleneck=stub_bottleneck)
            md = (out / "sweep.md").read_text()
        lines = {line.split("|")[1].strip(): line for line in md.splitlines() if line.startswith("| ")}
        self.assertIn("pinned to t CPUs", md)
        self.assertIn("| tool | 1 | 2 | 4 | fastest | barcode |", md)
        self.assertIn("| 8 | 4 (2×) | 2.5 (3.2×) | 2.5 at 4 (1.2× ripser.cpp) | same at every count; ripser.cpp: 0 |",
                      lines["tda4j-parallel-mt"])
        self.assertIn("| 6 | 3 (2×) | timeout | 3 at 2 (1.5× ripser.cpp) | differs at 2 threads from 1 "
                      "(H1: bar (0.5, 0.8) against (0.5, 0.75)); ripser.cpp: 0 |", lines["giotto-ph-mt"])
        self.assertIn("| 2 |  |  |  |  |", lines["ripser.cpp (single-threaded)"])


class SingleThreadedRowsTests(unittest.TestCase):
    """The primary tables are single-threaded: a TDA4j row runs on the main thread unless it is a multithreaded one."""

    def tda4j_command(self, opts):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "cp").write_text("x.jar")
            case = bench.Case("c", "vr", "pts.txt", "points", 2)
            with mock.patch.dict(os.environ, {"TDA4J_CP": str(Path(d) / "cp")}):
                return bench.command("tda4j", "t", opts, case, Path(d), 2, 1.0, 0, 1, "o", Namespace(jvm_heap="1g", jvm_opts=""))

    def test_a_row_runs_sequentially_and_a_multithreaded_row_does_not(self):
        self.assertIn("sequential=true", self.tda4j_command({"engine": "auto", "reps": "cycles"}))
        self.assertIn("sequential=true", self.tda4j_command({"engine": "parallelverb", "reps": "cocycles"}))
        mt = self.tda4j_command({"engine": "parallelverb", "reps": "cocycles", "threads": "4", "multithreaded": True})
        self.assertNotIn("sequential=true", mt)
        self.assertIn("threads=4", mt)

    def test_the_tools_name_the_shipped_parallel_engine_on_one_thread_and_on_every_cpu(self):
        tools = {name: opts for name, _, task, opts in bench.TOOLS if task == "vr"}
        self.assertEqual(tools["tda4j-parallelverb-1t"]["engine"], "parallelverb")
        self.assertNotIn("multithreaded", tools["tda4j-parallelverb-1t"])
        self.assertTrue(tools["tda4j-parallelverb-mt"]["multithreaded"])
        self.assertEqual(tools["tda4j-parallelverb-mt"]["threads"], "ALL")
        self.assertEqual(tools["tda4j-parallelverb-mt"]["reps"], "cocycles", "the verb's representatives, not bars only")

    def test_every_multithreaded_row_is_swept_for_threads_and_the_cubical_sweep_is_sequential(self):
        sweep = [name for name, _, task, opts in bench.TOOLS if task == "vr" and opts.get("multithreaded")]
        self.assertIn("tda4j-parallelverb-mt", sweep)
        self.assertIn('"sequential=true"', (HERE_BENCH / "sweep_cubical.py").read_text())


class ExperimentRowsTests(unittest.TestCase):
    """An `experiment` row is run when `--tools` names it, and otherwise left out."""

    def names_run(self, tools):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            data, out = Path(d) / "data", Path(d) / "out"
            data.mkdir()
            np.savetxt(data / "pts.txt", np.random.default_rng(0).random((20, 3)))
            saved = bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env
            bench.CASES = [bench.Case("pts", "vr", "pts.txt", "points", 1)]
            bench.TOOLS = [("base", "py", "vr", {}), ("lab", "py", "vr", {"experiment": True})]
            bench.available = lambda kind, opts, python: True
            bench.write_env = lambda out, args: None
            bench.run_one = lambda w, f, data, out, case, p, threshold, name, *rest: calls.append(name) or "ok"
            try:
                bench.run(Namespace(data=str(data), out=str(out), cases="", tasks="", tools=tools, fields="2",
                                    python="python3", rerun_timeouts=False))
            finally:
                bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env = saved
        return calls

    def test_left_out_unless_named(self):
        self.assertEqual(self.names_run(""), ["base"])
        self.assertEqual(self.names_run("lab"), ["lab"])
        self.assertEqual(self.names_run("base,lab"), ["base", "lab"])

    def test_the_block_parallel_experiment_is_an_experiment_and_the_shipped_engine_is_not(self):
        flags = {name: opts.get("experiment") for name, _, task, opts in bench.TOOLS if task == "vr"}
        self.assertTrue(flags["tda4j-parallel-1t"] and flags["tda4j-parallel-mt"])
        self.assertFalse(flags["tda4j-parallelverb-1t"] or flags["tda4j-parallelverb-mt"] or flags["tda4j"])


class AlphaRadiusParityTests(unittest.TestCase):
    """An alpha case without a radius gives every tool that takes one the enclosing radius (TDA4j stops there by
    default, GUDHI does not), and the tools that take none, none."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.data = Path(self.dir.name)
        self.points = np.random.default_rng(3).random((30, 3))
        np.savetxt(self.data / "pts.txt", self.points)

    def calls(self, case):
        calls = []
        saved = bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env
        bench.CASES = [case]
        bench.TOOLS = [("with", "py", "alpha", {}), ("without", "py", "alpha", {"no_radius": True}),
                       ("dqp", "py", "alpha", {"needs_radius": True})]
        bench.available = lambda kind, opts, python: True
        bench.write_env = lambda out, args: None
        bench.run_one = lambda w, f, data, out, case, p, threshold, name, *rest: calls.append((name, threshold)) or "ok"
        try:
            bench.run(Namespace(data=str(self.data), out=str(self.data / "out"), cases="", tasks="", tools="",
                                fields="2", python="python3", rerun_timeouts=False))
        finally:
            bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env = saved
        return dict(calls)

    def test_the_default_radius_is_the_enclosing_radius_of_the_points(self):
        d = np.sqrt(((self.points[:, None, :] - self.points[None, :, :]) ** 2).sum(axis=2))
        case = bench.Case("a", "alpha", "pts.txt", "points", 2)
        self.assertAlmostEqual(bench.default_alpha_radius(case, self.data), float(d.max(axis=1).min()))

    def test_a_case_without_a_radius_gives_the_tools_that_take_one_the_enclosing_radius(self):
        got = self.calls(bench.Case("a", "alpha", "pts.txt", "points", 2))
        radius = bench.default_alpha_radius(bench.Case("a", "alpha", "pts.txt", "points", 2), self.data)
        self.assertEqual(got["with"], radius)
        self.assertIsNone(got["without"], "the whole triangulation takes no radius")
        self.assertNotIn("dqp", got, "a tool that needs the case's own radius is still skipped without one")

    def test_a_case_with_its_own_radius_keeps_it_and_the_tools_that_take_none_skip_it(self):
        got = self.calls(bench.Case("a", "alpha", "pts.txt", "points", 2, threshold=0.3))
        self.assertEqual((got["with"], got["dqp"]), (0.3, 0.3))
        self.assertNotIn("without", got, "unsupported on a case with a radius, as before")

    def test_other_tasks_and_radius_k_cases_have_no_shared_radius(self):
        self.assertIsNone(bench.default_alpha_radius(bench.Case("v", "vr", "pts.txt", "points", 2), self.data))
        self.assertIsNone(bench.default_alpha_radius(bench.Case("a", "alpha", "pts.txt", "points", 2, radius_k=5), self.data))


def result_row(case, tool, mode="cold", status="ok", p="2", wall="1.0", median="", threshold="", **extra):
    row = {c: "" for c in bench.RUNS_COLUMNS}
    row.update(case=case, task="vr", p=p, tool=tool, mode=mode, status=status, wall_s=wall, median_s=median,
               threshold=threshold, **extra)
    return row


def write_results(directory, rows, env=None, bars=(), logs=()):
    directory = Path(directory)
    (directory / "bars").mkdir(parents=True, exist_ok=True)
    (directory / "logs").mkdir(parents=True, exist_ok=True)
    with open(directory / "runs.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=bench.RUNS_COLUMNS)
        w.writeheader()
        w.writerows(rows)
    (directory / "env.json").write_text(json.dumps(env or {"args": {"javaplex_divisions": 1000}}))
    for name, text in bars:
        (directory / "bars" / name).write_text(text)
    for name in logs:
        (directory / "logs" / name).write_text("log")


class MergeTests(unittest.TestCase):
    """bench.py merge: one results directory from several, the last source with a (case, p, tool) winning."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = Path(self.dir.name)
        bars = "0\t0.0\tinf\n1\t0.5\t0.75\n"
        self.old = self.root / "old"
        write_results(self.old, [result_row("c", "ripser.cpp"), result_row("c", "ripser.cpp", "warm", median="2.0"),
                                 result_row("c", "tda4j"), result_row("c", "tda4j", "warm", median="9.0"),
                                 result_row("c", "gudhi", status="timeout")],
                      env={"date": "2026-10-20T10:00:00", "host": "server", "tda4j_git": "a" * 40,
                           "args": {"javaplex_divisions": 1000}},
                      bars=[("c__p2__ripser.cpp.tsv", bars), ("c__p2__tda4j.tsv", "old"), ("c__p2__gudhi.tsv", "g")],
                      logs=["c__p2__ripser.cpp__cold.out", "c__p2__tda4j__cold.out", "c__p2__tda4j__cold.err",
                            "c__p2__tda4j-cocycles__cold.out"])
        self.new = self.root / "new"
        write_results(self.new, [result_row("c", "tda4j"), result_row("c", "tda4j", "warm", median="1.5"),
                                 result_row("c", "gudhi", status="missing", mode="-"),
                                 result_row("d", "tda4j", p="17")],
                      env={"date": "2026-10-27T10:00:00", "host": "server", "tda4j_git": "b" * 40,
                           "args": {"javaplex_divisions": 1000}},
                      bars=[("c__p2__tda4j.tsv", bars), ("d__p17__tda4j.tsv", bars)],
                      logs=["c__p2__tda4j__cold.out", "d__p17__tda4j__cold.out"])
        self.out = self.root / "merged"

    def runs(self):
        return {(r["case"], r["p"], r["tool"], r["mode"]): r for r in bench.read_runs(self.out)}

    def test_the_last_source_with_a_group_replaces_all_its_rows_of_the_earlier_ones(self):
        bench.merge(self.out, [self.old, self.new])
        runs = self.runs()
        self.assertEqual(runs[("c", "2", "tda4j", "warm")]["median_s"], "1.5")
        self.assertEqual(runs[("c", "2", "ripser.cpp", "warm")]["median_s"], "2.0", "not rerun: kept")
        self.assertIn(("d", "17", "tda4j", "cold"), runs)
        self.assertEqual(len([k for k in runs if k[:3] == ("c", "2", "tda4j")]), 2, "one cold and one warm row")

    def test_a_tool_that_is_missing_in_the_later_run_does_not_replace_a_measurement(self):
        bench.merge(self.out, [self.old, self.new])
        self.assertEqual(self.runs()[("c", "2", "gudhi", "cold")]["status"], "timeout")

    def test_a_tool_only_the_later_run_lists_is_kept_even_when_missing_there(self):
        bench.merge(self.out, [self.new])
        self.assertEqual(self.runs()[("c", "2", "gudhi", "-")]["status"], "missing")

    def test_bars_and_logs_follow_their_rows_and_only_theirs(self):
        bench.merge(self.out, [self.old, self.new])
        self.assertEqual((self.out / "bars" / "c__p2__tda4j.tsv").read_text(), "0\t0.0\tinf\n1\t0.5\t0.75\n")
        self.assertTrue((self.out / "bars" / "c__p2__ripser.cpp.tsv").exists(), "a dotted tool name")
        self.assertTrue((self.out / "logs" / "c__p2__ripser.cpp__cold.out").exists())
        self.assertEqual((self.out / "logs" / "c__p2__tda4j__cold.out").read_text(), "log")
        self.assertFalse((self.out / "logs" / "c__p2__tda4j__cold.err").exists(), "the replaced run's log is not kept")
        self.assertFalse((self.out / "logs" / "c__p2__tda4j-cocycles__cold.out").exists(), "a tool with no rows")

    def test_a_tools_filter_takes_only_those_tools_of_a_source(self):
        bench.merge(self.out, [f"{self.old}:ripser.cpp", f"{self.new}:tda4j"])
        self.assertEqual({(k[0], k[2]) for k in self.runs()}, {("c", "ripser.cpp"), ("c", "tda4j"), ("d", "tda4j")})

    def test_drop_leaves_tools_out(self):
        bench.merge(self.out, [self.old, self.new], drop="gudhi,ripser.cpp")
        self.assertEqual({k[2] for k in self.runs()}, {"tda4j"})

    def test_the_environment_is_the_last_source_that_ran_tda4j_and_names_every_source(self):
        bench.merge(self.out, [self.old, self.new])
        env = bench.read_env(self.out)
        self.assertEqual(env["tda4j_git"], "b" * 40)
        self.assertEqual([o["tda4j_git"][:1] for o in env["merged_from"]], ["a", "b"])
        self.assertEqual(env["merged_from"][0]["tools"], ["gudhi", "ripser.cpp"])
        self.assertEqual(env["merged_from"][1]["tools"], ["gudhi", "tda4j"][1:])

    def test_a_directory_that_holds_a_run_is_not_overwritten(self):
        bench.merge(self.out, [self.old])
        with self.assertRaises(SystemExit):
            bench.merge(self.out, [self.new])

    def test_the_merged_run_summarizes_with_the_sources_named_and_agreement_across_them(self):
        bench.merge(self.out, [self.old, self.new])
        bench.summarize(self.out, bottleneck=stub_bottleneck)
        md = (self.out / "summary.md").read_text()
        self.assertIn("Merged from:", md)
        self.assertIn(f"`{self.old}` (2026-10-20, server, TDA4j `{'a' * 12}`): gudhi, ripser.cpp", md)
        self.assertIn(f"`{self.new}` (2026-10-27, server, TDA4j `{'b' * 12}`): tda4j", md)
        self.assertIn("TDA4j `" + "b" * 12 + "`", md.splitlines()[2], "the header names the commit that ran TDA4j")
        summary = {r["tool"]: r for r in csv.DictReader(open(self.out / "summary.csv")) if r["case"] == "c"}
        self.assertEqual(summary["tda4j"]["agrees"], "True", "compared with the reference run in the other directory")

    def test_hosts_that_differ_are_a_warning_in_the_summary(self):
        env = json.loads((self.new / "env.json").read_text())
        (self.new / "env.json").write_text(json.dumps({**env, "host": "laptop"}))
        bench.merge(self.out, [self.old, self.new])
        bench.summarize(self.out, bottleneck=stub_bottleneck)
        self.assertIn("different machines (laptop, server)", (self.out / "summary.md").read_text())

    def test_parse_source(self):
        self.assertEqual(bench.parse_source("/a/b"), (Path("/a/b"), ""))
        self.assertEqual(bench.parse_source("/a/b:x,y-z"), (Path("/a/b"), "x,y-z"))
        self.assertEqual(bench.parse_source("/a/b:!tda4j*"), (Path("/a/b"), "!tda4j*"))
        self.assertEqual(bench.parse_source("a:b/c"), (Path("a:b/c"), ""))

    def test_a_pattern_source_takes_every_tool_but_the_excluded(self):
        bench.merge(self.out, [f"{self.old}:!tda4j*", f"{self.new}:tda4j*"])
        runs = self.runs()
        self.assertEqual({k[2] for k in runs}, {"ripser.cpp", "gudhi", "tda4j"})
        self.assertEqual(runs[("c", "2", "tda4j", "warm")]["median_s"], "1.5", "the old TDA4j rows are not taken")


class AnchorsTests(unittest.TestCase):
    """bench.py anchors: whether the rows two runs share took the same time."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = Path(self.dir.name)

    def runs(self, name, times):
        rows = []
        for (case, tool), (cold, warm) in times.items():
            rows.append(result_row(case, tool, wall=str(cold)))
            if warm:
                rows.append(result_row(case, tool, "warm", median=str(warm), wall="9"))
        write_results(self.root / name, rows)
        return self.root / name

    def test_rows_within_the_tolerance_are_ok_and_the_warm_median_is_the_time(self):
        old = self.runs("old", {("c", "ripser.cpp"): (5.0, 4.0), ("c", "gudhi"): (10.0, None)})
        new = self.runs("new", {("c", "ripser.cpp"): (9.0, 4.4), ("c", "gudhi"): (11.0, None)})
        rows, verdict = bench.anchors(new, old, tolerance=0.2)
        self.assertEqual(verdict, "ok")
        self.assertEqual([(r[2], round(r[5], 2)) for r in rows], [("gudhi", 1.1), ("ripser.cpp", 1.1)])

    def test_a_row_outside_the_tolerance_either_way_is_drift(self):
        old = self.runs("old", {("c", "ripser.cpp"): (5.0, 4.0), ("c", "gudhi"): (10.0, None)})
        for factor in (1.5, 0.5):
            new = self.runs("new", {("c", "ripser.cpp"): (5.0, 4.0), ("c", "gudhi"): (10.0 * factor, None)})
            rows, verdict = bench.anchors(new, old, tolerance=0.2)
            self.assertEqual(verdict, "drift")
            self.assertEqual([r[2] for r in rows if not r[-1]], ["gudhi"])
            shutil.rmtree(new)

    def test_tools_restrict_the_rows_and_failed_rows_are_not_anchors(self):
        write_results(self.root / "old", [result_row("c", "a", wall="1"), result_row("c", "b", wall="1"),
                                          result_row("c", "t", wall="1", status="timeout")])
        write_results(self.root / "new", [result_row("c", "a", wall="1"), result_row("c", "b", wall="5"),
                                          result_row("c", "t", wall="1")])
        rows, verdict = bench.anchors(self.root / "new", self.root / "old", {"a"})
        self.assertEqual(([r[2] for r in rows], verdict), (["a"], "ok"))
        self.assertEqual([r[2] for r in bench.anchors(self.root / "new", self.root / "old")[0]], ["a", "b"])

    def test_no_common_row_is_none(self):
        old = self.runs("old", {("c", "a"): (1.0, None)})
        new = self.runs("new", {("c", "b"): (1.0, None)})
        self.assertEqual(bench.anchors(new, old)[1], "none")

    def test_the_text_names_each_row_and_the_verdict(self):
        old = self.runs("old", {("c", "a"): (1.0, None)})
        new = self.runs("new", {("c", "a"): (2.0, None)})
        rows, verdict = bench.anchors(new, old)
        text = "\n".join(bench.anchors_text(rows, verdict, 0.2))
        self.assertIn("| c | 2 | a | 1.000 | 2.000 | 2.00 | NO |", text)
        self.assertIn("differs by more than 20%", text)


class PipConstraintsTests(unittest.TestCase):
    def test_a_freeze_becomes_pins_and_editable_or_url_installs_are_left_out(self):
        env = {"python_packages": "numpy==1.26.4\ngudhi==3.10.1\n-e git+https://x/y.git#egg=y\n"
                                  "pkg @ file:///tmp/pkg\nripser==0.6.8\n\noat_python==0.1.2.post1"}
        self.assertEqual(bench.pip_constraints(env),
                         ["numpy==1.26.4", "gudhi==3.10.1", "ripser==0.6.8", "oat_python==0.1.2.post1"])
        self.assertEqual(bench.pip_constraints({}), [])


class ToolSelectionTests(unittest.TestCase):
    def test_names_patterns_and_exclusions(self):
        sel = bench.tool_selected
        self.assertTrue(sel("anything", ""))
        self.assertTrue(sel("tda4j-js", "tda4j*"))
        self.assertTrue(sel("ripser.cpp", "ripser.cpp,gudhi"))
        self.assertFalse(sel("ripser.py", "ripser.cpp,gudhi"))
        self.assertFalse(sel("tda4j-js", "tda4j*,!tda4j-js*"))
        self.assertTrue(sel("tda4j-cocycles", "tda4j*,!tda4j-js*"))
        self.assertTrue(sel("gudhi", "!tda4j*"), "exclusions alone select the rest")
        self.assertFalse(sel("tda4j", "!tda4j*"))


class ResumeTests(unittest.TestCase):
    """--resume: a run continues where an interrupted one stopped."""

    def run_resumed(self, rows, tools=("a", "b", "c"), fields="2,17", resume=True):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            data, out = Path(d) / "data", Path(d) / "out"
            data.mkdir()
            out.mkdir()
            np.savetxt(data / "pts.txt", np.random.default_rng(0).random((20, 3)))
            if rows is not None:
                write_results(out, rows)
            saved = bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env
            bench.CASES = [bench.Case("pts", "vr", "pts.txt", "points", 1)]
            bench.TOOLS = [(t, "py", "vr", {"slower_than": "a"} if t == "c" else {}) for t in tools]
            bench.available = lambda kind, opts, python: True
            bench.write_env = lambda out, args: None
            bench.run_one = lambda w, f, data, out, case, p, threshold, name, *rest: calls.append((p, name)) or "ok"
            try:
                bench.run(Namespace(data=str(data), out=str(out), cases="", tasks="", tools="", fields=fields,
                                    python="python3", rerun_timeouts=False, resume=resume))
                kept = bench.read_runs(out)
            finally:
                bench.CASES, bench.TOOLS, bench.available, bench.run_one, bench.write_env = saved
        return calls, kept

    def test_finished_groups_are_skipped_and_the_others_run(self):
        rows = [result_row("pts", "a"), result_row("pts", "a", "warm", median="1"),  # finished
                result_row("pts", "b", status="timeout", reason="timed out"),  # finished: no warm row after a failure
                result_row("pts", "a", p="17")]  # stopped after its cold row
        calls, kept = self.run_resumed(rows)
        self.assertEqual(calls, [(2, "c"), (17, "a"), (17, "c")], "b timed out at 2, so it is not run at 17")
        self.assertEqual([(r["p"], r["tool"], r["mode"]) for r in kept[:3]],
                         [("2", "a", "cold"), ("2", "a", "warm"), ("2", "b", "cold")],
                         "the unfinished group's cold row is dropped, to be written again by the rerun")
        skipped = next(r for r in kept if (r["p"], r["tool"]) == ("17", "b"))
        self.assertIn("timed out at p=2", skipped["reason"])

    def test_a_timeout_before_the_interruption_still_stops_the_later_field_and_the_slower_variant(self):
        rows = [result_row("pts", "a", status="timeout", reason="timed out")]
        calls, _ = self.run_resumed(rows, tools=("a", "c"))
        self.assertEqual(calls, [], "a timed out at p=2: not run again at 17, and c is slower than a")

    def test_without_resume_nothing_is_skipped(self):
        rows = [result_row("pts", "a"), result_row("pts", "a", "warm", median="1")]
        calls, _ = self.run_resumed(rows, tools=("a",), fields="2", resume=False)
        self.assertEqual(calls, [(2, "a")])

    def test_resuming_where_nothing_ran_runs_everything(self):
        calls, _ = self.run_resumed(None, tools=("a", "b"), fields="2")
        self.assertEqual(calls, [(2, "a"), (2, "b")])


class CheckTests(unittest.TestCase):
    """bench.py check: the rows of a summarized run that failed or disagree."""

    def summary(self, rows):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        fields = ["case", "task", "p", "tool", "status", "reason", "agrees", "degenerate", "vs_reference", "note"]
        with open(Path(d.name) / "summary.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fields)
            w.writeheader()
            for r in rows:
                w.writerow({"task": "vr", "p": "2", "status": "ok", "reason": "", "agrees": "True", "degenerate": "False",
                            "vs_reference": "0", "note": "", **r})
        return d.name

    def test_failed_and_disagreeing_rows_are_problems_and_allowed_statuses_are_not(self):
        out = self.summary([{"case": "a", "tool": "tda4j"},
                            {"case": "b", "tool": "tda4j", "status": "error(1)", "reason": "boom"},
                            {"case": "c", "tool": "tda4j", "status": "timeout"},
                            {"case": "d", "tool": "tda4j", "status": "unsupported"},
                            {"case": "e", "tool": "tda4j", "agrees": "False", "vs_reference": "0.3"},
                            {"case": "f", "tool": "tda4j", "agrees": "False", "degenerate": "True"},
                            {"case": "g", "tool": "tda4j", "task": "sparse", "agrees": "False"},
                            {"case": "h", "tool": "gudhi", "status": "error(1)"}])
        problems = bench.check(out, tools="tda4j*")
        self.assertEqual([(c, t) for c, _, t, _ in problems], [("b", "tda4j"), ("c", "tda4j"), ("e", "tda4j")])
        self.assertIn("error(1) boom", problems[0][3])
        self.assertIn("(0.3)", problems[2][3])
        self.assertEqual([c for c, *_ in bench.check(out, tools="tda4j*", allow=("unsupported", "timeout"))], ["b", "e"])
        self.assertEqual(len(bench.check(out)), 4, "all tools: gudhi's error counts")


@unittest.skipUnless(shutil.which("bash") and shutil.which("git"), "needs bash and git")
class FinalRunScriptTests(unittest.TestCase):
    """bench/final_run.sh: the stages' commands, the frozen commit, the anchors verdict that gates the merge. run.sh is
    replaced by a script that logs its arguments, so no tool is needed."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = Path(self.dir.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        (self.repo / "tracked.txt").write_text("1")
        self.git("add", "-A")
        self.commit("first")
        self.log = self.root / "calls.log"
        self.fake = self.root / "run.sh"
        header = "case,p,tool,task,status,reason,agrees,degenerate,vs_reference,note"
        self.fake.write_text('#!/usr/bin/env bash\necho "$*" >> "$FAKE_LOG"\n'
                             f'if [ "$1" = merge ]; then mkdir -p "$2"; echo "{header}" > "$2/summary.csv"; fi\n'
                             f'if [ "$1" = run ] && [ -n "${{RESULTS_DIR:-}}" ]; then mkdir -p "$RESULTS_DIR"; '
                             f'echo "{header}" > "$RESULTS_DIR/summary.csv"; fi\n')
        self.fake.chmod(0o755)
        self.base = self.root / "base"
        write_results(self.base, [result_row("c", "ripser.cpp")],
                      env={"args": {"fields": "2,17", "timeout": 7200.0, "jvm_heap": "96g", "warmup": 1, "trials": 3,
                                    "warm_cap": 30.0, "jvm_opts": "-Xss8m"}})
        self.final = self.root / "work" / "final"

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repo), *args], capture_output=True, text=True, check=True).stdout.strip()

    def commit(self, message):
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", message)

    def script(self, *args, **env):
        e = {**os.environ, "REPO": str(self.repo), "RUN": str(self.fake), "PY": sys.executable,
             "WORKDIR": str(self.root / "work"), "BASE": str(self.base), "FAKE_LOG": str(self.log), **env}
        return subprocess.run(["bash", str(HERE_BENCH / "final_run.sh"), *args], capture_output=True, text=True, env=e)

    def calls(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def test_freeze_records_the_commit_builds_and_pins_the_stages_to_it(self):
        r = self.script("freeze")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((self.final / "commit").read_text().strip(), self.git("rev-parse", "HEAD"))
        self.assertIn("setup tda4j tda4j-js", self.calls())
        self.commit("second")
        for stage in ("smoke", "tda4j", "freeze"):
            r = self.script(stage)
            self.assertNotEqual(r.returncode, 0, stage)
            self.assertIn("use a new FINAL", r.stderr)

    def test_a_stage_needs_a_frozen_clean_checkout(self):
        r = self.script("tda4j")
        self.assertIn("run the freeze stage first", r.stderr)
        self.script("freeze")
        (self.repo / "tracked.txt").write_text("changed")
        r = self.script("tda4j")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("tracked files differ from HEAD (tracked.txt", r.stderr)
        self.assertEqual(self.script("plan").returncode, 0, "a plan changes nothing, so a dirty tree is fine")

    def test_the_tda4j_stage_runs_every_jvm_row_with_the_protocol_of_the_earlier_run(self):
        self.script("freeze")
        self.log.unlink()
        r = self.script("tda4j")
        self.assertEqual(r.returncode, 0, r.stderr)
        run = next(c for c in self.calls() if c.startswith("run "))
        self.assertIn("--tools tda4j*,!tda4j-js*,tda4j-parallel-1t,tda4j-parallel-mt", run)
        for option in ("--fields 2,17", "--timeout 7200.0", "--jvm-heap 96g", "--warmup 1", "--trials 3",
                       "--warm-cap 30.0", "--jvm-opts -Xss8m", "--resume"):
            self.assertIn(option, run)
        self.assertEqual(self.script("tda4j", TIMEOUT="100", FIELDS="17").returncode, 0)
        last = next(c for c in reversed(self.calls()) if c.startswith("run "))
        self.assertIn("--fields 17 --timeout 100 ", last, "the environment beats the earlier run's protocol")

    def test_the_merge_takes_the_other_tools_from_the_earlier_run_and_refuses_drifting_anchors(self):
        self.script("freeze")
        self.final.mkdir(parents=True, exist_ok=True)
        (self.final / "tda4j").mkdir()
        write_results(self.final / "tda4j", [result_row("c", "tda4j")])
        (self.final / "anchors.verdict").write_text("drift\n")
        r = self.script("merge")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("ALLOW_DRIFT=1", r.stderr)
        self.assertFalse(any(c.startswith("merge") for c in self.calls()))
        r = self.script("merge", ALLOW_DRIFT="1")
        self.assertEqual(r.returncode, 0, r.stderr)
        merge = next(c for c in self.calls() if c.startswith("merge "))
        self.assertEqual(merge, f"merge {self.final}/merged {self.base}:!tda4j* {self.final}/tda4j")
        self.assertNotEqual(self.script("merge", ALLOW_DRIFT="1").returncode, 0, "a merge is not overwritten")

    def test_the_v_construction_stage_runs_the_external_tools_at_z2_and_the_merge_takes_its_rows(self):
        self.script("freeze")
        self.log.unlink()
        r = self.script("cubicalv")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("setup flashcubical", self.calls())
        run = next(c for c in self.calls() if c.startswith("run "))
        for option in ("--tools gudhi,cripser,flashcubical", "--cases *_v", "--fields 2 ", "--timeout 7200.0", "--resume"):
            self.assertIn(option, run)
        (self.final / "tda4j").mkdir(parents=True, exist_ok=True)
        write_results(self.final / "tda4j", [result_row("c", "tda4j")])
        write_results(self.final / "cubicalv", [result_row("c_v", "gudhi")])
        (self.final / "anchors.verdict").write_text("ok\n")
        self.assertEqual(self.script("merge").returncode, 0)
        merge = next(c for c in self.calls() if c.startswith("merge "))
        self.assertEqual(merge, f"merge {self.final}/merged {self.base}:!tda4j* {self.final}/tda4j {self.final}/cubicalv")

    def test_the_redzed_stage_runs_it_at_z2_on_every_vietoris_rips_case_and_the_merge_takes_its_rows(self):
        self.script("freeze")
        self.log.unlink()
        r = self.script("redzed")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("setup redzed", self.calls())
        run = next(c for c in self.calls() if c.startswith("run "))
        for option in ("--tools redzed", "--fields 2 ", "--timeout 7200.0", "--resume"):
            self.assertIn(option, run)
        self.assertNotIn("--cases", run, "every case: the tool only has Vietoris-Rips rows")
        (self.final / "tda4j").mkdir(parents=True, exist_ok=True)
        write_results(self.final / "tda4j", [result_row("c", "tda4j")])
        write_results(self.final / "redzed", [result_row("c", "redzed")])
        (self.final / "anchors.verdict").write_text("ok\n")
        self.assertEqual(self.script("merge").returncode, 0)
        merge = next(c for c in self.calls() if c.startswith("merge "))
        self.assertEqual(merge, f"merge {self.final}/merged {self.base}:!tda4j* {self.final}/tda4j {self.final}/redzed")

    def test_the_smoke_stage_checks_redzed_against_ripser_at_z2_with_a_missing_tool_an_error(self):
        self.script("freeze")
        self.log.unlink()
        self.assertEqual(self.script("smoke").returncode, 0)
        runs = [c for c in self.calls() if c.startswith("run ")]
        z2 = next(c for c in runs if "--tools redzed,ripser.cpp" in c)
        self.assertIn("--cases sphere3_192,o3_1024,dragon2000", z2)
        self.assertIn("--fields 2 ", z2)
        self.assertIn("setup redzed", self.calls())

    def test_the_smoke_stage_checks_the_v_cases_at_z2_with_a_missing_tool_an_error(self):
        self.script("freeze")
        self.log.unlink()
        self.assertEqual(self.script("smoke").returncode, 0)
        runs = [c for c in self.calls() if c.startswith("run ")]
        v = next(c for c in runs if "--cases random2d_512_v,random3d_32_v" in c)
        self.assertIn("--fields 2 ", v)
        for tool in ("tda4j*", "gudhi", "cripser", "flashcubical"):
            self.assertIn(tool, v)
        self.assertIn("setup flashcubical", self.calls())

    def test_status_names_the_commit_and_counts_rows(self):
        self.script("freeze")
        (self.final / "tda4j").mkdir(parents=True)
        write_results(self.final / "tda4j", [result_row("c", "tda4j"), result_row("c", "tda4j", "warm", median="1")])
        out = self.script("status").stdout
        self.assertIn(f"frozen at: {self.git('rev-parse', 'HEAD')}", out)
        self.assertRegex(out, r"tda4j\s+2 rows")

    def test_the_plan_lists_every_stage_and_runs_nothing(self):
        r = self.script("plan")
        self.assertEqual(r.returncode, 0, r.stderr)
        for stage in ("freeze", "smoke", "anchors", "tda4j", "js", "cubicalv", "redzed", "merge", "sweeps"):
            self.assertIn(f"=== {stage}", r.stdout)
        self.assertIn("sweep-threads", r.stdout)
        self.assertEqual(self.calls(), [])
        self.assertFalse(self.final.exists())

    def test_the_defaults_name_cases_and_tools_that_exist(self):
        text = (HERE_BENCH / "final_run.sh").read_text()

        def default(name):
            return re.search(name + r'="\$\{' + name + r':-([^}]*)\}"', text).group(1)

        cases = {c.name for c in bench.CASES}
        for name in ("JS_CASES", "SMOKE_CASES", "ANCHOR_CASES", "SMOKE_V_CASES"):
            self.assertLessEqual(set(default(name).split(",")), cases, name)
        tools = {t[0] for t in bench.TOOLS}
        for name in ("ANCHOR_TOOLS", "CUBICALV_TOOLS", "REDZED_TOOLS"):
            self.assertLessEqual(set(default(name).split(",")), tools, name)
        v_cases = {c.name for c in bench.CASES if dict(c.params).get("construction") == "V"}
        self.assertEqual({c for c in cases if bench.tool_selected(c, default("CUBICALV_CASES"))}, v_cases,
                         "the V stage runs the V cases and nothing else")
        self.assertTrue(set(default("SMOKE_V_CASES").split(",")) <= v_cases)
        self.assertTrue(set(default("TDA4J_TOOLS").split(",")) & tools >= {"tda4j-parallel-1t", "tda4j-parallel-mt"})
        self.assertTrue(all(bench.tool_selected(t, default("JS_TOOLS")) for t in tools if t.startswith("tda4j-js")))
        selected = {t for t in tools if bench.tool_selected(t, default("TDA4J_TOOLS"))}
        self.assertFalse({t for t in selected if t.startswith("tda4j-js")}, "the JVM stage leaves the JS twins out")
        self.assertIn("tda4j-parallelverb-mt", selected)


def refined(vertices):
    """The T-construction array with the barcode of the V-construction array `vertices`: (2n - 1) per axis, a cell has the
    largest value of its vertices (the definition, not the driver's separable passes)."""
    import itertools
    out = np.empty(tuple(2 * n - 1 for n in vertices.shape))
    for idx in itertools.product(*[range(2 * n - 1) for n in vertices.shape]):
        axes = [[i // 2] if i % 2 == 0 else [i // 2, i // 2 + 1] for i in idx]
        out[idx] = max(vertices[v] for v in itertools.product(*axes))
    return out


class VConstructionTests(unittest.TestCase):
    """The `_v` cubical cases: the array read as vertex values (a cell born at the largest value of its vertices), the
    construction FlashCubical takes. TDA4j runs them on the refined image, the T-only rows leave them out, and the
    V-only row leaves the T cases out."""

    WORKER = Path(__file__).resolve().parent / "workers" / "py_worker.py"

    def case(self, name):
        return next(c for c in bench.CASES if c.name == name)

    def test_every_cubical_case_has_a_v_twin_on_the_same_array(self):
        for t in [c for c in bench.CASES if c.task == "cubical" and not c.name.endswith("_v")]:
            v = self.case(t.name + "_v")
            self.assertEqual((v.task, v.file, v.format, v.dim, v.threshold), (t.task, t.file, t.format, t.dim, t.threshold))
            self.assertEqual(dict(v.params), {"construction": "V"})
            self.assertEqual(dict(t.params), {})
        self.assertEqual(len({c.name for c in bench.CASES}), len(bench.CASES), "case names are unique")

    def test_the_cases_option_takes_patterns_like_the_tools_option(self):
        names = lambda spec: [c.name for c in bench.CASES if bench.tool_selected(c.name, spec) and c.task == "cubical"]
        self.assertTrue(all(n.endswith("_v") for n in names("*_v")) and len(names("*_v")) == 10)
        self.assertEqual(len(names("!*_v")), 10)
        self.assertEqual(names("random3d_32,random3d_32_v"), ["random3d_32", "random3d_32_v"])

    def test_flashcubical_runs_only_v_cases_at_z2_and_the_t_only_rows_only_t_cases(self):
        flash = dict((t[0], t[3]) for t in bench.TOOLS)["flashcubical"]
        t_case, v_case = self.case("random2d_512"), self.case("random2d_512_v")
        self.assertIn("V-construction only", bench.skip_reason(flash, t_case, 0, None, 2))
        self.assertIsNone(bench.skip_reason(flash, v_case, 0, None, 2))
        self.assertIn("Z/2", bench.skip_reason(flash, v_case, 0, None, 17))
        for name in ("tda4j-js", "tda4j-js-cohomology"):
            opts = dict((t[0], t[3]) for t in bench.TOOLS if t[2] == "cubical")[name]
            self.assertIsNone(bench.skip_reason(opts, t_case, 0, None, 17), name)
            self.assertIn("T-construction only", bench.skip_reason(opts, v_case, 0, None, 17), name)
        for name in ("tda4j", "tda4j-cohomology", "gudhi", "cripser"):
            opts = dict((t[0], t[3]) for t in bench.TOOLS if t[2] == "cubical")[name]
            for case in (t_case, v_case):
                self.assertIsNone(bench.skip_reason(opts, case, 0, None, 2), f"{name} {case.name}")

    def test_every_tool_is_told_the_construction_and_flashcubical_is_found_by_its_module(self):
        self.assertEqual(bench.case_extra_args(self.case("blob3d_64_v"), Path(".")), ["construction=V"])
        self.assertEqual(bench.case_extra_args(self.case("blob3d_64"), Path(".")), [])
        self.assertEqual(bench.available("py", {"pytool": "flashcubical"}, sys.executable), have("flash_cubical"))

    def test_the_reference_counts_a_v_case_by_its_vertices(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "a.npy"
            np.save(path, np.zeros((4, 5)))
            t = count_cells.count_cubical(self.case("random2d_512"), path)
            v = count_cells.count_cubical(self.case("random2d_512_v"), path)
        self.assertEqual(json.loads(t["cells"]), {"0": 30, "1": 49, "2": 20})  # 4 x 5 top cells
        self.assertEqual(json.loads(v["cells"]), {"0": 20, "1": 31, "2": 12})  # 4 x 5 vertices: 3 x 4 top cells
        reference = bench.cells_reference()
        self.assertEqual(int(reference["random2d_1024_v"]), 1048576 + 2 * 1024 * 1023 + 1023 * 1023)
        self.assertEqual(int(reference["random2d_1024"]), 1025 * 1025 + 2 * 1024 * 1025 + 1024 * 1024)

    def run_worker(self, tool, vertices, construction, dim=1, p=2, env=None):
        with tempfile.TemporaryDirectory() as d:
            source, out = Path(d) / "a.npy", Path(d) / "bars.tsv"
            np.save(source, vertices)
            cmd = [sys.executable, str(self.WORKER), f"tool={tool}", "task=cubical", f"input={source}", f"dim={dim}",
                   f"p={p}", "warmup=0", "trials=1", f"out={out}"] + ([f"construction={construction}"] if construction else [])
            r = subprocess.run(cmd, capture_output=True, text=True, env={**os.environ, **(env or {})})
            return r, (bench.read_bars(out) if r.returncode == 0 else None)

    def test_flashcubical_adapter_reads_birth_death_degree_and_keeps_the_degrees_asked_for(self):
        with tempfile.TemporaryDirectory() as d:
            # a stand-in for the extension: the rows of a Persistence.values (birth, death, degree), one in degree 2
            (Path(d) / "flash_cubical.py").write_text(
                "import numpy as np\n"
                "class _P:\n    values = np.array([[0.0, np.inf, 0], [1.0, 2.0, 0], [0.5, 4.0, 1], [3.0, 5.0, 2]])\n"
                "def compute(x, **kw):\n    assert x.dtype == np.float64 and x.flags.c_contiguous\n    return _P()\n")
            r, bars = self.run_worker("flashcubical", np.zeros((4, 4)), "V", env={"PYTHONPATH": d})
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(bars, {0: [(0.0, math.inf), (1.0, 2.0)], 1: [(0.5, 4.0)]})
            r, bars = self.run_worker("flashcubical", np.zeros((4, 4, 4)), "V", dim=2, env={"PYTHONPATH": d})
            self.assertEqual(sorted(bars), [0, 1, 2])
            self.assertEqual(json.loads(r.stdout.strip().splitlines()[-1])["bars"], {"0": 2, "1": 1, "2": 1})
            for construction, p in ((None, 2), ("T", 2), ("V", 17)):
                r, _ = self.run_worker("flashcubical", np.zeros((4, 4)), construction, p=p, env={"PYTHONPATH": d})
                self.assertEqual(r.returncode, 3, f"construction={construction} p={p}: exit 3 is 'unsupported'")

    @unittest.skipUnless(have("gudhi"), "gudhi missing")
    def test_gudhi_reads_the_vertex_array_as_the_t_construction_of_the_refined_one(self):
        # Two low vertices on a diagonal: one component as top cells (they share a corner), two as vertices.
        checkerboard = np.array([[0.0, 5.0], [5.0, 0.0]])
        _, as_t = self.run_worker("gudhi", checkerboard, None)
        _, as_v = self.run_worker("gudhi", checkerboard, "V")
        self.assertEqual(as_t, {0: [(0.0, math.inf)]})
        self.assertEqual(sorted(as_v[0]), [(0.0, 5.0), (0.0, math.inf)])
        self.assertEqual(sorted(as_v), [0])
        rng = np.random.default_rng(3)
        for shape in ((6, 7), (4, 4, 5)):
            vertices = rng.integers(0, 6, shape).astype(float)  # ties on purpose
            _, direct = self.run_worker("gudhi", vertices, "V", dim=len(shape) - 1)
            _, through_t = self.run_worker("gudhi", refined(vertices), None, dim=len(shape) - 1)
            for k in range(len(shape)):
                self.assertEqual(sorted(direct.get(k, [])), sorted(through_t.get(k, [])), f"{shape} degree {k}")

    @unittest.skipUnless(have("gudhi") and have("cripser"), "gudhi or cripser missing")
    def test_cripser_in_the_v_construction_has_the_barcode_of_gudhi(self):
        vertices = np.random.default_rng(5).integers(0, 6, (6, 6, 5)).astype(float)
        _, reference = self.run_worker("gudhi", vertices, "V", dim=2)
        r, bars = self.run_worker("cripser", vertices, "V", dim=2)
        self.assertEqual(r.returncode, 0, r.stderr)
        dist, note = bench.agreement(bars, reference, 2)
        self.assertEqual((dist, note), (0.0, ""))


if __name__ == "__main__":
    unittest.main(verbosity=2)
