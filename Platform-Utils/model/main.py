#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import requests
from bs4 import BeautifulSoup
from ruamel.yaml import YAML


ROOT = Path(__file__).resolve().parents[2]
RESOURCES_DIR = ROOT / "src" / "main" / "resources"
APP_YML = RESOURCES_DIR / "application.yml"
LOCAL_YML = RESOURCES_DIR / "application-local.yml"
DEFAULT_CONFIG = Path(__file__).resolve().parent / "config.example.yml"

yaml = YAML()
yaml.preserve_quotes = True
yaml.width = 4096


@dataclass
class ModelMapping:
    function_key: str
    model_name: str
    source_file: str
    path: str


def load_yaml(path: Path) -> Dict[str, Any]:
    if not path.exists():
        raise FileNotFoundError(f"文件不存在: {path}")
    with path.open("r", encoding="utf-8") as f:
        data = yaml.load(f) or {}
    if not isinstance(data, dict):
        raise ValueError(f"YAML 顶层必须是对象: {path}")
    return data


def write_yaml(path: Path, data: Dict[str, Any]) -> None:
    with path.open("w", encoding="utf-8") as f:
        yaml.dump(data, f)


def deep_merge(base: Dict[str, Any], override: Dict[str, Any]) -> Dict[str, Any]:
    result = deepcopy(base)
    for key, value in override.items():
        if (
            key in result
            and isinstance(result[key], dict)
            and isinstance(value, dict)
        ):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = deepcopy(value)
    return result


def get_nested(data: Dict[str, Any], keys: List[str]) -> Any:
    cur: Any = data
    for key in keys:
        if not isinstance(cur, dict) or key not in cur:
            return None
        cur = cur[key]
    return cur


def set_nested(data: Dict[str, Any], keys: List[str], value: Any) -> bool:
    cur: Any = data
    for key in keys[:-1]:
        if key not in cur or not isinstance(cur[key], dict):
            return False
        cur = cur[key]
    if keys[-1] not in cur:
        return False
    cur[keys[-1]] = value
    return True


