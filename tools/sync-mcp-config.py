#!/usr/bin/env python3
"""
MCP Configuration Sync Tool
============================
一劳永逸的 MCP 配置同步方案

工作原理:
  1. 以项目根目录的 .mcp.json 为 **单一数据源 (Single Source of Truth)**
  2. 自动同步到 .cursor/mcp.json（Cursor 原生 MCP 系统使用）
  3. 确保 Claude Code CLI（终端）和 Cursor CC 插件使用同一份配置

使用方法:
  python tools/sync-mcp-config.py              # 检查差异并同步
  python tools/sync-mcp-config.py --check       # 仅检查差异，不做修改
  python tools/sync-mcp-config.py --force       # 强制用 .mcp.json 覆盖 .cursor/mcp.json

Cursor CC 插件 MCP 扫描机制（逆向分析 v2.1.173）:
  - 插件调用 spawnClaude() 启动 Claude CLI 子进程
  - CLI 子进程的 cwd = workspaceFolders[0] = 项目根目录
  - 自动读取项目根目录的 .mcp.json（mcp-project 源）
  - 同时读取 .claude/settings.local.json 中的 disabledMcpjsonServers
  - 插件额外注入 MCP: claude-vscode, chrome-devtools, debugger, jupyter
  - CLI 参数: --mcp-config <extension_mcp> --setting-sources=user,project,local
"""

import json
import os
import sys
import shutil
import subprocess
from pathlib import Path


# === 配置 ===
PROJECT_ROOT = Path(__file__).resolve().parent.parent
MCP_JSON = PROJECT_ROOT / ".mcp.json"
CURSOR_MCP_JSON = PROJECT_ROOT / ".cursor" / "mcp.json"
SETTINGS_LOCAL = PROJECT_ROOT / ".claude" / "settings.local.json"
GIT_HOOKS_DIR = PROJECT_ROOT / ".git" / "hooks"
PRE_COMMIT_HOOK = GIT_HOOKS_DIR / "pre-commit"

# 需要排除的敏感字段（不在 sync 中校验）
SENSITIVE_ENV_KEYS = {"api_key", "token", "secret", "password", "auth", "key"}
MASKED_ENV_VALUES = {"EDGEONE_PAGES_API_TOKEN", "X-API-Key", "Authorization", "FIRECRAWL_API_KEY",
                     "CONTEXT7_API_KEY"}


def load_json(path):
    """安全加载 JSON 文件"""
    if not path.exists():
        print(f"[WARN] 文件不存在: {path}")
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except json.JSONDecodeError as e:
        print(f"[ERROR] JSON 解析失败 {path}: {e}")
        return None


