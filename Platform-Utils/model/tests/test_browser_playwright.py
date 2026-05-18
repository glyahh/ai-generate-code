from __future__ import annotations

import unittest

from quota.scrapers.browser_playwright import (
    _model_name_in_row,
    _row_text_is_main_quota_row,
)


class TestBrowserPlaywrightHelpers(unittest.TestCase):
    def test_main_row_accepts_table_with_date_and_quota(self) -> None:
        row = "kimi-k2.6\n剩 75,098 / 共 1,000,000\n2026/07/21\n已开启"
        self.assertTrue(_row_text_is_main_quota_row(row, "kimi-k2.6"))

    def test_main_row_rejects_top3_widget(self) -> None:
        row = "92%\nkimi-k2.6\n剩75,098/共1,000,000"
        self.assertFalse(_row_text_is_main_quota_row(row, "kimi-k2.6"))

    def test_model_name_token_boundary(self) -> None:
        self.assertTrue(_model_name_in_row("kimi-k2.6\n剩 1 / 共 2", "kimi-k2.6"))
        self.assertFalse(_model_name_in_row("not-kimi-k2.6-extra", "kimi-k2.6"))


if __name__ == "__main__":
    unittest.main()
