from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from GUI.quota_visualization import parse_model_stats, parse_quota_numbers
from quota.persist import viz_mode_from_env, save_gui_viz_mode, QUOTA_ENV_FILE


class TestParseQuotaNumbers(unittest.TestCase):
    def test_parses_remain_and_total(self) -> None:
        result = parse_quota_numbers("剩 699,514 / 共 1,000,000")
        self.assertIsNotNone(result)
        used, total = result  # type: ignore[misc]
        self.assertEqual(total, 1_000_000.0)
        self.assertEqual(used, 300_486.0)

    def test_empty_returns_none(self) -> None:
        self.assertIsNone(parse_quota_numbers(""))
        self.assertIsNone(parse_quota_numbers("无额度"))

    def test_zero_total_returns_none(self) -> None:
        self.assertIsNone(parse_quota_numbers("剩 0 / 共 0"))


class TestParseModelStats(unittest.TestCase):
    def test_sorts_by_remain_pct_ascending(self) -> None:
        rows = [
            {"model_name": "high", "quota": "剩 800,000 / 共 1,000,000"},
            {"model_name": "low", "quota": "剩 50,000 / 共 1,000,000"},
        ]
        stats = parse_model_stats(rows)
        self.assertEqual(len(stats), 2)
        self.assertEqual(stats[0]["model"], "low")
        self.assertEqual(stats[1]["model"], "high")
        self.assertAlmostEqual(float(stats[0]["remain_pct"]), 5.0)
        self.assertAlmostEqual(float(stats[1]["remain_pct"]), 80.0)

    def test_skips_unparseable_rows(self) -> None:
        rows = [
            {"model_name": "ok", "quota": "剩 1 / 共 10"},
            {"model_name": "bad", "quota": ""},
        ]
        stats = parse_model_stats(rows)
        self.assertEqual(len(stats), 1)
        self.assertEqual(stats[0]["model"], "ok")


class TestVizModeFromEnv(unittest.TestCase):
    def test_valid_modes(self) -> None:
        self.assertEqual(viz_mode_from_env({"GUI_QUOTA_VIZ_MODE": "bar"}), "bar")
        self.assertEqual(viz_mode_from_env({"GUI_QUOTA_VIZ_MODE": "donut"}), "donut")

    def test_invalid_falls_back_to_donut(self) -> None:
        self.assertEqual(viz_mode_from_env({"GUI_QUOTA_VIZ_MODE": "pie"}), "donut")
        self.assertEqual(viz_mode_from_env({}), "donut")

    def test_save_gui_viz_mode_writes_local_env(self) -> None:
        original = QUOTA_ENV_FILE.read_text(encoding="utf-8") if QUOTA_ENV_FILE.exists() else None
        try:
            with tempfile.TemporaryDirectory() as tmp:
                env_path = Path(tmp) / "quota.local.env"
                env_path.write_text("GUI_START_PAGE=quota\n", encoding="utf-8")
                import quota.persist as persist_mod

                old_path = persist_mod.QUOTA_ENV_FILE
                persist_mod.QUOTA_ENV_FILE = env_path
                try:
                    save_gui_viz_mode("bar")
                    text = env_path.read_text(encoding="utf-8")
                    self.assertIn("GUI_QUOTA_VIZ_MODE=bar", text)
                    self.assertEqual(viz_mode_from_env(persist_mod.load_quota_env(env_path)), "bar")
                finally:
                    persist_mod.QUOTA_ENV_FILE = old_path
        finally:
            if original is not None:
                QUOTA_ENV_FILE.write_text(original, encoding="utf-8")
            elif QUOTA_ENV_FILE.exists():
                QUOTA_ENV_FILE.unlink()


if __name__ == "__main__":
    unittest.main()
