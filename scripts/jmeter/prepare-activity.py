#!/usr/bin/env python3
"""为隔离 JMeter 项目复用或创建一个公开活动详情夹具。"""

from __future__ import annotations

import json
import importlib.util
import sys
from pathlib import Path

import pymysql

spec = importlib.util.spec_from_file_location("prepare_data", Path(__file__).with_name("prepare-data.py"))
if spec is None or spec.loader is None:
    raise RuntimeError("无法加载隔离环境检查函数")
prepare_data = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_data)
DATABASE = prepare_data.DATABASE
DEFAULT_ENV = prepare_data.DEFAULT_ENV
PROJECT = prepare_data.PROJECT
read_env = prepare_data.read_env
verify_docker_volume = prepare_data.verify_docker_volume
verify_guard = prepare_data.verify_guard


def main() -> int:
    env_path = DEFAULT_ENV.resolve()
    env = read_env(env_path)
    if env.get("MYSQL_HOST") != "127.0.0.1" or env.get("MYSQL_PORT") != "13307" or env.get("MYSQL_DATABASE") != DATABASE:
        raise RuntimeError("只接受 127.0.0.1:13307/ticketingjmeter 隔离数据库")
    if not env.get("MYSQL_ROOT_PASSWORD") or not env.get("JMETER_GUARD_TOKEN"):
        raise RuntimeError("隔离环境缺少 root 凭据或随机 guard")
    docker = verify_docker_volume(env_path)
    connection = pymysql.connect(
        host="127.0.0.1", port=13307, user="root", password=env["MYSQL_ROOT_PASSWORD"],
        database=DATABASE, charset="utf8mb4", autocommit=False, cursorclass=pymysql.cursors.Cursor,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT DATABASE(), UTC_TIMESTAMP(6)")
            database, now = cursor.fetchone()
            if database != DATABASE:
                raise RuntimeError("当前 schema 不是 ticketingjmeter")
            cursor.execute("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")
            migration = cursor.fetchone()
            if not migration or str(migration[0]) != "13":
                raise RuntimeError("隔离应用迁移尚未完成 V13")
        verify_guard(connection, env["JMETER_GUARD_TOKEN"])
        with connection.cursor() as cursor:
            cursor.execute("SELECT id,title FROM activity WHERE status='PUBLISHED' ORDER BY id LIMIT 1")
            existing = cursor.fetchone()
            created = existing is None
            if existing:
                activity_id, title = existing
            else:
                for table in ("activity", "activity_session", "ticket_sku", "ticket_order", "purchase_request"):
                    cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
                    if cursor.fetchone()[0] != 0:
                        raise RuntimeError(f"隔离库 {table} 已有数据且无可复用的已发布活动；拒绝猜测或清理")
                title = f"JMeter 隔离活动详情 {now.strftime('%Y%m%dT%H%M%SZ')}"
                cursor.execute(
                    """INSERT INTO activity(title,category,city,venue,summary,description,status,published_at)
                       VALUES(%s,'CONCERT','上海','隔离性能测试场馆','仅用于隔离详情读取压测',
                              '本地 ticketing-jmeter 项目的测试夹具','PUBLISHED',UTC_TIMESTAMP(6))""",
                    (title,),
                )
                activity_id = cursor.lastrowid
        connection.commit()
        result = {
            "environment": "ticketing-jmeter-only",
            "project": PROJECT,
            "database": database,
            "mysqlVersion": "8.4",
            "flywayVersion": "13",
            "dockerContainerId": docker["containerId"],
            "dockerVolume": docker["volume"],
            "activityId": str(activity_id),
            "title": title,
            "status": "PUBLISHED",
            "createdByThisRun": created,
            "preparedAtUtc": now.isoformat(timespec="microseconds"),
        }
        output = env_path.parent / "activity-data.json"
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
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
        print(f"活动详情夹具准备失败：{error}", file=sys.stderr)
        raise SystemExit(1)
