"""
脚本 01：从数据库物理删除已被用户删除的应用（app.isDelete=1）
========================================================

重要说明（务必读）：
- 本项目后端默认使用“逻辑删除”（isDelete），正常不会物理删除。
  你现在的需求是“清库”，所以脚本会执行真实 DELETE。
- 为避免误删，脚本默认是 dry-run（只打印将删除的内容，不执行）。
  需要传入 --apply 才会真正删除。
- 为避免留下孤儿数据：只要真正执行（--apply），本脚本会同时删除 chat_history 里对应 appId 的记录。

运行方式（Windows PowerShell 示例）：
1) 安装依赖：
   pip install -r script/requirements.txt

2) dry-run 预览将删除哪些记录：
   python script/01_purge_deleted_apps_from_db.py --db-password "你的密码"

3) 真正执行删除：
   python script/01_purge_deleted_apps_from_db.py --db-password "你的密码" --apply

环境变量方式（推荐，避免密码进历史）：
  $env:MYSQL_PASSWORD="你的密码"
  python script/01_purge_deleted_apps_from_db.py --apply
"""

from __future__ import annotations

import argparse
from typing import List

from script._common import (
    add_apply_args,
    add_db_args,
    chunked,
    connect_mysql,
    get_db_config_from_args,
    to_int_list,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="从数据库物理删除 app.isDelete=1 的记录（默认 dry-run）")
    add_db_args(parser)
    add_apply_args(parser)
    args = parser.parse_args()

    db_cfg = get_db_config_from_args(args)
    apply = bool(args.apply)

    conn = connect_mysql(db_cfg)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, appName, userId, deployKey, updateTime FROM app WHERE isDelete = 1")
            rows = cur.fetchall()

        app_ids: List[int] = to_int_list(rows, "id")

        if not app_ids:
            print("[OK] 未找到 app.isDelete=1 的记录，无需清理。")
            conn.rollback()
            return

        print(f"[INFO] 找到 {len(app_ids)} 条 app.isDelete=1 的记录：")
        for r in rows:
            print(
                f"  - id={r.get('id')}, appName={r.get('appName')}, userId={r.get('userId')}, "
                f"deployKey={r.get('deployKey')}, updateTime={r.get('updateTime')}"
            )

        if not apply:
            print("[DRY-RUN] 未传入 --apply，本次不会真正删除（将删除：chat_history + app）。")
            conn.rollback()
            return

        # 真正执行删除（使用事务保证一致性）
        with conn.cursor() as cur:
            # 先删除 chat_history，避免留下孤儿历史记录
            # 分块删除，避免 IN (...) 参数过长
            total_deleted = 0
            for part in chunked(app_ids, 500):
                placeholders = ",".join(["%s"] * len(part))
                cur.execute(f"DELETE FROM chat_history WHERE appId IN ({placeholders})", list(part))
                total_deleted += cur.rowcount
            print(f"[OK] 已删除 chat_history 记录数：{total_deleted}")

            total_apps_deleted = 0
            for part in chunked(app_ids, 500):
                placeholders = ",".join(["%s"] * len(part))
                cur.execute(f"DELETE FROM app WHERE id IN ({placeholders})", list(part))
                total_apps_deleted += cur.rowcount
            print(f"[OK] 已删除 app 记录数：{total_apps_deleted}")

        conn.commit()
        print("[OK] 数据库清理完成。")
    except Exception as e:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()

