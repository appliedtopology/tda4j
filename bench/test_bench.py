#!/usr/bin/env python3
"""Tests of the benchmark harness itself, on synthetic processes and synthetic results: nothing here needs the tools
being benchmarked (no sbt, GUDHI, ripser or Julia). Run from the repository root:

    python3 bench/test_bench.py        (or: python3 -m unittest discover -s bench -p 'test_*.py' -v)

CI does not run these (it has no Python step); they are what makes a change to bench.py checkable on a laptop.
"""
import csv
import importlib.util
import json
import math
import os
import sys
import tempfile
import time
import unittest
from argparse import Namespace
from pathlib import Path

import numpy as np

# By file, not by name: from the repository root `import bench` finds the directory, not bench.py.
_spec = importlib.util.spec_from_file_location("bench_py", Path(__file__).resolve().parent / "bench.py")
bench = importlib.util.module_from_spec(_spec)
sys.modules["bench_py"] = bench
_spec.loader.exec_module(bench)


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

    def test_every_tool_runs_a_task_with_a_reference(self):
        for name, kind, task, opts in bench.TOOLS:
            self.assertIn(task, bench.REFERENCE, name)

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

    def test_jvm_flags_are_one_policy(self):
        self.assertEqual(bench.jvm_flags("16g"), ["-Xmx16g", "-Xms16g", "-XX:+UseParallelGC"])
        self.assertEqual(bench.jvm_flags("4g", "-Xss8m -ea")[-2:], ["-Xss8m", "-ea"])


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
