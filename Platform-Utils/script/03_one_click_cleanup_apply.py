"""
IDE entry (apply)
-----------------
Runs `03_one_click_cleanup.py --apply --include-deploy`.

WARNING: This will DELETE data in MySQL and local files under temp/.
"""

from __future__ import annotations

import runpy
import sys
from pathlib import Path


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    target = project_root / "script" / "03_one_click_cleanup.py"
    sys.argv = [str(target), "--apply", "--include-deploy"]
    runpy.run_path(str(target), run_name="__main__")


if __name__ == "__main__":
    main()

