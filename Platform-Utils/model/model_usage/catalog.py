from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import main as core


@dataclass
class ModelUsageEntry:
    model_name: str
    functions: list[str]
    description: str
    in_yaml: bool
    orphan: bool

    @property
    def is_placeholder(self) -> bool:
        return is_placeholder_model(self.model_name)


def normalize_model_name(raw: str) -> str:
    text = str(raw or "").strip()
    if " #" in text:
        text = text.split("#", 1)[0].strip()
    elif text.startswith("#"):
        text = text.lstrip("#").strip()
    return text


def is_placeholder_model(model_name: str) -> bool:
    return model_name.strip().startswith("${")


def _aggregate_mappings(rows: list[dict[str, str]]) -> dict[str, list[str]]:
    by_model: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        name = normalize_model_name(row.get("model_name", ""))
        if not name:
            continue
        function_key = str(row.get("function", "")).strip()
        if function_key:
            by_model[name].add(function_key)
    return {k: sorted(v) for k, v in by_model.items()}


def build_model_catalog(
    app_yml: str,
    local_yml: str,
    saved: dict[str, dict[str, str]] | None = None,
    *,
    list_models_data_fn=None,
) -> list[ModelUsageEntry]:
    if list_models_data_fn is None:
        import main as core

        list_models_data_fn = core.list_models_data

    rows = list_models_data_fn(app_yml, local_yml)
    yaml_models = _aggregate_mappings(rows)
    saved = saved or {}

    catalog: list[ModelUsageEntry] = []
    seen: set[str] = set()

    for model_name in sorted(yaml_models.keys()):
        seen.add(model_name)
        desc = saved.get(model_name, {}).get("description", "")
        catalog.append(
            ModelUsageEntry(
                model_name=model_name,
                functions=yaml_models[model_name],
                description=desc,
                in_yaml=True,
                orphan=False,
            )
        )

    for model_name in sorted(saved.keys()):
        if model_name in seen:
            continue
        catalog.append(
            ModelUsageEntry(
                model_name=model_name,
                functions=[],
                description=saved[model_name].get("description", ""),
                in_yaml=False,
                orphan=True,
            )
        )

    return catalog


def catalog_to_descriptions(catalog: list[ModelUsageEntry]) -> dict[str, str]:
    return {entry.model_name: entry.description for entry in catalog}


def build_function_replace_context(
    rows: list[dict[str, str]],
    saved_usage: dict[str, dict[str, str]] | None = None,
) -> dict[str, dict[str, str]]:
    """function_key -> model_name, description, source_file, path."""
    saved_usage = saved_usage or {}
    descriptions = {
        name: str(entry.get("description", ""))
        for name, entry in saved_usage.items()
    }
    result: dict[str, dict[str, str]] = {}
    for row in rows:
        function_key = str(row.get("function", "")).strip()
        if not function_key:
            continue
        model_name = normalize_model_name(row.get("model_name", ""))
        result[function_key] = {
            "model_name": model_name,
            "description": descriptions.get(model_name, ""),
            "source_file": str(row.get("source_file", "")),
            "path": str(row.get("path", "")),
        }
    return result
