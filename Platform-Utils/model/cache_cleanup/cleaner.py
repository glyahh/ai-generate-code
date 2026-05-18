from __future__ import annotations

import shutil
from dataclasses import dataclass
from pathlib import Path

from cache_cleanup.scanner import CACHE_DIR, scan_cache_items
from quota.engine import DEFAULT_BROWSER_PROFILE


@dataclass
class CleanupResult:
    item_id: str
    label: str
    path: Path
    success: bool
    message: str
    freed_bytes: int = 0


def _delete_path(path: Path, *, is_dir: bool, size_bytes: int) -> CleanupResult:
    label = path.name
    try:
        if not path.exists():
            return CleanupResult("", label, path, True, "路径不存在，已跳过", 0)
        if is_dir:
            shutil.rmtree(path)
        else:
            path.unlink()
        return CleanupResult("", label, path, True, "已删除", size_bytes)
    except OSError as e:
        return CleanupResult("", label, path, False, str(e), 0)


def clear_cache_entries(item_ids: list[str]) -> list[CleanupResult]:
    """Delete selected cache items by id. Returns per-item results."""
    known = {item.item_id: item for item in scan_cache_items()}
    results: list[CleanupResult] = []

    for item_id in item_ids:
        item = known.get(item_id)
        if item is None:
            continue

        if item_id == "cache_misc":
            freed = item.size_bytes
            try:
                if CACHE_DIR.exists():
                    for child in list(CACHE_DIR.iterdir()):
                        if child.resolve() == DEFAULT_BROWSER_PROFILE.resolve():
                            continue
                        if child.is_dir():
                            shutil.rmtree(child)
                        else:
                            child.unlink()
                results.append(
                    CleanupResult(
                        item_id=item_id,
                        label=item.label,
                        path=CACHE_DIR,
                        success=True,
                        message="已清理 .cache 其它文件",
                        freed_bytes=freed,
                    )
                )
            except OSError as e:
                results.append(
                    CleanupResult(
                        item_id=item_id,
                        label=item.label,
                        path=CACHE_DIR,
                        success=False,
                        message=str(e),
                        freed_bytes=0,
                    )
                )
            continue

        raw = _delete_path(item.path, is_dir=item.is_dir, size_bytes=item.size_bytes)
        results.append(
            CleanupResult(
                item_id=item_id,
                label=item.label,
                path=item.path,
                success=raw.success,
                message=raw.message,
                freed_bytes=item.size_bytes if raw.success else 0,
            )
        )

    return results
