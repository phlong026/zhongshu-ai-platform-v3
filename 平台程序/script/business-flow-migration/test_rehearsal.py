import copy
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
from rehearsal import MaintenanceError, apply, build_plan, manifest, restore, save, validate_plan


class Api:
    def __init__(self):
        self.state = "ACTIVE"
        self.balance = None
        self.reserved = "0"
        self.audits = []
        self.calls = []
        self.commands = {}
        self.lose_response = False

    def call(self, method, path, body=None):
        self.calls.append((method, path, copy.deepcopy(body)))
        if method == "POST":
            key = body["requestKey"]
            if key in self.commands:
                if self.commands[key] != body:
                    raise MaintenanceError("server idempotency conflict")
                return {}
            if path.endswith("/provision"):
                self.balance = self.balance if self.balance is not None else "0"
            else:
                if body["confirmedName"] != "指定测试公司":
                    raise MaintenanceError("server target mismatch")
                if body["action"] == "close" and self.state != "ACTIVE":
                    raise MaintenanceError("server already disabled")
                self.state = "DISABLED" if body["action"] == "close" else "ACTIVE"
                self.audits.insert(0, {"id": str(len(self.audits)+1), "request_key": key, "reason": body["reason"], "action": body["action"]})
            self.commands[key] = copy.deepcopy(body)
            if self.lose_response:
                self.lose_response = False
                raise MaintenanceError("response lost after commit")
            return {}
        if path.endswith("/points"):
            return {"balance": self.balance, "status": None if self.balance is None else "ACTIVE", "in_flight": self.reserved}
        if "/audits?" in path:
            return {"list": copy.deepcopy(self.audits[:1]), "total": len(self.audits)}
        if "/ledger?" in path:
            return {"list": [], "total": 7}  # Existing financial history survives every operation.
        return {"organization": {"orgId": "11", "orgName": "指定测试公司", "orgType": "store", "status": self.state,
                                  "ownerName": "SHOULD_NOT_BE_SAVED", "mobile": "SHOULD_NOT_BE_SAVED"}}


def source(action="close"):
    return {"tenantId": "1", "reason": "已确认的测试名单", "companies": [{"companyId": "11", "confirmedName": "指定测试公司", "action": action}]}


class RehearsalTest(unittest.TestCase):
    def test_checkpoint_syncs_file_before_replace_then_parent_directory(self):
        events=[]; original_sync=os.fsync; original_replace=os.replace
        def sync(fd):
            events.append('directory' if stat.S_ISDIR(os.fstat(fd).st_mode) else 'file')
            original_sync(fd)
        def replace(source,target):
            events.append('replace');original_replace(source,target)
        with tempfile.TemporaryDirectory() as directory, patch('rehearsal.os.fsync',side_effect=sync), patch('rehearsal.os.replace',side_effect=replace):
            save(Path(directory)/'journal.json',{'phase':'SUBMITTING'})
        self.assertEqual(events,['file','replace','directory'])

    def test_failed_directory_sync_stops_before_post(self):
        api=Api();plan=build_plan(api,source());original=os.fsync
        def fail_directory(fd):
            if stat.S_ISDIR(os.fstat(fd).st_mode):
                raise OSError('durability unavailable')
            original(fd)
        with tempfile.TemporaryDirectory() as directory, patch('rehearsal.os.fsync',side_effect=fail_directory):
            with self.assertRaises(OSError):
                apply(api,plan,lambda value:save(Path(directory)/'journal.json',value))
        self.assertFalse(any(call[0]=='POST' for call in api.calls))

    def test_plan_is_read_only_and_does_not_save_contact_fields(self):
        api = Api()
        plan = build_plan(api, source())
        self.assertTrue(all(call[0] == "GET" for call in api.calls))
        self.assertNotIn("SHOULD_NOT_BE_SAVED", repr(plan))
        self.assertEqual(api.state, "ACTIVE")
        self.assertEqual(plan["targets"][0]["before"]["ledgerCount"], 7)

    def test_explicit_ids_and_allowed_actions_are_required(self):
        for value in ["*", "test-%", -1, 0, True, "1;DROP", 2**63]:
            raw = source(); raw["companies"][0]["companyId"] = value
            with self.assertRaises(MaintenanceError):
                manifest(raw)
        for action in ["delete", "restore", "sql", "credit"]:
            with self.assertRaises(MaintenanceError):
                manifest(source(action))
        raw = source(); raw["companies"] *= 2
        with self.assertRaises(MaintenanceError):
            manifest(raw)

    def test_snapshot_change_blocks_first_mutation(self):
        api = Api(); plan = build_plan(api, source()); api.balance = "25"
        with self.assertRaises(MaintenanceError):
            apply(api, plan, lambda _: None)
        self.assertFalse(any(call[0] == "POST" for call in api.calls))

    def test_unsettled_reservation_blocks_cleanup_plan(self):
        api = Api(); api.reserved = "9"
        with self.assertRaises(MaintenanceError):
            build_plan(api, source())

    def test_lost_close_response_resumes_exact_request_without_duplicate_effect(self):
        api = Api(); plan = build_plan(api, source()); saved = []
        api.lose_response = True
        with self.assertRaises(MaintenanceError):
            apply(api, plan, lambda value: saved.append(copy.deepcopy(value)))
        self.assertEqual(saved[-1]["targets"][0]["phase"], "SUBMITTING")
        self.assertEqual(api.state, "DISABLED")
        restored_journal = saved[-1]
        apply(api, restored_journal, lambda value: saved.append(copy.deepcopy(value)))
        posts = [call for call in api.calls if call[0] == "POST"]
        self.assertEqual(posts[0], posts[1])
        self.assertEqual(len(api.audits), 1)
        self.assertEqual(restored_journal["targets"][0]["phase"], "APPLIED")
        self.assertEqual(restored_journal["targets"][0]["after"]["ledgerCount"], 7)

    def test_restore_uses_lifecycle_and_keeps_history(self):
        api = Api(); plan = build_plan(api, source()); apply(api, plan, lambda _: None)
        api.lose_response = True
        with self.assertRaises(MaintenanceError):
            restore(api, plan, lambda _: None)
        self.assertEqual(plan["targets"][0]["phase"], "RESTORING")
        restore(api, plan, lambda _: None)
        restore(api, plan, lambda _: None)
        self.assertEqual(api.state, "ACTIVE")
        self.assertEqual(len(api.audits), 2)
        self.assertEqual(plan["targets"][0]["restored"]["ledgerCount"], 7)

    def test_later_lifecycle_audit_cannot_be_overwritten_by_restore(self):
        api = Api(); plan = build_plan(api, source()); apply(api, plan, lambda _: None)
        api.audits.insert(0, {"id": "later", "request_key": "another-admin", "action": "close", "reason": "new decision"})
        with self.assertRaises(MaintenanceError):
            restore(api, plan, lambda _: None)
        self.assertEqual(api.state, "DISABLED")

    def test_modified_journal_cannot_change_endpoint_or_command(self):
        api = Api(); plan = build_plan(api, source()); plan["targets"][0]["endpoint"] = "/platform/other"
        with self.assertRaises(MaintenanceError):
            apply(api, plan, lambda _: None)
        self.assertFalse(any(call[0] == "POST" for call in api.calls))

    def test_provision_is_not_reverted_or_credited(self):
        api = Api(); plan = build_plan(api, source("provision")); apply(api, plan, lambda _: None)
        self.assertEqual(api.balance, "0")
        restore(api, plan, lambda _: None)
        self.assertEqual(api.balance, "0")
        self.assertEqual(len(api.commands), 1)


if __name__ == "__main__":
    unittest.main()
