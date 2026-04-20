"""
脚本 01：从数据库物理删除已被用户删除的应用（app.isDelete=1）
========================================================

重要说明（务必读）：
- 本项目后端默认使用“逻辑删除”（isDelete），正常不会物理删除。
  你现在的需求是“清库”，所以脚本会执行真实 DELETE。
- 为避免误删，脚本默认是 dry-run（只打印将删除的内容，不执行）。
  需要传入 --apply 才会真正删除。
- 为避免留下孤儿数据：只要真正执行（--apply），本脚本会按顺序删除：
  user_app_apply（外键子表）→ chat_history → app。

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
import sys
from pathlib import Path
from typing import List

# 兼容“从任意工作目录直接运行脚本”场景，确保可导入 script 包
_PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from script._common import (
    add_apply_args,
    add_db_args,
    chunked,
    connect_mysql,
    delete_app_fk_children,
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
            print("[DRY-RUN] 未传入 --apply，本次不会真正删除（将删除：user_app_apply + chat_history + app）。")
            conn.rollback()
            return

        # 真正执行删除（使用事务保证一致性）
        with conn.cursor() as cur:
            # 先删除所有外键子表（动态扫描 information_schema），否则 app 会因为外键约束无法删除
            fk_deleted = delete_app_fk_children(conn, schema=db_cfg.database, app_ids=app_ids)
            if fk_deleted:
                for k, v in fk_deleted.items():
                    print(f"[OK] 已删除 FK child 记录数：{k}={v}")

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

# 非脚本入口
if __name__ == "__main__":
    main()

