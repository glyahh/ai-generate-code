from __future__ import annotations

from model_usage.catalog import normalize_model_name


def align_quota_rows_with_mappings(
    rows: list[dict[str, str]],
    mappings: list[dict[str, str]],
) -> list[dict[str, str]]:
    """Ensure each YAML 配置位有一条结果，且 model_name 与当前配置一致。"""
    by_function: dict[str, dict[str, str]] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        fn = str(row.get("function", "")).strip()
        if fn:
            by_function[fn] = dict(row)

    aligned: list[dict[str, str]] = []
    for mapping in mappings:
        fn = str(mapping.get("function", "")).strip()
        if not fn:
            continue
        model_name = normalize_model_name(str(mapping.get("model_name", "")))
        if fn in by_function:
            row = by_function[fn]
            row["function"] = fn
            row["model_name"] = model_name
            aligned.append(row)
        else:
            aligned.append(
                {
                    "function": fn,
                    "model_name": model_name,
                    "platform": "",
                    "quota": "",
                    "method": "",
                    "error": "",
                }
            )
    return aligned


def sync_quota_rows_after_replace(
    rows: list[dict[str, str]],
    changes: list[dict[str, str]],
) -> tuple[list[dict[str, str]], int]:
    """替换写入后更新快照中的模型名，清空已失效的额度字段。"""
    change_by_function = {
        str(c.get("function", "")).strip(): c
        for c in changes
        if str(c.get("function", "")).strip()
    }
    if not change_by_function:
        return list(rows), 0

    updated_count = 0
    result: list[dict[str, str]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        fn = str(row.get("function", "")).strip()
        if fn not in change_by_function:
            result.append(dict(row))
            continue
        change = change_by_function[fn]
        new_name = normalize_model_name(str(change.get("to", "")))
        old_name = normalize_model_name(str(row.get("model_name", "")))
        new_row = dict(row)
        if new_name:
            new_row["model_name"] = new_name
        if old_name != new_name or new_row.get("quota") or new_row.get("error"):
            new_row["quota"] = ""
            new_row["error"] = "模型已替换，请重新执行额度查询"
            updated_count += 1
        result.append(new_row)
    return result, updated_count
