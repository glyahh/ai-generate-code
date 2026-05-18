from __future__ import annotations

import unittest

from quota.sync_rows import align_quota_rows_with_mappings, sync_quota_rows_after_replace


class TestQuotaSyncRows(unittest.TestCase):
    def test_align_updates_model_name_and_fills_missing(self) -> None:
        rows = [
            {
                "function": "chat-model",
                "model_name": "old-name",
                "platform": "x",
                "quota": "剩 1 / 共 2",
                "method": "URL",
                "error": "",
            }
        ]
        mappings = [
            {"function": "chat-model", "model_name": "new-name"},
            {"function": "streaming-chat-model", "model_name": "MiniMax-M2.5"},
        ]
        aligned = align_quota_rows_with_mappings(rows, mappings)
        self.assertEqual(len(aligned), 2)
        self.assertEqual(aligned[0]["model_name"], "new-name")
        self.assertEqual(aligned[1]["function"], "streaming-chat-model")
        self.assertEqual(aligned[1]["quota"], "")

    def test_sync_after_replace_updates_name_and_clears_quota(self) -> None:
        rows = [
            {
                "function": "streaming-chat-model",
                "model_name": "glm-5",
                "quota": "剩 1 / 共 2",
                "method": "URL",
                "error": "",
            }
        ]
        changes = [{"function": "streaming-chat-model", "from": "glm-5", "to": "MiniMax-M2.5"}]
        synced, count = sync_quota_rows_after_replace(rows, changes)
        self.assertEqual(count, 1)
        self.assertEqual(synced[0]["model_name"], "MiniMax-M2.5")
        self.assertEqual(synced[0]["quota"], "")
        self.assertIn("重新", synced[0]["error"])


if __name__ == "__main__":
    unittest.main()
