"""恢复工具的授权和隔离边界；不连接数据库，不删除任何现场。"""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec=importlib.util.spec_from_file_location('v7_recovery',Path(__file__).with_name('recover-v7.py'))
recovery=importlib.util.module_from_spec(spec);spec.loader.exec_module(recovery)


class RecoverySafetyTests(unittest.TestCase):
    def test_restored_data_mismatch_stops_without_marking_recovery_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);data=b'private-test-only'
            (path/'database.sql').write_bytes(data)
            source={'serverUuid':'owned-test-instance','databaseName':'owned_test_it'}
            before={'user_account':{'rows':1,'sha256':'before'}}
            objects={table:'reference structure' for table in ('user_account',*recovery.V7_TABLES)}
            (path/'manifest.json').write_text(json.dumps({'dumpSha256':hashlib.sha256(data).hexdigest(),
                'restoreVerified':True,'source':source,'facts':before}),encoding='utf-8')
            (path/'reference-structure.json').write_text(json.dumps(objects),encoding='utf-8')
            args=MagicMock(apply=True,confirm_database='owned_test_it',database='owned_test_it',
                           maintenance_confirmed=True,confirm_server_uuid='owned-test-instance')
            status={**source,'state':'STOP_PARTIAL_V7','triggerPolicy':1,'triggers':[],'objects':objects}
            with patch.object(recovery,'connect'),patch.object(recovery,'audit',return_value=status), \
                    patch.object(recovery,'facts',side_effect=[before,{'user_account':{'rows':0,'sha256':'after'}}]), \
                    patch.object(recovery,'dump',return_value=b'preserved'),patch.object(recovery,'docker'), \
                    patch.object(recovery,'flyway') as flyway:
                with self.assertRaisesRegex(RuntimeError,'数据核验失败'):
                    recovery.apply(args,path)
                self.assertNotIn('validate',[call.args[1] for call in flyway.call_args_list])
                journal=json.loads((path/'recovery-journal.json').read_text(encoding='utf-8'))
                self.assertEqual(journal['stage'],'data-restored')
                self.assertNotIn('dataPreserved',journal)

    def test_default_recovery_is_read_only_without_apply(self):
        with patch.object(sys,'argv',['recover-v7.py','--action','recover']),patch.object(recovery,'connect') as connection, \
                patch.object(recovery,'audit',return_value={'state':'STOP_PARTIAL_V7'}),patch.object(recovery,'apply') as apply,contextlib.redirect_stdout(io.StringIO()):
            recovery.main()
            connection.assert_called_once();apply.assert_not_called()

    def test_identifier_injection_is_rejected(self):
        for value in ['ticketing;DROP DATABASE other','../ticketing','`ticketing`','a-b','',None]:
            with self.subTest(value=value),self.assertRaises(RuntimeError):recovery.identifier(value)

    def test_missing_authorization_or_verified_backup_never_connects(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);data=b'private-test-only'
            (path/'database.sql').write_bytes(data)
            (path/'manifest.json').write_text(json.dumps({'dumpSha256':hashlib.sha256(data).hexdigest(),'restoreVerified':False}))
            args=MagicMock(apply=True,confirm_database='test',database='test',maintenance_confirmed=True,confirm_server_uuid='test')
            with patch.object(recovery,'connect') as connect,self.assertRaises(RuntimeError):recovery.apply(args,path)
            connect.assert_not_called()

    def test_live_data_drift_stops_before_dump_drop_or_flyway(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);data=b'private-test-only'
            (path/'database.sql').write_bytes(data)
            source={'serverUuid':'owned-test-instance','databaseName':'owned_test_it'}
            (path/'manifest.json').write_text(json.dumps({'dumpSha256':hashlib.sha256(data).hexdigest(),'restoreVerified':True,'source':source,'facts':{'existing':'before'}}))
            args=MagicMock(apply=True,confirm_database='owned_test_it',database='owned_test_it',maintenance_confirmed=True,confirm_server_uuid='owned-test-instance')
            with patch.object(recovery,'connect') as connect,patch.object(recovery,'audit',return_value={**source,'state':'STOP_PARTIAL_V7','triggerPolicy':1}), \
                    patch.object(recovery,'facts',return_value={'existing':'changed'}),patch.object(recovery,'dump') as dump,patch.object(recovery,'flyway') as flyway:
                with self.assertRaises(RuntimeError):recovery.apply(args,path)
                dump.assert_not_called();flyway.assert_not_called()
                connect.return_value.__enter__.return_value.cursor.assert_not_called()

    def test_corrupted_backup_never_connects(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);(path/'database.sql').write_bytes(b'changed')
            (path/'manifest.json').write_text(json.dumps({'dumpSha256':hashlib.sha256(b'original').hexdigest(),'restoreVerified':True}))
            args=MagicMock(apply=True,confirm_database='test',database='test',maintenance_confirmed=True,confirm_server_uuid='test')
            with patch.object(recovery,'connect') as connect,self.assertRaises(RuntimeError):recovery.apply(args,path)
            connect.assert_not_called()


if __name__=='__main__':unittest.main()