def model_blocks_from_openai(open_ai_cfg: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    blocks: Dict[str, Dict[str, Any]] = {}
    for key, value in open_ai_cfg.items():
        if not isinstance(value, dict):
            continue
        if "model-name" in value and key.endswith("model"):
            blocks[key] = value
    return blocks


def collect_model_mappings(
    app_cfg: Dict[str, Any], local_cfg: Dict[str, Any]
) -> Tuple[List[ModelMapping], Dict[str, Dict[str, Any]]]:
    merged = deep_merge(app_cfg, local_cfg)
    open_ai = get_nested(merged, ["langchain4j", "open-ai"]) or {}
    if not isinstance(open_ai, dict):
        raise ValueError("langchain4j.open-ai 配置格式错误")

    app_open_ai = get_nested(app_cfg, ["langchain4j", "open-ai"]) or {}
    local_open_ai = get_nested(local_cfg, ["langchain4j", "open-ai"]) or {}
    app_blocks = model_blocks_from_openai(app_open_ai) if isinstance(app_open_ai, dict) else {}
    local_blocks = model_blocks_from_openai(local_open_ai) if isinstance(local_open_ai, dict) else {}
    merged_blocks = model_blocks_from_openai(open_ai)

    items: List[ModelMapping] = []
    for function_key in sorted(merged_blocks.keys()):
        model_name = str(merged_blocks[function_key].get("model-name", "")).strip()
        source = "application-local.yml" if function_key in local_blocks else "application.yml"
        items.append(
            ModelMapping(
                function_key=function_key,
                model_name=model_name,
                source_file=source,
                path=f"langchain4j.open-ai.{function_key}.model-name",
            )
        )
    return items, merged_blocks


def print_table(rows: List[Dict[str, str]], columns: List[str]) -> None:
    if not rows:
        print("无数据")
        return
    widths = {col: len(col) for col in columns}
    for row in rows:
        for col in columns:
            widths[col] = max(widths[col], len(str(row.get(col, ""))))
    header = " | ".join(col.ljust(widths[col]) for col in columns)
    sep = "-+-".join("-" * widths[col] for col in columns)
    print(header)
    print(sep)
    for row in rows:
        print(" | ".join(str(row.get(col, "")).ljust(widths[col]) for col in columns))


def load_runtime_config(config_path: Path) -> Dict[str, Any]:
    cfg = load_yaml(config_path)
    if "platforms" not in cfg or not isinstance(cfg["platforms"], list):
        raise ValueError("配置文件必须包含 platforms 列表")
    return cfg


def render_template(raw: str, context: Dict[str, Any]) -> str:
    pattern = re.compile(r"\{([a-zA-Z0-9_]+)\}")
    return pattern.sub(lambda m: str(context.get(m.group(1), m.group(0))), raw)


def resolve_placeholders(value: Any, context: Dict[str, Any]) -> Any:
    if isinstance(value, str):
        rendered = render_template(value, context)
        if rendered.startswith("${ENV:") and rendered.endswith("}"):
            env_name = rendered[6:-1]
            return os.getenv(env_name, "")
        return rendered
    if isinstance(value, dict):
        return {k: resolve_placeholders(v, context) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve_placeholders(v, context) for v in value]
    return value


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


def fetch_quota_by_api(platform: Dict[str, Any], context: Dict[str, Any], timeout: int) -> Optional[str]:
    api = platform.get("api")
    if not isinstance(api, dict):
        return None
    endpoint = resolve_placeholders(api.get("endpoint", ""), context)
    if not endpoint:
        return None

    method = str(api.get("method", "GET")).upper()
    headers = resolve_placeholders(api.get("headers", {}), context) or {}
    params = resolve_placeholders(api.get("params", {}), context) or {}
    body = resolve_placeholders(api.get("body", {}), context) or {}
    quota_path = str(api.get("quota_json_path", "")).strip()

    resp = requests.request(
        method=method,
        url=endpoint,
        headers=headers,
        params=params,
        json=body if method in {"POST", "PUT", "PATCH"} else None,
        timeout=timeout,
    )
    resp.raise_for_status()
    data = resp.json() if "application/json" in resp.headers.get("Content-Type", "") else {}
    quota_value = json_path_get(data, quota_path)
    if quota_value is None:
        return None
    return str(quota_value)


def has_firecrawl_cli() -> bool:
    return shutil.which("firecrawl") is not None


def scrape_with_firecrawl(url: str) -> str:
    with tempfile.NamedTemporaryFile(suffix=".md", delete=False) as tmp:
        out_path = Path(tmp.name)
    try:
        cmd = ["firecrawl", "scrape", url, "-o", str(out_path)]
        subprocess.run(cmd, check=True, capture_output=True, text=True)
        return out_path.read_text(encoding="utf-8", errors="ignore")
    finally:
        if out_path.exists():
            out_path.unlink()


def fetch_quota_by_web(platform: Dict[str, Any], context: Dict[str, Any], timeout: int) -> Optional[str]:
    web = platform.get("web")
    if not isinstance(web, dict):
        return None
    raw_url = str(web.get("url", "")).strip()
    if not raw_url:
        return None
    url = resolve_placeholders(raw_url, context)

    html_or_markdown = ""
    if has_firecrawl_cli():
        try:
            html_or_markdown = scrape_with_firecrawl(url)
        except Exception:  # noqa: BLE001
            # firecrawl 可用但调用失败时回退直连抓取，避免整条链路中断
            resp = requests.get(url, timeout=timeout)
            resp.raise_for_status()
            html_or_markdown = resp.text
    else:
        resp = requests.get(url, timeout=timeout)
        resp.raise_for_status()
        html_or_markdown = resp.text

    regex = web.get("regex")
    if isinstance(regex, str) and regex.strip():
        m = re.search(regex, html_or_markdown, re.MULTILINE)
        if m:
            if m.groups():
                return m.group(1).strip()
            return m.group(0).strip()

    selector = web.get("css_selector")
    if isinstance(selector, str) and selector.strip():
        soup = BeautifulSoup(html_or_markdown, "html.parser")
        node = soup.select_one(selector)
        if node:
            attr = web.get("attr")
            if isinstance(attr, str) and attr.strip():
                return (node.get(attr) or "").strip() or None
            return node.get_text(strip=True) or None

    return None


def fetch_page_text(url: str, timeout: int, headers: Optional[Dict[str, str]] = None) -> str:
    request_headers = headers or {}
    if has_firecrawl_cli():
        try:
            return scrape_with_firecrawl(url)
        except Exception:  # noqa: BLE001
            pass
    resp = requests.get(url, timeout=timeout, headers=request_headers)
    resp.raise_for_status()
    return resp.text


def find_quota_for_model(raw_text: str, model_name: str) -> str:
    escaped = re.escape(model_name)
    patterns = [
        rf"{escaped}[\s\S]{{0,120}}?(剩[^<\n\r/]*?[\d,]+(?:\.\d+)?\s*/\s*共?\s*[\d,]+(?:\.\d+)?)",
        rf"{escaped}[\s\S]{{0,120}}?(remaining[^<\n\r]*?[\d,]+(?:\.\d+)?)",
        rf"{escaped}[\s\S]{{0,120}}?([\d,]+(?:\.\d+)?\s*/\s*[\d,]+(?:\.\d+)?)",
    ]
    for pattern in patterns:
        m = re.search(pattern, raw_text, re.IGNORECASE)
        if m:
            return re.sub(r"\s+", " ", m.group(1)).strip()
    return ""


def parse_replacements(raw_values: List[str]) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for item in raw_values:
        if "=" not in item:
            raise ValueError(f"映射格式错误（应为 功能=模型）: {item}")
        key, value = item.split("=", 1)
        k = key.strip()
        v = value.strip()
        if not k or not v:
            raise ValueError(f"映射不能为空: {item}")
        result[k] = v
    return result


def list_models_data(app_yml: str = str(APP_YML), local_yml: str = str(LOCAL_YML)) -> List[Dict[str, str]]:
    app_cfg = load_yaml(Path(app_yml))
    local_cfg = load_yaml(Path(local_yml))
    items, _ = collect_model_mappings(app_cfg, local_cfg)
    return [
        {
            "function": i.function_key,
            "model_name": i.model_name,
            "source_file": i.source_file,
            "path": i.path,
        }
        for i in items
    ]


def check_quota_data(
    config: str,
    app_yml: str = str(APP_YML),
    local_yml: str = str(LOCAL_YML),
    platform: str = "",
    timeout: int = 20,
) -> List[Dict[str, str]]:
    app_cfg = load_yaml(Path(app_yml))
    local_cfg = load_yaml(Path(local_yml))
    mappings, _ = collect_model_mappings(app_cfg, local_cfg)
    runtime_cfg = load_runtime_config(Path(config))

    results: List[Dict[str, str]] = []
    for mapping in mappings:
        for platform_item in runtime_cfg["platforms"]:
            platform_name = str(platform_item.get("name", "unknown")).strip() or "unknown"
            if platform and platform_name != platform:
                continue
            context = {"model_name": mapping.model_name, "function": mapping.function_key}
            quota = None
            method = ""
            error_message = ""
            try:
                quota = fetch_quota_by_api(platform_item, context, timeout)
                if quota is not None:
                    method = "API"
            except Exception as e:  # noqa: BLE001
                error_message = f"API失败: {e}"

            if quota is None:
                try:
                    quota = fetch_quota_by_web(platform_item, context, timeout)
                    if quota is not None:
                        method = "WEB"
                except Exception as e:  # noqa: BLE001
                    if error_message:
                        error_message += f"; WEB失败: {e}"
                    else:
                        error_message = f"WEB失败: {e}"

            results.append(
                {
                    "function": mapping.function_key,
                    "model_name": mapping.model_name,
                    "platform": platform_name,
                    "quota": quota if quota is not None else "",
                    "method": method,
                    "error": error_message,
                }
            )
    return results


def check_quota_by_url_data(
    query_url: str,
    app_yml: str = str(APP_YML),
    local_yml: str = str(LOCAL_YML),
    timeout: int = 20,
    api_key: str = "",
) -> List[Dict[str, str]]:
    mappings = list_models_data(app_yml, local_yml)
    headers: Dict[str, str] = {}
    if api_key.strip():
        headers["Authorization"] = f"Bearer {api_key.strip()}"
    try:
        raw_text = fetch_page_text(query_url.strip(), timeout=timeout, headers=headers)
    except Exception as e:  # noqa: BLE001
        return [
            {
                "function": m["function"],
                "model_name": m["model_name"],
                "platform": "url-direct",
                "quota": "",
                "method": "URL",
                "error": f"URL 查询失败: {e}",
            }
            for m in mappings
        ]

    results: List[Dict[str, str]] = []
    for m in mappings:
        quota = find_quota_for_model(raw_text, m["model_name"])
        results.append(
            {
                "function": m["function"],
                "model_name": m["model_name"],
                "platform": "url-direct",
                "quota": quota,
                "method": "URL",
                "error": "" if quota else "未在页面中匹配到额度信息（可能需要登录态或页面为动态渲染）",
            }
        )
    return results


def check_quota_from_text_data(
    page_text: str,
    app_yml: str = str(APP_YML),
    local_yml: str = str(LOCAL_YML),
) -> List[Dict[str, str]]:
    mappings = list_models_data(app_yml, local_yml)
    results: List[Dict[str, str]] = []
    for m in mappings:
        quota = find_quota_for_model(page_text, m["model_name"])
        results.append(
            {
                "function": m["function"],
                "model_name": m["model_name"],
                "platform": "clipboard-text",
                "quota": quota,
                "method": "CLIPBOARD",
                "error": "" if quota else "未匹配到额度，请确认复制的是“模型用量”页面可见文本",
            }
        )
    return results


def preview_replace_data(
    mapping: Dict[str, str],
    app_yml: str = str(APP_YML),
    local_yml: str = str(LOCAL_YML),
) -> List[Dict[str, str]]:
    app_cfg = load_yaml(Path(app_yml))
    local_cfg = load_yaml(Path(local_yml))
    return _prepare_replace_changes(mapping, app_cfg, local_cfg)


def apply_replace_data(
    mapping: Dict[str, str],
    app_yml: str = str(APP_YML),
    local_yml: str = str(LOCAL_YML),
) -> List[Dict[str, str]]:
    app_path = Path(app_yml)
    local_path = Path(local_yml)
    app_cfg = load_yaml(app_path)
    local_cfg = load_yaml(local_path)
    changes = _prepare_replace_changes(mapping, app_cfg, local_cfg)
    if changes:
        write_yaml(app_path, app_cfg)
        write_yaml(local_path, local_cfg)
    return changes


def _prepare_replace_changes(
    mapping: Dict[str, str],
    app_cfg: Dict[str, Any],
    local_cfg: Dict[str, Any],
) -> List[Dict[str, str]]:
    changes: List[Dict[str, str]] = []
    for function_key, new_model in mapping.items():
        target_path = ["langchain4j", "open-ai", function_key, "model-name"]
        local_old = get_nested(local_cfg, target_path)
        app_old = get_nested(app_cfg, target_path)

        if local_old is not None:
            old_model = str(local_old)
            if old_model != new_model:
                if not set_nested(local_cfg, target_path, new_model):
                    raise ValueError(f"更新失败: {function_key}")
                changes.append(
                    {
                        "function": function_key,
                        "from": old_model,
                        "to": new_model,
                        "target_file": "application-local.yml",
                    }
                )
            continue

        if app_old is not None:
            old_model = str(app_old)
            if old_model != new_model:
                if not set_nested(app_cfg, target_path, new_model):
                    raise ValueError(f"更新失败: {function_key}")
                changes.append(
                    {
                        "function": function_key,
                        "from": old_model,
                        "to": new_model,
                        "target_file": "application.yml",
                    }
                )
            continue

        raise KeyError(f"功能未找到: {function_key}")
    return changes


def list_models_cmd(args: argparse.Namespace) -> int:
    rows = list_models_data(args.app_yml, args.local_yml)
    if args.output_json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0

    print_table(rows, ["function", "model_name", "source_file", "path"])
    return 0


def check_quota_cmd(args: argparse.Namespace) -> int:
    results = check_quota_data(
        config=args.config,
        app_yml=args.app_yml,
        local_yml=args.local_yml,
        platform=args.platform,
        timeout=args.timeout,
    )

    if args.output_json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
        return 0

    print_table(results, ["function", "model_name", "platform", "quota", "method", "error"])
    return 0


def replace_model_cmd(args: argparse.Namespace) -> int:
    replacements = parse_replacements(args.mapping)
    changes = preview_replace_data(replacements, args.app_yml, args.local_yml)

    if not changes:
        print("无变更，所有模型名已是目标值")
        return 0

    print_table(changes, ["function", "from", "to", "target_file"])

    if args.apply:
        apply_replace_data(replacements, args.app_yml, args.local_yml)
        print("已写回配置文件")
    else:
        print("当前为 dry-run，未写入文件。使用 --apply 执行落盘。")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="模型配置管理工具")
    parser.add_argument("--app-yml", default=str(APP_YML), help="application.yml 路径")
    parser.add_argument("--local-yml", default=str(LOCAL_YML), help="application-local.yml 路径")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p1 = sub.add_parser("list-models", help="列出模型与功能映射")
    p1.add_argument("--json", dest="output_json", action="store_true", help="JSON 输出")
    p1.set_defaults(func=list_models_cmd)

    p2 = sub.add_parser("check-quota", help="检查模型额度（API优先，失败回退WEB）")
    p2.add_argument("--config", default=str(DEFAULT_CONFIG), help="额度查询配置文件路径")
    p2.add_argument("--platform", default="", help="只查询指定平台名称")
    p2.add_argument("--timeout", type=int, default=20, help="请求超时秒数")
    p2.add_argument("--json", dest="output_json", action="store_true", help="JSON 输出")
    p2.set_defaults(func=check_quota_cmd)

    p3 = sub.add_parser("replace-model", help="按功能替换 model-name")
    p3.add_argument(
        "--mapping",
        nargs="+",
        required=True,
        help="映射列表，格式: 功能=模型名，例如 chat-model=qwen3.5-plus",
    )
    p3.add_argument("--apply", action="store_true", help="真正写入文件")
    p3.set_defaults(func=replace_model_cmd)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        return int(args.func(args))
    except Exception as e:  # noqa: BLE001
        print(f"[ERROR] {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
