#!/usr/bin/env python3
"""Exclusive backup output with a shared byte budget and live free-space floor."""
import io
import hashlib
import fcntl
import os
from pathlib import Path
import selectors
import shutil
import subprocess
import time


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def _fd_usage(fd):
    usage = os.fstatvfs(fd)
    return type("Usage", (), {"total": usage.f_blocks * usage.f_frsize,
                               "free": usage.f_bavail * usage.f_frsize,
                               "block_size": usage.f_frsize})()


def _active_mount_ids(path=Path("/proc/self/mountinfo")):
    demand(path.is_file(), "Linux mountinfo is required for backup output safety")
    return {int(line.split()[0]) for line in path.read_text().splitlines() if line.split()}


def _fd_mount_id(fd):
    info = Path(f"/proc/self/fdinfo/{fd}").read_text()
    for line in info.splitlines():
        if line.startswith("mnt_id:"):
            return int(line.split()[1])
    raise ValueError("Linux mount ID is unavailable for backup output")


class BackupBudget:
    def __init__(self, mount, minimum_free_bytes, maximum_backup_bytes, disk_usage=None,
                 directory_fd=None, verifier=None, allocation_unit=None):
        self.mount = mount if isinstance(mount, int) else Path(mount)
        self.minimum_free_bytes = int(minimum_free_bytes)
        self.maximum_backup_bytes = int(maximum_backup_bytes)
        self.directory_fd = directory_fd
        self.verifier = verifier or (lambda: None)
        self._disk_usage = disk_usage or shutil.disk_usage
        usage = self._usage()
        self.initial_free = usage.free
        self.allocation_unit = allocation_unit or getattr(usage, "block_size", 4096)
        self.written = 0
        demand(self.minimum_free_bytes > 0 and self.maximum_backup_bytes > 0,
               "Backup floor and budget must be positive")
        demand(self.initial_free >= self.minimum_free_bytes + self.maximum_backup_bytes,
               "Backup mount lacks floor plus the attested write budget")

    def reserve(self, size):
        self.verifier()
        demand(size >= 0 and self.written + size <= self.maximum_backup_bytes,
               "Backup write budget would be exceeded")
        live_free = self._usage().free
        conservative_free = min(live_free, self.initial_free - self.written)
        demand(conservative_free - size >= self.minimum_free_bytes,
               "Backup write would cross the protected free-space watermark")

    def record(self, size):
        self.written += size
        self.verifier()
        demand(self._usage().free >= self.minimum_free_bytes,
               "Backup write crossed the protected free-space watermark")

    def open(self, path):
        return BudgetedFile(self, Path(path), self.directory_fd)

    def check(self):
        self.reserve(0)

    def _usage(self):
        return self._disk_usage(self.mount)


