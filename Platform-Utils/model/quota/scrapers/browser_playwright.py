from __future__ import annotations

import re
from pathlib import Path
from typing import Literal

ROW_MODEL_MARKER = "__ROW_MODEL__"
PlaywrightSearchModeValue = Literal["full_page", "dom_locator"]

# 主表行：含过期日期 + 额度数字（用于区分 TOP3 概览卡片）
_MAIN_ROW_DATE_RE = re.compile(r"\d{4}/\d{1,2}/\d{1,2}")
_MAIN_ROW_QUOTA_RE = re.compile(r"(?:剩|剩余)\s*[\d,]+|[\d,]+\s*/\s*[\d,]+")

# 在页面内用 window.find 逐次定位（类似 Ctrl+F），跳过 TOP3 等非主表命中
_FIND_MAIN_TABLE_ROW_JS = """
(modelName) => {
  const name = String(modelName || "").trim();
  if (!name) return { ok: false, reason: "empty" };

  const sel = window.getSelection();
  sel.removeAllRanges();

  const isMainQuotaRow = (tr) => {
    if (!tr || tr.tagName !== "TR") return false;
    const text = tr.innerText || "";
    if (text.includes("TOP3") || text.includes("即将用尽")) return false;
    if (!/\\d{4}\\/\\d{1,2}\\/\\d{1,2}/.test(text)) return false;
    if (!/(?:剩|剩余)\\s*[\\d,]|[\\d,]+\\s*\\/\\s*[\\d,]+/.test(text)) return false;
    const escaped = name.replace(/[.*+?^${}()|[\\]\\\\]/g, "\\\\$&");
    const tokenRe = new RegExp("(?:^|[\\\\s])" + escaped + "(?:[\\\\s]|$)", "i");
    return tokenRe.test(text);
  };

  const anchorRow = () => {
    const node = sel.anchorNode;
    if (!node) return null;
    let el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
    while (el && el.tagName !== "TR" && el.tagName !== "BODY") {
      el = el.parentElement;
    }
    return el && el.tagName === "TR" ? el : null;
  };

  let attempts = 0;
  const maxAttempts = 40;
  while (attempts < maxAttempts) {
    attempts += 1;
    if (!window.find(name, false, false, true, false, false, false)) {
      break;
    }
    const tr = anchorRow();
    if (!tr || !isMainQuotaRow(tr)) {
      continue;
    }
    tr.scrollIntoView({ block: "center", behavior: "instant" });
    return { ok: true, text: tr.innerText || "", method: "window.find" };
  }
  return { ok: false, reason: "not-found", attempts };
}
"""


def _scroll_quota_table(page) -> None:
    """全文模式：滚动主表以加载更多模型行。"""
    for _ in range(14):
        page.mouse.wheel(0, 700)
        page.wait_for_timeout(180)
    page.evaluate("window.scrollTo(0, 0)")
    page.wait_for_timeout(350)


def _click_if_visible(page, labels: tuple[str, ...]) -> None:
    for label in labels:
        try:
            page.get_by_text(label, exact=False).first.click(timeout=2500)
            page.wait_for_timeout(600)
        except Exception:  # noqa: BLE001
            continue


def _main_table_scope(page):
    """尽量限定在「全部模型」下方的主表，减少误命中 TOP3 区域。"""
    try:
        anchor = page.get_by_text("全部模型", exact=False).first
        if anchor.count() > 0:
            following = anchor.locator("xpath=following::*[contains(@class,'next-table')][1]")
            if following.count() > 0:
                return following
            following = anchor.locator("xpath=following::table[1]")
            if following.count() > 0:
                return following
    except Exception:  # noqa: BLE001
        pass
    for selector in (
        "[class*='next-table-body']",
        "[class*='next-table']",
        "table",
    ):
        try:
            loc = page.locator(selector)
            if loc.count() > 0:
                return loc.last
        except Exception:  # noqa: BLE001
            continue
    return page.locator("body")


def _row_text_is_main_quota_row(row_text: str, model_name: str) -> bool:
    if not row_text or model_name.lower() not in row_text.lower():
        return False
    if "TOP3" in row_text or "即将用尽" in row_text:
        return False
    if not _MAIN_ROW_DATE_RE.search(row_text):
        return False
    return bool(_MAIN_ROW_QUOTA_RE.search(row_text))


def _model_name_in_row(row_text: str, model_name: str) -> bool:
    escaped = re.escape(model_name.strip())
    return bool(re.search(rf"(?:^|\s){escaped}(?:\s|$)", row_text, re.IGNORECASE))


