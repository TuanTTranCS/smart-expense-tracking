#!/usr/bin/env python
"""Hermes no-agent wrapper for the Smart Expense Excel processor."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

REPOSITORY = Path(r"D:\dev\repo\smart-expense-tracking")
ENTRY_POINT = REPOSITORY / "scripts" / "Invoke-HermesExcelUpdate.ps1"
OVERRIDE_PATH = Path(__file__).with_name("smart-expense-excel-update.override.json")
ALLOWED_OVERRIDE_KEYS = {
    "OneDriveRoot",
    "InboxRelativePath",
    "WorkbookRelativePath",
    "StatePath",
    "LogPath",
    "DryRun",
}
DEFAULT_LOG_PATH = Path(os.environ["LOCALAPPDATA"]) / "SmartExpenseTracking" / "Hermes" / "processor.jsonl"


def render_hermes_notification(result: dict) -> str:
    """Render every discovered handoff, including non-inserted outcomes."""
    counts = result.get("counts", {})
    count = lambda name: int(counts.get(name, 0) or 0)
    discovered = count("discovered")
    failed = count("failed")
    deferred = count("deferred")
    if discovered == 0 and failed == 0 and deferred == 0:
        return "[SILENT]"

    lines = [
        "## Smart Expense Update",
        "",
        (
            f"**Summary:** {discovered} received · {count('inserted')} added · "
            f"{count('exactDuplicate')} duplicate · {count('review')} needs review · "
            f"{count('updated')} updated · {count('invalidError')} invalid · {deferred} deferred · {failed} failed"
        ),
    ]
    orders = [
        item for item in result.get("items", [])
        if item.get("merchant") is not None
        and item.get("amount") is not None
        and item.get("receiptDate") is not None
    ]
    if orders:
        lines.extend(("", "### Order details"))
        for order in orders:
            currency = order.get("currency") or "CAD"
            status = str(order.get("outcome") or "unknown").replace("_", " ")
            source_device_name = order.get("sourceDeviceName") or "Unknown device"
            lines.append(
                f"- **{order['merchant']}** — {currency} {float(order['amount']):,.2f} "
                f"on {order['receiptDate']} "
                f"_(Device: {source_device_name} · Status: {status})_"
            )
    problems = [
        item for item in result.get("items", [])
        if item.get("outcome") in {"review", "error", "deferred", "failed"}
        or "structural_warning" in str(item.get("reasonCode", ""))
    ]
    if problems:
        lines.extend(("", "### Attention"))
        lines.extend(
            f"- {item.get('fileName') or 'Processor'}: {item.get('reasonCode') or 'unknown'}"
            for item in problems
        )
    return "\n".join(lines)


def latest_result_after(log_path: Path, previous_size: int) -> dict | None:
    """Recover the result persisted by this invocation if its stdout is lost."""
    try:
        with log_path.open("rb") as stream:
            stream.seek(previous_size)
            lines = stream.read().decode("utf-8").splitlines()
        return json.loads(lines[-1]) if lines else None
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def main() -> int:
    pwsh = shutil.which("pwsh") or r"C:\Program Files\PowerShell\7\pwsh.exe"
    command = [
        pwsh,
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        str(ENTRY_POINT),
        "-OutputFormat",
        "Json",
    ]
    log_path = DEFAULT_LOG_PATH

    if OVERRIDE_PATH.exists():
        override = json.loads(OVERRIDE_PATH.read_text(encoding="utf-8"))
        unknown = set(override) - ALLOWED_OVERRIDE_KEYS
        if unknown:
            raise ValueError(f"Unsupported override keys: {', '.join(sorted(unknown))}")
        if override.get("LogPath"):
            log_path = Path(override["LogPath"])
        for key, value in override.items():
            if key == "DryRun":
                if value:
                    command.append("-DryRun")
            elif value is not None:
                command.extend((f"-{key}", str(value)))

    previous_size = log_path.stat().st_size if log_path.exists() else 0
    completed = subprocess.run(command, cwd=REPOSITORY, text=True, capture_output=True, check=False)
    try:
        result = json.loads(completed.stdout)
    except json.JSONDecodeError:
        result = latest_result_after(log_path, previous_size)

    if result is not None:
        sys.stdout.write(render_hermes_notification(result))
    elif completed.stderr:
        sys.stdout.write(completed.stderr)
    elif completed.stdout:
        sys.stdout.write(completed.stdout)
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
