import importlib.util
import base64
import hashlib
import json
import os
import stat
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import sys
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verify = load("verify_capability", "verify-capability-deployment.py")
backup = load("full_backup", "full-backup.py")
bounded = load("bounded_backup_io_test", "bounded_backup_io.py")
release = load("release_capabilities", "release-capabilities.py")
same_host = load("same_host_storage", "same_host_storage.py")
ops = load("platform_ops", "ops.py")


def plain_full_model():
    digest = "registry.example/test@sha256:" + "a" * 64
    services = {name: {"image": digest, "pull_policy": "never", "networks": {"private": {}}}
                for name in verify.EXPECTED}
    services["api"]["environment"] = {"PF_DESIGN_ORIGIN": "https://design-provider:48443",
                                      "PF_DESIGN_PUBLIC_CASES_ORIGIN": "https://public-a.example.test"}
    services["ai-runtime"].update({"environment": {"ZS_AI_CORE_URL": "https://design-provider:48443"},
                                   "volumes": [{"type": "bind", "source": "/mnt/runtime-journal",
                                                "target": "/data"}]})
    services["design-provider"].update({
        "environment": {"HEALTH_URL": "https://design-provider:48443/actuator/health/readiness"},
        "depends_on": {"ai-runtime": {"condition": "service_healthy"}},
        "command": ["--spring.main.lazy-initialization=false", "--spring.flyway.enabled=false"],
    })
    volumes = {name: {"name": f"zhongshu-platform-b_{name}"}
               for name in same_host.VOLUME_DIRECTORIES}
    return {"name": "zhongshu-platform-b", "services": services, "networks": {"private": {}},
            "volumes": volumes, "secrets": {}}


