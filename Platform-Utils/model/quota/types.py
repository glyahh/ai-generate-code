from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class QuotaQueryMode(str, Enum):
    CONFIG = "config"
    API_KEY = "api_key"
    URL_BROWSER = "url_browser"
    LOCAL_FILE = "local_file"
    CLIPBOARD = "clipboard"


class PlaywrightSearchMode(str, Enum):
    """URL+Playwright 下的两种抓取策略（由 GUI「执行查询」/「定位搜索」选择）。"""

    FULL_PAGE = "full_page"  # 滚动抓取全文，再文本匹配
    DOM_LOCATOR = "dom_locator"  # Ctrl+F 式定位主表行，只读该行


@dataclass
class QuotaQueryRequest:
    mode: QuotaQueryMode
    app_yml: str
    local_yml: str
    timeout: int = 20
    # config mode
    config_path: str = ""
    platform_filter: str = ""
    # api key mode
    api_key: str = ""
    api_key_platform: str = "dashscope"
    read_api_key_from_local_yml: bool = False
    custom_config_for_api: str = ""
    api_key_endpoint: str = ""
    api_key_json_path: str = ""
    # url + browser mode
    query_url: str = ""
    cookie_file: str = ""
    use_playwright: bool = False
    use_firecrawl_browser: bool = False
    browser_profile_dir: str = ""
    playwright_search_mode: PlaywrightSearchMode = PlaywrightSearchMode.FULL_PAGE
    # local file mode
    local_file_path: str = ""
    # clipboard mode
    page_text: str = ""


@dataclass
class QuotaQueryResult:
    rows: list[dict[str, str]] = field(default_factory=list)
    log_hints: list[str] = field(default_factory=list)

    def to_row_dicts(self) -> list[dict[str, str]]:
        return self.rows
