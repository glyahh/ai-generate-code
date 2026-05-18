from __future__ import annotations

import re
from collections import Counter

from model_usage.catalog import normalize_model_name

QUOTA_WITH_LABEL_RE = re.compile(
    r"(?:剩|剩余)\s*([\d,]+(?:\.\d+)?)\s*/\s*(?:共\s*)?([\d,]+(?:\.\d+)?)",
    re.IGNORECASE,
)
QUOTA_BARE_RE = re.compile(
    r"(?<![\d,/])([\d,]{1,3}(?:,\d{3})+(?:\.\d+)?|[\d,]+(?:\.\d+)?)\s*/\s*([\d,]{1,3}(?:,\d{3})+(?:\.\d+)?|[\d,]+(?:\.\d+)?)(?![\d,/])"
)
MODEL_ID_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9._+:/-]{0,127}$")
MODEL_ID_TOKEN_RE = re.compile(r"[a-zA-Z][a-zA-Z0-9._+:/-]{0,127}")
DEFAULT_TOTAL = "1,000,000"
_DASH_CHARS = "\u2010\u2011\u2012\u2013\u2014\u2212\uff0d\u00ad"
ROW_MODEL_MARKER = "__ROW_MODEL__"


def quota_lookup_model_name(raw: str) -> str:
    """额度匹配使用的模型名：与 YAML list_models_data 保持一致。"""
    return normalize_model_name(raw)


def _normalize_model_key(name: str) -> str:
    return quota_lookup_model_name(name).lower()


def _canonical_model_key(name: str) -> str:
    """忽略大小写与 - _ . 差异，便于匹配控制台与 YAML 写法。"""
    return re.sub(r"[^a-z0-9]", "", _normalize_model_key(name))


def normalize_page_text(raw: str) -> str:
    """统一页面文本中的连字符与零宽字符，避免控制台与 YAML 写法不一致。"""
    if not raw:
        return ""
    text = raw
    for ch in _DASH_CHARS:
        text = text.replace(ch, "-")
    return text.replace("\u200b", "").replace("\ufeff", "")


def _canonical_id_in_page(raw_text: str, model_name: str) -> bool:
    target = _canonical_model_key(model_name)
    if len(target) < 4:
        return False
    flat = re.sub(r"[^a-z0-9]", "", normalize_page_text(raw_text).lower())
    return target in flat


def _format_quota(remain: str, total: str) -> str:
    return f"剩 {remain} / 共 {total}"


def _looks_like_model_id(text: str) -> bool:
    candidate = _normalize_model_key(text)
    if not candidate:
        return False
    if QUOTA_WITH_LABEL_RE.search(candidate) or QUOTA_BARE_RE.fullmatch(candidate):
        return False
    return bool(MODEL_ID_RE.match(candidate))


def _extract_model_token(text: str) -> str:
    """从「模型名 + 后缀说明」同一行里取出模型 ID。"""
    raw = text.strip()
    if not raw:
        return ""
    if _looks_like_model_id(raw):
        return raw.split("#", 1)[0].strip()
    match = MODEL_ID_TOKEN_RE.match(raw)
    if match and _looks_like_model_id(match.group(0)):
        return match.group(0)
    return ""


def _infer_default_total(raw_text: str) -> str:
    totals = re.findall(r"共\s*([\d,]+(?:\.\d+)?)", raw_text)
    if totals:
        return Counter(totals).most_common(1)[0][0]
    return DEFAULT_TOTAL


def _quota_match_on_line(line: str) -> re.Match[str] | None:
    labeled = QUOTA_WITH_LABEL_RE.search(line)
    if labeled:
        return labeled
    bare = QUOTA_BARE_RE.search(line)
    if bare:
        try:
            left = float(bare.group(1).replace(",", ""))
            right = float(bare.group(2).replace(",", ""))
        except ValueError:
            return None
        if right > 0 and left <= right:
            return bare
    return None


def _page_model_tokens(raw_text: str) -> list[str]:
    tokens: list[str] = []
    seen: set[str] = set()
    for line in raw_text.splitlines():
        token = _extract_model_token(line.strip())
        if token:
            key = _normalize_model_key(token)
            if key and key not in seen:
                seen.add(key)
                tokens.append(token)
    for match in MODEL_ID_TOKEN_RE.finditer(raw_text):
        token = match.group(0)
        if not _looks_like_model_id(token):
            continue
        key = _normalize_model_key(token)
        if key and key not in seen:
            seen.add(key)
            tokens.append(token.split("#", 1)[0].strip())
    return tokens


