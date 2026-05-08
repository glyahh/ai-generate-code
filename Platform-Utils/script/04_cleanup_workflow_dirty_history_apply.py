"""
IDE entry (apply)
-----------------
Runs `04_cleanup_workflow_dirty_history.py --apply`.
"""

from __future__ import annotations

import runpy
import sys
from pathlib import Path


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    target = project_root / "script" / "04_cleanup_workflow_dirty_history.py"
    sys.argv = [str(target), "--apply"]
    runpy.run_path(str(target), run_name="__main__")


if __name__ == "__main__":
    main()