def save_json(path, data):
    """写出格式化的 JSON 文件"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def compare_mcp_servers(a_data, b_data):
    """比较两个 MCP 配置的差异（跳过敏感值）"""
    a_servers = a_data.get("mcpServers", {})
    b_servers = b_data.get("mcpServers", {})

    a_names = set(a_servers.keys())
    b_names = set(b_servers.keys())

    only_in_a = a_names - b_names
    only_in_b = b_names - a_names

    # 对同名服务器逐字段比较
    diff_fields = {}
    for name in a_names & b_names:
        a_srv = a_servers[name]
        b_srv = b_servers[name]

        # 标准化比较（忽略 env 中的敏感值）
        a_normalized = _normalize_for_compare(a_srv)
        b_normalized = _normalize_for_compare(b_srv)

        if a_normalized != b_normalized:
            diff_fields[name] = {
                "source": a_srv,
                "target": b_srv
            }

    return {
        "only_in_source": only_in_a,
        "only_in_target": only_in_b,
        "differing": diff_fields,
        "identical": len(a_names & b_names) - len(diff_fields)
    }


def _normalize_for_compare(server_config):
    """标准化服务器配置用于比较（屏蔽敏感值）"""
    result = {}
    for k, v in (server_config or {}).items():
        if k == "env" and isinstance(v, dict):
            result[k] = {ek: "***MASKED***" if ek in MASKED_ENV_VALUES else ev
                         for ek, ev in v.items()}
        elif k == "headers" and isinstance(v, dict):
            result[k] = {hk: "***MASKED***" if hk in MASKED_ENV_VALUES else hv
                         for hk, hv in v.items()}
        else:
            result[k] = v
    return result


def sync_to_cursor():
    """将 .mcp.json 同步到 .cursor/mcp.json"""
    mcp_data = load_json(MCP_JSON)
    if mcp_data is None:
        return False

    cursor_data = load_json(CURSOR_MCP_JSON)
    if cursor_data is None:
        return False

    # 保留 .cursor/mcp.json 中的非 mcpServers 字段（如有）
    for key in mcp_data:
        if key != "mcpServers":
            cursor_data[key] = mcp_data[key]

    # 同步 mcpServers
    cursor_data["mcpServers"] = mcp_data.get("mcpServers", {})

    save_json(CURSOR_MCP_JSON, cursor_data)
    return True


def check_settings_local():
    """检查 settings.local.json 中是否有 disabledMcpjsonServers 阻塞 MCP"""
    data = load_json(SETTINGS_LOCAL)
    if data is None:
        return {"blocked": False, "count": 0, "servers": []}

    disabled = data.get("disabledMcpjsonServers", [])
    if disabled:
        return {"blocked": True, "count": len(disabled), "servers": disabled}
    return {"blocked": False, "count": 0, "servers": []}


def fix_settings_local(auto_fix=False):
    """修复 settings.local.json 中的 disabledMcpjsonServers"""
    data = load_json(SETTINGS_LOCAL)
    if data is None:
        return False

    if "disabledMcpjsonServers" not in data:
        return True  # 没有此字段，无需修复

    if auto_fix:
        del data["disabledMcpjsonServers"]
        save_json(SETTINGS_LOCAL, data)
        print("[FIX] 已移除 disabledMcpjsonServers（解封 MCP 服务）")
        return True
    return False


def install_git_hook():
    """安装 git pre-commit hook 自动同步 MCP 配置"""
    hook_content = """#!/bin/sh
# MCP Config Sync Hook - 在每次 commit 前自动同步 MCP 配置
# 确保 .mcp.json (Claude Code) 和 .cursor/mcp.json (Cursor) 保持一致

MCP_SRC=".mcp.json"
MCP_DST=".cursor/mcp.json"

if [ -f "$MCP_SRC" ]; then
    # 检查两个文件是否一致（忽略排序差异）
    if ! diff <(jq -S . "$MCP_SRC" 2>/dev/null) <(jq -S . "$MCP_DST" 2>/dev/null) > /dev/null 2>&1; then
        echo "[MCP-SYNC] 检测到 MCP 配置变更，同步到 .cursor/mcp.json..."
        cp "$MCP_SRC" "$MCP_DST"
        git add "$MCP_DST"
        echo "[MCP-SYNC] 同步完成"
    fi
fi
"""
    # 检查是否有 jq 和 git
    try:
        subprocess.run(["git", "--version"], capture_output=True, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("[WARN] git 不可用，跳过 hook 安装")
        return False

    try:
        subprocess.run(["jq", "--version"], capture_output=True, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("[WARN] jq 未安装，hook 将使用 Python 替代")
        hook_content = """#!/usr/bin/env python3
import json, sys, subprocess
from pathlib import Path
root = Path(__file__).resolve().parent.parent.parent
src = root / ".mcp.json"
dst = root / ".cursor" / "mcp.json"
if src.exists():
    with open(src, "r") as f: src_data = json.load(f)
    if dst.exists():
        with open(dst, "r") as f: dst_data = json.load(f)
    else:
        dst_data = {}
    dst_data["mcpServers"] = src_data.get("mcpServers", {})
    dst.parent.mkdir(parents=True, exist_ok=True)
    with open(dst, "w") as f:
        json.dump(dst_data, f, indent=2, ensure_ascii=False)
        f.write("\\n")
    subprocess.run(["git", "add", str(dst)], check=False)
    print("[MCP-SYNC] .cursor/mcp.json 已同步")
