from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from model_usage.persist import MODEL_USAGE_FILE
from quota.engine import DEFAULT_BROWSER_PROFILE
from quota.persist import LATEST_SNAPSHOT, RUNTIME_PLATFORMS_YML

MODEL_DIR = Path(__file__).resolve().parents[1]
CACHE_DIR = MODEL_DIR / ".cache"


@dataclass
class CacheItem:
    item_id: str
    label: str
    path: Path
    description: str
    exists: bool
    size_bytes: int
    is_dir: bool
    default_selected: bool = True


PROTECTED_ITEMS: tuple[tuple[str, Path, str], ...] = (
    ("model_usage", MODEL_USAGE_FILE, "模型功能说明"),
    ("quota_env", MODEL_DIR / "quota.local.env", "quota.local.env"),
)


def format_size(num_bytes: int) -> str:
    if num_bytes < 1024:
        return f"{num_bytes} B"
    if num_bytes < 1024 * 1024:
        return f"{num_bytes / 1024:.1f} KB"
    if num_bytes < 1024 * 1024 * 1024:
        return f"{num_bytes / (1024 * 1024):.1f} MB"
    return f"{num_bytes / (1024 * 1024 * 1024):.2f} GB"


def _path_size(path: Path) -> int:
    if not path.exists():
        return 0
    if path.is_file():
        try:
            return path.stat().st_size
        except OSError:
            return 0
    total = 0
    try:
        for root, _dirs, files in os.walk(path):
            for name in files:
                try:
                    total += (Path(root) / name).stat().st_size
                except OSError:
                    continue
    except OSError:
        return 0
    return total


def _cache_item(
    item_id: str,
    label: str,
    path: Path,
    description: str,
    *,
    default_selected: bool = True,
) -> CacheItem:
    exists = path.exists()
    return CacheItem(
        item_id=item_id,
        label=label,
        path=path,
        description=description,
        exists=exists,
        size_bytes=_path_size(path) if exists else 0,
        is_dir=exists and path.is_dir(),
        default_selected=default_selected,
    )


def scan_cache_items() -> list[CacheItem]:
    """Return cleanable cache entries (newest / largest first within groups)."""
    items = [
        _cache_item(
            "quota_snapshot",
            "额度查询快照",
            LATEST_SNAPSHOT,
            "上次额度查询结果 latest.json",
        ),
        _cache_item(
            "quota_runtime",
            "Platforms 运行时配置",
            RUNTIME_PLATFORMS_YML,
            "platforms.runtime.yml，下次 Config 查询时自动重建",
        ),
        _cache_item(
            "browser_profile",
            "Playwright 浏览器会话",
            DEFAULT_BROWSER_PROFILE,
            "Playwright 浏览器用户数据目录",
        ),
        _cache_item(
            "cache_misc",
            ".cache 其它文件",
            CACHE_DIR,
            "除 browser_profile 外 .cache 下的其它文件",
            default_selected=False,
        ),
    ]

    misc_size = 0
    misc_exists = False
    if CACHE_DIR.exists():
        for child in CACHE_DIR.iterdir():
            if child.resolve() == DEFAULT_BROWSER_PROFILE.resolve():
                continue
            misc_exists = True
            misc_size += _path_size(child)
    misc = items[-1]
    misc.exists = misc_exists
    misc.size_bytes = misc_size

    present = [i for i in items if i.exists and i.size_bytes > 0]
    present.sort(key=lambda x: x.size_bytes, reverse=True)
    return present
