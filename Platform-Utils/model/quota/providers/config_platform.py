from __future__ import annotations

from copy import deepcopy
from pathlib import Path
from typing import Any

import main as core


def check_quota_config(
    *,
    config: str,
    app_yml: str,
    local_yml: str,
    platform: str,
    timeout: int,
) -> tuple[list[dict[str, str]], list[str]]:
    rows = core.check_quota_data(
        config=config,
        app_yml=app_yml,
        local_yml=local_yml,
        platform=platform,
        timeout=timeout,
    )
    return rows, []


def check_quota_api_key_custom(
    *,
    api_key: str,
    config: str,
    app_yml: str,
    local_yml: str,
    platform: str,
    timeout: int,
) -> tuple[list[dict[str, str]], list[str]]:
    cfg_path = Path(config)
    runtime = core.load_runtime_config(cfg_path)
    app_cfg = core.load_yaml(Path(app_yml))
    local_cfg = core.load_yaml(Path(local_yml))
    mappings, _ = core.collect_model_mappings(app_cfg, local_cfg)

    platforms = runtime.get("platforms", [])
    results: list[dict[str, str]] = []

    for mapping in mappings:
        for platform_item in platforms:
            if not isinstance(platform_item, dict):
                continue
            platform_name = str(platform_item.get("name", "unknown")).strip() or "unknown"
            if platform and platform_name != platform:
                continue

            patched = deepcopy(platform_item)
            api = patched.get("api")
            if isinstance(api, dict) and api_key.strip():
                headers = api.get("headers")
                if not isinstance(headers, dict):
                    headers = {}
                headers = dict(headers)
                headers["Authorization"] = f"Bearer {api_key.strip()}"
                api["headers"] = headers
                patched["api"] = api

            context = {"model_name": mapping.model_name, "function": mapping.function_key}
            quota = None
            method = ""
            error_message = ""
            try:
                quota = core.fetch_quota_by_api(patched, context, timeout)
                if quota is not None:
                    method = "API_KEY"
            except Exception as e:  # noqa: BLE001
                error_message = f"API失败: {e}"

            if quota is None:
                try:
                    quota = core.fetch_quota_by_web(patched, context, timeout)
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
                    "method": method or "API_KEY",
                    "error": error_message,
                }
            )

    return results, ["已使用 API Key 作为 Bearer 调用 config 中平台 API。"]
