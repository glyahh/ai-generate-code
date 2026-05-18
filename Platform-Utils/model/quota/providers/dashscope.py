from __future__ import annotations

import requests

import main as core
from quota.scrapers.http_text import json_path_get

DASHSCOPE_CREDIT_URL = "https://dashscope.aliyuncs.com/api/v1/billing/credit"
CREDIT_JSON_PATH = "data.totalBalance"


def fetch_account_credit(
    api_key: str,
    timeout: int,
    *,
    endpoint: str = "",
    json_path: str = "",
) -> str:
    url = endpoint.strip() or DASHSCOPE_CREDIT_URL
    quota_path = json_path.strip() or CREDIT_JSON_PATH

    resp = requests.get(
        url,
        headers={"Authorization": f"Bearer {api_key.strip()}"},
        timeout=timeout,
    )
    resp.raise_for_status()
    content_type = resp.headers.get("Content-Type", "")
    if "application/json" in content_type:
        data = resp.json()
    else:
        text = resp.text.strip()
        if text:
            return text[:500]
        raise ValueError("响应非 JSON 且正文为空")

    value = json_path_get(data, quota_path)
    if value is None:
        raise ValueError(f"响应中未找到 {quota_path}，请检查 URL 或 JSON 路径")
    return str(value)


def check_quota_dashscope_api_key(
    *,
    api_key: str,
    app_yml: str,
    local_yml: str,
    timeout: int,
    endpoint: str = "",
    json_path: str = "",
) -> tuple[list[dict[str, str]], list[str]]:
    balance = fetch_account_credit(
        api_key,
        timeout,
        endpoint=endpoint,
        json_path=json_path,
    )
    mappings = core.list_models_data(app_yml, local_yml)
    quota_label = f"账户余额 {balance}"
    rows: list[dict[str, str]] = []
    for m in mappings:
        rows.append(
            {
                "function": m["function"],
                "model_name": m["model_name"],
                "platform": "dashscope",
                "quota": quota_label,
                "method": "API_KEY",
                "error": "",
            }
        )
    hints = [
        "当前为 DashScope 账户级余额，非控制台「逐模型免费额度」。",
        "逐模型剩/共请使用：URL+浏览器（Cookie/Playwright）、剪贴板或本地文件模式。",
    ]
    if endpoint.strip():
        hints.insert(0, f"已使用自定义查询 URL: {endpoint.strip()}")
    elif not (endpoint.strip() or json_path.strip()):
        hints.insert(0, f"默认 URL: {DASHSCOPE_CREDIT_URL}（若 404 请在「查询 URL」填写可用接口）")
    return rows, hints
