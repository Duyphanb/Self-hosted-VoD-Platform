"""Keep the Playwright process tree owned until its temporary profile can be removed."""
import ctypes
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

GATE = "import subprocess, sys; sys.exit(subprocess.call(sys.argv[1:]) if sys.stdin.read(1) == '1' else 1)"


class WindowsJob:
    def __init__(self):
        from ctypes import wintypes

        class Accounting(ctypes.Structure):
            _fields_ = [(name, ctypes.c_longlong) for name in ("user", "kernel", "period_user", "period_kernel")]
            _fields_ += [(name, wintypes.DWORD) for name in ("faults", "total", "active", "terminated")]

        class BasicLimits(ctypes.Structure):
            _fields_ = [
                ("process_time", ctypes.c_longlong), ("job_time", ctypes.c_longlong),
                ("flags", wintypes.DWORD), ("minimum_working_set", ctypes.c_size_t),
                ("maximum_working_set", ctypes.c_size_t), ("active_processes", wintypes.DWORD),
                ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD), ("scheduling", wintypes.DWORD),
            ]

        class ExtendedLimits(ctypes.Structure):
            _fields_ = [("basic", BasicLimits), ("io_counters", ctypes.c_ulonglong * 6)]
            _fields_ += [(name, ctypes.c_size_t) for name in ("process", "job", "peak_process", "peak_job")]

        self.accounting = Accounting()
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.create = kernel.CreateJobObjectW
        self.create.argtypes = [wintypes.LPVOID, wintypes.LPCWSTR]
        self.create.restype = wintypes.HANDLE
        self.assign = kernel.AssignProcessToJobObject
        self.assign.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.terminate = kernel.TerminateJobObject
        self.terminate.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.query = kernel.QueryInformationJobObject
        self.query.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD, wintypes.LPDWORD]
        self.close = kernel.CloseHandle
        self.close.argtypes = [wintypes.HANDLE]
        self.handle = self.create(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = ExtendedLimits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        configure = kernel.SetInformationJobObject
        configure.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD]
        if not configure(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            error = ctypes.WinError(ctypes.get_last_error())
            self.release()
            raise error

    def attach(self, process):
        if not self.assign(self.handle, int(process._handle)):
            raise ctypes.WinError(ctypes.get_last_error())

    def stop(self):
        if not self.terminate(self.handle, 1):
            raise ctypes.WinError(ctypes.get_last_error())
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if not self.query(self.handle, 1, ctypes.byref(self.accounting), ctypes.sizeof(self.accounting), None):
                raise ctypes.WinError(ctypes.get_last_error())
            if not self.accounting.active:
                return
            time.sleep(0.05)
        raise TimeoutError("Browser job still has active processes")

    def release(self):
        self.close(self.handle)


class LinuxTree:
    def __init__(self):
        self.libc = ctypes.CDLL(None, use_errno=True)
        self.original = ctypes.c_int()
        if self.libc.prctl(37, ctypes.byref(self.original), 0, 0, 0) or self.libc.prctl(36, 1, 0, 0, 0):
            raise OSError(ctypes.get_errno(), "Could not configure the browser subreaper")
        pid = os.getpid()
        self.children = Path(f"/proc/{pid}/task/{pid}/children")
        self.existing = set(self.children.read_text().split())
        self.process = None

    def attach(self, process):
        self.process = process

    def stop(self):
        # Playwright detaches Chrome on Linux. Subreaping keeps that branch visible after Node exits.
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            self.process.poll()
            owned = set(self.children.read_text().split()) - self.existing
            if not owned:
                return
            for child in owned:
                pid = int(child)
                try:
                    os.kill(pid, signal.SIGKILL)
                    if pid != self.process.pid:
                        os.waitpid(pid, os.WNOHANG)
                except (ProcessLookupError, ChildProcessError):
                    pass
            time.sleep(0.05)
        raise TimeoutError("Browser descendants still active")

    def release(self):
        self.libc.prctl(36, self.original.value, 0, 0, 0)


def run_browser_tree(args, *, cwd, env, timeout, after_stop):
    tree = None
    process = None
    attached = False
    first_failure = None
    result = None

    def cleanup(action, label):
        nonlocal first_failure
        try:
            action()
            return True
        except BaseException as failure:
            print(f"FAIL: {label}", file=sys.stderr, flush=True)
            if first_failure is None and (result is None or not result.returncode):
                first_failure = failure
            return False

    try:
        if os.name == "nt":
            tree = WindowsJob()
        elif sys.platform == "linux":
            tree = LinuxTree()
        else:
            raise OSError("Chrome smoke process supervision requires Windows or Linux")
        process = subprocess.Popen([sys.executable, "-c", GATE, *args], cwd=cwd, env=env,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        tree.attach(process)
        attached = True
        process.stdin.write(b"1")
        process.stdin.flush()
        deadline = time.monotonic() + timeout
        while True:
            try:
                stdout, stderr = process.communicate(timeout=max(0, min(0.5, deadline - time.monotonic())))
                break
            except subprocess.TimeoutExpired:
                if time.monotonic() >= deadline:
                    raise subprocess.TimeoutExpired(args, timeout) from None
        result = subprocess.CompletedProcess(args, process.returncode, stdout.decode("utf-8", "replace"),
                                             stderr.decode("utf-8", "replace"))
    except BaseException as failure:
        first_failure = failure
    finally:
        if attached:
            stopped = cleanup(tree.stop, "stop browser descendants")
        elif process is not None:
            stopped = cleanup(process.kill, "stop gated browser launcher")
        else:
            stopped = True
        if process is not None:
            stopped = cleanup(lambda: process.wait(timeout=15), "reap browser launcher") and stopped
            for stream in (process.stdin, process.stdout, process.stderr):
                cleanup(stream.close, "close browser process pipe")
        if tree is not None:
            cleanup(tree.release, "release browser process supervisor")
        if stopped:
            cleanup(after_stop, "remove browser temporary directory")
    if first_failure is not None:
        raise first_failure
    return result
