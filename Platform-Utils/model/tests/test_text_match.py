from __future__ import annotations

import unittest

from quota.parsers.text_match import find_quota_for_model, rows_from_page_text


class TestTextMatch(unittest.TestCase):
    def test_same_line_with_chinese_suffix(self) -> None:
        page = "MiniMax-M2.5 高速版 剩 12,345 / 共 1,000,000\n"
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertIn("12,345", quota)

    def test_model_on_previous_line(self) -> None:
        page = "qwen3.5-flash\n剩 500 / 共 1000000\n"
        quota = find_quota_for_model(page, "qwen3.5-flash")
        self.assertIn("500", quota)

    def test_yaml_comment_in_model_name(self) -> None:
        page = "glm-5\n剩 100 / 共 1000000\n"
        quota = find_quota_for_model(page, "glm-5  # was default")
        self.assertIn("100", quota)

    def test_not_listed_shows_zero(self) -> None:
        page = "other-model\n剩 100 / 共 1000000\n"
        quota = find_quota_for_model(page, "missing-model")
        self.assertTrue(quota.startswith("剩 0 / 共"))

    def test_bailian_labeled_quota_on_next_line(self) -> None:
        page = "MiniMax-M2.5\n剩 1,000,000 / 共 1,000,000\n"
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertIn("1,000,000", quota)
        self.assertNotIn("剩 0 / 共", quota)

    def test_unicode_dash_in_page_model_name(self) -> None:
        page = "MiniMax\u2013M2.5\n剩 500 / 共 1000000\n"
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertIn("500", quota)

    def test_bailian_bare_quota_without_sheng(self) -> None:
        page = (
            "qwen3.5-flash\n"
            "剩 500 / 共 1000000\n"
            "MiniMax-M2.5\n"
            "362,917/1,000,000\n"
        )
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertIn("362,917", quota)
        self.assertNotIn("剩 0 / 共", quota)

    def test_canonical_match_ignores_case_and_separators(self) -> None:
        page = "minimax-m2.5\n剩 88 / 共 1000000\n"
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertIn("88", quota)

    def test_bailian_row_with_expiry_between_model_and_quota(self) -> None:
        page = (
            "MiniMax-M2.5\n"
            "2026/05/25\n"
            "自动停止当免费额度用尽\n"
            "1,000,000 / 1,000,000\n"
        )
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertIn("1,000,000", quota)

    def test_row_marker_block(self) -> None:
        page = (
            "__ROW_MODEL__MiniMax-M2.5\n"
            "MiniMax-M2.5\n"
            "1,000,000 / 1,000,000\n"
            "2026/05/25\n"
        )
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertIn("1,000,000", quota)

    def test_split_minimax_lines(self) -> None:
        page = "MiniMax\nM2.5\n1,000,000 / 1,000,000\n"
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertIn("1,000,000", quota)

    def test_bailian_top3_widget_does_not_steal_quota_from_other_models(self) -> None:
        """TOP3 概览区与主表重复列出模型时，额度应绑定最近一行模型名。"""
        page = (
            "免费额度即将用尽TOP3模型\n"
            "92%\n"
            "kimi-k2.6\n"
            "剩75,098/共1,000,000\n"
            "31%\n"
            "qwen3.5-flash\n"
            "剩685,087/共1,000,000\n"
            "30%\n"
            "qwen3.5-122b-a10b\n"
            "剩699,514/共1,000,000\n"
            "全部模型\n"
            "qwen3.5-122b-a10b\n"
            "剩699,514/共1,000,000\n"
            "2026/05/25\n"
        )
        kimi_quota = find_quota_for_model(page, "kimi-k2.6")
        self.assertIn("75,098", kimi_quota)
        self.assertNotIn("699,514", kimi_quota)
        flash_quota = find_quota_for_model(page, "qwen3.5-flash")
        self.assertIn("685,087", flash_quota)

    def test_listed_but_unparseable_returns_empty_with_error(self) -> None:
        page = "MiniMax-M2.5\n（额度加载中）\n"
        quota = find_quota_for_model(page, "MiniMax-M2.5")
        self.assertEqual(quota, "")
        rows = rows_from_page_text(
            [{"function": "streaming-chat-model", "model_name": "MiniMax-M2.5"}],
            page,
            platform="t",
            method="t",
            empty_error="未匹配",
        )
        self.assertIn("未能解析", rows[0]["error"])


if __name__ == "__main__":
    unittest.main()
