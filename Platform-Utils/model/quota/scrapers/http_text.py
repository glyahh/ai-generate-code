from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Dict, Optional

import requests

from quota.persist import firecrawl_api_key_from_env


def json_path_get(data: Any, path: str) -> Any:
    if not path:
        return data
    cur = data
    for token in path.split("."):
        if isinstance(cur, dict) and token in cur:
            cur = cur[token]
        else:
            return None
    return cur


def resolve_firecrawl_cli() -> str | None:
    """返回 firecrawl 可执行文件的绝对路径（供 subprocess 使用）。

    Windows 上 npm 全局包是 firecrawl.cmd；shutil.which 能找到，但
    subprocess.run(['firecrawl', ...]) 不会按 PATH 解析 .cmd，会报「找不到文件」。
    """
    return shutil.which("firecrawl")


def has_firecrawl_cli() -> bool:
    return resolve_firecrawl_cli() is not None


def _firecrawl_argv(*args: str) -> list[str]:
    exe = resolve_firecrawl_cli()
    if not exe:
        raise FileNotFoundError(
            "未检测到 firecrawl 命令行，请先安装：npm install -g firecrawl-cli"
        )
    return [exe, *args]


def firecrawl_ready() -> tuple[bool, str]:
    """CLI 是否在 PATH 且（建议）已配置 API Key。"""
    if not has_firecrawl_cli():
        return False, "未检测到 firecrawl 命令行，请先安装：npm install -g firecrawl-cli"
    if not firecrawl_api_key_from_env() and not os.environ.get("FIRECRAWL_API_KEY", "").strip():
        return False, "未配置 FIRECRAWL_API_KEY，请在右侧引导配置中填写并保存"
    return True, ""


def firecrawl_subprocess_env() -> dict[str, str]:
    env = os.environ.copy()
    key = firecrawl_api_key_from_env()
    if key:
        env["FIRECRAWL_API_KEY"] = key
    return env


def scrape_with_firecrawl(url: str, wait_ms: int = 5000) -> str:
    with tempfile.NamedTemporaryFile(suffix=".md", delete=False) as tmp:
        out_path = Path(tmp.name)
    try:
        cmd = _firecrawl_argv("scrape", url, "-o", str(out_path), "--wait-for", str(wait_ms))
        subprocess.run(cmd, check=True, capture_output=True, text=True, env=firecrawl_subprocess_env())
        return out_path.read_text(encoding="utf-8", errors="ignore")
    finally:
        if out_path.exists():
            out_path.unlink()


def scrape_with_firecrawl_browser(url: str) -> str:
    ready, reason = firecrawl_ready()
    if not ready:
        raise RuntimeError(reason)
    fc_env = firecrawl_subprocess_env()
    with tempfile.NamedTemporaryFile(suffix=".md", delete=False) as tmp:
        out_path = Path(tmp.name)
    try:
        subprocess.run(
            _firecrawl_argv("browser", f"open {url}"),
            check=True,
            capture_output=True,
            text=True,
            env=fc_env,
        )
        subprocess.run(
            _firecrawl_argv("browser", "wait", "8"),
            check=False,
            capture_output=True,
            text=True,
            env=fc_env,
        )
        subprocess.run(
            _firecrawl_argv("browser", "scrape", "-o", str(out_path)),
            check=True,
            capture_output=True,
            text=True,
            env=fc_env,
        )
        return out_path.read_text(encoding="utf-8", errors="ignore")
    finally:
        if out_path.exists():
            out_path.unlink()


def fetch_page_text(
    url: str,
    timeout: int,
    headers: Optional[Dict[str, str]] = None,
    cookies: Optional[requests.cookies.RequestsCookieJar] = None,
    use_firecrawl: bool = True,
    wait_ms: int = 5000,
) -> str:
    request_headers = headers or {}
    if use_firecrawl and has_firecrawl_cli():
        try:
            return scrape_with_firecrawl(url, wait_ms=wait_ms)
        except Exception:  # noqa: BLE001
            pass
    resp = requests.get(url, timeout=timeout, headers=request_headers, cookies=cookies)
    resp.raise_for_status()
    return resp.text