def _model_listed_on_page(raw_text: str, model_name: str) -> bool:
    key = _normalize_model_key(model_name)
    target = _canonical_model_key(model_name)
    if not key or not target:
        return False
    escaped = re.escape(model_name.strip().split("#", 1)[0].strip())
    pattern = re.compile(rf"(?<![a-zA-Z0-9_./+:-]){escaped}(?![a-zA-Z0-9_./+:-])", re.IGNORECASE)
    for line in raw_text.splitlines():
        if pattern.search(line):
            return True
        token = _extract_model_token(line)
        if token and _normalize_model_key(token) == key:
            return True
    for token in _page_model_tokens(raw_text):
        token_key = _canonical_model_key(token)
        if token_key == target or token_key.endswith(target) or target.endswith(token_key):
            return True
    return _canonical_id_in_page(raw_text, model_name)


def _quota_from_line_match(line: str, match: re.Match[str]) -> str:
    return _format_quota(match.group(1), match.group(2))


def _find_quota_after_model_marker(raw_text: str, model_name: str) -> str:
    lookup = quota_lookup_model_name(model_name)
    markers = {lookup, lookup.replace("-", ""), _normalize_model_key(lookup)}
    text = normalize_page_text(raw_text)
    for marker in markers:
        if len(marker) < 3:
            continue
        for match in re.finditer(re.escape(marker), text, re.IGNORECASE):
            snippet = text[match.start() : match.start() + 400]
            labeled = QUOTA_WITH_LABEL_RE.search(snippet)
            if labeled:
                return _format_quota(labeled.group(1), labeled.group(2))
            bare = QUOTA_BARE_RE.search(snippet)
            if bare:
                return _format_quota(bare.group(1), bare.group(2))
    return ""


def _merge_split_model_lines(lines: list[str]) -> list[str]:
    merged: list[str] = []
    index = 0
    while index < len(lines):
        current = lines[index].strip()
        if index + 1 < len(lines):
            nxt = lines[index + 1].strip()
            if re.match(r"^[A-Za-z][A-Za-z0-9._-]{0,48}$", current) and re.match(
                r"^M\d[\w.]*$", nxt, re.IGNORECASE
            ):
                merged.append(f"{current}-{nxt}")
                index += 2
                continue
        merged.append(lines[index])
        index += 1
    return merged


def _quota_from_row_marker_block(raw_text: str, lookup_name: str) -> str:
    marker = f"{ROW_MODEL_MARKER}{lookup_name}"
    start = raw_text.find(marker)
    if start < 0:
        return ""
    block = raw_text[start + len(marker) :]
    next_marker = block.find(ROW_MODEL_MARKER)
    if next_marker >= 0:
        block = block[:next_marker]
    labeled = QUOTA_WITH_LABEL_RE.search(block)
    if labeled:
        return _format_quota(labeled.group(1), labeled.group(2))
    bare = QUOTA_BARE_RE.search(block)
    if bare:
        return _format_quota(bare.group(1), bare.group(2))
    return ""


def extract_model_quota_map(raw_text: str) -> dict[str, str]:
    raw_text = normalize_page_text(raw_text)
    lines = _merge_split_model_lines([ln.strip() for ln in raw_text.splitlines() if ln.strip()])
    model_quota: dict[str, str] = {}

    for i, line in enumerate(lines):
        if line.startswith(ROW_MODEL_MARKER):
            continue
        match = _quota_match_on_line(line)
        if not match:
            continue
        quota = _quota_from_line_match(line, match)
        before = line[: match.start()].strip()
        row_token = ""
        if before:
            row_token = _extract_model_token(before)
        best_token = row_token
        if not best_token:
            for j in range(1, 12):
                if i - j < 0:
                    break
                prev_token = _extract_model_token(lines[i - j])
                if prev_token:
                    best_token = prev_token
                    break
        if best_token:
            model_quota[_normalize_model_key(best_token)] = quota

    for block_name in re.findall(rf"{re.escape(ROW_MODEL_MARKER)}([^\n]+)", raw_text):
        name = block_name.strip()
        if not name:
            continue
        quota = _quota_from_row_marker_block(raw_text, name)
        if quota:
            model_quota[_normalize_model_key(name)] = quota

    return model_quota


def _find_quota_near_model_in_text(raw_text: str, model_name: str) -> str:
    lookup = quota_lookup_model_name(model_name)
    target = _canonical_model_key(lookup)
    if not target:
        return ""
    for token in _page_model_tokens(raw_text):
        if _canonical_model_key(token) != target and not (
            _canonical_model_key(token).endswith(target) or target.endswith(_canonical_model_key(token))
        ):
            continue
        for match in re.finditer(re.escape(token), raw_text, re.IGNORECASE):
            start = max(0, match.start() - 40)
            end = min(len(raw_text), match.end() + 280)
            snippet = raw_text[start:end]
            legacy = _find_quota_legacy(snippet, token)
            if legacy:
                return legacy
            line_match = _quota_match_on_line(snippet.replace("\n", " "))
            if line_match:
                return _quota_from_line_match(snippet, line_match)
    return ""


