from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from model_usage.catalog import build_model_catalog
from model_usage.migrate import migrate_model_usage_after_replace, prune_orphan_model_usage
from model_usage.persist import load_model_usage, save_model_usage


class TestModelUsageMigrate(unittest.TestCase):
    def test_migrates_description_and_removes_stale_model(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage(
                {
                    "glm-5": "# streaming",
                    "MiniMax-M2.5": "",
                },
                path=path,
            )
            changes = [
                {
                    "function": "streaming-chat-model",
                    "from": "glm-5",
                    "to": "MiniMax-M2.5",
                    "target_file": "application-local.yml",
                }
            ]
            yaml_names = {"MiniMax-M2.5", "qwen3.5-122b-a10b"}
            result = migrate_model_usage_after_replace(changes, yaml_names, path=path)

            self.assertIn(("glm-5", "MiniMax-M2.5"), result.migrated_pairs)
            self.assertIn("glm-5", result.removed_models)

            saved = load_model_usage(path)
            self.assertNotIn("glm-5", saved)
            self.assertEqual(saved["MiniMax-M2.5"]["description"], "# streaming")

    def test_keeps_old_model_when_still_in_yaml(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage({"glm-5": "desc-a", "other": "desc-b"}, path=path)
            changes = [
                {"function": "streaming-chat-model", "from": "glm-5", "to": "MiniMax-M2.5"},
            ]
            yaml_names = {"glm-5", "MiniMax-M2.5"}
            result = migrate_model_usage_after_replace(changes, yaml_names, path=path)

            saved = load_model_usage(path)
            self.assertIn("glm-5", saved)
            self.assertNotIn("glm-5", result.removed_models)
            self.assertEqual(saved["MiniMax-M2.5"]["description"], "desc-a")

    def test_orphan_not_in_catalog_after_removal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage({"glm-5": "orphan desc"}, path=path)
            changes = [{"function": "f", "from": "glm-5", "to": "new-model"}]
            migrate_model_usage_after_replace(changes, {"new-model"}, path=path)

            catalog = build_model_catalog(
                "",
                "",
                saved=load_model_usage(path),
                list_models_data_fn=lambda _a, _l: [
                    {"function": "f", "model_name": "new-model"},
                ],
            )
            names = [e.model_name for e in catalog]
            self.assertNotIn("glm-5", names)
            self.assertIn("new-model", names)

    def test_does_not_overwrite_existing_new_description(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage(
                {"glm-5": "old text", "MiniMax-M2.5": "already set"},
                path=path,
            )
            changes = [{"function": "f", "from": "glm-5", "to": "MiniMax-M2.5"}]
            migrate_model_usage_after_replace(changes, {"MiniMax-M2.5"}, path=path)
            saved = load_model_usage(path)
            self.assertEqual(saved["MiniMax-M2.5"]["description"], "already set")
            self.assertNotIn("glm-5", saved)


    def test_prune_orphan_removes_stale_keys(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model-usage.json"
            save_model_usage({"glm-5": "gone", "qwen3.5-flash": "stay"}, path=path)
            removed = prune_orphan_model_usage({"qwen3.5-flash"}, path=path)
            self.assertEqual(removed, ["glm-5"])
            self.assertNotIn("glm-5", load_model_usage(path))


if __name__ == "__main__":
    unittest.main()
