"""隔离边界测试，无需 Docker；真实一致性反例见 audit_probes.py。"""
import argparse
import unittest
from unittest.mock import MagicMock, patch
import perf


class SafetyTests(unittest.TestCase):
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
