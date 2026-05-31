from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

from model_usage.persist import (
    MODEL_USAGE_FILE,
    MODEL_USAGE_VERSION,
    _read_usage_payload,
    load_model_usage,
    save_model_usage,
)

if TYPE_CHECKING:
    import main as core


@dataclass
class ModelUsageMigrateResult:
    migrated_pairs: list[tuple[str, str]] = field(default_factory=list)
    removed_functions: list[str] = field(default_factory=list)
    kept_orphan_functions: list[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return bool(self.migrated_pairs or self.removed_functions)

    @property
    def message(self) -> str:
        parts: list[str] = []
        if self.migrated_pairs:
            pairs = "、".join(f"{a}→{b}" for a, b in self.migrated_pairs)
            parts.append(f"已迁移功能说明：{pairs}")
        if self.removed_functions:
            parts.append(f"已移除本地记录：{'、'.join(self.removed_functions)}")
        if self.kept_orphan_functions:
            parts.append(
                f"仍保留未绑定 YAML 的说明：{'、'.join(self.kept_orphan_functions)}"
            )
        if not parts:
            return "model-usage.json 无需调整（说明已绑定配置位）。"
        return "；".join(parts) + "。"


def _yaml_function_keys_from_rows(rows: list[dict[str, str]]) -> set[str]:
    keys: set[str] = set()
    for row in rows:
        function_key = str(row.get("function", "")).strip()
        if function_key:
            keys.add(function_key)
    return keys


def migrate_model_usage_after_replace(
    changes: list[dict[str, str]],
    yaml_function_keys: set[str],
    *,
    path: Path | None = None,
) -> ModelUsageMigrateResult:
    """Descriptions are bound to function_key; model-name replace needs no migration."""
    _ = (changes, yaml_function_keys, path)
    return ModelUsageMigrateResult()


def prune_orphan_model_usage(
    yaml_function_keys: set[str],
    *,
    path: Path | None = None,
) -> list[str]:
    """Remove function usage entries that are not referenced in current YAML."""
    file_path = path or MODEL_USAGE_FILE
    version, _ = _read_usage_payload(file_path)
    if version < MODEL_USAGE_VERSION:
        return []

    working = load_model_usage(path)
    removed: list[str] = []
    for key in sorted(working.keys()):
        if key in yaml_function_keys:
            continue
        working.pop(key, None)
        removed.append(key)
    if removed:
        descriptions = {key: str(entry.get("description", "")) for key, entry in working.items()}
        save_model_usage(descriptions, path=path, existing=working)
    return removed


def migrate_model_usage_for_replace(
    changes: list[dict[str, str]],
    app_yml: str,
    local_yml: str,
    *,
    path: Path | None = None,
    list_models_data_fn=None,
) -> ModelUsageMigrateResult:
    if list_models_data_fn is None:
        import main as core

        list_models_data_fn = core.list_models_data

    rows = list_models_data_fn(app_yml, local_yml)
    yaml_keys = _yaml_function_keys_from_rows(rows)
    result = migrate_model_usage_after_replace(changes, yaml_keys, path=path)

    saved = load_model_usage(path)
    for key in sorted(saved.keys()):
        if key not in yaml_keys and key not in result.removed_functions:
            result.kept_orphan_functions.append(key)

    return result
