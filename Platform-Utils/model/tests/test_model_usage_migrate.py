from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from model_usage.catalog import build_function_replace_context, build_model_catalog
from model_usage.migrate import migrate_model_usage_after_replace, prune_orphan_model_usage
from model_usage.persist import (
    detect_v1_orphan_models,
    load_model_usage,
    save_model_usage,
)


def _sample_rows() -> list[dict[str, str]]:
    return [
        {
            "function": "chat-model",
            "model_name": "qwen3.6-plus-2026-04-02",
            "source_file": "application.yml",
            "path": "langchain4j.open-ai.chat-model.model-name",
        },
        {
            "function": "code-exam-chat-model",
            "model_name": "qwen3.6-plus-2026-04-02",
            "source_file": "application.yml",
            "path": "langchain4j.open-ai.code-exam-chat-model.model-name",
        },
        {
            "function": "streaming-chat-model",
            "model_name": "qwen3.6-flash-2026-04-16",
            "source_file": "application.yml",
            "path": "langchain4j.open-ai.streaming-chat-model.model-name",
        },
    ]


class TestModelUsageMigrate(unittest.TestCase):
    def test_migrates_v1_models_to_v2_functions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            path.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "models": {
                            "qwen3.6-plus-2026-04-02": {
                                "description": "shared desc",
                                "updated_at": "2026-01-01T00:00:00+08:00",
                            }
                        },
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            saved = load_model_usage(path, migration_rows=_sample_rows())
            self.assertEqual(
                saved["chat-model"]["description"],
                "shared desc",
            )
            self.assertEqual(
                saved["code-exam-chat-model"]["description"],
                "shared desc",
            )
            payload = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(payload["version"], 2)
            self.assertIn("functions", payload)

    def test_independent_descriptions_for_shared_model(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage(
                {
                    "chat-model": "desc for chat",
                    "code-exam-chat-model": "desc for exam",
                },
                path=path,
            )
            rows = _sample_rows()
            saved = load_model_usage(path)
            ctx = build_function_replace_context(rows, saved)
            self.assertEqual(ctx["chat-model"]["description"], "desc for chat")
            self.assertEqual(ctx["code-exam-chat-model"]["description"], "desc for exam")

    def test_replace_does_not_move_descriptions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage({"streaming-chat-model": "# streaming"}, path=path)
            changes = [
                {
                    "function": "streaming-chat-model",
                    "from": "glm-5",
                    "to": "MiniMax-M2.5",
                }
            ]
            yaml_keys = {"streaming-chat-model", "chat-model"}
            result = migrate_model_usage_after_replace(changes, yaml_keys, path=path)
            self.assertFalse(result.changed)
            saved = load_model_usage(path)
            self.assertEqual(saved["streaming-chat-model"]["description"], "# streaming")
            self.assertNotIn("MiniMax-M2.5", saved)
            self.assertNotIn("glm-5", saved)

    def test_orphan_not_in_catalog_after_removal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage({"old-function": "orphan desc"}, path=path)
            prune_orphan_model_usage({"chat-model"}, path=path)
            catalog = build_model_catalog(
                "",
                "",
                saved=load_model_usage(path),
                list_models_data_fn=lambda _a, _l: [
                    {"function": "chat-model", "model_name": "new-model"},
                ],
            )
            keys = [e.function_key for e in catalog]
            self.assertNotIn("old-function", keys)
            self.assertIn("chat-model", keys)

    def test_prune_orphan_removes_stale_keys(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage(
                {
                    "gone-function": "gone",
                    "chat-model": "stay",
                },
                path=path,
            )
            removed = prune_orphan_model_usage({"chat-model"}, path=path)
            self.assertEqual(removed, ["gone-function"])
            self.assertNotIn("gone-function", load_model_usage(path))

    def test_catalog_one_entry_per_function(self) -> None:
        rows = _sample_rows()
        saved = {
            "chat-model": {"description": "a", "updated_at": ""},
            "code-exam-chat-model": {"description": "b", "updated_at": ""},
        }
        catalog = build_model_catalog("", "", saved=saved, list_models_data_fn=lambda _a, _l: rows)
        by_key = {entry.function_key: entry for entry in catalog if entry.in_yaml}
        self.assertEqual(len(by_key), 3)
        self.assertEqual(by_key["chat-model"].description, "a")
        self.assertEqual(by_key["code-exam-chat-model"].description, "b")

    def test_prune_noop_on_v1_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            path.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "models": {
                            "qwen3.6-plus-2026-04-02": {
                                "description": "keep me",
                                "updated_at": "2026-01-01T00:00:00+08:00",
                            }
                        },
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            removed = prune_orphan_model_usage({"chat-model"}, path=path)
            self.assertEqual(removed, [])
            payload = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(payload.get("version", 1), 1)
            self.assertIn("models", payload)

    def test_refresh_order_migrates_before_prune(self) -> None:
        rows = _sample_rows()
        yaml_keys = {row["function"] for row in rows}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            path.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "models": {
                            "qwen3.6-plus-2026-04-02": {
                                "description": "shared desc",
                                "updated_at": "2026-01-01T00:00:00+08:00",
                            }
                        },
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            saved = load_model_usage(path, migration_rows=rows)
            prune_orphan_model_usage(yaml_keys, path=path)
            saved_after = load_model_usage(path)
            self.assertEqual(saved_after["chat-model"]["description"], "shared desc")
            self.assertEqual(saved_after["code-exam-chat-model"]["description"], "shared desc")
            self.assertEqual(saved["chat-model"]["description"], "shared desc")

    def test_save_rejects_v1_without_migration(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            path.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "models": {
                            "old-model": {
                                "description": "desc",
                                "updated_at": "2026-01-01T00:00:00+08:00",
                            }
                        },
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                save_model_usage({"chat-model": "new desc"}, path=path)

    def test_detect_v1_orphan_models(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            path.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "models": {
                            "qwen3.6-plus-2026-04-02": {"description": "in yaml", "updated_at": ""},
                            "orphan-model": {"description": "gone", "updated_at": ""},
                        },
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            is_v1, orphans = detect_v1_orphan_models(path, _sample_rows())
            self.assertTrue(is_v1)
            self.assertEqual(orphans, ["orphan-model"])

            load_model_usage(path, migration_rows=_sample_rows())
            is_v2, orphans_v2 = detect_v1_orphan_models(path, _sample_rows())
            self.assertFalse(is_v2)
            self.assertEqual(orphans_v2, [])


if __name__ == "__main__":
    unittest.main()