def _find_quota_legacy(raw_text: str, model_name: str) -> str:
    raw_text = normalize_page_text(raw_text)
    escaped = re.escape(quota_lookup_model_name(model_name))
    patterns = [
        rf"{escaped}[\s\S]{{0,120}}?(剩[^<\n\r/]*?[\d,]+(?:\.\d+)?\s*/\s*共?\s*[\d,]+(?:\.\d+)?)",
        rf"{escaped}[\s\S]{{0,120}}?(remaining[^<\n\r]*?[\d,]+(?:\.\d+)?)",
        rf"{escaped}[\s\S]{{0,80}}?([\d,]+(?:\.\d+)?\s*/\s*[\d,]+(?:\.\d+)?)",
    ]
    for pattern in patterns:
        m = re.search(pattern, raw_text, re.IGNORECASE)
        if m:
            return re.sub(r"\s+", " ", m.group(1)).strip()
    return ""


def _parse_remain_total(quota: str) -> tuple[float, float] | None:
    m = QUOTA_WITH_LABEL_RE.search(quota)
    if m:
        remain_s, total_s = m.group(1), m.group(2)
    else:
        m2 = QUOTA_BARE_RE.search(quota)
        if not m2:
            return None
        remain_s, total_s = m2.group(1), m2.group(2)
    try:
        remain = float(remain_s.replace(",", ""))
        total = float(total_s.replace(",", ""))
        return remain, total
    except ValueError:
        return None


def _quota_from_page_map(page_map: dict[str, str], lookup_name: str) -> str:
    key = _normalize_model_key(lookup_name)
    if key in page_map:
        return page_map[key]

    for map_key, quota in page_map.items():
        if map_key == key or map_key.endswith(key) or key.endswith(map_key):
            return quota

    canonical = _canonical_model_key(lookup_name)
    if not canonical:
        return ""

    canonical_to_quota: dict[str, str] = {}
    for map_key, quota in page_map.items():
        ck = _canonical_model_key(map_key)
        if ck and ck not in canonical_to_quota:
            canonical_to_quota[ck] = quota
    if canonical in canonical_to_quota:
        return canonical_to_quota[canonical]

    for ck, quota in canonical_to_quota.items():
        if ck.endswith(canonical) or canonical.endswith(ck):
            return quota
    return ""


def resolve_quota_for_model(raw_text: str, model_name: str) -> str:
    if not raw_text or not model_name:
        return ""

    raw_text = normalize_page_text(raw_text)
    lookup_name = quota_lookup_model_name(model_name)
    row_quota = _quota_from_row_marker_block(raw_text, lookup_name)
    if row_quota:
        return row_quota

    page_map = extract_model_quota_map(raw_text)
    matched = _quota_from_page_map(page_map, lookup_name)
    if matched:
        return matched

    listed = _model_listed_on_page(raw_text, lookup_name)

    legacy = _find_quota_legacy(raw_text, lookup_name)
    if not legacy:
        legacy = _find_quota_near_model_in_text(raw_text, lookup_name)
    if not legacy:
        legacy = _find_quota_after_model_marker(raw_text, lookup_name)
    if legacy:
        parsed = _parse_remain_total(legacy)
        if parsed:
            remain, total = parsed
            if total > 0 and remain >= total and _normalize_model_key(lookup_name) not in page_map:
                if not listed:
                    return _format_quota("0", _infer_default_total(raw_text))
        return legacy

    if not listed:
        total = _infer_default_total(raw_text)
        return _format_quota("0", total)

    return ""


def find_quota_for_model(raw_text: str, model_name: str) -> str:
    return resolve_quota_for_model(raw_text, model_name)


def rows_from_page_text(
    mappings: list[dict[str, str]],
    page_text: str,
    *,
    platform: str,
    method: str,
    empty_error: str,
) -> list[dict[str, str]]:
    page_text = normalize_page_text(page_text)
    results: list[dict[str, str]] = []
    for m in mappings:
        yaml_name = quota_lookup_model_name(str(m.get("model_name", "")))
        quota = resolve_quota_for_model(page_text, yaml_name)
        error = ""
        if not quota:
            if _model_listed_on_page(page_text, yaml_name):
                error = (
                    f"页面已列出「{yaml_name}」，但未能解析额度格式，请用 Playwright 重新抓取"
                )
            else:
                error = f"{empty_error}（YAML 检索名：{yaml_name}）"
        elif quota.startswith("剩 0 / 共") and not _model_listed_on_page(page_text, yaml_name):
            error = f"控制台未列出「{yaml_name}」（按未开通显示 0/共）"
        results.append(
            {
                "function": m["function"],
                "model_name": yaml_name,
                "platform": platform,
                "quota": quota,
                "method": method,
                "error": error,
            }
        )
    return results
