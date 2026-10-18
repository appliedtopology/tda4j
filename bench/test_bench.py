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
sys.path.insert(0, str(Path(__file__).resolve().parent))
import sweep_alpha  # noqa: E402  (imports bench.py by name: a second copy of the module, which is fine here)


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

    def test_every_tool_runs_a_task_with_a_reference_except_cech_which_only_the_alpha_sweep_runs(self):
        for name, kind, task, opts in bench.TOOLS:
            self.assertTrue(task in bench.REFERENCE or task == "cech", name)
        self.assertNotIn("cech", {c.task for c in bench.CASES}, "no case of the main run uses the Cech task")

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
