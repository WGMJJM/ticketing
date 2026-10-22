"""在固定ticketing-perf隔离环境执行原MySQL/RabbitMQ回归命令，不清理或重建既有业务库。"""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts' / 'performance'))
import perf


def main():
    # 必须先验证随机隔离标记，不接受调用者传入地址、schema或凭据。
    with perf.connect() as database, database.cursor() as cursor:
        cursor.execute('CREATE DATABASE IF NOT EXISTS ticketing_perf_aftercare_it '
                       'CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    config = perf.config()
    prefixes = ('SPRING_', 'MANAGEMENT_', 'TICKETING_', 'MYSQL_', 'RABBITMQ_', 'REDIS_',
                'SERVER_', 'ORDER_', 'WAITLIST_', 'ADMIN_', 'SESSION_', 'TICKET_', 'CHECKIN_')
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().replace('.', '_').replace('-', '_').startswith(prefixes)
                   and key.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
    url = ('jdbc:mysql://127.0.0.1:13307/ticketing_perf_aftercare_it'
           '?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8')
    environment.update(
        MYSQL_HOST='127.0.0.1', MYSQL_PORT='13307', MYSQL_DATABASE='ticketing_perf_aftercare_it',
        MYSQL_USER='root', MYSQL_PASSWORD=config['rootPassword'],
        SPRING_DATASOURCE_URL=url, SPRING_DATASOURCE_USERNAME='root',
        SPRING_DATASOURCE_PASSWORD=config['rootPassword'],
        SPRING_FLYWAY_URL=url, SPRING_FLYWAY_USER='root', SPRING_FLYWAY_PASSWORD=config['rootPassword'],
        RABBITMQ_HOST='127.0.0.1', RABBITMQ_PORT='15673', RABBITMQ_USER='ticketing_perf',
        RABBITMQ_PASSWORD=config['mqPassword'], REDIS_HOST='127.0.0.1', REDIS_PORT='16379')
    print('已核对专用环境标记，在独立验收schema执行原VerifyDatabase命令', flush=True)
    result = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                             str(ROOT / 'scripts' / 'start-backend.ps1'), '-VerifyDatabase'],
                            cwd=ROOT, env=environment)
    return result.returncode


if __name__ == '__main__':
    sys.exit(main())