class BudgetedFile(io.RawIOBase):
    def __init__(self, budget, path, directory_fd=None):
        super().__init__()
        self.budget = budget
        self.logical_size = 0
        if budget:
            budget.reserve(budget.allocation_unit)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0)
        if directory_fd is None:
            self.fd = os.open(path, flags, 0o600)
        else:
            demand(path.name == str(path), "Backup output name must be a single path component")
            self.fd = os.open(str(path), flags, 0o600, dir_fd=directory_fd)
        if budget:
            budget.record(budget.allocation_unit)

    def writable(self):
        return True

    def tell(self):
        return os.lseek(self.fd, 0, os.SEEK_CUR)

    def write(self, data):
        view = memoryview(data)
        allocation = 0
        if self.budget:
            unit = self.budget.allocation_unit
            before = ((self.logical_size + unit - 1) // unit) * unit
            after = ((self.logical_size + len(view) + unit - 1) // unit) * unit
            allocation = after - before
            self.budget.reserve(allocation)
        total = 0
        while view:
            written = os.write(self.fd, view)
            demand(written > 0, "Backup output write made no progress")
            total += written
            self.logical_size += written
            view = view[written:]
        if self.budget:
            self.budget.record(allocation)
        return total

    def flush(self):
        if not self.closed and self.fd >= 0:
            os.fsync(self.fd)

    def close(self):
        if not self.closed:
            try:
                self.flush()
            finally:
                os.close(self.fd)
                self.fd = -1
        super().close()


class BackupTarget:
    """Pin a validated mount/root/output directory for the entire backup transaction."""

    def __init__(self, contract, output, mount_id_reader=_fd_mount_id,
                 active_mount_ids=_active_mount_ids):
        self.contract = contract
        self.output = Path(output)
        self._mount_id_reader = mount_id_reader
        self._active_mount_ids = active_mount_ids
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0)
        self.mount_fd = os.open(contract["mount"], flags)
        self.root_fd = self.output_fd = self.lock_fd = -1
        try:
            self.mount_id = mount_id_reader(self.mount_fd)
            self.root_fd = os.open(Path(contract["root"]).name, flags, dir_fd=self.mount_fd)
            self.verify(include_output=False)
            lock_flags = os.O_RDWR | os.O_CREAT | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0)
            self.lock_fd = os.open(".backup.lock", lock_flags, 0o600, dir_fd=self.root_fd)
            try:
                fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise ValueError("Another B backup transaction already holds the reviewed backup root lock") from error
            os.mkdir(self.output.name, mode=0o700, dir_fd=self.root_fd)
            self.output_fd = os.open(self.output.name, flags, dir_fd=self.root_fd)
            self.verify()
            os.fsync(self.root_fd)
            progress_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0)
            progress_fd = os.open(".backup-in-progress", progress_flags, 0o600, dir_fd=self.output_fd)
            try:
                os.write(progress_fd, b"backup transaction has not committed\n")
                os.fsync(progress_fd)
            finally:
                os.close(progress_fd)
            os.fsync(self.output_fd)
            self.budget = BackupBudget(
                self.mount_fd, contract["minimumFreeBytes"], contract["maximumBackupBytes"],
                disk_usage=_fd_usage, directory_fd=self.output_fd, verifier=self.verify)
            initial_allocation = 2 * self.budget.allocation_unit
            self.budget.reserve(initial_allocation)
            self.budget.record(initial_allocation)
        except Exception:
            self.close()
            raise

    def verify(self, include_output=True):
        demand(self.mount_id in self._active_mount_ids(), "Reviewed backup mount is no longer active")
        mount_stat = os.fstat(self.mount_fd)
        root_stat = os.fstat(self.root_fd)
        demand((mount_stat.st_dev, mount_stat.st_ino) == tuple(self.contract["mountIdentity"]),
               "Reviewed backup mount identity changed")
        demand((root_stat.st_dev, root_stat.st_ino) == tuple(self.contract["rootIdentity"])
               and self._mount_id_reader(self.root_fd) == self.mount_id,
               "Reviewed backup root identity or mount changed")
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0)
        try:
            visible_root_fd = os.open(Path(self.contract["root"]).name, flags, dir_fd=self.mount_fd)
        except OSError as error:
            raise ValueError("Reviewed visible root is missing or unsafe") from error
        try:
            visible_root = os.fstat(visible_root_fd)
            demand((visible_root.st_dev, visible_root.st_ino) == (root_stat.st_dev, root_stat.st_ino)
                   and self._mount_id_reader(visible_root_fd) == self.mount_id,
                   "Reviewed visible root no longer names the held backup root")
        finally:
            os.close(visible_root_fd)
        if include_output and self.output_fd >= 0:
            output_stat = os.fstat(self.output_fd)
            demand(output_stat.st_dev == mount_stat.st_dev
                   and self._mount_id_reader(self.output_fd) == self.mount_id,
                   "Backup output directory left the reviewed mount")
            try:
                visible_output_fd = os.open(self.output.name, flags, dir_fd=self.root_fd)
            except OSError as error:
                raise ValueError("Reviewed visible output is missing or unsafe") from error
            try:
                visible_output = os.fstat(visible_output_fd)
                demand((visible_output.st_dev, visible_output.st_ino) ==
                       (output_stat.st_dev, output_stat.st_ino)
                       and self._mount_id_reader(visible_output_fd) == self.mount_id,
                       "Reviewed visible output no longer names the held output directory")
            finally:
                os.close(visible_output_fd)

    def open(self, name):
        self.verify()
        return self.budget.open(name)

    def sha256(self, name):
        digest = hashlib.sha256()
        fd = os.open(name, os.O_RDONLY | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0),
                     dir_fd=self.output_fd)
        try:
            with os.fdopen(fd, "rb", closefd=False) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
        finally:
            os.close(fd)
        self.verify()
        return digest.hexdigest()

    def commit_manifest(self, content, final_check):
        temporary = f".manifest.json.tmp-{os.getpid()}"
        completion_temporary = f".backup-complete.tmp-{os.getpid()}"
        manifest_identity = completion_identity = None
        manifest_published = completion_published = False
        try:
            with self.open(temporary) as stream:
                stream.write(content)
            manifest_metadata = os.stat(temporary, dir_fd=self.output_fd, follow_symlinks=False)
            manifest_identity = (manifest_metadata.st_dev, manifest_metadata.st_ino)
            completion = (hashlib.sha256(content).hexdigest() + "\n").encode()
            with self.open(completion_temporary) as stream:
                stream.write(completion)
            completion_metadata = os.stat(completion_temporary, dir_fd=self.output_fd,
                                          follow_symlinks=False)
            completion_identity = (completion_metadata.st_dev, completion_metadata.st_ino)
            self.verify()
            self.budget.check()
            final_check()
            self.verify()
            os.rename(temporary, "manifest.json", src_dir_fd=self.output_fd,
                      dst_dir_fd=self.output_fd)
            manifest_published = True
            os.fsync(self.output_fd)
            self.verify()
            os.rename(completion_temporary, ".backup-complete", src_dir_fd=self.output_fd,
                      dst_dir_fd=self.output_fd)
            completion_published = True
            os.fsync(self.output_fd)
            self.verify()
            os.unlink(".backup-in-progress", dir_fd=self.output_fd)
            os.fsync(self.output_fd)
        except Exception as error:
            cleanup_errors = []
            try:
                self._ensure_in_progress()
            except OSError as cleanup_error:
                cleanup_errors.append(f"in-progress marker: {type(cleanup_error).__name__}")
            for name, identity in (
                    (".backup-complete" if completion_published else completion_temporary, completion_identity),
                    ("manifest.json" if manifest_published else temporary, manifest_identity)):
                try:
                    self._unlink_owned(name, identity)
                except OSError as cleanup_error:
                    cleanup_errors.append(f"{name}: {type(cleanup_error).__name__}")
            try:
                os.fsync(self.output_fd)
            except OSError as cleanup_error:
                cleanup_errors.append(f"output fsync: {type(cleanup_error).__name__}")
            if cleanup_errors:
                raise RuntimeError("Backup publication failed and cleanup is incomplete; "
                                   "the in-progress marker forces verify refusal; "
                                   + "; ".join(cleanup_errors)) from error
            raise

    def _ensure_in_progress(self):
        try:
            os.stat(".backup-in-progress", dir_fd=self.output_fd, follow_symlinks=False)
            return
        except FileNotFoundError:
            pass
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(".backup-in-progress", flags, 0o600, dir_fd=self.output_fd)
        try:
            os.write(fd, b"backup publication failed\n")
            os.fsync(fd)
        finally:
            os.close(fd)

    def _unlink_owned(self, name, identity):
        if identity is None:
            return
        try:
            metadata = os.stat(name, dir_fd=self.output_fd, follow_symlinks=False)
        except FileNotFoundError:
            return
        if (metadata.st_dev, metadata.st_ino) == identity:
            os.unlink(name, dir_fd=self.output_fd)

    def close(self):
        for name in ("output_fd", "lock_fd", "root_fd", "mount_fd"):
            fd = getattr(self, name, -1)
            if fd >= 0:
                os.close(fd)
                setattr(self, name, -1)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def stream_command(command, context, output, environment, timeout=1800, stderr_limit=64 * 1024):
    """Copy subprocess stdout through the budgeted writer; never expose its raw output FD."""
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ, "stdout")
    selector.register(process.stderr, selectors.EVENT_READ, "stderr")
    deadline = time.monotonic() + timeout
    stderr_bytes = 0
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(f"{context}: timed out; inspect scoped B service logs")
            events = selector.select(min(1.0, remaining))
            for key, _ in events:
                block = os.read(key.fileobj.fileno(), 16 * 1024 if key.data == "stderr" else 1024 * 1024)
                if not block:
                    selector.unregister(key.fileobj)
                elif key.data == "stdout":
                    output.write(block)
                else:
                    stderr_bytes += len(block)
                    if stderr_bytes > stderr_limit:
                        raise RuntimeError(f"{context}: stderr limit exceeded; inspect scoped B service logs")
        return_code = process.wait(timeout=max(0.1, deadline - time.monotonic()))
    except Exception:
        if process.poll() is None:
            process.kill()
        process.wait()
        raise
    finally:
        selector.close()
        process.stdout.close()
        process.stderr.close()
    if return_code:
        raise RuntimeError(f"{context}: exit {return_code}; inspect scoped B service logs")
