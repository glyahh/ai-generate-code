from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import requests


def load_cookie_jar(cookie_file: str) -> requests.cookies.RequestsCookieJar:
    path = Path(cookie_file)
    if not path.exists():
        raise FileNotFoundError(f"Cookie 文件不存在: {cookie_file}")

    raw = path.read_text(encoding="utf-8", errors="ignore").strip()
    jar = requests.cookies.RequestsCookieJar()

    if raw.startswith("[") or raw.startswith("{"):
        _load_json_cookies(raw, jar)
    else:
        _load_netscape_cookies(path, jar)

    if not jar:
        raise ValueError("Cookie 文件未解析到任何有效条目")
    return jar


def _load_json_cookies(raw: str, jar: requests.cookies.RequestsCookieJar) -> None:
    data: Any = json.loads(raw)
    items: list[Any]
    if isinstance(data, list):
        items = data
    elif isinstance(data, dict) and "cookies" in data:
        items = data["cookies"] if isinstance(data["cookies"], list) else []
    else:
        items = []

    for item in items:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name", "")).strip()
        value = str(item.get("value", ""))
        if not name:
            continue
        domain = str(item.get("domain", "")).strip() or None
        path = str(item.get("path", "/")).strip() or "/"
        jar.set(name, value, domain=domain, path=path)


def _load_netscape_cookies(path: Path, jar: requests.cookies.RequestsCookieJar) -> None:
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 7:
            continue
        domain, _flag, cpath, secure, expiry, name, value = parts[:7]
        if not name:
            continue
        jar.set(name, value, domain=domain, path=cpath or "/")
