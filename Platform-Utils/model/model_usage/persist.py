from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

MODEL_DIR = Path(__file__).resolve().parents[1]
LOCAL_DIR = MODEL_DIR / ".local"
MODEL_USAGE_FILE = LOCAL_DIR / "model-usage.json"
MODEL_USAGE_VERSION = 2


def _normalize_model_name(raw: str) -> str:
    text = str(raw or "").strip()
    if " #" in text:
        text = text.split("#", 1)[0].strip()
    elif text.startswith("#"):
        text = text.lstrip("#").strip()
    return text


def ensure_local_dirs() -> None:
    LOCAL_DIR.mkdir(parents=True, exist_ok=True)


def _parse_function_entries(raw: dict[str, Any]) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for name, entry in raw.items():
        if not isinstance(name, str) or not isinstance(entry, dict):
            continue
        result[name] = {
            "description": str(entry.get("description", "")),
            "updated_at": str(entry.get("updated_at", "")),
        }
    return result


def _parse_model_entries(raw: dict[str, Any]) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for name, entry in raw.items():
        if not isinstance(name, str) or not isinstance(entry, dict):
            continue
        result[name] = {
            "description": str(entry.get("description", "")),
            "updated_at": str(entry.get("updated_at", "")),
        }
    return result


def _migrate_v1_to_v2(
    v1_by_model: dict[str, dict[str, str]],
    migration_rows: list[dict[str, str]],
) -> dict[str, dict[str, str]]:
    """Copy v1 model_name descriptions to each linked function_key."""
    result: dict[str, dict[str, str]] = {}
    for row in migration_rows:
        function_key = str(row.get("function", "")).strip()
        if not function_key:
            continue
        model_name = _normalize_model_name(row.get("model_name", ""))
        prior = v1_by_model.get(model_name, {})
        result[function_key] = {
            "description": str(prior.get("description", "")),
            "updated_at": str(prior.get("updated_at", "")),
        }
    return result


def _read_usage_payload(file_path: Path) -> tuple[int, dict[str, dict[str, str]]]:
    if not file_path.exists():
        return MODEL_USAGE_VERSION, {}
    try:
        data = json.loads(file_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return MODEL_USAGE_VERSION, {}
    if not isinstance(data, dict):
        return MODEL_USAGE_VERSION, {}

    version = int(data.get("version", 1))
    functions = data.get("functions")
    if version >= 2 and isinstance(functions, dict):
        return MODEL_USAGE_VERSION, _parse_function_entries(functions)

    models = data.get("models")
    if isinstance(models, dict):
        return 1, _parse_model_entries(models)
    return MODEL_USAGE_VERSION, {}


def detect_v1_orphan_models(
    path: Path | None = None,
    migration_rows: list[dict[str, str]] | None = None,
) -> tuple[bool, list[str]]:
    """Return (is_v1, model_name keys in v1 JSON not referenced by current YAML rows)."""
    file_path = path or MODEL_USAGE_FILE
    version, entries = _read_usage_payload(file_path)
    if version >= MODEL_USAGE_VERSION:
        return False, []
    if not migration_rows:
        return True, sorted(entries.keys())

    yaml_models = {
        _normalize_model_name(row.get("model_name", ""))
        for row in migration_rows
        if _normalize_model_name(row.get("model_name", ""))
    }
    orphans = sorted(key for key in entries if key not in yaml_models)
    return True, orphans


def load_model_usage(
    path: Path | None = None,
    *,
    migration_rows: list[dict[str, str]] | None = None,
) -> dict[str, dict[str, str]]:
    """Return function_key -> {description, updated_at}."""
    file_path = path or MODEL_USAGE_FILE
    version, entries = _read_usage_payload(file_path)
    if version >= MODEL_USAGE_VERSION:
        return entries
    if not migration_rows:
        return entries

    migrated = _migrate_v1_to_v2(entries, migration_rows)
    if file_path.exists():
        backup = file_path.with_suffix(".json.bak")
        shutil.copy2(file_path, backup)
    descriptions = {key: val.get("description", "") for key, val in migrated.items()}
    save_model_usage(descriptions, path=file_path, existing=migrated)
    return load_model_usage(file_path)


def save_model_usage(
    descriptions: dict[str, str],
    *,
    path: Path | None = None,
    existing: dict[str, dict[str, str]] | None = None,
) -> Path:
    """Persist function_key descriptions; merge timestamps for untouched keys."""
    ensure_local_dirs()
    file_path = path or MODEL_USAGE_FILE
    now = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    if existing is not None:
        prior = existing
    else:
        version, prior = _read_usage_payload(file_path)
        if version < MODEL_USAGE_VERSION:
            raise ValueError(
                "model-usage.json 仍为 v1，请先通过 load_model_usage(migration_rows=...) 迁移"
            )
    functions: dict[str, Any] = {}
    all_keys = set(prior.keys()) | set(descriptions.keys())
    for key in sorted(all_keys):
        if key in descriptions:
            functions[key] = {
                "description": descriptions[key],
                "updated_at": now,
            }
        elif key in prior:
            functions[key] = prior[key]
    payload = {
        "version": MODEL_USAGE_VERSION,
        "updated_at": now,
        "functions": functions,
    }
    file_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return file_path
