from __future__ import annotations

from pathlib import Path
from typing import Any

from ruamel.yaml import YAML

_yaml = YAML()


def read_dashscope_api_key(app_yml: str, local_yml: str) -> str:
    for path_str in (local_yml, app_yml):
        path = Path(path_str)
        if not path.exists():
            continue
        with path.open("r", encoding="utf-8") as f:
            data: Any = _yaml.load(f) or {}
        if not isinstance(data, dict):
            continue
        key = _extract_api_key(data)
        if key:
            return key
    return ""


def _extract_api_key(data: dict[str, Any]) -> str:
    open_ai = data.get("langchain4j", {})
    if not isinstance(open_ai, dict):
        return ""
    open_ai = open_ai.get("open-ai", {})
    if not isinstance(open_ai, dict):
        return ""

    for block in open_ai.values():
        if not isinstance(block, dict):
            continue
        if "api-key" in block:
            val = str(block.get("api-key", "")).strip()
            if val and not val.startswith("${"):
                return val

    dashscope = data.get("dashscope", {})
    if isinstance(dashscope, dict):
        val = str(dashscope.get("api-key", "")).strip()
        if val and not val.startswith("${"):
            return val
    return ""
