import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('diagnosis_runner', Path(__file__).with_name('diagnose-purchase.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class CompletionFeedTest(unittest.TestCase):
    def test_transient_windows_reader_lock_retries_without_publishing_partial_json(self):
        replace = runner.os.replace
        calls = 0
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'flow.json'
            runner.save(path, {'completed': 1})

            def briefly_locked(source, target):
                nonlocal calls
                calls += 1
                if calls < 3:
                    self.assertEqual(json.loads(path.read_text())['completed'], 1)
                    raise PermissionError('transient sharing violation')
                return replace(source, target)

            with patch.object(runner.os, 'replace', side_effect=briefly_locked), patch.object(runner.time, 'sleep'):
                runner.publish_flow(path, {'completed': 2})
            self.assertEqual(json.loads(path.read_text())['completed'], 2)
            self.assertEqual(calls, 3)

    def test_persistent_reader_lock_remains_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'flow.json'
            with patch.object(runner.os, 'replace', side_effect=PermissionError), patch.object(runner.time, 'sleep'):
                with self.assertRaises(PermissionError):
                    runner.publish_flow(path, {'completed': 2})
            self.assertFalse(path.exists())


if __name__ == '__main__':
    unittest.main()
