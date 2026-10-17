"""Regression tests for the Hermes no-agent stdout wrapper."""

from __future__ import annotations

import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

WRAPPER_PATH = Path(__file__).with_name("smart-expense-excel-update.py")


def load_wrapper():
    spec = importlib.util.spec_from_file_location("smart_expense_excel_update", WRAPPER_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    with patch.dict(os.environ, {"LOCALAPPDATA": tempfile.gettempdir()}):
        spec.loader.exec_module(module)
    return module


class HermesNotificationTests(unittest.TestCase):
    def setUp(self):
        self.wrapper = load_wrapper()

    def test_updated_only_renders_count_and_expense(self):
        message = self.wrapper.render_hermes_notification({
            "counts": {"discovered": 1, "updated": 1},
            "items": [{"outcome": "updated", "merchant": "Corrected shop", "amount": 65,
                       "receiptDate": "2026-10-16", "currency": "CAD"}],
        })
        self.assertIn("1 received · 0 added · 0 duplicate · 0 needs review · 1 updated", message)
        self.assertIn("**Corrected shop** — CAD 65.00 on 2026-10-16", message)
        self.assertIn("Status: updated", message)

    def test_mixed_counts_include_corrections_and_existing_outcomes(self):
        message = self.wrapper.render_hermes_notification({"counts": {
            "discovered": 8, "inserted": 1, "updated": 2, "exactDuplicate": 1,
            "review": 1, "invalidError": 1, "deferred": 1, "failed": 1,
        }})
        self.assertIn("8 received · 1 added · 1 duplicate · 1 needs review · 2 updated · "
                      "1 invalid · 1 deferred · 1 failed", message)

    def test_legacy_counts_default_updated_to_zero(self):
        message = self.wrapper.render_hermes_notification({"counts": {
            "discovered": 1, "inserted": 1, "failed": None,
        }})
        self.assertIn("0 updated", message)
        self.assertIn("0 deferred · 0 failed", message)

    def test_empty_run_is_silent_but_failures_and_deferrals_are_reported(self):
        for result in ({}, {"counts": {}}, {"counts": {"discovered": 0, "failed": 0, "deferred": 0}}):
            with self.subTest(result=result):
                self.assertEqual("[SILENT]", self.wrapper.render_hermes_notification(result))
        for count in ("failed", "deferred"):
            with self.subTest(count=count):
                self.assertNotEqual("[SILENT]", self.wrapper.render_hermes_notification({"counts": {count: 1}}))

    def test_conflict_without_order_details_shows_actionable_reason(self):
        message = self.wrapper.render_hermes_notification({
            "counts": {"discovered": 1, "review": 1},
            "items": [{"outcome": "review", "fileName": "expense_correction_operation.json",
                       "reasonCode": "processing_changed"}],
        })
        self.assertNotIn("### Order details", message)
        self.assertIn("### Attention\n- expense_correction_operation.json: processing_changed", message)

    def test_attention_matches_powershell_outcome_and_warning_categories(self):
        # Format-HermesOutput reports these four outcomes plus structural warnings,
        # using the filename or Processor when no filename exists.
        items = [{"outcome": outcome, "reasonCode": f"reason_{outcome}"}
                 for outcome in ("review", "error", "deferred", "failed")]
        items.extend((
            {"outcome": "inserted", "fileName": "warning.json", "reasonCode": "inserted_with_structural_warning"},
            {"outcome": "updated", "fileName": "updated.json", "reasonCode": "updated"},
        ))
        message = self.wrapper.render_hermes_notification({"counts": {"discovered": 6}, "items": items})
        for outcome in ("review", "error", "deferred", "failed"):
            self.assertIn(f"- Processor: reason_{outcome}", message)
        self.assertIn("- warning.json: inserted_with_structural_warning", message)
        self.assertNotIn("- updated.json:", message)

    def test_logged_handled_records_render_when_child_stdout_is_empty(self):
        wrapper = load_wrapper()
        result = {
            "outcome": "handled",
            "counts": {
                "discovered": 2,
                "inserted": 1,
                "exactDuplicate": 0,
                "review": 1,
                "invalidError": 0,
                "deferred": 0,
                "failed": 0,
            },
            "items": [
                {
                    "outcome": "inserted",
                    "merchant": "COSTCO WHOLESALE",
                    "amount": 128.27,
                    "receiptDate": "2026-09-19",
                    "currency": "CAD",
                    "sourceDeviceName": "Hugo's Pixel",
                },
                {
                    "outcome": "review",
                    "merchant": "T&T Supermarket",
                    "amount": 61.94,
                    "receiptDate": "2026-09-19",
                    "currency": "CAD",
                    "sourceDeviceName": "Hugo's Galaxy Tab",
                },
            ],
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            log_path = Path(temporary_directory) / "processor.jsonl"
            log_path.write_text(json.dumps(result) + "\n", encoding="utf-8")
            message = wrapper.render_hermes_notification(result)

        self.assertIn("**Summary:** 2 received · 1 added · 0 duplicate · 1 needs review", message)
        self.assertIn(
            "**COSTCO WHOLESALE** — CAD 128.27 on 2026-09-19 "
            "_(Device: Hugo's Pixel · Status: inserted)_",
            message,
        )
        self.assertIn(
            "**T&T Supermarket** — CAD 61.94 on 2026-09-19 "
            "_(Device: Hugo's Galaxy Tab · Status: review)_",
            message,
        )


class HermesWrapperInvocationTests(unittest.TestCase):
    def setUp(self):
        self.wrapper = load_wrapper()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.wrapper.REPOSITORY = self.root
        self.wrapper.ENTRY_POINT = self.root / "processor entry.ps1"
        self.wrapper.OVERRIDE_PATH = self.root / "override.json"
        self.wrapper.DEFAULT_LOG_PATH = self.root / "processor.jsonl"

    def invoke(self, *, stdout="", stderr="", returncode=0, during_child=None):
        output = io.StringIO()
        def child(*args, **kwargs):
            if during_child:
                during_child()
            return SimpleNamespace(stdout=stdout, stderr=stderr, returncode=returncode)
        with patch.object(self.wrapper.shutil, "which", return_value="mock-pwsh"), \
                patch.object(self.wrapper.subprocess, "run", side_effect=child) as run, \
                patch.object(self.wrapper.sys, "stdout", output):
            code = self.wrapper.main()
        return code, output.getvalue(), run

    def test_valid_stdout_json_is_used_without_log_fallback(self):
        result = {"counts": {"discovered": 1, "updated": 1}}
        self.wrapper.DEFAULT_LOG_PATH.write_text("not valid JSON\n", encoding="utf-8")
        with patch.object(self.wrapper, "latest_result_after", side_effect=AssertionError("stdout should win")):
            code, message, run = self.invoke(stdout=json.dumps(result))
        self.assertEqual(0, code)
        self.assertIn("1 updated", message)
        self.assertEqual([
            "mock-pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
            str(self.wrapper.ENTRY_POINT), "-OutputFormat", "Json",
        ], run.call_args.args[0])
        self.assertEqual(dict(cwd=self.root, text=True, capture_output=True, check=False), run.call_args.kwargs)

    def test_real_correction_result_includes_details_through_stdout_and_jsonl(self):
        result_path = self.root / "correction-result.json"
        completed = subprocess.run([
            "pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
            str(WRAPPER_PATH.with_name("test-corrections.ps1")),
            "-NotificationResultPath", str(result_path),
        ], capture_output=True, text=True, check=False, timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        result_json = result_path.read_text(encoding="utf-8")
        for use_log in (False, True):
            with self.subTest(use_log=use_log):
                def append_result():
                    with self.wrapper.DEFAULT_LOG_PATH.open("a", encoding="utf-8") as log:
                        log.write(json.dumps(json.loads(result_json)) + "\n")
                code, message, _ = self.invoke(
                    stdout="" if use_log else result_json,
                    during_child=append_result if use_log else None,
                )
                self.assertEqual(0, code)
                self.assertIn("1 updated", message)
                self.assertIn("### Order details", message)
                self.assertIn("**Corrected shop** — CAD 30.00 on 2026-11-02", message)
                self.assertIn("Device: Test · Status: updated", message)

    def test_repeated_deferred_consumer_result_is_silent_for_stdout_and_jsonl(self):
        result_path = self.root / "quiet-deferred-result.json"
        completed = subprocess.run([
            "pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
            str(WRAPPER_PATH.with_name("test-corrections.ps1")),
            "-QuietDeferredResultPath", str(result_path),
        ], capture_output=True, text=True, check=False, timeout=60)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        result_json = result_path.read_text(encoding="utf-8")
        for use_log in (False, True):
            with self.subTest(use_log=use_log):
                def append_result():
                    with self.wrapper.DEFAULT_LOG_PATH.open("a", encoding="utf-8") as log:
                        log.write(json.dumps(json.loads(result_json)) + "\n")
                code, message, _ = self.invoke(
                    stdout="" if use_log else result_json,
                    during_child=append_result if use_log else None,
                )
                self.assertEqual((0, "[SILENT]"), (code, message))

    def test_empty_or_invalid_stdout_uses_only_newly_appended_jsonl(self):
        for stdout in ("", "non-JSON child progress"):
            with self.subTest(stdout=stdout):
                self.wrapper.DEFAULT_LOG_PATH.write_text(json.dumps({"counts": {"failed": 99}}) + "\n", encoding="utf-8")
                def append_result():
                    with self.wrapper.DEFAULT_LOG_PATH.open("a", encoding="utf-8") as log:
                        log.write(json.dumps({"counts": {"discovered": 2, "updated": 2}}) + "\n")
                code, message, _ = self.invoke(stdout=stdout, during_child=append_result)
                self.assertEqual(0, code)
                self.assertIn("2 updated", message)
                self.assertNotIn("99 failed", message)

    def test_old_jsonl_is_not_replayed_when_child_appends_nothing(self):
        self.wrapper.DEFAULT_LOG_PATH.write_text(json.dumps({"counts": {"failed": 99}}) + "\n", encoding="utf-8")
        code, message, _ = self.invoke(stderr="child failed before result", returncode=7)
        self.assertEqual(7, code)
        self.assertEqual("child failed before result", message)

    def test_missing_log_and_invalid_appended_log_preserve_child_diagnostics(self):
        for append in (False, True):
            with self.subTest(append=append):
                def corrupt_log():
                    self.wrapper.DEFAULT_LOG_PATH.write_text("malformed json\n", encoding="utf-8")
                code, message, _ = self.invoke(stdout="raw stdout", stderr="actionable stderr", returncode=3,
                                               during_child=corrupt_log if append else None)
                self.assertEqual(3, code)
                self.assertEqual("actionable stderr", message)

    def test_nonzero_child_exit_is_preserved_when_valid_result_is_rendered(self):
        code, message, _ = self.invoke(stdout=json.dumps({"counts": {"discovered": 1, "failed": 1}}), returncode=12)
        self.assertEqual(12, code)
        self.assertIn("1 failed", message)

    def test_empty_result_prints_silent_and_raw_stdout_is_retained_without_result(self):
        code, message, _ = self.invoke(stdout=json.dumps({"counts": {}}))
        self.assertEqual((0, "[SILENT]"), (code, message))
        code, message, _ = self.invoke(stdout="raw diagnostics")
        self.assertEqual((0, "raw diagnostics"), (code, message))

    def test_override_forwards_allowlisted_values_as_literal_arguments_and_uses_its_log(self):
        override_log = self.root / "custom log.jsonl"
        override = {
            "OneDriveRoot": str(self.root / "drive with spaces; literal"),
            "InboxRelativePath": "logs", "WorkbookRelativePath": "book.xlsx",
            "StatePath": str(self.root / "state.json"), "LogPath": str(override_log), "DryRun": True,
        }
        self.wrapper.OVERRIDE_PATH.write_text(json.dumps(override), encoding="utf-8")
        def append_result():
            override_log.write_text(json.dumps({"counts": {"discovered": 1, "updated": 1}}) + "\n", encoding="utf-8")
        code, message, run = self.invoke(during_child=append_result)
        self.assertEqual(0, code)
        self.assertIn("1 updated", message)
        command = run.call_args.args[0]
        for key, value in override.items():
            self.assertIn(f"-{key}", command)
            if key != "DryRun":
                self.assertEqual(value, command[command.index(f"-{key}") + 1])
        self.assertNotIn("shell", run.call_args.kwargs)

    def test_false_dry_run_and_null_values_are_not_forwarded(self):
        self.wrapper.OVERRIDE_PATH.write_text(json.dumps({"DryRun": False, "StatePath": None}), encoding="utf-8")
        _, _, run = self.invoke(stdout="{}")
        self.assertNotIn("-DryRun", run.call_args.args[0])
        self.assertNotIn("-StatePath", run.call_args.args[0])

    def test_unknown_override_key_is_rejected_before_child_execution(self):
        self.wrapper.OVERRIDE_PATH.write_text(json.dumps({"Command": "unapproved"}), encoding="utf-8")
        with patch.object(self.wrapper.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "Unsupported override keys: Command"):
                self.wrapper.main()
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
