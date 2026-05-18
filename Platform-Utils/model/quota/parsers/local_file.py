from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


def extract_text_from_local_file(file_path: str) -> str:
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"文件不存在: {file_path}")

    suffix = path.suffix.lower()
    raw = path.read_text(encoding="utf-8", errors="ignore")

    if suffix == ".har":
        return _text_from_har(raw)
    if suffix == ".json":
        return _text_from_json(raw)
    return raw


def _text_from_json(raw: str) -> str:
    try:
        data: Any = json.loads(raw)
    except json.JSONDecodeError:
        return raw
    return json.dumps(data, ensure_ascii=False)


def _text_from_har(raw: str) -> str:
    try:
        har: Any = json.loads(raw)
    except json.JSONDecodeError:
        return raw

    chunks: list[str] = []
    entries = []
    if isinstance(har, dict):
        log = har.get("log")
        if isinstance(log, dict) and isinstance(log.get("entries"), list):
            entries = log["entries"]

    for entry in entries:
        if not isinstance(entry, dict):
            continue
        content = entry.get("response", {})
        if not isinstance(content, dict):
            continue
        c = content.get("content", {})
        if not isinstance(c, dict):
            continue
        text = c.get("text", "")
        mime = str(c.get("mimeType", ""))
        if not text:
            continue
        if "json" in mime or "text" in mime or "html" in mime:
            chunks.append(str(text))
    if chunks:
        return "\n".join(chunks)
    return raw


def try_extract_quota_from_json_blob(raw: str, model_name: str) -> str:
    """Try structured fields in console API JSON responses."""
    try:
        data: Any = json.loads(raw)
    except json.JSONDecodeError:
        return ""

    candidates = _walk_for_model_quota(data, model_name.lower())
    if candidates:
        return candidates[0]
    return ""


def _walk_for_model_quota(node: Any, model_lower: str) -> list[str]:
    found: list[str] = []
    if isinstance(node, dict):
        model_keys = ("model", "modelName", "model_name", "modelCode", "modelId", "name")
        remain_keys = (
            "remain",
            "remaining",
            "remainQuota",
            "freeQuota",
            "balance",
            "left",
            "surplus",
        )
        total_keys = ("total", "totalQuota", "quota", "limit")

        model_val = ""
        for mk in model_keys:
            if mk in node and isinstance(node[mk], str):
                model_val = node[mk].lower()
                break

        if model_val and (model_lower in model_val or model_val in model_lower):
            remain = _first_number(node, remain_keys)
            total = _first_number(node, total_keys)
            if remain is not None and total is not None:
                found.append(f"剩 {remain} / 共 {total}")
            elif remain is not None:
                found.append(str(remain))

        for v in node.values():
            found.extend(_walk_for_model_quota(v, model_lower))
    elif isinstance(node, list):
        for item in node:
            found.extend(_walk_for_model_quota(item, model_lower))
    return found


def _first_number(node: dict[str, Any], keys: tuple[str, ...]) -> str | None:
    for key in keys:
        if key not in node:
            continue
        val = node[key]
        if isinstance(val, (int, float)):
            return _format_num(val)
        if isinstance(val, str) and re.search(r"\d", val):
            return val.strip()
    return None


def _format_num(val: int | float) -> str:
    if isinstance(val, float) and val.is_integer():
        return f"{int(val):,}"
    if isinstance(val, int):
        return f"{val:,}"
    return str(val)
