"""Exercise event input precedence, strict candidates and honest legacy evidence."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from select_pair import CLIENT, TEST_CLASS, TEST_METHOD, TEST_REPORT, TEST_SOURCE, capabilities, require_passed_report, select

FRONT = "a" * 40
BACK = "b" * 40
MERGE = "c" * 40
EXECUTOR = "d" * 40
OTHER = "e" * 40


def event(body="", own=BACK):
    return {"pull_request": {"body": body, "head": {"sha": own}}}


class PairSelectionTest(unittest.TestCase):
    def test_backend_pr_uses_actual_head_and_default_frontend(self):
        result = select(event(), "pull_request", MERGE)
        self.assertEqual((result["frontendRef"], result["backendRef"]), ("main", BACK))
        self.assertFalse(result["requireExternalHandover"])

    def test_backend_marker_selects_exact_other_side_and_cannot_disable_external(self):
        result = select(event(f"Paired-Frontend-SHA: {FRONT}"), "pull_request", MERGE, require_external=False)
        self.assertEqual((result["frontendRef"], result["backendRef"]), (FRONT, BACK))
        self.assertTrue(result["requireExternalHandover"])

    def test_input_priority_does_not_hide_bad_or_duplicate_pr_markers(self):
        invalid = ("main", "ABCDEF" * 6 + "ABCD", "abc", "$(touch /tmp/pair-injection)", FRONT + " extra")
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ValueError):
                select(event(f"Paired-Frontend-SHA: {value}"), "pull_request", MERGE, frontend_input=OTHER)
        for body in (f"Paired-Frontend-SHA: {FRONT}\nPaired-Frontend-SHA: {FRONT}",
                     f"Paired-Frontend-SHA {FRONT}", f"<!-- Paired-Frontend-SHA: {FRONT} -->",
                     f"paired-frontend-sha: {FRONT}",
                     f"Paired-Frontend-SHA: {FRONT}\npaired-frontend-sha: {FRONT}"):
            with self.subTest(body=body), self.assertRaises(ValueError):
                select(event(body), "pull_request", MERGE, frontend_input=OTHER)

    def test_explicit_input_wins_a_valid_marker_but_keeps_its_required_gate(self):
        result = select(event(f"Paired-Frontend-SHA: {FRONT}"), "pull_request", MERGE, frontend_input=OTHER)
        self.assertEqual(result["frontendRef"], OTHER)
        self.assertEqual(result["source"], "explicit_inputs")
        self.assertTrue(result["markerPresent"])
        self.assertTrue(result["requireExternalHandover"])

    def test_pr_cannot_replace_its_own_backend_head(self):
        with self.assertRaisesRegex(ValueError, "actual backend PR head"):
            select(event(), "pull_request", MERGE, backend_input=OTHER)

    def test_dispatch_exact_inputs_are_strict_but_branch_defaults_are_legacy(self):
        self.assertTrue(select({}, "workflow_dispatch", BACK, frontend_input=FRONT, require_external=False)["requireExternalHandover"])
        self.assertTrue(select({}, "workflow_dispatch", BACK, backend_input=BACK, require_external=False)["requireExternalHandover"])
        result = select({}, "workflow_dispatch", BACK, frontend_input="main", backend_input="backend-main")
        self.assertFalse(result["requireExternalHandover"])
        self.assertTrue(select({}, "workflow_dispatch", BACK, require_external="true")["requireExternalHandover"])

    def test_non_pr_event_never_reads_pr_description(self):
        result = select(event("Paired-Frontend-SHA: invalid"), "push", BACK)
        self.assertEqual(result["frontendRef"], "main")
        self.assertEqual(result["backendRef"], BACK)

    def test_frontend_call_default_does_not_treat_its_own_exact_sha_as_a_new_chain(self):
        result = select(event(own=FRONT), "pull_request", MERGE, FRONT, "backend-main", False, EXECUTOR)
        self.assertEqual((result["frontendRef"], result["backendRef"]), (FRONT, "backend-main"))
        self.assertFalse(result["requireExternalHandover"])

    def test_frontend_call_validates_its_marker_before_explicit_inputs(self):
        body = f"Paired-Backend-SHA: {BACK}"
        result = select(event(body, FRONT), "pull_request", MERGE, FRONT, OTHER, False, EXECUTOR)
        self.assertEqual(result["backendRef"], OTHER)
        self.assertTrue(result["requireExternalHandover"])
        for invalid in ("Paired-Backend-SHA: main", body + "\n" + body,
                        f"paired-backend-sha: {BACK}", body + f"\npaired-backend-sha: {BACK}"):
            with self.subTest(body=invalid), self.assertRaises(ValueError):
                select(event(invalid, FRONT), "pull_request", MERGE, FRONT, OTHER, False, EXECUTOR)

    def test_frontend_call_pinned_backend_cannot_disable_external(self):
        result = select({}, "workflow_dispatch", FRONT, FRONT, BACK, False, EXECUTOR)
        self.assertTrue(result["requireExternalHandover"])

    def test_frontend_call_cannot_replace_actual_pr_head(self):
        with self.assertRaisesRegex(ValueError, "actual caller PR head"):
            select(event(own=FRONT), "pull_request", MERGE, OTHER, BACK, False, EXECUTOR)

    def test_non_sha_executor_and_non_boolean_gate_are_rejected(self):
        for executor in ("main", "deadbeef", EXECUTOR + "\nX=1"):
            with self.subTest(executor=executor), self.assertRaises(ValueError):
                select({}, "push", FRONT, FRONT, BACK, False, executor)
        with self.assertRaises(ValueError):
            select({}, "push", BACK, require_external="yes")


class ExternalCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.backend, self.frontend = self.root / "backend", self.root / "frontend"
        self.backend.mkdir()
        self.frontend.mkdir()

    def files(self, backend=True, frontend=True):
        if backend:
            source = self.backend / TEST_SOURCE
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(f"@Test void {TEST_METHOD}() throws Exception {{}}", encoding="utf-8")
        if frontend:
            client = self.frontend / CLIENT
            client.parent.mkdir(parents=True, exist_ok=True)
            client.write_text("require('../build/external-handover/api.js');", encoding="utf-8")

    def report(self, cases):
        target = self.backend / TEST_REPORT
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("<testsuite>" + cases + "</testsuite>", encoding="utf-8")
        return target

    def case(self, child="", name=TEST_METHOD):
        return f'<testcase classname="{TEST_CLASS}" name="{name}">{child}</testcase>'

    def test_legacy_missing_capabilities_have_explicit_not_run_evidence(self):
        result = capabilities(self.backend, self.frontend, False)
        self.assertEqual(result["status"], "not_run_legacy_pair")
        self.assertFalse(result["backendHasTest"])
        self.assertFalse(result["frontendHasDriver"])
        self.files(backend=False)
        self.assertEqual(capabilities(self.backend, self.frontend, False)["status"], "not_run_legacy_pair")

    def test_explicit_pair_missing_either_capability_fails_instead_of_going_green(self):
        with self.assertRaises(ValueError):
            capabilities(self.backend, self.frontend, True)
        self.files(backend=False)
        with self.assertRaises(ValueError):
            capabilities(self.backend, self.frontend, True)
        self.files()
        (self.frontend / CLIENT).write_text("")
        with self.assertRaises(ValueError):
            capabilities(self.backend, self.frontend, True)

    def test_default_pair_with_both_capabilities_always_runs_external(self):
        self.files()
        self.assertEqual(capabilities(self.backend, self.frontend, False)["status"], "pending")
        self.assertEqual(capabilities(self.backend, self.frontend, True)["status"], "pending")

    def test_real_report_requires_exact_test_not_maven_success_or_skipped_test(self):
        require_passed_report(self.report(self.case()))
        for cases in ("", self.case(name="unrelated"), self.case() + self.case(),
                      self.case("<skipped/>"), self.case("<failure/>"), self.case("<error/>")):
            with self.subTest(cases=cases), self.assertRaises(ValueError):
                require_passed_report(self.report(cases))
        (self.backend / TEST_REPORT).unlink()
        with self.assertRaises(ValueError):
            require_passed_report(self.backend / TEST_REPORT)

    def test_copied_executor_reads_event_safely_and_runs_against_backend_without_new_tools(self):
        executor = self.root / "executor"
        script = executor / "平台程序/script/release-rehearsal/select_pair.py"
        script.parent.mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name("select_pair.py"), script)
        subprocess.run(["git", "init", "-q", str(executor)], check=True)
        subprocess.run(["git", "-C", str(executor), "add", "."], check=True)
        subprocess.run(["git", "-C", str(executor), "-c", "user.email=test@example.invalid", "-c", "user.name=Pair test", "commit", "-qm", "executor"], check=True)
        executor_sha = subprocess.check_output(["git", "-C", str(executor), "rev-parse", "HEAD"], text=True).strip()
        scratch = self.root / "scratch"
        scratch.mkdir()
        event_file, output_file, summary = scratch / "event.json", scratch / "output", scratch / "summary"
        sentinel = scratch / "must-not-exist"
        event_file.write_text(json.dumps(event(f'Unrelated text $(touch "{sentinel}") and `touch {sentinel}`')), encoding="utf-8")
        env = dict(os.environ, RUNNER_TEMP=str(scratch), GITHUB_EVENT_PATH=str(event_file),
                   GITHUB_EVENT_NAME="pull_request", GITHUB_SHA=MERGE, EXPECTED_EXECUTOR_SHA=executor_sha,
                   GITHUB_OUTPUT=str(output_file), GITHUB_STEP_SUMMARY=str(summary),
                   INPUT_EXECUTOR_SHA="", INPUT_FRONTEND_SHA="", INPUT_BACKEND_SHA="", INPUT_REQUIRE_EXTERNAL="false",
                   FRONTEND_REV=FRONT, BACKEND_REV=BACK)
        def run(path, command):
            return subprocess.run([sys.executable, "-I", str(path), command], cwd=self.backend, env=env, capture_output=True, text=True)
        selected = run(script, "select")
        self.assertEqual(selected.returncode, 0, selected.stderr)
        self.assertFalse(sentinel.exists())
        selection = json.loads((scratch / "release-pair-selection.json").read_text())
        self.assertEqual(selection["executorCommit"], executor_sha)
        self.assertIn(f"backend_ref={BACK}\n", output_file.read_text())
        copied = scratch / "select_pair.py"
        shutil.copyfile(script, copied)
        shutil.rmtree(executor)
        self.assertFalse((self.backend / "平台程序/script/release-rehearsal/select_pair.py").exists())
        checked = run(copied, "capabilities")
        self.assertEqual(checked.returncode, 0, checked.stderr)
        delivery = scratch / "delivery"
        delivery.mkdir()
        (delivery / "acceptance.json").write_text(json.dumps({"frontendCommit": FRONT, "backendCommit": BACK}))
        finalized = run(copied, "summarize")
        self.assertEqual(finalized.returncode, 0, finalized.stderr)
        evidence = json.loads((delivery / "acceptance.json").read_text())
        self.assertEqual(evidence["externalHandover"]["status"], "not_run_legacy_pair")
        self.assertEqual(evidence["pairSelection"]["executorCommit"], executor_sha)
        self.assertIn("未验收新增八步外部整链", summary.read_text())
        selection["requireExternalHandover"] = True
        (scratch / "release-pair-selection.json").write_text(json.dumps(selection))
        self.assertNotEqual(run(copied, "summarize").returncode, 0)


if __name__ == "__main__":
    unittest.main()
