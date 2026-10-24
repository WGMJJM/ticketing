"""隔离边界测试，无需 Docker；真实一致性反例见 audit_probes.py。"""
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import perf


class SafetyTests(unittest.TestCase):
    def test_inherited_rate_limit_settings_cannot_disable_protected_perf_mode_or_leak_secret(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            jar = directory / 'test.jar'
            jar.write_bytes(b'isolated-test-artifact')
            cfg = {'dbPassword': 'unused', 'mqPassword': 'unused', 'adminPassword': 'unused',
                   'ticketKeys': 'unused', 'rootPassword': 'unused'}
            process = MagicMock(pid=123)
            process.poll.return_value = None
            with patch.object(perf, 'PRIVATE', directory), patch.object(perf, 'config', return_value=cfg), \
                    patch.dict(perf.os.environ, {'RATE_LIMIT_ENABLED': 'false', 'RATE_LIMIT_KEY_SECRET': 'inherited',
                                                 'PERF_RATE_LIMIT_ENABLED': 'false', 'PERF_RATE_LIMIT_ORDER_USER': '1',
                                                 'CATALOG_CACHE_ENABLED': 'false', 'CATALOG_CACHE_CONCURRENT_LOADS': '32',
                                                 'CLIENT_ADDRESS_MODE': 'TRUSTED_PROXY', 'CLIENT_ADDRESS_TRUSTED_PROXIES': '10.0.0.0/8'}), \
                    patch.object(perf.subprocess, 'Popen', return_value=process) as start, \
                    patch.object(perf.requests, 'get') as get, patch.object(perf, 'admin') as admin:
                get.return_value.status_code = 200
                admin.return_value.request.return_value = {'performance': {'environment': 'ticketing-perf-only'}}
                perf.app_start(jar, rate_limit_mode='production')
                child = start.call_args.kwargs['env']
                self.assertEqual(child['PERF_RATE_LIMIT_ENABLED'], 'true')
                self.assertNotIn('RATE_LIMIT_ENABLED', child)
                self.assertNotIn('RATE_LIMIT_KEY_SECRET', child)
                self.assertNotIn('PERF_RATE_LIMIT_ORDER_USER', child)
                self.assertNotIn('CATALOG_CACHE_ENABLED', child)
                self.assertNotIn('CATALOG_CACHE_CONCURRENT_LOADS', child)
                self.assertNotIn('CLIENT_ADDRESS_MODE', child)
                self.assertNotIn('CLIENT_ADDRESS_TRUSTED_PROXIES', child)
                report = (directory / 'app.json').read_text(encoding='utf-8')
                self.assertNotIn(child['PERF_RATE_LIMIT_KEY_SECRET'], report)
                self.assertEqual(json.loads(report)['rateLimitMode'], 'production')

    def test_rate_limit_modes_are_explicit_isolated_and_have_private_random_secrets(self):
        baseline = perf.rate_limit_environment('baseline')
        regression = perf.rate_limit_environment('regression')
        production = perf.rate_limit_environment('production')
        self.assertEqual(baseline['PERF_RATE_LIMIT_ENABLED'], 'false')
        self.assertEqual(regression['PERF_RATE_LIMIT_ENABLED'], 'true')
        self.assertEqual(regression['PERF_RATE_LIMIT_REGISTER_IP'], '10000')
        self.assertEqual(regression['PERF_RATE_LIMIT_CSRF_SESSION_IP'], '10000')
        self.assertNotIn('PERF_RATE_LIMIT_CSRF_SESSION_IP', production)
        self.assertNotIn('PERF_RATE_LIMIT_REGISTER_IP', production)
        self.assertNotEqual(regression['PERF_RATE_LIMIT_KEY_SECRET'], production['PERF_RATE_LIMIT_KEY_SECRET'])
        with self.assertRaisesRegex(RuntimeError, '未知专用限流模式'):
            perf.rate_limit_environment('invalid')

    def test_sensitive_overrides_cannot_be_copied_into_reports(self):
        with self.assertRaisesRegex(RuntimeError, '无敏感压测参数'):
            perf.app_start('unused.jar', PERF_ADMIN_PASSWORD='not-a-real-password')
        with self.assertRaisesRegex(RuntimeError, '无敏感压测参数'):
            perf.app_start('unused.jar', WAITLIST_JOBS_ENABLED='not-a-real-password')

    def test_database_guard_rejects_foreign_environment_before_mutation(self):
        db = MagicMock()
        cursor = db.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = {'token': 'foreign-environment'}
        cfg = {'environment':'ticketing-perf-only', 'rootPassword':'temporary-test-value', 'guardToken':'own-environment'}
        with patch.object(perf, 'config', return_value=cfg), patch.object(perf.pymysql, 'connect', return_value=db) as connection:
            with self.assertRaisesRegex(RuntimeError, '隔离标记错误'):
                perf.connect()
            self.assertEqual(connection.call_args.kwargs['database'], 'ticketing_perf')
            self.assertEqual(connection.call_args.kwargs['port'], 13307)
            self.assertFalse(any('DELETE' in str(c) or 'UPDATE' in str(c) for c in cursor.execute.call_args_list))
            db.close.assert_called_once()

    def test_report_label_cannot_escape_results_directory(self):
        for label in ['../production','D:/other','a/b','a\\b','', '..']:
            with self.subTest(label=label), self.assertRaisesRegex(RuntimeError, '报告标签'):
                perf.run(argparse.Namespace(label=label))

    def test_fault_compose_target_is_fixed_and_has_no_shell_expansion(self):
        cfg = {'dbPassword':'unused','rootPassword':'unused','mqPassword':'unused'}
        with patch.object(perf.subprocess, 'run') as run, patch.object(perf.subprocess,'check_output',return_value='npipe://local'), patch.object(perf,'config',return_value=cfg):
            perf.compose('stop','mysql')
            args = run.call_args.args[0]
            self.assertEqual(args[args.index('--project-name')+1], 'ticketing-perf')
            self.assertEqual(args[-2:], ['stop','mysql'])
            self.assertNotIn('shell', run.call_args.kwargs)

    def test_remote_docker_is_rejected_before_side_effects(self):
        with patch.dict(perf.os.environ, {'DOCKER_HOST':'tcp://remote.example:2376'}), patch.object(perf.subprocess,'run') as run:
            with self.assertRaisesRegex(RuntimeError,'本机Docker'):
                perf.compose('stop','mysql')
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
