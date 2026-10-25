"""最终验收只读快照；源码清单、路由、测试名与冻结摘要，不采集环境秘密。"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
import perf


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,50}', args.label):
        raise RuntimeError('标签格式无效')
    directory = perf.ROOT / 'docs/architecture/results' / args.label
    if directory.exists():
        raise RuntimeError('结果已存在，禁止覆盖')
    inventory = []
    roots = ['backend/src/main', 'backend/src/test', 'frontend/src', 'frontend/e2e', 'scripts', 'deploy', '.github',
             'backend/pom.xml', 'frontend/package.json', 'frontend/package-lock.json',
             'frontend/playwright.config.mjs', '.env.example', 'AGENTS.md', '.gitignore']
    extensions = {'.java', '.sql', '.yml', '.yaml', '.xml', '.ts', '.vue', '.css', '.py', '.js', '.mjs', '.ps1', '.sh', '.json', '.properties'}
    for root in roots:
        entry = perf.ROOT / root
        for path in [entry] if entry.is_file() else sorted(entry.rglob('*')):
            if not path.is_file() or (not entry.is_file() and path.suffix not in extensions) or '__pycache__' in path.parts:
                continue
            raw = path.read_bytes()
            source = raw.decode('utf-8-sig')
            row = {'path': path.relative_to(perf.ROOT).as_posix(), 'sha256': hashlib.sha256(raw).hexdigest(),
                   'lines': len(source.splitlines())}
            if path.suffix == '.java':
                row['routes'] = re.findall(r'@(?:Request|Get|Post|Put|Patch|Delete)Mapping[^\n]*', source)
                row['tests'] = re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)', source)
                row['transactions'] = re.findall(r'@Transactional[^\n]*', source)
            inventory.append(row)
    migrations = []
    for path in sorted((perf.ROOT / 'backend/src/main/resources/db/migration').glob('V*__*.sql')):
        relative = path.relative_to(perf.ROOT).as_posix()
        current = path.read_bytes()
        frozen = subprocess.check_output(['git', 'show', '9830a26:' + relative], cwd=perf.ROOT)
        normalized = current.replace(b'\r\n', b'\n')
        if normalized != frozen.replace(b'\r\n', b'\n'):
            raise RuntimeError('历史迁移内容发生变化 ' + path.name)
        migrations.append({'path': relative, 'workingTreeSha256': hashlib.sha256(current).hexdigest(),
                           'gitBlobSha256': hashlib.sha256(frozen).hexdigest(), 'contentMatchesStartingHead': True})
    documents = []
    for pattern in ['0[0-5]-*.md', '2[5-9]-*.md', '3[0-2]-*.md']:
        for path in sorted((perf.ROOT / 'docs').glob(pattern)):
            raw = path.read_bytes()
            source = raw.decode('utf-8-sig')
            documents.append({'path': path.relative_to(perf.ROOT).as_posix(), 'sha256': hashlib.sha256(raw).hexdigest(),
                              'headings': re.findall(r'^#{1,3} .+', source, re.M)})
    perf.save(directory / 'source-inventory.json', {'capturedAt': datetime.now(timezone.utc).isoformat(),
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=perf.ROOT, text=True).strip(),
        'sourceFiles': inventory, 'documents': documents, 'migrations': migrations,
        'scope': '完整文件读取及摘要/入口索引；不等同于逐行人工证明，重点协议另见最终报告。'})
    original = perf.RESULTS
    try:
        perf.RESULTS = directory
        perf.environment_snapshot()
    finally:
        perf.RESULTS = original
    containers = json.loads(subprocess.check_output(['docker', 'inspect',
        'ticketing-perf-mysql-1', 'ticketing-perf-rabbitmq-1', 'ticketing-perf-redis-1']))
    runtime = {'maven': subprocess.check_output([shutil.which('mvn') or 'mvn', '-version'], text=True).strip(),
        'node': subprocess.check_output(['node', '--version'], text=True).strip(),
        'rabbitmq': subprocess.check_output(['docker', 'exec', 'ticketing-perf-rabbitmq-1', 'rabbitmq-diagnostics', '-q', 'server_version'], text=True).strip(),
        'redis': subprocess.check_output(['docker', 'exec', 'ticketing-perf-redis-1', 'redis-cli', 'INFO', 'server'], text=True).splitlines()[:5],
        'containers': [{'name': c['Name'], 'image': c['Config']['Image'],
                        'memoryLimit': c['HostConfig']['Memory'], 'nanoCpus': c['HostConfig']['NanoCpus'],
                        'portBindings': c['HostConfig']['PortBindings'], 'restartPolicy': c['HostConfig']['RestartPolicy']}
                       for c in containers],
        'baselineParameters': {'stock': 'SINGLE', 'hikariMax': 10, 'consumerConcurrency': 2, 'prefetch': 1,
            'jvmFlags': ['-Xms512m', '-Xmx1024m', '-XX:+UseG1GC'],
            'outboxWindow': 16, 'outboxBatch': 100, 'purchaseCapacity': 200, 'purchaseQueueSeconds': 120,
            'purchaseMessageTtlMs': 120000, 'catalogCache': True, 'extraLuaAdmission': False}}
    perf.save(directory / 'runtime.json', runtime)
    print('只读环境及源码快照完成', len(inventory), '个源码文件，', len(migrations), '份冻结迁移')


if __name__ == '__main__':
    main()
