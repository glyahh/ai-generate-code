"""
脚本 04：一次性清理 workflow 历史回显重复脏数据（MySQL + Redis）
===========================================================

用途：
1) 识别并处理 chat_history 中疑似被 workflow 重复噪声污染的 AI 消息
2) 备份原始消息到本地 SQL 文件（便于回滚）
3) 清理对应 appId 的 Redis chat memory key，触发后续从 MySQL 重建

默认 dry-run，仅打印将执行内容；传 --apply 才会真正写库/删 Redis。
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import subprocess
import sys
from pathlib import Path
from typing import Iterable

_PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from script._common import add_apply_args, add_db_args, connect_mysql, get_db_config_from_args


WORKFLOW_TOKEN = "[选择工具]"
TOOL_EXEC_TOKEN = "[工具调用]"
NOISE_TOKEN = "[workflow]"
DEFAULT_LONG_TEXT_THRESHOLD = 120_000
DEFAULT_DUP_HINT_THRESHOLD = 6


def _escape_sql_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "''")


def _iter_rows_in_chunks(items: list[dict], size: int) -> Iterable[list[dict]]:
    if size <= 0:
        size = 200
    for i in range(0, len(items), size):
        yield items[i : i + size]


def query_dirty_rows(conn, *, app_id: int | None, since: str | None, long_text_threshold: int, dup_hint_threshold: int):
    sql = """
    SELECT id, appId, userId, messageType, message, createTime
    FROM chat_history
    WHERE messageType = 'ai'
    """
    params: list[object] = []
    if app_id is not None:
        sql += " AND appId = %s"
        params.append(app_id)
    if since:
        sql += " AND createTime >= %s"
        params.append(since)

    # 仅抓取疑似脏数据：重复工具回显堆叠 / 异常超长 / 混入 workflow 噪声
    sql += """
      AND (
            LENGTH(message) >= %s
            OR (
                 (LENGTH(message) - LENGTH(REPLACE(message, %s, ''))) / LENGTH(%s) >= %s
               )
            OR (
                 (LENGTH(message) - LENGTH(REPLACE(message, %s, ''))) / LENGTH(%s) >= %s
               )
            OR INSTR(message, %s) > 0
      )
    ORDER BY appId ASC, createTime ASC, id ASC
    """
    params.extend(
        [
            long_text_threshold,
            WORKFLOW_TOKEN,
            WORKFLOW_TOKEN,
            dup_hint_threshold,
            TOOL_EXEC_TOKEN,
            TOOL_EXEC_TOKEN,
            dup_hint_threshold,
            NOISE_TOKEN,
        ]
    )

    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchall()


def sanitize_message(message: str, max_len: int) -> tuple[str, bool]:
    if not message:
        return "", False
    lines = message.splitlines()
    cleaned_lines: list[str] = []
    last_tool_request = ""
    removed = False

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("[workflow]") or stripped.startswith("[workflow_notice]"):
            removed = True
            continue
        if stripped.startswith("生成目录") or stripped.startswith("构建目录"):
            removed = True
            continue
        if stripped.startswith("[选择工具]"):
            if stripped == last_tool_request:
                removed = True
                continue
            last_tool_request = stripped
            cleaned_lines.append(line)
            continue
        cleaned_lines.append(line)

    cleaned = "\n".join(cleaned_lines).strip()
    if len(cleaned) > max_len:
        cleaned = cleaned[: max_len - 32] + "\n...[workflow message truncated]"
        removed = True

    if cleaned != message.strip():
        removed = True
    return cleaned, removed


def dump_backup_sql(rows: list[dict], output_dir: Path) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    ts = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_path = output_dir / f"workflow_dirty_history_backup_{ts}.sql"
    with backup_path.open("w", encoding="utf-8") as f:
        f.write("-- workflow dirty history backup\n")
        f.write("-- generated at: " + dt.datetime.now().isoformat() + "\n\n")
        for row in rows:
            msg = _escape_sql_text(str(row.get("message") or ""))
            f.write(
                "UPDATE chat_history SET message='{}' WHERE id={};\n".format(
                    msg,
                    int(row["id"]),
                )
            )
    return backup_path


def delete_redis_chat_memory_keys(app_ids: set[int], *, redis_host: str, redis_port: int, redis_password: str, apply: bool) -> None:
    if not app_ids:
        print("[REDIS] 无需清理：未命中 appId")
        return
    if not apply:
        print(f"[REDIS][DRY-RUN] 将按 appId 清理 chat memory key，appIds={sorted(app_ids)}")
        return

    if not redis_host or not redis_port:
        print("[REDIS][SKIP] 缺少 redis 配置，跳过 Redis 清理")
        return

    for app_id in sorted(app_ids):
        pattern = f"*{app_id}*"
        cmd = ["redis-cli", "-h", redis_host, "-p", str(redis_port), "--scan", "--pattern", pattern]
        if redis_password:
            cmd.extend(["-a", redis_password])
        try:
            scan = subprocess.run(cmd, capture_output=True, text=True, check=False)
        except FileNotFoundError:
            print("[REDIS][SKIP] 未找到 redis-cli，跳过 Redis 清理")
            return

        keys = [k.strip() for k in scan.stdout.splitlines() if k.strip() and "chat" in k.lower() and str(app_id) in k]
        if not keys:
            print(f"[REDIS] appId={app_id} 未匹配到 chat memory key")
            continue

        for key in keys:
            del_cmd = ["redis-cli", "-h", redis_host, "-p", str(redis_port), "DEL", key]
            if redis_password:
                del_cmd.extend(["-a", redis_password])
            res = subprocess.run(del_cmd, capture_output=True, text=True, check=False)
            if res.returncode == 0:
                print(f"[REDIS][OK] DEL {key} => {res.stdout.strip()}")
            else:
                print(f"[REDIS][WARN] DEL {key} 失败: {res.stderr.strip()}")


def main() -> None:
    parser = argparse.ArgumentParser(description="清理 workflow 历史回显重复脏数据（MySQL + Redis）")
    add_db_args(parser)
    add_apply_args(parser)
    parser.add_argument("--app-id", type=int, default=None, help="仅处理指定 appId")
    parser.add_argument("--since", default=None, help="仅处理 createTime >= 该时间（格式示例：2026-05-01 00:00:00）")
    parser.add_argument("--long-text-threshold", type=int, default=DEFAULT_LONG_TEXT_THRESHOLD, help="超长消息阈值")
    parser.add_argument("--dup-hint-threshold", type=int, default=DEFAULT_DUP_HINT_THRESHOLD, help="重复工具提示阈值")
    parser.add_argument(
        "--sanitize-max-len",
        type=int,
        default=80_000,
        help="清洗后消息最大长度（超过则截断）",
    )
    parser.add_argument(
        "--backup-dir",
        default=str((_PROJECT_ROOT / "script" / "backup").resolve()),
        help="备份 SQL 输出目录",
    )
    parser.add_argument("--redis-host", default=os.getenv("REDIS_HOST", "localhost"))
    parser.add_argument("--redis-port", type=int, default=int(os.getenv("REDIS_PORT", "6379")))
    parser.add_argument("--redis-password", default=os.getenv("REDIS_PASSWORD", ""))
    args = parser.parse_args()

    apply = bool(args.apply)
    db_cfg = get_db_config_from_args(args)
    backup_dir = Path(args.backup_dir).resolve()

    conn = connect_mysql(db_cfg)
    try:
        rows = query_dirty_rows(
            conn,
            app_id=args.app_id,
            since=args.since,
            long_text_threshold=max(1, int(args.long_text_threshold)),
            dup_hint_threshold=max(1, int(args.dup_hint_threshold)),
        )
        if not rows:
            print("[OK] 未命中疑似脏数据，无需处理")
            conn.rollback()
            return

        print(f"[INFO] 命中疑似脏数据 {len(rows)} 条")
        changed_rows: list[dict] = []
        app_ids: set[int] = set()
        for row in rows:
            old_msg = str(row.get("message") or "")
            new_msg, changed = sanitize_message(old_msg, max_len=max(1000, int(args.sanitize_max_len)))
            if not changed:
                continue
            changed_rows.append(
                {
                    "id": int(row["id"]),
                    "appId": int(row["appId"]),
                    "old": old_msg,
                    "new": new_msg,
                }
            )
            app_ids.add(int(row["appId"]))

        if not changed_rows:
            print("[OK] 命中记录均无需变更")
            conn.rollback()
            return

        print(f"[INFO] 实际需清洗 {len(changed_rows)} 条，涉及 appId={sorted(app_ids)}")
        backup_rows = [{"id": item["id"], "message": item["old"]} for item in changed_rows]
        backup_path = dump_backup_sql(backup_rows, backup_dir)
        print(f"[INFO] 已生成回滚备份: {backup_path}")

        if not apply:
            print("[DRY-RUN] 未传 --apply，本次不写入数据库、不删除 Redis")
            conn.rollback()
            delete_redis_chat_memory_keys(
                app_ids,
                redis_host=args.redis_host,
                redis_port=args.redis_port,
                redis_password=args.redis_password,
                apply=False,
            )
            return

        with conn.cursor() as cur:
            for part in _iter_rows_in_chunks(changed_rows, 200):
                for item in part:
                    cur.execute(
                        "UPDATE chat_history SET message = %s WHERE id = %s",
                        (item["new"], item["id"]),
                    )

        conn.commit()
        print("[OK] MySQL 脏数据清洗完成")

        delete_redis_chat_memory_keys(
            app_ids,
            redis_host=args.redis_host,
            redis_port=args.redis_port,
            redis_password=args.redis_password,
            apply=True,
        )
        print("[OK] Redis chat memory 清理完成")
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()

