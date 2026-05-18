from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

MODEL_DIR = Path(__file__).resolve().parents[1]
LOCAL_DIR = MODEL_DIR / ".local"
MODEL_USAGE_FILE = LOCAL_DIR / "model-usage.json"


def ensure_local_dirs() -> None:
    LOCAL_DIR.mkdir(parents=True, exist_ok=True)


def load_model_usage(path: Path | None = None) -> dict[str, dict[str, str]]:
    """Return model_name -> {description, updated_at}."""
    file_path = path or MODEL_USAGE_FILE
    if not file_path.exists():
        return {}
    try:
        data = json.loads(file_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}
    if not isinstance(data, dict):
        return {}
    models = data.get("models")
    if not isinstance(models, dict):
        return {}
    result: dict[str, dict[str, str]] = {}
    for name, entry in models.items():
        if not isinstance(name, str) or not isinstance(entry, dict):
            continue
        description = str(entry.get("description", ""))
        updated_at = str(entry.get("updated_at", ""))
        result[name] = {"description": description, "updated_at": updated_at}
    return result


def save_model_usage(
    descriptions: dict[str, str],
    *,
    path: Path | None = None,
    existing: dict[str, dict[str, str]] | None = None,
) -> Path:
    """Persist all model descriptions; merge timestamps for untouched keys."""
    ensure_local_dirs()
    file_path = path or MODEL_USAGE_FILE
    now = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    prior = existing if existing is not None else load_model_usage(file_path)
    models: dict[str, Any] = {}
    all_names = set(prior.keys()) | set(descriptions.keys())
    for name in sorted(all_names):
        if name in descriptions:
            models[name] = {
                "description": descriptions[name],
                "updated_at": now,
            }
        elif name in prior:
            models[name] = prior[name]
    payload = {
        "version": 1,
        "updated_at": now,
        "models": models,
    }
    file_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return file_path
