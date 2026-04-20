"""
脚本 03：一键执行脚本 01 + 脚本 02
================================

为什么建议按这个顺序：
- 先清库（删除 app 记录），再扫本地 temp：
  这样“刚被清库的 appId / deployKey”会被视为数据库不存在，从而本地目录也能被扫掉。

安全机制：
- 默认 dry-run（只打印，不执行删除）
- 传入 --apply 才会真正执行

运行示例：
  $env:MYSQL_PASSWORD="你的密码"
  python script/03_one_click_cleanup.py

真正一键清理（推荐同时带上 --include-deploy）：
  python script/03_one_click_cleanup.py --apply --include-deploy
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 兼容“从任意工作目录直接运行脚本”场景，确保可导入 script 包
_PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from script._common import add_apply_args, add_db_args, add_temp_args, get_db_config_from_args
from script._common import connect_mysql
from script._common import chunked, to_int_list
from script._common import delete_path, parse_app_id_from_code_output_dirname
from script._common import delete_app_fk_children


def step_01_delete_apps_from_db(db_cfg, *, apply: bool) -> list[int]:
    """
    复用脚本 01 的核心逻辑：查 app.isDelete=1，并按需物理删除。
    返回：被删除（或将被删除）的 appId 列表
    """

    conn = connect_mysql(db_cfg)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, appName, userId, deployKey, updateTime FROM app WHERE isDelete = 1")
            rows = cur.fetchall()

        app_ids = to_int_list(rows, "id")
        if not app_ids:
            print("[STEP01] 未找到 app.isDelete=1 的记录。")
            conn.rollback()
            return []

        print(f"[STEP01] 找到 {len(app_ids)} 条 app.isDelete=1 的记录：")
        for r in rows:
            print(
                f"  - id={r.get('id')}, appName={r.get('appName')}, userId={r.get('userId')}, "
                f"deployKey={r.get('deployKey')}, updateTime={r.get('updateTime')}"
            )

        if not apply:
            print("[STEP01][DRY-RUN] 未传入 --apply，本步骤不会真正删除（将删除：user_app_apply + chat_history + app）。")
            conn.rollback()
            return app_ids

        with conn.cursor() as cur:
            # 先删除所有外键子表（动态扫描 information_schema），否则 app 会因为外键约束无法删除
            fk_deleted = delete_app_fk_children(conn, schema=db_cfg.database, app_ids=app_ids)
            if fk_deleted:
                for k, v in fk_deleted.items():
                    print(f"[STEP01] 已删除 FK child 记录数：{k}={v}")

            # 先删除 chat_history，避免留下孤儿历史记录
            total_deleted = 0
            for part in chunked(app_ids, 500):
                placeholders = ",".join(["%s"] * len(part))
                cur.execute(f"DELETE FROM chat_history WHERE appId IN ({placeholders})", list(part))
                total_deleted += cur.rowcount
            print(f"[STEP01] 已删除 chat_history 记录数：{total_deleted}")

            total_apps_deleted = 0
            for part in chunked(app_ids, 500):
                placeholders = ",".join(["%s"] * len(part))
                cur.execute(f"DELETE FROM app WHERE id IN ({placeholders})", list(part))
                total_apps_deleted += cur.rowcount
            print(f"[STEP01] 已删除 app 记录数：{total_apps_deleted}")

        conn.commit()
        print("[STEP01] 数据库删除完成。")
        return app_ids
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def fetch_all_app_ids_and_deploy_keys(db_cfg) -> tuple[set[int], set[str]]:
    conn = connect_mysql(db_cfg)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, deployKey FROM app")
            rows = cur.fetchall()
        conn.rollback()
    finally:
        conn.close()

    app_ids: set[int] = set()
    deploy_keys: set[str] = set()
    for r in rows:
        if r.get("id") is not None:
            app_ids.add(int(r["id"]))
        dk = r.get("deployKey")
        if dk:
            deploy_keys.add(str(dk))
    return app_ids, deploy_keys


def step_02_cleanup_temp(db_cfg, temp_root: Path, *, apply: bool, include_deploy: bool) -> None:
    app_ids_in_db, deploy_keys_in_db = fetch_all_app_ids_and_deploy_keys(db_cfg)
    print(f"[STEP02] 数据库 app 表剩余记录数：{len(app_ids_in_db)}（deployKey 非空数：{len(deploy_keys_in_db)}）")

    # code_output
    code_output = temp_root / "code_output"
    if code_output.is_dir():
        deleted = 0
        for p in code_output.iterdir():
            if not p.is_dir():
                continue
            app_id = parse_app_id_from_code_output_dirname(p.name)
            if app_id is None or app_id in app_ids_in_db:
                continue
            delete_path(p, allowed_root=temp_root, apply=apply)
            deleted += 1
        print(f"[STEP02] code_output 清理数量：{deleted}")
    else:
        print(f"[STEP02][SKIP] 目录不存在：{code_output}")

    # code_deploy
    if include_deploy:
        code_deploy = temp_root / "code_deploy"
        if code_deploy.is_dir():
            deleted = 0
            for p in code_deploy.iterdir():
                if not p.is_dir():
                    continue
                if p.name in deploy_keys_in_db:
                    continue
                delete_path(p, allowed_root=temp_root, apply=apply)
                deleted += 1
            print(f"[STEP02] code_deploy 清理数量：{deleted}")
        else:
            print(f"[STEP02][SKIP] 目录不存在：{code_deploy}")


def main() -> None:
    parser = argparse.ArgumentParser(description="一键执行：删库(app.isDelete=1) + 清理本地 temp 孤儿目录（默认 dry-run）")
    add_db_args(parser)
    add_apply_args(parser)
    add_temp_args(parser)
    parser.add_argument("--include-deploy", action="store_true", help="同时清理 temp/code_deploy")
    args = parser.parse_args()

    db_cfg = get_db_config_from_args(args)
    apply = bool(args.apply)
    temp_root = Path(args.temp_root).resolve()

    print(f"[INFO] tempRoot：{temp_root}")
    if not apply:
        print("[INFO] 当前为 DRY-RUN（只打印，不执行删除），加 --apply 才会真正清理。")

    step_01_delete_apps_from_db(db_cfg, apply=apply)
    step_02_cleanup_temp(db_cfg, temp_root, apply=apply, include_deploy=bool(args.include_deploy))

    if apply:
        print("[OK] 一键清理完成。")

# 非脚本入口
if __name__ == "__main__":
    main()

