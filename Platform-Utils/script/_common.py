"""
清理脚本公共工具（仅供 script/ 下脚本使用）
------------------------------------------------
本文件提供：
1) MySQL 连接创建（pymysql）
2) 统一的 dry-run / apply 行为
3) 统一的“仅允许在 temp 目录下删除”的安全校验

注意：
- 为了让你在 IDE 里点“运行小箭头”也能直接跑：
  - 脚本会自动读取 `script/.env` 或 `script/.env.local`（二选一）作为本地私有配置
  - 如果你没配置 .env，脚本会尝试从后端 `src/main/resources/application-local.yml` 读取本地数据库账号密码
  - 以上读取都不会覆盖你手动传入的命令行参数或环境变量
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional, Sequence, Tuple

try:
    import pymysql
except ModuleNotFoundError as exc:
    raise ModuleNotFoundError(
        "缺少依赖 `pymysql`。请先安装：\n"
        "  python -m pip install -r script/requirements.txt\n"
        "若使用虚拟环境，请确保使用该虚拟环境对应的 python 执行上述命令。"
    ) from exc


@dataclass(frozen=True)
class DbConfig:
    """数据库连接配置（MySQL）。"""

    host: str
    port: int
    user: str
    password: str
    database: str


def build_default_project_root() -> Path:
    """
    推断项目根目录：
    - 约定脚本位于 {projectRoot}/script/ 下，所以 projectRoot = script/ 的上一级。
    """

    return Path(__file__).resolve().parents[1]


def build_default_temp_root(project_root: Path) -> Path:
    """默认 temp 目录：{projectRoot}/temp"""

    return (project_root / "temp").resolve()


def _set_env_if_absent(key: str, value: str) -> None:
    """仅当环境变量不存在/为空时才写入，避免覆盖用户显式配置。"""

    if value is None:
        return
    if os.getenv(key):
        return
    value = str(value).strip()
    if not value:
        return
    os.environ[key] = value


def _load_env_file(env_path: Path) -> None:
    """
    加载一个简单的 .env 文件（KEY=VALUE，每行一个）。
    - 支持注释行：以 # 开头
    - 支持空行
    - 不解析复杂语法（对本项目足够）
    """

    try:
        text = env_path.read_text(encoding="utf-8")
    except Exception:
        return

    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        v = v.strip().strip('"').strip("'")
        if not k:
            continue
        _set_env_if_absent(k, v)


def _try_load_mysql_from_application_local_yml(project_root: Path) -> None:
    """
    兜底读取后端本地配置：src/main/resources/application-local.yml
    目标字段：
    - spring.datasource.username
    - spring.datasource.password
    - spring.datasource.url（可解析 host/port/dbName，但这里先只兜底账号密码）
    """

    yml_path = project_root / "src" / "main" / "resources" / "application-local.yml"
    if not yml_path.is_file():
        return

    try:
        text = yml_path.read_text(encoding="utf-8")
    except Exception:
        return

    # 非严格 YAML 解析：用正则抓取最常见的两行
    m_user = re.search(r"^\s*username:\s*(.+?)\s*$", text, re.MULTILINE)
    m_pwd = re.search(r"^\s*password:\s*(.+?)\s*$", text, re.MULTILINE)

    if m_user:
        _set_env_if_absent("MYSQL_USER", m_user.group(1).strip().strip('"').strip("'"))
    if m_pwd:
        _set_env_if_absent("MYSQL_PASSWORD", m_pwd.group(1).strip().strip('"').strip("'"))


def load_local_db_defaults() -> None:
    """
    为“点 IDE 运行按钮”场景准备默认值（不覆盖显式参数/环境变量）：
    1) 先读 script/.env.local（如果存在）
    2) 再读 script/.env（如果存在）
    3) 再兜底读 application-local.yml（如果存在）
    """

    project_root = build_default_project_root()
    script_dir = project_root / "script"

    # 优先 .env.local（更私有），再 .env
    env_local = script_dir / ".env.local"
    env_plain = script_dir / ".env"
    if env_local.is_file():
        _load_env_file(env_local)
    if env_plain.is_file():
        _load_env_file(env_plain)

    # 如果还没有密码，再兜底读取 application-local.yml
    if not os.getenv("MYSQL_PASSWORD"):
        _try_load_mysql_from_application_local_yml(project_root)


# 模块导入时自动加载（不会覆盖用户显式配置）
load_local_db_defaults()


def add_db_args(parser: argparse.ArgumentParser) -> None:
    """
    添加通用数据库参数。
    读取优先级：命令行参数 > 环境变量 > 默认值
    """

    parser.add_argument("--db-host", default=os.getenv("MYSQL_HOST", "localhost"), help="MySQL 主机（默认 localhost）")
    parser.add_argument("--db-port", type=int, default=int(os.getenv("MYSQL_PORT", "3306")), help="MySQL 端口（默认 3306）")
    parser.add_argument("--db-user", default=os.getenv("MYSQL_USER", "root"), help="MySQL 用户名（默认 root）")
    parser.add_argument(
        "--db-password",
        default=os.getenv("MYSQL_PASSWORD", ""),
        help="MySQL 密码（建议用环境变量 MYSQL_PASSWORD；命令行会被 shell 历史记录）",
    )
    parser.add_argument("--db-name", default=os.getenv("MYSQL_DB", "gly_ai_generate_code"), help="数据库名（默认 gly_ai_generate_code）")


def get_db_config_from_args(args: argparse.Namespace) -> DbConfig:
    return DbConfig(
        host=args.db_host,
        port=args.db_port,
        user=args.db_user,
        password=args.db_password,
        database=args.db_name,
    )


def connect_mysql(cfg: DbConfig) -> pymysql.connections.Connection:
    """
    创建 MySQL 连接。

    - autocommit=False：让脚本能显式控制事务（避免只删一半）
    - cursorclass=DictCursor：结果以 dict 形式返回，便于阅读
    """

    return pymysql.connect(
        host=cfg.host,
        port=cfg.port,
        user=cfg.user,
        password=cfg.password,
        database=cfg.database,
        charset="utf8mb4",
        autocommit=False,
        cursorclass=pymysql.cursors.DictCursor,
    )


_SAFE_SQL_IDENT = re.compile(r"^[A-Za-z0-9_]+$")


def find_app_fk_children(conn: pymysql.connections.Connection, schema: str) -> list[tuple[str, str]]:
    """
    查询所有引用 app(id) 的外键子表，返回 [(tableName, columnName), ...]。

    说明：
    - 你遇到的 1451 报错就是典型的“子表外键未清理导致父表无法删除”
    - 这里用 information_schema 动态发现依赖，避免未来新增表后脚本失效
    """

    sql = """
        SELECT TABLE_NAME, COLUMN_NAME
        FROM information_schema.KEY_COLUMN_USAGE
        WHERE REFERENCED_TABLE_SCHEMA = %s
          AND REFERENCED_TABLE_NAME = 'app'
          AND REFERENCED_COLUMN_NAME = 'id'
          AND TABLE_NAME IS NOT NULL
          AND COLUMN_NAME IS NOT NULL
    """

    with conn.cursor() as cur:
        cur.execute(sql, (schema,))
        rows = cur.fetchall()

    children: list[tuple[str, str]] = []
    for r in rows:
        table = str(r["TABLE_NAME"])
        col = str(r["COLUMN_NAME"])
        # 额外做一次白名单校验，避免 SQL 标识符注入
        if not _SAFE_SQL_IDENT.match(table) or not _SAFE_SQL_IDENT.match(col):
            continue
        children.append((table, col))
    return children


def delete_app_fk_children(
    conn: pymysql.connections.Connection,
    *,
    schema: str,
    app_ids: Sequence[int],
    chunk_size: int = 500,
) -> dict[str, int]:
    """
    删除所有外键子表中引用 appIds 的记录。
    返回：{ "table.column": deletedCount }
    """

    children = find_app_fk_children(conn, schema)
    if not children:
        return {}

    result: dict[str, int] = {}
    with conn.cursor() as cur:
        for table, col in children:
            deleted = 0
            for part in chunked(list(app_ids), chunk_size):
                placeholders = ",".join(["%s"] * len(part))
                cur.execute(f"DELETE FROM `{table}` WHERE `{col}` IN ({placeholders})", list(part))
                deleted += cur.rowcount
            result[f"{table}.{col}"] = deleted
    return result


def add_apply_args(parser: argparse.ArgumentParser) -> None:
    """
    添加通用执行开关：
    - 默认 dry-run：只打印将要执行的删除动作，不做实际删除
    - 传入 --apply：才真正删除
    """

    parser.add_argument(
        "--apply",
        action="store_true",
        help="真正执行删除（默认不删除，只打印将要删除的内容）",
    )


def add_temp_args(parser: argparse.ArgumentParser) -> None:
    """
    添加 temp 路径参数（支持自定义）。
    默认使用 {projectRoot}/temp
    """

    project_root = build_default_project_root()
    default_temp = build_default_temp_root(project_root)
    parser.add_argument(
        "--temp-root",
        default=str(default_temp),
        help=f"temp 根目录（默认 {default_temp}）",
    )


def ensure_safe_delete_target(path: Path, allowed_root: Path) -> None:
    """
    安全删除校验：只允许删除 allowed_root 目录内的路径，避免误删到系统盘。
    """

    allowed_root = allowed_root.resolve()
    path = path.resolve()
    try:
        path.relative_to(allowed_root)
    except ValueError as e:
        raise RuntimeError(f"拒绝删除：目标不在允许范围内，target={path}, allowed_root={allowed_root}") from e


def delete_path(path: Path, *, allowed_root: Path, apply: bool) -> None:
    """
    删除文件/目录：
    - dry-run（apply=False）：只打印
    - apply=True：实际删除
    """

    ensure_safe_delete_target(path, allowed_root)

    if not path.exists():
        print(f"[SKIP] 不存在：{path}")
        return

    if not apply:
        print(f"[DRY-RUN] 将删除：{path}")
        return

    if path.is_dir():
        shutil.rmtree(path, ignore_errors=False)
        print(f"[OK] 已删除目录：{path}")
        return

    path.unlink()
    print(f"[OK] 已删除文件：{path}")


_CODE_OUTPUT_APPID_PATTERN = re.compile(r"^(?P<prefix>.+?)_(?P<appid>\d+)$")
_CODE_OUTPUT_VUE_APPID_PATTERN = re.compile(r"^(?P<prefix>.+?)_project_(?P<appid>\d+)$")


def parse_app_id_from_code_output_dirname(dir_name: str) -> Optional[int]:
    """
    从 code_output 下目录名解析 appId。

    后端命名规则（参考 AppController / AppServiceImpl）：
    - HTML / MULTI_FILE：{type}_{appId}  例如 html_123、multi_file_123
    - VUE：{type}_project_{appId}       例如 vue_project_123
    """

    m = _CODE_OUTPUT_VUE_APPID_PATTERN.match(dir_name)
    if m:
        return int(m.group("appid"))
    m = _CODE_OUTPUT_APPID_PATTERN.match(dir_name)
    if m:
        return int(m.group("appid"))
    return None


def chunked(seq: Sequence, size: int) -> Iterable[Sequence]:
    """把序列按 size 分块，便于拼接 SQL 的 IN (...) 参数。"""

    if size <= 0:
        raise ValueError("size must be > 0")
    for i in range(0, len(seq), size):
        yield seq[i : i + size]


def to_int_list(rows: Iterable[dict], key: str) -> list[int]:
    """从 dict 行结果中提取 int 列表。"""

    out: list[int] = []
    for r in rows:
        v = r.get(key)
        if v is None:
            continue
        out.append(int(v))
    return out

