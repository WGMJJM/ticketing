#!/usr/bin/env python3
"""仅为 ticketing-jmeter 专用 MySQL 项目创建真实购票链路所需的隔离夹具。"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import secrets
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pymysql


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENV = ROOT / ".local" / "jmeter" / "compose.env"
PROJECT = "ticketing-jmeter"
DATABASE = "ticketingjmeter"


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError("运行配置必须是 KEY=value 格式")
        values[key] = value
    return values


def run(command: list[str]) -> str:
    result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
    return result.stdout.strip()


def verify_docker_volume(env_file: Path) -> dict[str, object]:
    compose = [
        "docker",
        "compose",
        "--project-name",
        PROJECT,
        "--env-file",
        str(env_file),
        "--file",
        str(ROOT / "deploy" / "compose.yml"),
    ]
    container_id = run(compose + ["ps", "--status", "running", "--quiet", "mysql"])
    if not container_id:
        raise RuntimeError("ticketing-jmeter 的 MySQL 容器未运行")
    container = json.loads(run(["docker", "inspect", container_id]))[0]
    labels = container.get("Config", {}).get("Labels") or {}
    if labels.get("com.docker.compose.project") != PROJECT or labels.get("com.docker.compose.service") != "mysql":
        raise RuntimeError("MySQL 容器 Compose 项目或服务标签不匹配")
    mounts = [mount for mount in container.get("Mounts", []) if mount.get("Destination") == "/var/lib/mysql"]
    if len(mounts) != 1 or mounts[0].get("Type") != "volume" or PROJECT not in mounts[0].get("Name", ""):
        raise RuntimeError("MySQL 数据卷不属于 ticketing-jmeter；拒绝准备测试数据")
    return {"containerId": container_id, "volume": mounts[0]["Name"]}


def verify_guard(connection: pymysql.Connection, token: str) -> None:
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    with connection.cursor() as cursor:
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS jmeter_environment_guard (
                id TINYINT NOT NULL PRIMARY KEY,
                token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                CONSTRAINT ck_jmeter_guard_id CHECK (id = 1)
            ) ENGINE=InnoDB
        """)
        cursor.execute("SELECT token_sha256 FROM jmeter_environment_guard WHERE id=1")
        row = cursor.fetchone()
        if row is None:
            cursor.execute(
                "INSERT INTO jmeter_environment_guard(id,token_sha256) VALUES(1,%s)",
                (token_hash,),
            )
        elif row[0] != token_hash:
            raise RuntimeError("隔离数据库 guard 与本地随机标记不一致")


