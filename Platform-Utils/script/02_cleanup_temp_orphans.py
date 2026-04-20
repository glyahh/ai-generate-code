"""
脚本 02：清理 temp 下“数据库不存在的应用”本地文件
==================================================

本项目 temp 目录结构（来自后端常量 AppConstant）：
- temp/code_output/  生成代码目录
  - html_{appId}/
  - multi_file_{appId}/
  - vue_project_{appId}/
- temp/code_deploy/   部署后的静态站点目录
  - {deployKey}/

这里的判断规则（严格按照你的描述）：
- 不关心 app.isDelete 是否为 1
- 只要 `app` 表里“没有这条记录”，就视为数据库不存在
  - code_output：解析目录名得到 appId；若 appId 不在 app 表任何记录中 => 删除该目录
  - code_deploy（可选）：目录名即 deployKey；若 deployKey 不在 app 表任何记录中 => 删除该目录

安全机制：
- 默认 dry-run：只打印将要删除的目录，不真正删除
- 传入 --apply 才会真正删除
- 严格限制：只允许删除 {tempRoot} 目录内部的内容（防止误删）

运行示例：
  $env:MYSQL_PASSWORD="你的密码"
  python script/02_cleanup_temp_orphans.py

真正删除：
  python script/02_cleanup_temp_orphans.py --apply

同时清理 code_deploy（推荐一起开）：
  python script/02_cleanup_temp_orphans.py --apply --include-deploy
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Set

# 兼容“从任意工作目录直接运行脚本”场景，确保可导入 script 包
_PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from script._common import (
    add_apply_args,
    add_db_args,
    add_temp_args,
    connect_mysql,
    delete_path,
    get_db_config_from_args,
    parse_app_id_from_code_output_dirname,
)


def fetch_all_app_ids_and_deploy_keys(db_cfg) -> tuple[Set[int], Set[str]]:
    """
    从 app 表读取所有 id 与 deployKey（包含 isDelete=1 的记录）。
    """

    app_ids: Set[int] = set()
    deploy_keys: Set[str] = set()

    conn = connect_mysql(db_cfg)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, deployKey FROM app")
            rows = cur.fetchall()
        conn.rollback()
    finally:
        conn.close()

    for r in rows:
        if r.get("id") is not None:
            app_ids.add(int(r["id"]))
        dk = r.get("deployKey")
        if dk:
            deploy_keys.add(str(dk))

    return app_ids, deploy_keys


def cleanup_code_output(temp_root: Path, app_ids_in_db: Set[int], *, apply: bool) -> int:
    """
    清理 temp/code_output 下的孤儿目录（数据库不存在的 appId）。
    返回删除数量。
    """

    code_output = temp_root / "code_output"
    if not code_output.is_dir():
        print(f"[SKIP] 目录不存在：{code_output}")
        return 0

    deleted = 0
    for p in code_output.iterdir():
        if not p.is_dir():
            continue
        app_id = parse_app_id_from_code_output_dirname(p.name)
        if app_id is None:
            # 不是后端约定的命名规则，谨慎起见不删除
            continue
        if app_id in app_ids_in_db:
            continue

        delete_path(p, allowed_root=temp_root, apply=apply)
        deleted += 1

    return deleted


def cleanup_code_deploy(temp_root: Path, deploy_keys_in_db: Set[str], *, apply: bool) -> int:
    """
    清理 temp/code_deploy 下的孤儿目录（数据库不存在的 deployKey）。
    返回删除数量。
    """

    code_deploy = temp_root / "code_deploy"
    if not code_deploy.is_dir():
        print(f"[SKIP] 目录不存在：{code_deploy}")
        return 0

    deleted = 0
    for p in code_deploy.iterdir():
        if not p.is_dir():
            continue
        deploy_key = p.name
        if deploy_key in deploy_keys_in_db:
            continue
        delete_path(p, allowed_root=temp_root, apply=apply)
        deleted += 1

    return deleted


def main() -> None:
    parser = argparse.ArgumentParser(description="清理 temp 下数据库不存在的应用目录（默认 dry-run）")
    add_db_args(parser)
    add_apply_args(parser)
    add_temp_args(parser)
    parser.add_argument(
        "--include-deploy",
        action="store_true",
        help="同时清理 temp/code_deploy 下 deployKey 不存在的目录（默认不清理）",
    )
    args = parser.parse_args()

    db_cfg = get_db_config_from_args(args)
    apply = bool(args.apply)
    temp_root = Path(args.temp_root).resolve()

    app_ids_in_db, deploy_keys_in_db = fetch_all_app_ids_and_deploy_keys(db_cfg)
    print(f"[INFO] 数据库 app 表记录数：{len(app_ids_in_db)}（deployKey 非空数：{len(deploy_keys_in_db)}）")
    print(f"[INFO] tempRoot：{temp_root}")

    deleted_output = cleanup_code_output(temp_root, app_ids_in_db, apply=apply)
    print(f"[INFO] code_output 清理数量：{deleted_output}")

    if args.include_deploy:
        deleted_deploy = cleanup_code_deploy(temp_root, deploy_keys_in_db, apply=apply)
        print(f"[INFO] code_deploy 清理数量：{deleted_deploy}")

    if not apply:
        print("[DRY-RUN] 未传入 --apply，本次不会真正删除。")
    else:
        print("[OK] 本地 temp 清理完成。")

# 非脚本入口
if __name__ == "__main__":
    main()

