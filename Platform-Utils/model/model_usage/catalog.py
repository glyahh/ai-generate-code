from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import main as core


@dataclass
class ModelUsageEntry:
    function_key: str
    model_name: str
    description: str
    source_file: str
    path: str
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
    saved = saved or {}

    catalog: list[ModelUsageEntry] = []
    seen: set[str] = set()

    for row in rows:
        function_key = str(row.get("function", "")).strip()
        if not function_key:
            continue
        seen.add(function_key)
        model_name = normalize_model_name(row.get("model_name", ""))
        desc = saved.get(function_key, {}).get("description", "")
        catalog.append(
            ModelUsageEntry(
                function_key=function_key,
                model_name=model_name,
                description=desc,
                source_file=str(row.get("source_file", "")),
                path=str(row.get("path", "")),
                in_yaml=True,
                orphan=False,
            )
        )

    for function_key in sorted(saved.keys()):
        if function_key in seen:
            continue
        catalog.append(
            ModelUsageEntry(
                function_key=function_key,
                model_name="",
                description=saved[function_key].get("description", ""),
                source_file="",
                path="",
                in_yaml=False,
                orphan=True,
            )
        )

    catalog.sort(key=lambda entry: (not entry.in_yaml, entry.function_key))
    return catalog


def catalog_to_descriptions(catalog: list[ModelUsageEntry]) -> dict[str, str]:
    return {entry.function_key: entry.description for entry in catalog}


def build_function_replace_context(
    rows: list[dict[str, str]],
    saved_usage: dict[str, dict[str, str]] | None = None,
) -> dict[str, dict[str, str]]:
    """function_key -> model_name, description, source_file, path."""
    saved_usage = saved_usage or {}
    result: dict[str, dict[str, str]] = {}
    for row in rows:
        function_key = str(row.get("function", "")).strip()
        if not function_key:
            continue
        model_name = normalize_model_name(row.get("model_name", ""))
        description = str(saved_usage.get(function_key, {}).get("description", ""))
        result[function_key] = {
            "model_name": model_name,
            "description": description,
            "source_file": str(row.get("source_file", "")),
            "path": str(row.get("path", "")),
        }
    return result
