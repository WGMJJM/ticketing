import importlib.util
import io
from pathlib import Path
import sys
import unittest

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
spec = importlib.util.spec_from_file_location('diagnose_purchase', SCRIPT_DIR / 'diagnose-purchase.py')
diagnose = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnose)


class RedisProbeTest(unittest.TestCase):
    def test_resp_reader_handles_scalar_and_nested_replies(self):
        reader = io.BytesIO(b'+PONG\r\n:2\r\n$3\r\nfoo\r\n*2\r\n:1\r\n$1\r\nx\r\n')
        self.assertEqual(diagnose.read_resp(reader), 'PONG')
        self.assertEqual(diagnose.read_resp(reader), 2)
        self.assertEqual(diagnose.read_resp(reader), 'foo')
        self.assertEqual(diagnose.read_resp(reader), [1, 'x'])

    def test_info_sections_and_commandstats_are_separated(self):
        parsed = diagnose.parse_redis_info('# Stats\r\ninstantaneous_ops_per_sec:4\r\n'
                                           '# Commandstats\r\ncmdstat_eval:calls=2,usec=4\r\n')
        self.assertEqual(parsed['sections']['stats']['instantaneous_ops_per_sec'], '4')
        self.assertEqual(parsed['commandstats']['cmdstat_eval'], 'calls=2,usec=4')

    def test_slowlog_discards_all_command_arguments(self):
        cleaned = diagnose.sanitized_slowlog([[7, 1000, 500, ['EVAL', 'secret-script', 'sensitive-key']]])
        self.assertEqual(cleaned, [{'id': 7, 'unixTime': 1000, 'durationUs': 500, 'command': 'EVAL'}])
        self.assertNotIn('sensitive-key', str(cleaned))


if __name__ == '__main__':
    unittest.main()
