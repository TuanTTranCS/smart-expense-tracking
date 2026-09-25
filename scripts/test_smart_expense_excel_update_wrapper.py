"""Regression tests for the Hermes no-agent stdout wrapper."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

WRAPPER_PATH = Path(r"C:\Users\tuant\AppData\Local\hermes\scripts\smart-expense-excel-update.py")


def load_wrapper():
    spec = importlib.util.spec_from_file_location("smart_expense_excel_update", WRAPPER_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HermesNotificationTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
