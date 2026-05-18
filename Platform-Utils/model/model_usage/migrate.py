from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

from model_usage.catalog import normalize_model_name
from model_usage.persist import load_model_usage, save_model_usage

if TYPE_CHECKING:
    import main as core


@dataclass
class ModelUsageMigrateResult:
    migrated_pairs: list[tuple[str, str]] = field(default_factory=list)
    removed_models: list[str] = field(default_factory=list)
    kept_orphan_models: list[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return bool(self.migrated_pairs or self.removed_models)

    @property
    def message(self) -> str:
        parts: list[str] = []
        if self.migrated_pairs:
            pairs = "、".join(f"{a}→{b}" for a, b in self.migrated_pairs)
            parts.append(f"已迁移功能说明：{pairs}")
        if self.removed_models:
            parts.append(f"已移除本地记录：{'、'.join(self.removed_models)}")
        if self.kept_orphan_models:
            parts.append(
                f"仍保留未绑定 YAML 的说明：{'、'.join(self.kept_orphan_models)}"
            )
        if not parts:
            return "model-usage.json 无需调整。"
        return "；".join(parts) + "。"


def _yaml_model_names_from_rows(rows: list[dict[str, str]]) -> set[str]:
    names: set[str] = set()
    for row in rows:
        name = normalize_model_name(row.get("model_name", ""))
        if name:
            names.add(name)
    return names


def migrate_model_usage_after_replace(
    changes: list[dict[str, str]],
    yaml_model_names: set[str],
    *,
    path: Path | None = None,
) -> ModelUsageMigrateResult:
    """Move descriptions from replaced models to new names; drop stale local keys."""
    result = ModelUsageMigrateResult()
    if not changes:
        return result

    working = load_model_usage(path)
    if not working:
        working = {}

    now = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    from_models: set[str] = set()

    for change in changes:
        old_name = normalize_model_name(str(change.get("from", "")))
        new_name = normalize_model_name(str(change.get("to", "")))
        if not old_name or not new_name or old_name == new_name:
            continue
        from_models.add(old_name)

        old_desc = str(working.get(old_name, {}).get("description", "")).strip()
        if not old_desc:
            continue

        new_entry = dict(working.get(new_name, {}))
        new_desc = str(new_entry.get("description", "")).strip()
        if not new_desc:
            new_entry["description"] = old_desc
            new_entry["updated_at"] = now
            working[new_name] = new_entry
            if (old_name, new_name) not in result.migrated_pairs:
                result.migrated_pairs.append((old_name, new_name))

    for old_name in sorted(from_models):
        if old_name in yaml_model_names:
            continue
        if old_name not in working:
            continue
        working.pop(old_name, None)
        result.removed_models.append(old_name)

    if result.migrated_pairs or result.removed_models:
        descriptions = {
            name: str(entry.get("description", "")) for name, entry in working.items()
        }
        save_model_usage(descriptions, path=path, existing=working)

    return result


def prune_orphan_model_usage(
    yaml_model_names: set[str],
    *,
    path: Path | None = None,
) -> list[str]:
    """Remove model-usage entries that are not referenced in current YAML."""
    working = load_model_usage(path)
    removed: list[str] = []
    for name in sorted(working.keys()):
        if name in yaml_model_names:
            continue
        working.pop(name, None)
        removed.append(name)
    if removed:
        descriptions = {name: str(entry.get("description", "")) for name, entry in working.items()}
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
    yaml_names = _yaml_model_names_from_rows(rows)
    result = migrate_model_usage_after_replace(changes, yaml_names, path=path)

    # Orphans unrelated to this replace batch are left for manual cleanup.
    saved = load_model_usage(path)
    for name in sorted(saved.keys()):
        if name not in yaml_names and name not in result.removed_models:
            result.kept_orphan_models.append(name)

    return result
