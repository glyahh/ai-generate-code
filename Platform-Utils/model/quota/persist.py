from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

MODEL_DIR = Path(__file__).resolve().parents[1]
LOCAL_QUOTA_DIR = MODEL_DIR / ".local" / "quota"
LATEST_SNAPSHOT = LOCAL_QUOTA_DIR / "latest.json"
RUNTIME_PLATFORMS_YML = LOCAL_QUOTA_DIR / "platforms.runtime.yml"
QUOTA_ENV_FILE = MODEL_DIR / "quota.local.env"
QUOTA_ENV_EXAMPLE = MODEL_DIR / "quota.env.example"
ENV_YAML_SEPARATOR = "---"

DEFAULT_PLATFORMS_YAML = """\
platforms:
  - name: dashscope
    api:
      endpoint: "https://dashscope.aliyuncs.com/api/v1/billing/credit"
      method: "GET"
      headers:
        Authorization: "Bearer ${ENV:DASHSCOPE_API_KEY}"
      params: {}
      quota_json_path: "data.totalBalance"
    web:
      url: "https://help.aliyun.com/zh/model-studio/model-usage-statistics"
      regex: ""

  - name: custom-platform
    api:
      endpoint: "https://api.example.com/quota?model={model_name}"
      method: "GET"
      headers:
        X-API-Key: "${ENV:CUSTOM_API_KEY}"
      params: {}
      quota_json_path: "quota.remaining"
    web:
      url: "https://console.example.com/models/{model_name}/quota"
      css_selector: ".quota-remaining"
      attr: ""
"""

QUOTA_MODE_LABELS: dict[str, str] = {
    "config": "Config 配置",
    "api_key": "API Key",
    "url_browser": "URL + 浏览器",
    "local_file": "本地文件",
    "clipboard": "剪贴板/文本",
}


def ensure_local_dirs() -> None:
    LOCAL_QUOTA_DIR.mkdir(parents=True, exist_ok=True)


def split_env_file(path: Path) -> tuple[dict[str, str], str | None]:
    """解析 env 文件：上半 KEY=VALUE，可选 `---` 以下为 platforms YAML。"""
    if not path.exists():
        return {}, None
    text = path.read_text(encoding="utf-8", errors="ignore")
    env_part, _, tail = text.partition(f"\n{ENV_YAML_SEPARATOR}\n")
    platforms: str | None = None
    if tail.strip():
        platforms = tail.strip()
    result: dict[str, str] = {}
    for line in env_part.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key:
            result[key] = value
    return result, platforms


def load_quota_env(path: Path | None = None) -> dict[str, str]:
    env_path = path or QUOTA_ENV_FILE
    env, _ = split_env_file(env_path)
    return env


def load_gui_env_local() -> tuple[dict[str, str], str]:
    """GUI 表单回显：仅读 quota.local.env，不读样例文件。"""
    local_env, _ = split_env_file(QUOTA_ENV_FILE)
    if local_env:
        return local_env, QUOTA_ENV_FILE.name
    return {}, ""


def load_gui_env_merged() -> tuple[dict[str, str], str]:
    """兼容旧调用名；行为同 load_gui_env_local。"""
    return load_gui_env_local()


def merged_platforms_yaml() -> str:
    """Config 模式 platforms：仅 local 的 --- 段，否则内置默认（不读样例）。"""
    _, local_plat = split_env_file(QUOTA_ENV_FILE)
    if local_plat:
        return local_plat
    return DEFAULT_PLATFORMS_YAML


def materialize_platforms_config() -> Path:
    """将合并后的 platforms 写入 .local（gitignore），供 Config 模式与 CLI 使用。"""
    ensure_local_dirs()
    body = merged_platforms_yaml()
    RUNTIME_PLATFORMS_YML.write_text(body + "\n", encoding="utf-8")
    return RUNTIME_PLATFORMS_YML


def resolve_config_yml_path(_env: dict[str, str] | None = None) -> str:
    return str(materialize_platforms_config().resolve())


def save_quota_snapshot(
    rows: list[dict[str, str]],
    *,
    query_mode: str,
    query_meta: dict[str, Any] | None = None,
    log_hints: list[str] | None = None,
) -> Path:
    ensure_local_dirs()
    payload = {
        "saved_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "query_mode": query_mode,
        "query_meta": query_meta or {},
        "log_hints": log_hints or [],
        "rows": rows,
    }
    LATEST_SNAPSHOT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return LATEST_SNAPSHOT


