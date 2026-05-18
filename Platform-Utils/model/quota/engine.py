from __future__ import annotations

from pathlib import Path

import main as core
from quota.parsers.local_file import extract_text_from_local_file, try_extract_quota_from_json_blob
from quota.parsers.text_match import (
    find_quota_for_model,
    normalize_page_text,
    quota_lookup_model_name,
    rows_from_page_text,
)
from quota.parsers.text_match import _canonical_id_in_page  # noqa: PLC2701
from quota.providers import config_platform, dashscope
from quota.scrapers import browser_playwright, http_text
from quota.scrapers.cookie_session import load_cookie_jar
from quota.types import PlaywrightSearchMode, QuotaQueryMode, QuotaQueryRequest, QuotaQueryResult
from quota.yaml_keys import read_dashscope_api_key

MODEL_DIR = Path(__file__).resolve().parents[1]
DEFAULT_BROWSER_PROFILE = MODEL_DIR / ".cache" / "browser_profile"


def default_browser_profile_dir() -> str:
    return str(DEFAULT_BROWSER_PROFILE)


def _save_scrape_debug(raw_text: str) -> Path:
    path = MODEL_DIR / ".local" / "quota" / "last_scrape.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(normalize_page_text(raw_text), encoding="utf-8")
    return path


def _scrape_coverage_hints(raw_text: str, mappings: list[dict[str, str]]) -> list[str]:
    hints: list[str] = []
    if not raw_text.strip():
        return ["页面文本为空，请检查登录态或 URL。"]
    debug_path = _save_scrape_debug(raw_text)
    hints.append(f"抓取文本已保存：{debug_path}")
    missing: list[str] = []
    for mapping in mappings:
        name = quota_lookup_model_name(mapping["model_name"])
        if not _canonical_id_in_page(raw_text, name):
            missing.append(f"{mapping['function']}({name})")
    if missing:
        hints.append("下列模型未出现在抓取文本中（表格虚拟滚动或未加载）：" + "、".join(missing))
    return hints


def run_quota_query(request: QuotaQueryRequest) -> QuotaQueryResult:
    mode = request.mode
    hints: list[str] = []

    if mode == QuotaQueryMode.CONFIG:
        rows, extra = config_platform.check_quota_config(
            config=request.config_path,
            app_yml=request.app_yml,
            local_yml=request.local_yml,
            platform=request.platform_filter,
            timeout=request.timeout,
        )
        hints.extend(extra)
        return QuotaQueryResult(rows=rows, log_hints=hints)

    if mode == QuotaQueryMode.API_KEY:
        api_key = request.api_key.strip()
        if request.read_api_key_from_local_yml and not api_key:
            api_key = read_dashscope_api_key(request.app_yml, request.local_yml)
            if api_key:
                hints.append("已从 application-local.yml / application.yml 读取 API Key。")
        if not api_key:
            raise ValueError("请填写 API Key，或勾选从项目配置读取。")

        if request.api_key_platform == "dashscope":
            rows, extra = dashscope.check_quota_dashscope_api_key(
                api_key=api_key,
                app_yml=request.app_yml,
                local_yml=request.local_yml,
                timeout=request.timeout,
                endpoint=request.api_key_endpoint,
                json_path=request.api_key_json_path,
            )
            hints.extend(extra)
            return QuotaQueryResult(rows=rows, log_hints=hints)

        if request.custom_config_for_api:
            rows, extra = config_platform.check_quota_api_key_custom(
                api_key=api_key,
                config=request.custom_config_for_api,
                app_yml=request.app_yml,
                local_yml=request.local_yml,
                platform=request.platform_filter or request.api_key_platform,
                timeout=request.timeout,
            )
            hints.extend(extra)
            return QuotaQueryResult(rows=rows, log_hints=hints)

        raise ValueError("自定义平台需在 config 路径中配置 platforms，并选择对应 platform 名称。")

    if mode == QuotaQueryMode.URL_BROWSER:
        return _run_url_browser(request)

    if mode == QuotaQueryMode.LOCAL_FILE:
        return _run_local_file(request)

    if mode == QuotaQueryMode.CLIPBOARD:
        text = request.page_text.strip()
        if not text:
            raise ValueError("剪贴板/文本内容为空。")
        rows = core.check_quota_from_text_data(
            page_text=text,
            app_yml=request.app_yml,
            local_yml=request.local_yml,
        )
        return QuotaQueryResult(rows=rows, log_hints=["已使用剪贴板/文本解析模式。"])

    raise ValueError(f"未知查询方式: {mode}")