def _find_row_via_locator(page, model_name: str) -> tuple[str, str]:
    """
    Playwright 定位器：在主表 scope 内找同时含「精确模型名 + 日期 + 额度」的 tr。
    等价于 Ctrl+F 找到后人工确认「是主表那一行」。
    """
    scope = _main_table_scope(page)
    name = model_name.strip()
    if not name:
        return "", ""

    try:
        rows = scope.locator("tr").filter(has_text=re.compile(re.escape(name), re.IGNORECASE))
        count = min(rows.count(), 20)
    except Exception:  # noqa: BLE001
        return "", ""

    for index in range(count):
        try:
            row = rows.nth(index)
            row.scroll_into_view_if_needed(timeout=5000)
            page.wait_for_timeout(150)
            row_text = row.inner_text(timeout=4000).strip()
            if _row_text_is_main_quota_row(row_text, name) and _model_name_in_row(row_text, name):
                return row_text, "locator"
        except Exception:  # noqa: BLE001
            continue
    return "", ""


def _find_row_via_window_find(page, model_name: str) -> tuple[str, str]:
    """浏览器原生 window.find（与 Ctrl+F 相同底层），逐条跳过 TOP3 直到主表行。"""
    try:
        result = page.evaluate(_FIND_MAIN_TABLE_ROW_JS, model_name.strip())
    except Exception:  # noqa: BLE001
        return "", ""
    if not result or not result.get("ok"):
        return "", ""
    text = str(result.get("text", "")).strip()
    if _row_text_is_main_quota_row(text, model_name):
        return text, "window.find"
    return "", ""


def _find_quota_table_row(page, model_name: str) -> tuple[str, str]:
    """先 locator 精确筛主表行，失败再用 window.find；均只返回整行 innerText。"""
    row_text, method = _find_row_via_locator(page, model_name)
    if row_text:
        return row_text, method
    return _find_row_via_window_find(page, model_name)


def _append_table_row_snippets(page, model_names: list[str]) -> str:
    """
    对每个 YAML 模型用「Ctrl+F 式」DOM 定位主表行，写入 __ROW_MODEL__ 块；
    解析端优先读该块，不再依赖全文回溯。
    """
    blocks: list[str] = []
    methods: list[str] = []
    for model_name in model_names:
        name = model_name.strip()
        if len(name) < 3:
            continue
        row_text, method = _find_quota_table_row(page, name)
        if row_text:
            blocks.append(f"{ROW_MODEL_MARKER}{name}\n{row_text}")
            methods.append(f"{name}={method}")
    return "\n\n".join(blocks)


def _collect_visible_text(page) -> str:
    chunks: list[str] = []
    seen: set[str] = set()
    selectors = (
        "table",
        "[class*='next-table-body']",
        "[class*='next-table']",
        "[class*='Table']",
        "main",
        "body",
    )
    for selector in selectors:
        try:
            locator = page.locator(selector)
            count = min(locator.count(), 5)
            for index in range(count):
                text = locator.nth(index).inner_text(timeout=5000)
                text = text.strip()
                if not text or text in seen:
                    continue
                seen.add(text)
                chunks.append(text)
        except Exception:  # noqa: BLE001
            continue
    return "\n\n".join(chunks)


def fetch_page_text_with_playwright(
    url: str,
    *,
    profile_dir: str,
    headless: bool = True,
    wait_seconds: float = 20.0,
    cookie_file: str = "",
    model_names: list[str] | None = None,
    search_mode: PlaywrightSearchModeValue = "full_page",
) -> str:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as e:
        raise ImportError(
            "未安装 playwright。请执行: pip install playwright && playwright install chromium"
        ) from e

    profile_path = Path(profile_dir)
    profile_path.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as p:
        context = p.chromium.launch_persistent_context(
            user_data_dir=str(profile_path),
            headless=headless,
            locale="zh-CN",
            viewport={"width": 1440, "height": 900},
        )
        try:
            if cookie_file:
                from quota.scrapers.cookie_session import load_cookie_jar  # noqa: PLC0415

                jar = load_cookie_jar(cookie_file)
                cookies_for_pw = []
                for c in jar:
                    if not c.name:
                        continue
                    entry: dict[str, str | bool] = {
                        "name": c.name,
                        "value": c.value,
                        "path": c.path or "/",
                    }
                    if c.domain:
                        entry["domain"] = c.domain
                        entry["secure"] = str(c.domain).startswith(".")
                    cookies_for_pw.append(entry)
                if cookies_for_pw:
                    context.add_cookies(cookies_for_pw)

            page = context.pages[0] if context.pages else context.new_page()
            page.goto(url, wait_until="domcontentloaded", timeout=120_000)
            page.wait_for_timeout(int(min(wait_seconds, 60) * 1000))

            for selector in (
                "text=免费额度",
                "text=模型用量",
                "text=剩余",
                "table",
            ):
                try:
                    page.wait_for_selector(selector, timeout=8_000)
                    break
                except Exception:  # noqa: BLE001
                    continue

            _click_if_visible(page, ("免费额度", "大语言模型"))
            page.wait_for_timeout(500)

            if search_mode == "dom_locator" and model_names:
                row_text = _append_table_row_snippets(page, model_names)
                if row_text:
                    return row_text
                return _collect_visible_text(page)

            _scroll_quota_table(page)
            body_text = _collect_visible_text(page)
            if model_names:
                row_text = _append_table_row_snippets(page, model_names)
                if row_text:
                    return f"{body_text}\n\n{row_text}"
            return body_text
        finally:
            context.close()