def load_quota_snapshot() -> dict[str, Any] | None:
    if not LATEST_SNAPSHOT.exists():
        return None
    try:
        data = json.loads(LATEST_SNAPSHOT.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None
    if not isinstance(data, dict) or not isinstance(data.get("rows"), list):
        return None
    return data


def normalize_http_url(url: str) -> str:
    """去掉重复协议前缀，如 https:https:// → https://。"""
    s = url.strip()
    if not s:
        return s
    patterns = (
        (r"^(https?):https://", r"\1://"),
        (r"^(http?):http://", r"\1://"),
        (r"^(https?):http://", r"\1://"),
        (r"^(http?):https://", r"\1://"),
    )
    for _ in range(5):
        prev = s
        for pat, repl in patterns:
            s = re.sub(pat, repl, s, count=1, flags=re.IGNORECASE)
        if s == prev:
            break
    return s


def resolve_env_path(value: str, *, base: Path | None = None) -> str:
    raw = value.strip()
    if not raw:
        return ""
    p = Path(raw)
    if not p.is_absolute():
        p = (base or MODEL_DIR) / p
    return str(p.resolve())


def _env_bool(env: dict[str, str], key: str) -> bool | None:
    if key not in env:
        return None
    return env[key].strip().lower() in {"1", "true", "yes", "on"}


def apply_env_to_gui(gui: Any, env: dict[str, str] | None = None) -> list[str]:
    data = env if env is not None else load_gui_env_local()[0]
    if not data:
        return []

    mapping = {
        "QUOTA_QUERY_URL": "query_url",
        "QUOTA_COOKIE_FILE": "cookie_file",
        "QUOTA_BROWSER_PROFILE": "browser_profile_dir",
        "QUOTA_API_KEY_ENDPOINT": "api_key_endpoint",
        "QUOTA_API_KEY_JSON_PATH": "api_key_json_path",
        "QUOTA_PLATFORM_FILTER": "platform_name",
        "GUI_APP_YML": "app_yml",
        "GUI_LOCAL_YML": "local_yml",
        "QUOTA_API_KEY_PLATFORM": "api_key_platform",
        "QUOTA_LOCAL_QUOTA_FILE": "local_quota_file",
    }
    applied: list[str] = []

    if hasattr(gui, "config_yml"):
        gui.config_yml.set(resolve_config_yml_path(data))

    for env_key, attr in mapping.items():
        if env_key not in data:
            continue
        raw = data[env_key].strip()
        if env_key in {"QUOTA_QUERY_URL", "QUOTA_API_KEY_ENDPOINT"} and raw:
            raw = normalize_http_url(raw)
        if not raw and env_key not in {
            "QUOTA_PLATFORM_FILTER",
            "QUOTA_COOKIE_FILE",
            "QUOTA_API_KEY_ENDPOINT",
        }:
            continue
        if env_key in {"QUOTA_COOKIE_FILE", "QUOTA_LOCAL_QUOTA_FILE", "GUI_APP_YML", "GUI_LOCAL_YML"}:
            raw = resolve_env_path(raw) if raw else ""
        if env_key == "QUOTA_BROWSER_PROFILE" and raw:
            raw = resolve_env_path(raw)
        if not raw and env_key in {"QUOTA_COOKIE_FILE", "QUOTA_LOCAL_QUOTA_FILE"}:
            continue
        var = getattr(gui, attr, None)
        if var is not None and hasattr(var, "set"):
            var.set(raw)
            applied.append(env_key)

    playwright = _env_bool(data, "QUOTA_USE_PLAYWRIGHT")
    if playwright is not None:
        gui.use_playwright.set(playwright)
        applied.append("QUOTA_USE_PLAYWRIGHT")
    firecrawl = _env_bool(data, "QUOTA_USE_FIRECRAWL")
    if firecrawl is not None:
        gui.use_firecrawl_browser.set(firecrawl)
        applied.append("QUOTA_USE_FIRECRAWL")
    read_key = _env_bool(data, "QUOTA_READ_KEY_FROM_YML")
    if read_key is not None:
        gui.read_key_from_yml.set(read_key)
        applied.append("QUOTA_READ_KEY_FROM_YML")

    mode = data.get("QUOTA_QUERY_MODE", "").strip()
    if mode in QUOTA_MODE_LABELS:
        gui.quota_mode.set(QUOTA_MODE_LABELS[mode])
        applied.append("QUOTA_QUERY_MODE")

    profile = data.get("QUOTA_BROWSER_PROFILE", "").strip()
    if not profile and hasattr(gui, "browser_profile_dir"):
        from quota.engine import default_browser_profile_dir

        gui.browser_profile_dir.set(default_browser_profile_dir())

    return applied


def start_page_from_env(env: dict[str, str] | None = None) -> str:
    data = env if env is not None else load_gui_env_local()[0]
    page = data.get("GUI_START_PAGE", "quota").strip().lower()
    if page in {"quota", "map", "replace", "usage"}:
        return page
    return "quota"


def viz_mode_from_env(env: dict[str, str] | None = None) -> str:
    data = env if env is not None else load_gui_env_local()[0]
    mode = data.get("GUI_QUOTA_VIZ_MODE", "donut").strip().lower()
    if mode in {"donut", "bar"}:
        return mode
    return "donut"


def save_gui_env_fields(updates: dict[str, str]) -> Path | None:
    """将键值写回 quota.local.env（保留 --- 下方 YAML）；不存在则创建。"""
    if not updates:
        return None
    if QUOTA_ENV_FILE.exists():
        text = QUOTA_ENV_FILE.read_text(encoding="utf-8", errors="ignore")
        env_part, sep, tail = text.partition(f"\n{ENV_YAML_SEPARATOR}\n")
    else:
        if QUOTA_ENV_EXAMPLE.exists():
            env_part = QUOTA_ENV_EXAMPLE.read_text(encoding="utf-8", errors="ignore")
            env_part, sep, tail = env_part.partition(f"\n{ENV_YAML_SEPARATOR}\n")
        else:
            env_part, sep, tail = "", "", ""

    lines = env_part.splitlines()
    pending = {k: v for k, v in updates.items() if k}
    new_lines: list[str] = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            new_lines.append(line)
            continue
        key = stripped.split("=", 1)[0].strip()
        if key in pending:
            new_lines.append(f"{key}={pending.pop(key)}")
        else:
            new_lines.append(line)
    for key, value in pending.items():
        if new_lines and new_lines[-1].strip():
            new_lines.append("")
        new_lines.append(f"{key}={value}")

    body = "\n".join(new_lines).rstrip()
    if sep:
        body = f"{body}\n{ENV_YAML_SEPARATOR}\n{tail.rstrip()}"
    QUOTA_ENV_FILE.write_text(body + "\n", encoding="utf-8")
    return QUOTA_ENV_FILE


def firecrawl_api_key_from_env(env: dict[str, str] | None = None) -> str:
    data = env if env is not None else load_quota_env()
    return data.get("FIRECRAWL_API_KEY", "").strip()


def save_gui_viz_mode(mode: str) -> None:
    """将可视化视图偏好写回 quota.local.env（不存在则跳过）。"""
    if mode not in {"donut", "bar"}:
        return
    if not QUOTA_ENV_FILE.exists():
        return
    text = QUOTA_ENV_FILE.read_text(encoding="utf-8", errors="ignore")
    env_part, sep, tail = text.partition(f"\n{ENV_YAML_SEPARATOR}\n")
    lines = env_part.splitlines()
    found = False
    new_lines: list[str] = []
    for line in lines:
        if line.strip().startswith("GUI_QUOTA_VIZ_MODE="):
            new_lines.append(f"GUI_QUOTA_VIZ_MODE={mode}")
            found = True
        else:
            new_lines.append(line)
    if not found:
        if new_lines and new_lines[-1].strip():
            new_lines.append("")
        new_lines.append(f"GUI_QUOTA_VIZ_MODE={mode}")
    body = "\n".join(new_lines)
    if sep:
        body = f"{body}\n{ENV_YAML_SEPARATOR}\n{tail}"
    QUOTA_ENV_FILE.write_text(body.rstrip() + "\n", encoding="utf-8")


def snapshot_path_display() -> str:
    return str(LATEST_SNAPSHOT)
