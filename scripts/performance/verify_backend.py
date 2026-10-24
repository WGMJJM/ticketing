"""固定ticketing-perf与随机guard中的后端集成验证；成功后仅清理本轮创建的测试schema。"""
import argparse
import os
import re
import shutil
import subprocess
import uuid
from pathlib import Path
import perf


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tests', help='Failsafe it.test筛选；省略时执行完整回归')
    parser.add_argument('--label', default='stage15')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', args.label):
        raise RuntimeError('标签格式错误')
    if args.tests and not re.fullmatch(r'[A-Za-z0-9_,*]+', args.tests):
        raise RuntimeError('测试筛选格式错误')
    cfg = perf.config()
    with perf.connect() as db, db.cursor() as cur:
        cur.execute('SHOW DATABASES')
        original = {next(iter(row.values())) for row in cur.fetchall()}
        schema = 'ticketing_' + args.label + '_' + uuid.uuid4().hex[:12] + '_it'
        cur.execute('CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    prefixes = ('SPRING_', 'MYSQL_', 'RABBITMQ_', 'REDIS_', 'TICKETING_', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith(prefixes)}
    env.update(MYSQL_HOST='127.0.0.1', MYSQL_PORT='13307', MYSQL_DATABASE=schema,
               MYSQL_USER='root', MYSQL_PASSWORD=cfg['rootPassword'],
               REDIS_HOST='127.0.0.1', REDIS_PORT='16379', RATE_LIMIT_TEST_REDIS_PORT='16379',
               RABBITMQ_HOST='127.0.0.1', RABBITMQ_PORT='15673', RABBITMQ_USER='ticketing_perf', RABBITMQ_PASSWORD=cfg['mqPassword'],
               MIGRATION_TEST_PASSWORD=cfg['dbPassword'], RECOVERY_TEST_GUARD=cfg['guardToken'],
               ADMIN_BOOTSTRAP_ENABLED='false')
    command = [shutil.which('mvn') or 'mvn', '-f', str(perf.ROOT / 'backend/pom.xml'), 'clean', 'verify', '-Pmysql-it']
    if args.tests:
        command.append('-Dit.test=' + args.tests)
    print('隔离集成验证schema ' + schema, flush=True)
    completed = subprocess.run(command, cwd=perf.ROOT, env=env)
    if completed.returncode:
        raise RuntimeError('测试失败，保留本轮数据库现场 ' + schema)
    # 测试中的迁移验证会创建多个独立schema，仅在整轮成功后删除新增且名称匹配者。
    allowed = re.compile(r'ticketing_(?:' + re.escape(args.label) + r'_[a-f0-9]+_it|s14_migration_[a-f0-9]+_it|v7_guard_[a-f0-9]+|stage11_upgrade_[a-f0-9]+|stage10_(?:upgrade|failure)_[a-f0-9]+)\Z')
    with perf.connect() as db, db.cursor() as cur:
        cur.execute('SHOW DATABASES')
        created = {next(iter(row.values())) for row in cur.fetchall()} - original
        cleaned = []
        for name in sorted(created):
            if allowed.fullmatch(name):
                cur.execute('DROP DATABASE `' + name + '`')
                cleaned.append(name)
    print('验证通过，已清理本轮隔离schema ' + str(len(cleaned)), flush=True)


if __name__ == '__main__':
    main()