def _run_url_browser(request: QuotaQueryRequest) -> QuotaQueryResult:
    from quota.persist import normalize_http_url

    url = normalize_http_url(request.query_url)
    if not url:
        raise ValueError("请填写查询 URL。")

    hints: list[str] = []
    mappings = core.list_models_data(request.app_yml, request.local_yml)
    profile = request.browser_profile_dir.strip() or default_browser_profile_dir()

    cookie_file = request.cookie_file.strip()
    jar = None
    if cookie_file:
        jar = load_cookie_jar(cookie_file)
        hints.append("已加载 Cookie 文件。")

    raw_text = ""
    method = "URL"

    try:
        if request.use_playwright and request.use_firecrawl_browser:
            hints.append(
                "已同时勾选 Playwright 与 Firecrawl：当前优先使用 Playwright。"
                "若要用 Firecrawl，请取消 Playwright 后重试。"
            )
        if request.use_playwright:
            search_mode = request.playwright_search_mode
            model_names = [quota_lookup_model_name(m["model_name"]) for m in mappings]
            if search_mode == PlaywrightSearchMode.DOM_LOCATOR:
                hints.append(
                    "定位搜索：Playwright 在主表内 Ctrl+F 式定位每个模型行，仅读取该行额度。"
                )
                method = "BROWSER-LOCATE"
            else:
                hints.append(
                    "全文搜索：Playwright 滚动抓取页面文本，再按模型名匹配额度。"
                )
                method = "BROWSER"
            raw_text = browser_playwright.fetch_page_text_with_playwright(
                url,
                profile_dir=profile,
                headless=True,
                wait_seconds=20.0,
                cookie_file=cookie_file,
                model_names=model_names,
                search_mode=search_mode.value,
            )
        elif request.use_firecrawl_browser:
            ready, reason = http_text.firecrawl_ready()
            if not ready:
                raise RuntimeError(reason)
            hints.append("使用 Firecrawl Browser 抓取（需先在控制台登录态可用）。")
            raw_text = http_text.scrape_with_firecrawl_browser(url)
            method = "BROWSER-FC"
        elif jar is not None:
            headers: dict[str, str] = {}
            raw_text = http_text.fetch_page_text(
                url,
                timeout=request.timeout,
                headers=headers,
                cookies=jar,
                use_firecrawl=False,
            )
            method = "COOKIE"
            if not _page_has_quota_hints(raw_text):
                hints.append("Cookie 直连未拿到额度文本，可勾选 Playwright 重试。")
        else:
            api_key = request.api_key.strip()
            headers = {}
            if api_key:
                headers["Authorization"] = f"Bearer {api_key}"
            raw_text = http_text.fetch_page_text(url, timeout=request.timeout, headers=headers)
            method = "URL"
            hints.append("无 Cookie/浏览器时使用 HTTP 抓取（控制台 SPA 常失败）。")
    except Exception as e:  # noqa: BLE001
        return QuotaQueryResult(
            rows=[
                {
                    "function": m["function"],
                    "model_name": m["model_name"],
                    "platform": "url-direct",
                    "quota": "",
                    "method": method,
                    "error": f"URL 查询失败: {e}",
                }
                for m in mappings
            ],
            log_hints=hints,
        )

    if raw_text.strip():
        hints.extend(_scrape_coverage_hints(raw_text, mappings))

    rows = rows_from_page_text(
        mappings,
        raw_text,
        platform="url-direct",
        method=method,
        empty_error="未在页面中匹配到额度（需登录态时请用 Cookie 或 Playwright）",
    )
    if not any(r["quota"] for r in rows) and not request.use_playwright and jar:
        hints.append("建议勾选 Playwright，并确保 Cookie 或会话目录已登录。")
    return QuotaQueryResult(rows=rows, log_hints=hints)


def _page_has_quota_hints(text: str) -> bool:
    markers = ("剩", "剩余", "免费额度", "remaining", "model-usage", "/共")
    lower = text.lower()
    return any(m in text or m.lower() in lower for m in markers)


def _run_local_file(request: QuotaQueryRequest) -> QuotaQueryResult:
    path = request.local_file_path.strip()
    if not path:
        raise ValueError("请选择本地文件路径。")

    raw = extract_text_from_local_file(path)
    mappings = core.list_models_data(request.app_yml, request.local_yml)
    rows: list[dict[str, str]] = []
    for m in mappings:
        yaml_name = quota_lookup_model_name(m["model_name"])
        quota = try_extract_quota_from_json_blob(raw, yaml_name)
        if not quota:
            quota = find_quota_for_model(raw, yaml_name)
        rows.append(
            {
                "function": m["function"],
                "model_name": yaml_name,
                "platform": "local-file",
                "quota": quota,
                "method": "LOCAL_FILE",
                "error": "" if quota else f"本地文件中未匹配到「{yaml_name}」额度",
            }
        )
    return QuotaQueryResult(
        rows=rows,
        log_hints=[f"已解析本地文件: {path}"],
    )