"""

    try:
        PRE_COMMIT_HOOK.parent.mkdir(parents=True, exist_ok=True)
        PRE_COMMIT_HOOK.write_text(hook_content)
        PRE_COMMIT_HOOK.chmod(0o755)
        print(f"[OK] git pre-commit hook 已安装: {PRE_COMMIT_HOOK}")
        return True
    except Exception as e:
        print(f"[ERROR] 安装 hook 失败: {e}")
        return False


def print_status():
    """打印当前 MCP 系统状态"""
    print(f"\n{'=' * 60}")
    print(f"  MCP 配置状态报告")
    print(f"  项目: {PROJECT_ROOT.name}")
    print(f"{'=' * 60}")

    # 1. 检查文件存在性
    print(f"\n[1] 配置文件检查:")
    files = [
        (MCP_JSON, ".mcp.json (Claude Code)"),
        (CURSOR_MCP_JSON, ".cursor/mcp.json (Cursor)"),
        (SETTINGS_LOCAL, ".claude/settings.local.json (本地设置)"),
    ]
    for fpath, label in files:
        status = "OK" if fpath.exists() else "MISSING"
        mark = "[OK]" if fpath.exists() else "[!!]"
        print(f"  {mark} {label:45s} [{status}]")

    # 2. 检查 settings.local.json 阻塞
    print(f"\n[2] MCP 禁用状态检查:")
    blocked = check_settings_local()
    if blocked["blocked"]:
        print(f"  [BLOCKED] WARNING: disabledMcpjsonServers 列表中包含 {blocked['count']} 个 MCP 服务!")
        for s in blocked["servers"]:
            print(f"     - {s}")
        print(f"  → 这些服务在 Claude Code (含 Cursor 插件) 中将无法使用")
    else:
        print(f"  [OK] settings.local.json 中没有 disabledMcpjsonServers 阻塞，MCP 可正常加载")

    # 3. 比较两份配置
    print(f"\n[3] MCP 配置一致性检查:")
    mcp_data = load_json(MCP_JSON)
    cursor_data = load_json(CURSOR_MCP_JSON)
    if mcp_data and cursor_data:
        diff = compare_mcp_servers(mcp_data, cursor_data)
        if not diff["only_in_source"] and not diff["only_in_target"] and not diff["differing"]:
            print(f"  [OK] 两份配置完全一致（{diff['identical']} 个 MCP 服务器）")
        else:
            if diff["only_in_source"]:
                print(f"  ⚠ 仅在 .mcp.json 中: {diff['only_in_source']}")
            if diff["only_in_target"]:
                print(f"  ⚠ 仅在 .cursor/mcp.json 中: {diff['only_in_target']}")
            if diff["differing"]:
                print(f"  ⚠ 配置不同的服务器: {list(diff['differing'].keys())}")
            print(f"  → 运行 sync-mcp-config.py (不带 --check) 来同步")
    else:
        print(f"  - 无法读取配置进行比较")

    # 4. 检查 Cursor CC 插件版本
    print(f"\n[4] Cursor CC 插件检查:")
    ext_dir = Path.home() / ".cursor" / "extensions"
    if ext_dir.exists():
        versions = sorted([d.name for d in ext_dir.glob("anthropic.claude-code-*")])
        if versions:
            print(f"  已安装版本: {', '.join(v.replace('anthropic.claude-code-', '') for v in versions)}")
        else:
            print(f"  - 未找到 CC 插件")
    else:
        print(f"  - .cursor 扩展目录不存在")

    # 5. Cursor MCP 扫描机制
    print(f"\n[5] Cursor CC 插件 MCP 扫描路径:")
    print(f"  Claude Code CLI (.mcp.json):   {MCP_JSON}")
    print(f"  Cursor 原生 MCP (.cursor/):    {CURSOR_MCP_JSON}")
    print(f"  MCP 设置源:  user -> project -> local")
    print(f"  settings.local 状态:           {'已修复' if not blocked['blocked'] else '需要修复'}")

    print(f"\n{'=' * 60}\n")


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="MCP 配置同步工具 - 一劳永逸解决 Cursor CC 插件 MCP 扫描问题",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python tools/sync-mcp-config.py              # 检查差异并同步
  python tools/sync-mcp-config.py --check       # 仅检查，不做任何修改
  python tools/sync-mcp-config.py --force       # 强制覆盖同步
  python tools/sync-mcp-config.py --install-hook  # 安装 git pre-commit hook
  python tools/sync-mcp-config.py --fix-settings   # 修复 settings.local.json
  python tools/sync-mcp-config.py --status        # 打印完整状态报告
        """
    )
    parser.add_argument("--check", action="store_true", help="仅检查差异，不做修改")
    parser.add_argument("--force", action="store_true", help="强制用 .mcp.json 覆盖 .cursor/mcp.json")
    parser.add_argument("--install-hook", action="store_true", help="安装 git pre-commit hook 自动同步")
    parser.add_argument("--fix-settings", action="store_true", help="修复 settings.local.json 中的禁用列表")
    parser.add_argument("--status", action="store_true", help="打印完整状态报告")

    args = parser.parse_args()

    if args.status:
        print_status()
        return

    # 默认行为: 检查并同步
    if args.check:
        print("[CHECK] 仅检查模式...")
        print_status()
        return

    if args.install_hook:
        install_git_hook()
        return

    if args.fix_settings:
        print("[FIX] 检查 settings.local.json...")
        blocked = check_settings_local()
        if blocked["blocked"]:
            fix_settings_local(auto_fix=True)
        else:
            print("[OK] settings.local.json 无需修复")
        return

    # 同步流程
    print(f"[SYNC] MCP 配置同步工具")
    print(f"[SYNC] 源: {MCP_JSON}")
    print(f"[SYNC] 目标: {CURSOR_MCP_JSON}")

    # 1. 修复 settings
    print(f"\n[1/3] 检查 settings.local.json...")
    blocked = check_settings_local()
    if blocked["blocked"]:
        if not args.force:
            print(f"  [WARN] 发现 {blocked['count']} 个 MCP 服务被禁用!")
            print(f"  [INFO] 运行 --fix-settings 来修复")
            print(f"  [INFO] 或手动删除 disabledMcpjsonServers 字段")
        else:
            fix_settings_local(auto_fix=True)
    else:
        print(f"  [OK] 无阻塞")

    # 2. 同步 MCP 配置
    print(f"\n[2/3] 同步 MCP 配置...")
    mcp_data = load_json(MCP_JSON)
    cursor_data = load_json(CURSOR_MCP_JSON)
    if mcp_data and cursor_data:
        diff = compare_mcp_servers(mcp_data, cursor_data)
        needs_sync = bool(diff["only_in_source"] or diff["only_in_target"] or diff["differing"])
        if needs_sync or args.force:
            if sync_to_cursor():
                print(f"  [OK] 已同步到 {CURSOR_MCP_JSON}")
                print(f"  [INFO] 变更: only_in_source={diff['only_in_source']}, "
                      f"only_in_target={diff['only_in_target']}, "
                      f"different={len(diff['differing'])}")
            else:
                print(f"  [ERROR] 同步失败")
        else:
            print(f"  [OK] 两文件已一致 (无需同步)")

    # 3. 安装 git hook
    print(f"\n[3/3] 安装 git pre-commit hook...")
    if install_git_hook():
        print(f"  [OK] 以后每次 git commit 会自动同步 MCP 配置")
    else:
        print(f"  [-] hook 安装已跳过（非强制）")

    print(f"\n[完成] MCP 配置已同步！")
    print(f"       请重启 Cursor 或刷新 Claude Code 面板使更改生效")
    print(f"       或在终端运行: claude mcp list")


if __name__ == "__main__":
    main()
