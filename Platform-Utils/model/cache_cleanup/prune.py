from __future__ import annotations

from dataclasses import dataclass

from model_usage.catalog import normalize_model_name
from quota.persist import LATEST_SNAPSHOT, load_quota_snapshot, save_quota_snapshot
from quota.sync_rows import sync_quota_rows_after_replace


@dataclass
class PruneQuotaResult:
    removed_count: int
    kept_count: int
    snapshot_deleted: bool
    affected_functions: list[str]
    affected_models: list[str]
    remaining_rows: list[dict[str, str]]

    @property
    def message(self) -> str:
        if self.removed_count == 0:
            return "额度快照已同步模型名；请重新执行额度查询以更新数值。"
        fn_part = "、".join(self.affected_functions) if self.affected_functions else ""
        target = fn_part or "相关配置位"
        return f"已更新额度快照中「{target}」共 {self.removed_count} 条（模型名已同步，额度需重新查询）。"


def affected_keys_from_replace_changes(
    changes: list[dict[str, str]],
) -> tuple[set[str], set[str]]:
    functions: set[str] = set()
    models: set[str] = set()
    for change in changes:
        function_key = str(change.get("function", "")).strip()
        if function_key:
            functions.add(function_key)
        for key in ("from", "to"):
            model_name = normalize_model_name(str(change.get(key, "")))
            if model_name:
                models.add(model_name)
    return functions, models


def prune_quota_cache_for_replace_changes(
    changes: list[dict[str, str]],
    *,
    in_memory_rows: list[dict[str, str]] | None = None,
) -> PruneQuotaResult:
    """Remove quota snapshot rows tied to replaced functions / model names only."""
    functions, models = affected_keys_from_replace_changes(changes)
    affected_functions = sorted(functions)
    affected_models = sorted(models)

    snapshot = load_quota_snapshot()
    file_rows: list[dict[str, str]] = []
    query_mode = "unknown"
    query_meta: dict = {}
    log_hints: list[str] = []
    had_snapshot_file = LATEST_SNAPSHOT.exists()
    if snapshot:
        raw_rows = snapshot.get("rows", [])
        if isinstance(raw_rows, list):
            file_rows = [r for r in raw_rows if isinstance(r, dict)]
        query_mode = str(snapshot.get("query_mode", "unknown"))
        meta = snapshot.get("query_meta")
        query_meta = meta if isinstance(meta, dict) else {}
        hints = snapshot.get("log_hints")
        log_hints = hints if isinstance(hints, list) else []

    # 以内存为准（更新），否则读磁盘快照
    if in_memory_rows is not None:
        source_rows = list(in_memory_rows)
    else:
        source_rows = file_rows

    synced_rows, updated_count = sync_quota_rows_after_replace(source_rows, changes)

    snapshot_deleted = False
    if had_snapshot_file or source_rows or synced_rows:
        if synced_rows:
            save_quota_snapshot(
                synced_rows,
                query_mode=query_mode,
                query_meta=query_meta,
                log_hints=log_hints,
            )
        elif had_snapshot_file:
            LATEST_SNAPSHOT.unlink()
            snapshot_deleted = True

    return PruneQuotaResult(
        removed_count=updated_count,
        kept_count=len(synced_rows),
        snapshot_deleted=snapshot_deleted,
        affected_functions=affected_functions,
        affected_models=affected_models,
        remaining_rows=synced_rows,
    )