def write_csv(path: Path, rows: list[tuple[str, str]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(["username", "password"])
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description="仅初始化隔离购票账号、合法活动场次和足量库存")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV)
    parser.add_argument("--users", type=int, default=1, help="新建测试账号数；每用户请求预算由 --orders-per-user 指定")
    parser.add_argument("--warmup-users", type=int, default=0, help="预热专用账号数，与正式测试账号隔离")
    parser.add_argument("--bucket-count",type=int,choices=(0,4,8,16,32),default=0,help="仅新建空票档；0为SINGLE")
    parser.add_argument("--orders-per-user",type=int,choices=(1,2,3,4),default=1,help="按真实场次限购预留库存，不改变限购")
    parser.add_argument("--stop-file",type=Path,help="隔离初始化监控的停建标志")
    args = parser.parse_args()
    if args.users < 1 or args.warmup_users < 0 or args.users + args.warmup_users > 60000:
        parser.error("正式账号至少 1 个；账号总数不得超过 60000")
    env_path = args.env_file.resolve()
    env = read_env(env_path)
    if env.get("MYSQL_HOST") != "127.0.0.1" or env.get("MYSQL_PORT") != "13307" or env.get("MYSQL_DATABASE") != DATABASE:
        raise RuntimeError("只接受 127.0.0.1:13307/ticketingjmeter 隔离数据库")
    if not env.get("MYSQL_ROOT_PASSWORD") or not env.get("JMETER_GUARD_TOKEN"):
        raise RuntimeError("隔离环境缺少 root 凭据或随机 guard")
    if not env.get("ADMIN_BOOTSTRAP_USERNAME"):
        raise RuntimeError("隔离管理员引导用户名缺失")

    docker = verify_docker_volume(env_path)
    connection = pymysql.connect(
        host="127.0.0.1",
        port=13307,
        user="root",
        password=env["MYSQL_ROOT_PASSWORD"],
        database=DATABASE,
        charset="utf8mb4",
        autocommit=False,
        cursorclass=pymysql.cursors.Cursor,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT DATABASE(), VERSION(), UTC_TIMESTAMP(6)")
            db_name, db_version, now = cursor.fetchone()
            if db_name != DATABASE:
                raise RuntimeError("当前 schema 不是 ticketing_jmeter")
            cursor.execute("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")
            latest = cursor.fetchone()
            if not latest or str(latest[0]) != "17":
                raise RuntimeError("隔离应用迁移尚未完成 V17")
        verify_guard(connection, env["JMETER_GUARD_TOKEN"])
        with connection.cursor() as cursor:
            for table in ("activity", "activity_session", "ticket_sku", "ticket_order", "purchase_request"):
                cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
                if cursor.fetchone()[0] != 0:
                    raise RuntimeError(f"隔离库 {table} 已有数据；初始化器拒绝覆盖或清理")
            cursor.execute("SELECT id,password_hash,status,role FROM user_account WHERE username=%s", (env["ADMIN_BOOTSTRAP_USERNAME"],))
            seed = cursor.fetchone()
            if not seed or seed[2] != "ACTIVE" or seed[3] != "ADMIN" or not str(seed[1]).startswith("$2"):
                raise RuntimeError("尚未找到有效的隔离管理员 BCrypt 引导账号；先启动专用后端")
            cursor.execute("SELECT COUNT(*) FROM user_account")
            if cursor.fetchone()[0] != 1:
                raise RuntimeError("隔离库包含引导账号之外的用户；拒绝猜测或重用账号")
            password_hash = seed[1]

        batch = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "_" + secrets.token_hex(4)
        password = env.get("ADMIN_BOOTSTRAP_PASSWORD")
        if not password:
            raise RuntimeError("缺少本地隔离管理员种子口令")
        cohorts: dict[str, list[tuple[str, str]]] = {"warmup": [], "measure": []}
        for cohort, count in (("warmup", args.warmup_users), ("measure", args.users)):
            for index in range(1, count + 1):
                username = f"jm_{batch}_{'w' if cohort == 'warmup' else 'm'}_{index:04d}"
                email = f"{username}@example.invalid"
                cohorts[cohort].append((username, password))

        stock = (args.users + args.warmup_users) * args.orders_per_user
        sale_start = now - timedelta(minutes=5)
        starts_at = now + timedelta(days=7)
        ends_at = starts_at + timedelta(hours=2)
        sale_end = starts_at - timedelta(minutes=1)
        with connection.cursor() as cursor:
            for rows in cohorts.values():
                for offset in range(0,len(rows),500):
                    if args.stop_file and args.stop_file.exists(): raise RuntimeError('建数资源保护触发，回滚未提交夹具')
                    cursor.executemany(
                        """INSERT INTO user_account(username,email,email_verified_at,password_hash,status,role,created_at,updated_at)
                           VALUES(%s,%s,%s,%s,'ACTIVE','USER',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))""",
                        [(username, f"{username}@example.invalid", now, password_hash) for username, _ in rows[offset:offset+500]],
                    )
            cursor.execute(
                """INSERT INTO activity(title,category,city,venue,summary,description,status,published_at)
                   VALUES(%s,'CONCERT','上海','隔离性能测试场馆','仅用于本机隔离性能验证','不对外展示的测试夹具','PUBLISHED',UTC_TIMESTAMP(6))""",
                (f"JM 隔离测试 {batch}",),
            )
            activity_id = cursor.lastrowid
            cursor.execute(
                """INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at,status)
                   VALUES(%s,'隔离测试场次',%s,%s,%s,'SCHEDULED')""",
                (activity_id, starts_at, ends_at, sale_start),
            )
            session_id = cursor.lastrowid
            cursor.execute(
                """INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,locked_stock,sold_stock,
                       sale_starts_at,sale_ends_at,max_quantity_per_order,status)
                   VALUES(%s,'隔离测试票档',9900,%s,0,0,%s,%s,1,'ON_SALE')""",
                (session_id, stock, sale_start, sale_end),
            )
            sku_id = cursor.lastrowid
            if args.bucket_count:
                # 只处理当前事务刚创建的空票档，不切换任何历史库存归属。
                cursor.execute("UPDATE ticket_sku SET inventory_strategy='BUCKETED',bucket_count=%s WHERE id=%s",
                               (args.bucket_count,sku_id))
                cursor.executemany("INSERT INTO ticket_stock_bucket(sku_id,bucket_no,total_stock) VALUES(%s,%s,%s)",
                    [(sku_id,b,stock//args.bucket_count+(b<stock%args.bucket_count)) for b in range(args.bucket_count)])
                cursor.execute("INSERT INTO ticket_waitlist_gate(sku_id) VALUES(%s)",(sku_id,))
                receipt=hashlib.sha256(json.dumps(dict(kind='NEW_EMPTY_SKU',skuId=sku_id,
                    bucketCount=args.bucket_count,total=stock),sort_keys=True).encode()).hexdigest()
                cursor.execute("INSERT INTO stock_strategy_switch(sku_id,bucket_count,operator_id,backup_sha256,previous_locked,previous_sold) VALUES(%s,%s,%s,%s,0,0)",
                               (sku_id,args.bucket_count,seed[0],receipt))
        connection.commit()

        runtime = env_path.parent
        write_csv(runtime / "warmup-users.csv", cohorts["warmup"])
        write_csv(runtime / "measure-users.csv", cohorts["measure"])
        metadata = {
            "environment": "ticketing-jmeter-only",
            "project": PROJECT,
            "database": db_name,
            "mysqlVersion": db_version,
            "flywayVersion": "17",
            "dockerContainerId": docker["containerId"],
            "dockerVolume": docker["volume"],
            "batch": batch,
            "activityId": str(activity_id),
            "sessionId": str(session_id),
            "skuId": str(sku_id),
            "totalStock": stock,
            "warmupUsers": args.warmup_users,
            "measureUsers": args.users,
            "accountsPerOrder": 1,
            "ordersPerUser": args.orders_per_user,
            "inventoryStrategy": "BUCKETED" if args.bucket_count else "SINGLE",
            "bucketCount": args.bucket_count,
            "preparedAtUtc": now.isoformat(timespec="microseconds"),
        }
        (runtime / "prepared-data.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(metadata, ensure_ascii=False, indent=2))
        print("已生成本地敏感 CSV；没有创建订单、支付流水或模拟成功结果。")
        return 0
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"准备失败：{error}", file=sys.stderr)
        raise SystemExit(1)