def actual_bind_volumes(model):
    items = []
    for logical, config in model["volumes"].items():
        options = None
        if logical == "pgdata":
            options = {"type": "none", "o": "bind",
                       "device": "/mnt/datadisk0/zhongshu-platform-b/postgres-b"}
        items.append({"Name": config["name"], "Driver": "local", "Options": options,
                      "Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                                 "com.docker.compose.volume": logical}})
    return items


class CapabilityDeploymentTest(unittest.TestCase):
    def test_same_host_overlay_is_optional_but_partial_configuration_is_refused(self):
        self.assertEqual(same_host.compose_overlay({}), [])
        self.assertEqual(same_host.compose_overlay({"AI_RUNTIME_JOURNAL_DIR": "/mnt/runtime-journal"}), [])
        values = same_host.example_values("/mnt/datadisk0")
        self.assertEqual(same_host.compose_overlay(values), ["-f", str(HERE / "compose.same-host.yaml")])
        del values["B_SAME_HOST_STORAGE_PROOF_FILE"]
        with self.assertRaises(ValueError):
            same_host.compose_overlay(values)

    def test_same_host_paths_are_fixed_b_children_and_never_overlap_a(self):
        values = same_host.example_values("/mnt/datadisk0")
        planned = same_host.path_plan(values, protected_paths=[Path("/srv/zhongshu-platform-a")])
        self.assertEqual(planned["postgres-b"], Path("/mnt/datadisk0/zhongshu-platform-b/postgres-b"))
        self.assertEqual(planned["backup-staging"],
                         Path("/mnt/datadisk0/zhongshu-platform-b/backup-staging"))
        values["B_SAME_HOST_DATA_ROOT"] = "/srv/zhongshu-platform-a/b-data"
        with self.assertRaises(ValueError):
            same_host.path_plan(values, protected_paths=[Path("/srv/zhongshu-platform-a")])

    def test_same_host_directory_contracts_keep_database_uids_and_private_modes(self):
        contracts = same_host.directory_contracts(Path("/mnt/datadisk0/zhongshu-platform-b"))
        for name in ("postgres-b", "redis-b", "postgres-design", "redis-design"):
            self.assertEqual((contracts[name].uid, contracts[name].gid, contracts[name].mode), (999, 999, 0o700))
        self.assertEqual((contracts["runtime-journal"].uid, contracts["runtime-journal"].gid,
                          contracts["runtime-journal"].mode), (1000, 1000, 0o700))
        self.assertEqual(contracts["backup-staging"].mode, 0o700)

    def test_every_deployment_path_uses_the_same_host_overlay(self):
        values = same_host.example_values("/mnt/datadisk0")
        overlay = str(HERE / "compose.same-host.yaml")
        self.assertEqual(verify.compose_files(values)[-2:], ["-f", overlay])
        self.assertEqual(backup.compose(Path("/external/deployment.env"), values)[-2:], ["-f", overlay])
        self.assertEqual(release.compose_base(Path("/external/deployment.env"), values)[-2:], ["-f", overlay])
        self.assertEqual(ops.compose_base(Path("/external/deployment.env"), values)[-2:], ["-f", overlay])

    def test_same_host_volume_devices_symlinks_and_backup_escape_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory)
            values = same_host.example_values(mount)
            root = mount / "zhongshu-platform-b"
            staging = root / "backup-staging"
            staging.mkdir(parents=True)
            model = {"volumes": {}}
            for volume, child in same_host.VOLUME_DIRECTORIES.items():
                model["volumes"][volume] = {
                    "name": f"zhongshu-platform-b_{volume}", "driver": "local",
                    "driver_opts": {"type": "none", "o": "bind", "device": str(root / child)},
                }
            same_host.validate_compose_volumes(model, values)
            model["volumes"]["pgdata"]["driver_opts"]["device"] = str(mount / "a-data")
            with self.assertRaises(ValueError):
                same_host.validate_compose_volumes(model, values)

            link = mount / "linked"
            link.symlink_to(staging, target_is_directory=True)
            with self.assertRaises(ValueError):
                same_host._reject_symlink_components(link / "backup")
            data = root / "postgres-b"
            data.mkdir()
            (data / "pg_wal").symlink_to(staging, target_is_directory=True)
            with self.assertRaises(ValueError):
                same_host.validate_tree_with_mounts(data, data.stat().st_dev, set())
            (data / "pg_wal").unlink()
            with self.assertRaises(ValueError):
                same_host.validate_tree_with_mounts(data, data.stat().st_dev, {data.resolve()})

            journal = root / "runtime-journal"
            journal.mkdir()
            same_host.validate_journal(values, journal, set())
            with self.assertRaises(ValueError):
                same_host.validate_journal({}, journal)

            with self.assertRaises(ValueError):
                same_host.validate_backup_output(values, staging / "backup-1")
            with self.assertRaises(ValueError):
                ops.validate_backup_location("backup", values, staging / "backup-2")

            volume = {
                "Name": "zhongshu-platform-b_pgdata", "Driver": "local",
                "Options": {"type": "none", "o": "bind", "device": str(root / "postgres-b")},
                "Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                           "com.docker.compose.volume": "pgdata"},
            }
            same_host.validate_volume_inspection([volume], values)
            volume["Options"] = None
            with self.assertRaises(ValueError):
                same_host.validate_volume_inspection([volume], values)

    def test_secure_journal_archive_rejects_a_real_symlink_swap(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journal, outside = root / "journal", root / "outside"
            journal.mkdir()
            outside.write_text("must-not-be-archived")
            victim = journal / "event.json"
            victim.write_text("safe")
            original_open = backup._open_child
            swapped = False

            def swap_before_open(parent_fd, name, flags):
                nonlocal swapped
                if name == "event.json" and not swapped:
                    victim.unlink()
                    victim.symlink_to(outside)
                    swapped = True
                return original_open(parent_fd, name, flags)

            with patch.object(backup, "_open_child", side_effect=swap_before_open), \
                    patch.object(backup, "_fd_mount_id", return_value=1):
                with self.assertRaises((OSError, ValueError)):
                    backup.secure_journal_archive(journal, root / "journal.tar.gz")
            self.assertTrue(swapped)

    def test_secure_journal_archive_rejects_mount_id_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journal = root / "journal"
            (journal / "nested").mkdir(parents=True)
            (journal / "nested" / "event.json").write_text("safe")
            real_mount_id = backup._fd_mount_id
            nested_inode = (journal / "nested").stat().st_ino

            def different_child_mount(fd):
                return 2 if os.fstat(fd).st_ino == nested_inode else 1

            with patch.object(backup, "_fd_mount_id", side_effect=different_child_mount):
                with self.assertRaisesRegex(ValueError, "mount"):
                    backup.secure_journal_archive(journal, root / "journal.tar.gz")
            self.assertTrue(callable(real_mount_id))

    def test_secure_journal_archive_rejects_directory_replacement_after_open(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journal, moved = root / "journal", root / "moved-journal"
            journal.mkdir()
            (journal / "event.json").write_text("safe")
            original_archive = backup._archive_open_directory
            replaced = False

            def replace_root(*args):
                nonlocal replaced
                if not replaced:
                    journal.rename(moved)
                    journal.mkdir()
                    (journal / "foreign.json").write_text("replacement")
                    replaced = True
                return original_archive(*args)

            with patch.object(backup, "_archive_open_directory", side_effect=replace_root), \
                    patch.object(backup, "_fd_mount_id", return_value=1):
                with self.assertRaisesRegex(ValueError, "root"):
                    backup.secure_journal_archive(journal, root / "journal.tar.gz")
            self.assertTrue(replaced)

    def test_secure_journal_archive_rejects_real_linux_bind_submount(self):
        if not sys.platform.startswith("linux"):
            self.skipTest("requires Linux mount IDs")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journal, source = root / "journal", root / "source"
            (journal / "nested").mkdir(parents=True)
            source.mkdir()
            (source / "event.json").write_text("safe")
            mounted = subprocess.run(["mount", "--bind", str(source), str(journal / "nested")],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
            if not mounted:
                self.skipTest("requires CAP_SYS_ADMIN for a real bind-mount regression")
            try:
                with self.assertRaisesRegex(ValueError, "mount"):
                    backup.secure_journal_archive(journal, root / "journal.tar.gz")
            finally:
                subprocess.run(["umount", str(journal / "nested")], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def test_backup_budget_stops_during_write_when_floor_or_limit_would_be_crossed(self):
        usage = SimpleNamespace(total=11_000, used=1_000, free=10_000)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            budget = backup.BackupBudget(root, minimum_free_bytes=8_000,
                                         maximum_backup_bytes=1_500,
                                         disk_usage=lambda _: usage, allocation_unit=1)
            with budget.open(root / "bounded.bin") as stream:
                stream.write(b"a" * 700)
                usage.free = 8_500
                with self.assertRaisesRegex(ValueError, "watermark"):
                    stream.write(b"b" * 600)
            usage.free = 9_000
            budget = backup.BackupBudget(root, minimum_free_bytes=7_000,
                                         maximum_backup_bytes=500,
                                         disk_usage=lambda _: usage, allocation_unit=1)
            with budget.open(root / "limited.bin") as stream:
                with self.assertRaisesRegex(ValueError, "budget"):
                    stream.write(b"x" * 501)
            free_values = iter((1_000, 1_000, 1_000, 1_000, 100))
            budget = backup.BackupBudget(
                root, minimum_free_bytes=500, maximum_backup_bytes=100,
                disk_usage=lambda _: SimpleNamespace(total=2_000, used=1_000,
                                                      free=next(free_values)), allocation_unit=1)
            with budget.open(root / "post-write-floor.bin") as stream:
                with self.assertRaisesRegex(ValueError, "crossed"):
                    stream.write(b"x" * 10)

    def test_dump_subprocess_stdout_is_limited_while_streaming(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            budget = backup.BackupBudget(root, minimum_free_bytes=1, maximum_backup_bytes=128,
                                         allocation_unit=1)
            with budget.open(root / "dump.bin") as output:
                with self.assertRaisesRegex(ValueError, "budget"):
                    backup.stream_command(
                        [sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'x' * 1024)"],
                        "test dump", output, dict(os.environ), timeout=10)
            self.assertLessEqual((root / "dump.bin").stat().st_size, 128)

    def test_dump_subprocess_stderr_is_memory_bounded_and_never_spooled(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            budget = backup.BackupBudget(root, minimum_free_bytes=1, maximum_backup_bytes=1024,
                                         allocation_unit=1)
            with budget.open(root / "dump.bin") as output:
                with self.assertRaisesRegex(RuntimeError, "stderr limit"):
                    backup.stream_command(
                        [sys.executable, "-c",
                         "import sys; sys.stderr.buffer.write(b'e' * (1024 * 1024))"],
                        "hostile stderr", output, dict(os.environ), timeout=10)
            self.assertFalse(any(path.name.startswith("tmp") for path in root.iterdir()))

    def test_backup_target_refuses_inactive_mount_without_falling_back_to_path(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            backup_root = mount / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            contract = {
                "mount": mount, "root": backup_root, "minimumFreeBytes": 1,
                "maximumBackupBytes": 1024 * 1024,
                "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                "rootIdentity": (backup_root.stat().st_dev, backup_root.stat().st_ino),
            }
            active = {7}
            with bounded.BackupTarget(contract, backup_root / "backup-1",
                                      mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: set(active)) as target:
                active.clear()
                with self.assertRaisesRegex(ValueError, "no longer active"):
                    target.open("dump.bin")
            self.assertFalse((backup_root / "backup-1" / "dump.bin").exists())

    def test_backup_target_rejects_real_linux_mount_detach(self):
        if not sys.platform.startswith("linux"):
            self.skipTest("requires Linux mount IDs")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, mount = root / "source", root / "mount"
            backup_root = source / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            mount.mkdir()
            mounted = subprocess.run(["mount", "--bind", str(source), str(mount)],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
            if not mounted:
                self.skipTest("requires CAP_SYS_ADMIN for a real detach regression")
            target = None
            try:
                visible_root = mount / "zhongshu-platform-b-backups"
                contract = {
                    "mount": mount, "root": visible_root, "minimumFreeBytes": 1,
                    "maximumBackupBytes": 1024 * 1024,
                    "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                    "rootIdentity": (visible_root.stat().st_dev, visible_root.stat().st_ino),
                }
                target = bounded.BackupTarget(contract, visible_root / "backup-1")
                subprocess.run(["umount", "-l", str(mount)], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                mounted = False
                with self.assertRaisesRegex(ValueError, "no longer active"):
                    target.open("dump.bin")
                self.assertFalse((mount / "zhongshu-platform-b-backups" / "backup-1" / "dump.bin").exists())
            finally:
                if target:
                    target.close()
                if mounted:
                    subprocess.run(["umount", "-l", str(mount)], check=False,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def test_manifest_is_not_published_when_final_gate_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            backup_root = mount / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            contract = {
                "mount": mount, "root": backup_root, "minimumFreeBytes": 1,
                "maximumBackupBytes": 1024 * 1024,
                "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                "rootIdentity": (backup_root.stat().st_dev, backup_root.stat().st_ino),
            }
            output = backup_root / "backup-1"
            with bounded.BackupTarget(contract, output, mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: {7}) as target:
                with self.assertRaisesRegex(ValueError, "final gate"):
                    target.commit_manifest(b'{}\n', lambda: (_ for _ in ()).throw(
                        ValueError("final gate failed")))
            self.assertFalse((output / "manifest.json").exists())
            self.assertFalse(any(path.name.startswith(".manifest.json.tmp-") for path in output.iterdir()))
            self.assertTrue((output / ".backup-in-progress").exists())

    def test_manifest_refuses_output_directory_replacement_and_cleans_owned_temp(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            backup_root = mount / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            contract = {
                "mount": mount, "root": backup_root, "minimumFreeBytes": 1,
                "maximumBackupBytes": 1024 * 1024,
                "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                "rootIdentity": (backup_root.stat().st_dev, backup_root.stat().st_ino),
            }
            output, moved = backup_root / "backup-1", backup_root / "moved-backup"
            with bounded.BackupTarget(contract, output, mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: {7}) as target:
                def replace_output():
                    output.rename(moved)
                    output.mkdir()

                with self.assertRaisesRegex(ValueError, "visible output"):
                    target.commit_manifest(b'{}\n', replace_output)
            self.assertFalse((output / "manifest.json").exists())
            self.assertFalse((moved / "manifest.json").exists())
            self.assertFalse(any(path.name.startswith(".manifest.json.tmp-") for path in moved.iterdir()))
            self.assertTrue((moved / ".backup-in-progress").exists())

    def test_manifest_refuses_backup_root_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            backup_root = mount / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            contract = {
                "mount": mount, "root": backup_root, "minimumFreeBytes": 1,
                "maximumBackupBytes": 1024 * 1024,
                "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                "rootIdentity": (backup_root.stat().st_dev, backup_root.stat().st_ino),
            }
            output = backup_root / "backup-1"
            moved_root = mount / "moved-root"
            with bounded.BackupTarget(contract, output, mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: {7}) as target:
                def replace_root():
                    backup_root.rename(moved_root)
                    backup_root.mkdir()

                with self.assertRaisesRegex(ValueError, "visible root"):
                    target.commit_manifest(b'{}\n', replace_root)
            self.assertFalse((backup_root / "manifest.json").exists())
            self.assertFalse((moved_root / "backup-1" / "manifest.json").exists())

    def test_backup_root_lock_refuses_a_second_process_and_releases_on_close(self):
        if not hasattr(os, "fork"):
            self.skipTest("requires fork for an independent lock contender")
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            backup_root = mount / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            contract = {
                "mount": mount, "root": backup_root, "minimumFreeBytes": 1,
                "maximumBackupBytes": 1024 * 1024,
                "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                "rootIdentity": (backup_root.stat().st_dev, backup_root.stat().st_ino),
            }
            read_fd, write_fd = os.pipe()
            with bounded.BackupTarget(contract, backup_root / "backup-1",
                                      mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: {7}) as first:
                pid = os.fork()
                if pid == 0:
                    os.close(read_fd)
                    first.close()
                    try:
                        bounded.BackupTarget(contract, backup_root / "backup-2",
                                             mount_id_reader=lambda _: 7,
                                             active_mount_ids=lambda: {7})
                    except ValueError as error:
                        os.write(write_fd, b"blocked" if "already holds" in str(error) else b"wrong-error")
                    else:
                        os.write(write_fd, b"unexpected-success")
                    os.close(write_fd)
                    os._exit(0)
                os.close(write_fd)
                result = os.read(read_fd, 64)
                os.close(read_fd)
                _, status = os.waitpid(pid, 0)
                self.assertEqual((result, status), (b"blocked", 0))
            with bounded.BackupTarget(contract, backup_root / "backup-3",
                                      mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: {7}):
                pass

    def test_manifest_directory_fsync_failure_removes_official_and_completion_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            backup_root = mount / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            contract = {
                "mount": mount, "root": backup_root, "minimumFreeBytes": 1,
                "maximumBackupBytes": 1024 * 1024,
                "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                "rootIdentity": (backup_root.stat().st_dev, backup_root.stat().st_ino),
            }
            output = backup_root / "backup-1"
            with bounded.BackupTarget(contract, output, mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: {7}) as target:
                real_fsync = bounded.os.fsync

                def fail_directory_fsync(fd):
                    if fd == target.output_fd:
                        raise OSError("injected directory fsync failure")
                    return real_fsync(fd)

                with patch.object(bounded.os, "fsync", side_effect=fail_directory_fsync):
                    with self.assertRaisesRegex(RuntimeError, "cleanup is incomplete"):
                        target.commit_manifest(b'{}\n', lambda: None)
            self.assertFalse((output / "manifest.json").exists())
            self.assertFalse((output / ".backup-complete").exists())
            self.assertFalse(any("tmp-" in path.name for path in output.iterdir()))
            self.assertTrue((output / ".backup-in-progress").exists())

    def test_successful_manifest_has_matching_completion_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            backup_root = mount / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            contract = {
                "mount": mount, "root": backup_root, "minimumFreeBytes": 1,
                "maximumBackupBytes": 1024 * 1024,
                "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                "rootIdentity": (backup_root.stat().st_dev, backup_root.stat().st_ino),
            }
            output = backup_root / "backup-1"
            content = b'{"project":"zhongshu-platform-b"}\n'
            with bounded.BackupTarget(contract, output, mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: {7}) as target:
                target.commit_manifest(content, lambda: None)
            self.assertEqual((output / ".backup-complete").read_text(),
                             hashlib.sha256(content).hexdigest() + "\n")
            self.assertFalse((output / ".backup-in-progress").exists())

    def test_cleanup_failure_is_explicit_and_in_progress_marker_forces_verify_refusal(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            backup_root = mount / "zhongshu-platform-b-backups"
            backup_root.mkdir(parents=True)
            contract = {
                "mount": mount, "root": backup_root, "minimumFreeBytes": 1,
                "maximumBackupBytes": 1024 * 1024,
                "mountIdentity": (mount.stat().st_dev, mount.stat().st_ino),
                "rootIdentity": (backup_root.stat().st_dev, backup_root.stat().st_ino),
            }
            output = backup_root / "backup-1"
            with bounded.BackupTarget(contract, output, mount_id_reader=lambda _: 7,
                                      active_mount_ids=lambda: {7}) as target:
                with patch.object(target, "_unlink_owned", side_effect=OSError("injected unlink failure")):
                    with self.assertRaisesRegex(RuntimeError, "cleanup is incomplete"):
                        target.commit_manifest(b'{}\n', lambda: (_ for _ in ()).throw(
                            ValueError("final gate failed")))
            self.assertTrue((output / ".backup-in-progress").exists())
            with self.assertRaisesRegex(ValueError, "publication is incomplete"):
                backup.verify(SimpleNamespace(output=output, confirm_sha256="0" * 64))

    def test_full_backup_verify_rejects_manifest_without_completion_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            manifest = output / "manifest.json"
            manifest.write_text('{}\n')
            args = SimpleNamespace(output=output, confirm_sha256=hashlib.sha256(
                manifest.read_bytes()).hexdigest())
            with self.assertRaisesRegex(ValueError, "publication is incomplete"):
                backup.verify(args)

    def test_full_backup_verify_rejects_dangling_in_progress_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            manifest = output / "manifest.json"
            manifest.write_text('{}\n')
            (output / ".backup-complete").write_text(hashlib.sha256(
                manifest.read_bytes()).hexdigest() + "\n")
            (output / ".backup-in-progress").symlink_to(output / "missing-target")
            args = SimpleNamespace(output=output, confirm_sha256=hashlib.sha256(
                manifest.read_bytes()).hexdigest())
            with self.assertRaisesRegex(ValueError, "publication is incomplete"):
                backup.verify(args)

    def test_path_manifest_double_fault_keeps_in_progress_and_verify_refuses(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            real_fsync = backup.os.fsync
            real_unlink = backup.os.unlink
            directory_fsyncs = 0

            def fail_directory_fsync(fd):
                nonlocal directory_fsyncs
                if stat.S_ISDIR(os.fstat(fd).st_mode):
                    directory_fsyncs += 1
                    if directory_fsyncs >= 2:
                        raise OSError("injected directory fsync failure")
                return real_fsync(fd)

            def fail_publication_unlink(path, *args, **kwargs):
                if str(path) in ("manifest.json", ".backup-complete"):
                    raise OSError("injected unlink failure")
                return real_unlink(path, *args, **kwargs)

            with patch.object(backup.os, "fsync", side_effect=fail_directory_fsync), \
                    patch.object(backup.os, "unlink", side_effect=fail_publication_unlink):
                with self.assertRaisesRegex(RuntimeError, "cleanup is incomplete"):
                    backup.commit_path_manifest(output, b'{}\n', lambda: None)
            self.assertTrue(os.path.lexists(output / ".backup-in-progress"))
            with self.assertRaisesRegex(ValueError, "publication is incomplete"):
                backup.verify(SimpleNamespace(output=output, confirm_sha256="0" * 64))

    def test_verify_regular_open_rejects_fifo_without_blocking(self):
        if not hasattr(os, "fork"):
            self.skipTest("requires fork to bound a blocking-open regression")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.mkfifo(root / "fifo")
            directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            read_fd, write_fd = os.pipe()
            pid = os.fork()
            if pid == 0:
                os.close(read_fd)
                try:
                    backup._open_regular_at(directory_fd, "fifo")
                except ValueError:
                    os.write(write_fd, b"rejected")
                except Exception:
                    os.write(write_fd, b"wrong-error")
                else:
                    os.write(write_fd, b"accepted")
                os.close(write_fd)
                os.close(directory_fd)
                os._exit(0)
            os.close(write_fd)
            deadline = time.monotonic() + 1
            finished = False
            while time.monotonic() < deadline:
                waited, _ = os.waitpid(pid, os.WNOHANG)
                if waited:
                    finished = True
                    break
                time.sleep(0.01)
            if not finished:
                os.kill(pid, 9)
                os.waitpid(pid, 0)
                result = b"blocked"
            else:
                result = os.read(read_fd, 32)
            os.close(read_fd)
            os.close(directory_fd)
            self.assertEqual(result, b"rejected")

    def test_metadata_read_rejects_actual_growth_beyond_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "manifest.json"
            path.write_bytes(b"12345")
            directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            real_fstat = backup.os.fstat

            def stale_size(fd):
                metadata = real_fstat(fd)
                if stat.S_ISREG(metadata.st_mode):
                    return SimpleNamespace(st_mode=metadata.st_mode, st_size=4)
                return metadata

            try:
                with patch.object(backup.os, "fstat", side_effect=stale_size):
                    with self.assertRaisesRegex(ValueError, "too large"):
                        backup._read_regular_at(directory_fd, "manifest.json", 4)
            finally:
                os.close(directory_fd)

    def test_redis_dump_streams_rdb_without_writing_production_data_volume(self):
        captured = []
        budget = object()

        class Output:
            def __enter__(self): return self
            def __exit__(self, *args): return False

        with patch.object(backup, "_output_stream", return_value=Output()), \
                patch.object(backup, "stream_command",
                             side_effect=lambda command, *args: captured.append(command)):
            backup.redis_dump(["docker", "compose"], "redis-b", "platform_backup",
                              "redis_backup_password", Path("/unused"), {}, budget)
        command_text = " ".join(captured[0])
        self.assertIn("--rdb /dev/stdout", command_text)
        self.assertNotIn("mktemp /data", command_text)

    def test_same_host_backup_requires_independent_mount_contract(self):
        values = same_host.example_values("/mnt/data-b")
        values.update(same_host.example_backup_values("/mnt/backup-b"))
        self.assertEqual(same_host.backup_path_plan(values),
                         Path("/mnt/backup-b/zhongshu-platform-b-backups"))
        values["B_SAME_HOST_BACKUP_MOUNT"] = "/mnt/data-b"
        with self.assertRaises(ValueError):
            same_host.backup_path_plan(values)

    def test_backup_mount_rejects_any_non_b_root_content(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory)
            (mount / "lost+found").mkdir()
            same_host._validate_backup_mount_exclusive(mount, mount / "zhongshu-platform-b-backups")
            (mount / "foreign-project").mkdir()
            with self.assertRaisesRegex(ValueError, "exclusively"):
                same_host._validate_backup_mount_exclusive(mount, mount / "zhongshu-platform-b-backups")

    def test_prepare_can_create_backup_root_after_existing_data_root_is_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory)
            values = same_host.example_values(mount)
            values.update(same_host.example_backup_values(mount / "backup-disk"))
            Path(values["B_SAME_HOST_DATA_ROOT"]).mkdir()
            with patch.object(same_host.os, "geteuid", return_value=0), \
                    patch.object(same_host, "validate_host") as validate, \
                    patch.object(same_host, "prepare_backup") as prepare_backup:
                same_host.prepare(values)
            self.assertEqual(validate.call_args_list[0].kwargs, {"require_directories": False})
            self.assertEqual(validate.call_args_list[1].kwargs, {"check_backup": False})
            self.assertEqual(validate.call_args_list[2].kwargs, {})
            prepare_backup.assert_called_once_with(values)

    def test_full_backup_binds_actual_container_mounts_to_reviewed_model(self):
        model = {
            "services": {"redis-b": {"image": "redis@sha256:" + "a" * 64, "volumes": [
                {"type": "volume", "source": "redisdata", "target": "/data"},
                {"type": "bind", "source": str(HERE / "redis-start.sh"),
                 "target": "/etc/platform/redis-start.sh", "read_only": True},
            ]}},
            "volumes": {"redisdata": {"name": "zhongshu-platform-b_redisdata"}},
            "secrets": {},
        }
        container = {
            "Id": "redis-container",
            "Config": {"Image": "redis@sha256:" + "a" * 64,
                       "Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                                   "com.docker.compose.service": "redis-b"}},
            "NetworkSettings": {"Networks": {"zhongshu-platform-b_private": {}}},
            "Mounts": [
                {"Type": "volume", "Name": "zhongshu-platform-b_redisdata",
                 "Destination": "/data", "RW": True},
                {"Type": "bind", "Source": str(HERE / "redis-start.sh"),
                 "Destination": "/etc/platform/redis-start.sh", "RW": False},
            ],
        }
        backup.validate_container_scope(container, "redis-b", model)
        container["Mounts"][0]["Name"] = "a-shared-volume"
        with self.assertRaises(ValueError):
            backup.validate_container_scope(container, "redis-b", model)

    def test_same_host_release_is_closed_without_reviewed_approver_trust_anchor(self):
        with tempfile.TemporaryDirectory() as directory:
            values = same_host.example_values(directory)
            with patch.object(same_host, "TRUSTED_APPROVER_KEY_HASH_FILE",
                              Path(directory) / "missing-trust-anchor"):
                with self.assertRaisesRegex(ValueError, "not provisioned"):
                    same_host.validate_attestation(values, {}, Path(directory) / "proof",
                                                   Path(directory) / "protected", set(), set())

    def test_physical_block_ancestors_must_be_stable_and_disjoint(self):
        topology = [
            {"name": "vda", "kname": "vda", "maj:min": "8:0", "serial": "A-DISK",
             "children": [{"name": "vda1", "kname": "vda1", "pkname": "vda",
                           "maj:min": "8:1"}]},
            {"name": "vdb", "kname": "vdb", "maj:min": "8:16", "serial": "B-DISK",
             "children": [{"name": "vdb1", "kname": "vdb1", "pkname": "vdb",
                           "maj:min": "8:17"}]},
        ]
        b_ids, a_ids = same_host.validate_block_independence("8:17", ["8:1"], topology)
        self.assertEqual((b_ids, a_ids), ({"B-DISK"}, {"A-DISK"}))
        with self.assertRaises(ValueError):
            same_host.validate_block_independence("8:17", ["8:16"], topology)

    def test_actual_same_host_bind_volume_cannot_be_hidden_by_plain_env(self):
        plain_model = {"volumes": {"pgdata": {"name": "zhongshu-platform-b_pgdata"}}}
        actual = [{
            "Name": "zhongshu-platform-b_pgdata", "Driver": "local",
            "Options": {"type": "none", "o": "bind", "device": "/mnt/datadisk0/zhongshu-platform-b/postgres-b"},
            "Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                       "com.docker.compose.volume": "pgdata"},
        }]
        with self.assertRaises(ValueError):
            same_host.validate_actual_volumes_against_model(actual, plain_model)

    def test_verify_cli_rejects_hidden_bind_volume_before_external_validation(self):
        model = plain_full_model()
        hidden = actual_bind_volumes(model)
        gate = lambda rendered, environment=None: same_host.validate_actual_volumes_against_model(hidden, rendered)
        with tempfile.NamedTemporaryFile() as env_file, \
                patch.object(verify, "selected_env", return_value=(
                    {"AI_RUNTIME_JOURNAL_DIR": "/mnt/runtime-journal"}, {})), \
                patch.object(verify, "run", return_value=json.dumps(model).encode()), \
                patch.object(verify.same_host, "inspect_existing_model_volumes", side_effect=gate):
            with self.assertRaises(ValueError):
                verify.validate(Path(env_file.name), False)

    def test_release_cli_rechecks_hidden_bind_volume_before_any_up(self):
        model = plain_full_model()
        hidden = actual_bind_volumes(model)
        commands = []

        def fake_run(command, context, environment, timeout=1800):
            commands.append(command)
            return json.dumps(model).encode()

        gate = lambda rendered, environment=None: same_host.validate_actual_volumes_against_model(hidden, rendered)
        args = SimpleNamespace(env_file=Path("/external/deployment.env"))
        with patch.object(release.VERIFY, "validate"), \
                patch.object(release.VERIFY, "selected_env", return_value=(
                    {"AI_RUNTIME_JOURNAL_DIR": "/mnt/runtime-journal"}, {})), \
                patch.object(release, "run", side_effect=fake_run), \
                patch.object(release.VERIFY.same_host, "inspect_existing_model_volumes", side_effect=gate):
            with self.assertRaises(ValueError):
                release.release(args)
        self.assertFalse(any("up" in command for command in commands))

    def test_ops_cli_rejects_hidden_bind_volume_before_dump(self):
        model = {"services": {name: {} for name in ("api", "postgres-b", "redis-b")},
                 "volumes": {name: {"name": f"zhongshu-platform-b_{name}"}
                             for name in ("pgdata", "redisdata")}}
        hidden = actual_bind_volumes({"volumes": model["volumes"]})
        containers = [{"Id": service, "Config": {"Labels": {
            "com.docker.compose.project": "zhongshu-platform-b", "com.docker.compose.service": service}},
            "State": {"Running": service != "api", "Health": {"Status": "healthy"}}}
            for service in model["services"]]

        def fake_run(command, **kwargs):
            if command[:3] == ["docker", "ps", "-aq"]:
                return b"api postgres-b redis-b"
            if command[:2] == ["docker", "inspect"]:
                return json.dumps(containers).encode()
            if command[:3] == ["docker", "volume", "inspect"]:
                name = command[-1]
                return json.dumps([next(item for item in hidden if item["Name"] == name)]).encode()
            if command[:3] == ["docker", "network", "inspect"]:
                return json.dumps([{"Labels": {"com.docker.compose.project": "zhongshu-platform-b",
                                                "com.docker.compose.network": "private"}}]).encode()
            raise AssertionError(command)

        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "deployment.env"
            env_file.write_text("AI_RUNTIME_JOURNAL_DIR=/mnt/runtime-journal\n")
            env_file.chmod(0o600)
            backup_dir = Path(directory) / "backup"
            argv = ["ops.py", "backup", "--env-file", str(env_file), "--execute", "--writes-frozen",
                    "--confirm-target", ops.TARGET, "--backup-dir", str(backup_dir)]
            with patch.object(sys, "argv", argv), patch.object(ops, "load_model", return_value=model), \
                    patch.object(ops, "compose_base", return_value=["docker", "compose"]), \
                    patch.object(ops, "run", side_effect=fake_run), patch.object(ops, "validate_container"), \
                    patch.object(ops, "backup") as dump:
                with self.assertRaises(ValueError):
                    ops.main()
                dump.assert_not_called()

    def test_full_backup_cli_rejects_hidden_bind_volume_before_dump(self):
        model = plain_full_model()
        hidden = actual_bind_volumes(model)
        gate = lambda items, rendered, values: same_host.validate_actual_volumes_against_model(items, rendered)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file, inventory = root / "deployment.env", root / "cos.json"
            journal = root / "journal"
            journal.mkdir()
            env_file.write_text(f"AI_RUNTIME_JOURNAL_DIR={journal}\nCOS_B_BUCKET=b-test\n")
            inventory.write_text("{}")
            args = SimpleNamespace(confirm_target="zhongshu-platform-b/full", writes_frozen=True,
                                   output=root / "backup", env_file=env_file, cos_inventory=inventory)
            with patch.object(backup, "selected_env", return_value=(
                    {"AI_RUNTIME_JOURNAL_DIR": str(journal), "COS_B_BUCKET": "b-test"}, {})), \
                    patch.object(backup, "run", return_value=json.dumps(model).encode()), \
                    patch.object(backup, "require_frozen", side_effect=lambda base, environment, rendered, values:
                                 gate(hidden, rendered, values)), \
                    patch.object(backup, "pg_dump") as dump:
                with self.assertRaises(ValueError):
                    backup.backup(args)
                dump.assert_not_called()

    def test_independent_attestation_requires_a_real_reviewed_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private_key, public_key = root / "private.pem", root / "public.pem"
            proof, protected = root / "proof.json", root / "protected.txt"
            attestation, signature, trust = root / "attestation.json", root / "attestation.sig", root / "trust.sha256"
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                            "-out", str(private_key)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            proof.write_text('{"volumeId":"volume-b"}\n')
            protected.write_text('/opt/zhongshu\n')
            values = same_host.example_values(root)
            values.update({
                "B_SAME_HOST_STORAGE_ATTESTATION_FILE": str(attestation),
                "B_SAME_HOST_STORAGE_ATTESTATION_SIGNATURE_FILE": str(signature),
                "B_SAME_HOST_APPROVER_PUBLIC_KEY_FILE": str(public_key),
                "B_SAME_HOST_STORAGE_PROOF_FILE": str(proof),
                "B_SAME_HOST_PROTECTED_A_PATHS_FILE": str(protected),
            })
            now = datetime.now(timezone.utc)
            payload = {
                "project": "zhongshu-platform-b", "mountpoint": str(root), "volumeId": "volume-b",
                "provisioning": "thick", "measurementSha256": hashlib.sha256(proof.read_bytes()).hexdigest(),
                "protectedPathsSha256": hashlib.sha256(protected.read_bytes()).hexdigest(),
                "minimumFreeBytes": int(values["B_SAME_HOST_MIN_FREE_BYTES"]), "aScopeComplete": True,
                "physicalVolumeIds": ["B-DISK"], "protectedPhysicalVolumeIds": ["A-DISK"],
                "issuedAt": now.isoformat(), "expiresAt": (now + timedelta(days=1)).isoformat(),
            }
            attestation.write_text(json.dumps(payload))
            subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(private_key), "-out", str(signature),
                            str(attestation)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            trust.write_text(hashlib.sha256(public_key.read_bytes()).hexdigest())
            with patch.object(same_host, "TRUSTED_APPROVER_KEY_HASH_FILE", trust), \
                    patch.object(same_host, "_private_root_file"):
                same_host.validate_attestation(values, {"volumeId": "volume-b"}, proof, protected,
                                               {"B-DISK"}, {"A-DISK"})
                for changed in (
                        {**payload, "minimumFreeBytes": 1},
                        {**payload, "physicalVolumeIds": ["OTHER-B-DISK"]},
                        {**payload, "protectedPathsSha256": "0" * 64},
                ):
                    attestation.write_text(json.dumps(changed))
                    subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(private_key),
                                    "-out", str(signature), str(attestation)], check=True,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    with self.assertRaises(ValueError):
                        same_host.validate_attestation(values, {"volumeId": "volume-b"}, proof, protected,
                                                       {"B-DISK"}, {"A-DISK"})

    def test_backup_attestation_binds_physical_ids_floor_and_total_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private_key, public_key = root / "private.pem", root / "public.pem"
            proof, protected = root / "proof.json", root / "protected.txt"
            attestation, signature, trust = root / "attestation.json", root / "attestation.sig", root / "trust.sha256"
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                            "-out", str(private_key)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            proof.write_text('{"volumeId":"backup-volume"}\n')
            protected.write_text('/opt/zhongshu\n')
            values = same_host.example_values(root / "data")
            values.update(same_host.example_backup_values(root / "backup"))
            values.update({
                "B_SAME_HOST_BACKUP_STORAGE_ATTESTATION_FILE": str(attestation),
                "B_SAME_HOST_BACKUP_STORAGE_ATTESTATION_SIGNATURE_FILE": str(signature),
                "B_SAME_HOST_BACKUP_APPROVER_PUBLIC_KEY_FILE": str(public_key),
                "B_SAME_HOST_BACKUP_STORAGE_PROOF_FILE": str(proof),
                "B_SAME_HOST_PROTECTED_A_PATHS_FILE": str(protected),
            })
            now = datetime.now(timezone.utc)
            payload = {
                "project": "zhongshu-platform-b", "storageRole": "backup",
                "mountpoint": values["B_SAME_HOST_BACKUP_MOUNT"], "volumeId": "backup-volume",
                "provisioning": "thick", "measurementSha256": hashlib.sha256(proof.read_bytes()).hexdigest(),
                "protectedPathsSha256": hashlib.sha256(protected.read_bytes()).hexdigest(),
                "minimumFreeBytes": int(values["B_SAME_HOST_BACKUP_MIN_FREE_BYTES"]),
                "maximumBackupBytes": int(values["B_SAME_HOST_BACKUP_MAX_BYTES"]),
                "physicalVolumeIds": ["BACKUP-DISK"],
                "protectedPhysicalVolumeIds": ["A-DISK", "B-DATA-DISK"], "aScopeComplete": True,
                "issuedAt": now.isoformat(), "expiresAt": (now + timedelta(days=1)).isoformat(),
            }
            trust.write_text(hashlib.sha256(public_key.read_bytes()).hexdigest())
            with patch.object(same_host, "TRUSTED_APPROVER_KEY_HASH_FILE", trust), \
                    patch.object(same_host, "_private_root_file"):
                for candidate, accepted in ((payload, True),
                                            ({**payload, "maximumBackupBytes": 1}, False),
                                            ({**payload, "physicalVolumeIds": ["B-DATA-DISK"]}, False)):
                    attestation.write_text(json.dumps(candidate))
                    subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(private_key),
                                    "-out", str(signature), str(attestation)], check=True,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    if accepted:
                        same_host.validate_backup_attestation(
                            values, {"volumeId": "backup-volume"}, proof, protected,
                            {"BACKUP-DISK"}, {"A-DISK", "B-DATA-DISK"})
                    else:
                        with self.assertRaises(ValueError):
                            same_host.validate_backup_attestation(
                                values, {"volumeId": "backup-volume"}, proof, protected,
                                {"BACKUP-DISK"}, {"A-DISK", "B-DATA-DISK"})

    def test_only_sha256_image_references_are_immutable(self):
        self.assertTrue(verify.immutable("registry.example/b@sha256:" + "a" * 64))
        self.assertFalse(verify.immutable("registry.example/b:2026.11.14"))

    def test_env_file_rejects_ambient_override(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "deployment.env"
            path.write_text("DESIGN_PROVIDER_IMAGE=file-value\n")
            with self.assertRaisesRegex(ValueError, "ambient"):
                verify.selected_env(path, {"DESIGN_PROVIDER_IMAGE": "shell-value"})

    def test_provider_runtime_api_and_cos_contracts_must_match(self):
        key = base64.b64encode(b"k" * 32).decode()
        platform = {
            "zhongshu.design.remote.active-key-id": "b-platform",
            "zhongshu.design.remote.caller-app-id": "platform-b",
            "zhongshu.design.remote.keys.b-platform": key,
        }
        provider = {
            "zhongshu.design.asset.storage.provider": "cos",
            "zhongshu.design.asset.storage.cos.bucket": "platform-b",
            "zhongshu.design.asset.storage.cos.endpoint": "https://platform-b.cos.example.test",
            "zhongshu.ai.internal-secret": "s" * 32,
            "zhongshu.design.capability.keys.b-platform.caller-app-id": "platform-b",
            "zhongshu.design.capability.keys.b-platform.secret-base64": key,
        }
        runtime = {"ZS_INTERNAL_SECRET": "s" * 32, "ZS_AI_STORAGE_MODE": "https",
                   "ZS_AI_STORAGE_ORIGINS": "https://platform-b.cos.example.test"}
        verify.validate_configuration_contract(platform, provider, runtime, "platform-b")
        for changed in ({**provider, "zhongshu.design.asset.storage.cos.bucket": "other"},
                        {**provider, "zhongshu.ai.internal-secret": "x" * 32},
                        {**provider, "zhongshu.design.asset.storage.provider": "local"},
                        {**provider, "zhongshu.design.capability.keys.b-platform.secret-base64":
                         base64.b64encode(b"z" * 32).decode()}):
            with self.assertRaises(ValueError):
                verify.validate_configuration_contract(platform, changed, runtime, "platform-b")
        with self.assertRaises(ValueError):
            verify.validate_configuration_contract(platform, provider,
                                                   {**runtime, "ZS_AI_STORAGE_MODE": "local-fs"}, "platform-b")
        with self.assertRaises(ValueError):
            verify.validate_configuration_contract(platform, provider,
                                                   {**runtime, "ZS_AI_STORAGE_ORIGINS": "https://other.example"},
                                                   "platform-b")

    def test_cos_inventory_is_bound_to_bucket_freeze_and_hashes(self):
        valid = {
            "bucket": "platform-b", "generatedAt": "2026-11-14T10:00:05+00:00",
            "versioningEnabled": True,
            "objects": [{"key": "outputs/a", "versionId": "v1", "size": 3, "sha256": "b" * 64}],
        }
        backup.validate_cos_inventory(valid, "platform-b", "2026-11-14T10:00:00+00:00",
                                      "2026-11-14T10:01:00+00:00")
        for field, value in (("bucket", "other"), ("generatedAt", "2026-11-14T09:59:59+00:00")):
            invalid = dict(valid)
            invalid[field] = value
            with self.assertRaises(ValueError):
                backup.validate_cos_inventory(invalid, "platform-b", "2026-11-14T10:00:00+00:00",
                                              "2026-11-14T10:01:00+00:00")
        invalid = {**valid, "objects": [{"key": "outputs/a", "versionId": "v1", "size": 3}]}
        with self.assertRaises(ValueError):
            backup.validate_cos_inventory(invalid, "platform-b", "2026-11-14T10:00:00+00:00",
                                          "2026-11-14T10:01:00+00:00")

    def test_freeze_requires_exact_writers_and_successful_migration(self):
        provider_ref = "registry.example/provider@sha256:" + "c" * 64
        rows = [
            {"Service": service, "ID": service, "State": "exited", "ExitCode": 0,
             "FinishedAt": "2026-11-14T10:00:00Z",
             "ImageRef": (provider_ref if service == "design-provider" else
                          "registry.example/" + service + "@sha256:" + "a" * 64),
             "ImageID": ("sha256:" + ("d" if service == "design-provider" else "a") * 64)}
            for service in ("api", "design-provider", "ai-runtime")
        ]
        rows.append({"Service": "design-provider-migrate", "ID": "migration", "State": "exited",
                     "ExitCode": 0, "FinishedAt": "2026-11-14T09:59:00Z", "ImageRef": provider_ref,
                     "ImageID": "sha256:" + "d" * 64})
        rows.extend({"Service": service, "ID": service, "State": "running", "Health": "healthy"}
                    | {"ImageRef": "registry.example/" + service + "@sha256:" + "b" * 64,
                       "ImageID": "sha256:" + "b" * 64}
                    for service in ("postgres-b", "redis-b", "postgres-design", "redis-design"))
        backup.freeze_evidence(rows)
        with self.assertRaises(ValueError):
            backup.freeze_evidence([row for row in rows if row["Service"] != "ai-runtime"])
        failed = [dict(row) for row in rows]
        next(row for row in failed if row["Service"] == "design-provider-migrate")["ExitCode"] = 1
        with self.assertRaises(ValueError):
            backup.freeze_evidence(failed)

    def test_candidate_container_is_bound_to_digest_and_actual_image_id(self):
        digest = "registry.example/provider@sha256:" + "c" * 64
        container = {"Config": {"Image": digest, "Labels": {
            "com.docker.compose.project": "zhongshu-platform-b",
            "com.docker.compose.service": "design-provider-migrate",
        }}, "Image": "sha256:" + "d" * 64}
        release.validate_candidate_container(container, "design-provider-migrate", digest,
                                             "sha256:" + "d" * 64)
        container["Image"] = "sha256:" + "e" * 64
        with self.assertRaises(ValueError):
            release.validate_candidate_container(container, "design-provider-migrate", digest,
                                                 "sha256:" + "d" * 64)

    def test_failed_up_stops_attempted_service_and_verifies_all_writers(self):
        digest = "registry.example/service@sha256:" + "c" * 64
        image_id = "sha256:" + "d" * 64
        expected = {service: (digest, image_id) for service in ("ai-runtime", "design-provider", "api")}
        calls = []

        def fake_run(command, context, environment, timeout=1800):
            calls.append(command)
            if "up" in command and command[-1] == "design-provider":
                raise RuntimeError("injected up failure")
            return b""

        container = {"Id": "cid", "Config": {"Image": digest, "Labels": {
            "com.docker.compose.project": "zhongshu-platform-b", "com.docker.compose.service": "ai-runtime",
        }}, "Image": image_id}
        with patch.object(release, "run", side_effect=fake_run), \
                patch.object(release, "service_container", return_value=container), \
                patch.object(release, "wait_healthy", return_value=container), \
                patch.object(release, "validate_candidate_container"), \
                patch.object(release, "require_writers_stopped") as stopped:
            with self.assertRaisesRegex(RuntimeError, "injected up failure"):
                release.start_writers(["docker", "compose"], expected, {})

        stop = next(command for command in calls if "stop" in command)
        self.assertEqual(stop[-2:], ["ai-runtime", "design-provider"])
        stopped.assert_called_once()

    def test_smoke_failure_never_attempts_api_and_stops_runtime_provider(self):
        digest = "registry.example/service@sha256:" + "c" * 64
        image_id = "sha256:" + "d" * 64
        expected = {service: (digest, image_id) for service in ("ai-runtime", "design-provider", "api")}
        calls = []

        def fake_run(command, context, environment, timeout=1800):
            calls.append(command)
            if "exec" in command and "ai-runtime" in command:
                raise RuntimeError("injected smoke failure")
            return b""

        def container(base, service, environment):
            return {"Id": service, "Config": {"Image": digest, "Labels": {
                "com.docker.compose.project": "zhongshu-platform-b", "com.docker.compose.service": service,
            }}, "Image": image_id}

        with patch.object(release, "run", side_effect=fake_run), \
                patch.object(release, "service_container", side_effect=container), \
                patch.object(release, "wait_healthy"), patch.object(release, "require_writers_stopped"):
            with self.assertRaisesRegex(RuntimeError, "injected smoke failure"):
                release.start_writers(["docker", "compose"], expected, {})

        self.assertFalse(any("up" in command and command[-1] == "api" for command in calls))
        stop = next(command for command in calls if "stop" in command)
        self.assertEqual(stop[-2:], ["ai-runtime", "design-provider"])


if __name__ == "__main__":
    unittest.main()
