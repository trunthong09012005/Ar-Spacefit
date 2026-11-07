"""Executable startup checks, not a claim of successful reconstruction.

HTTP snapshots never expose command output, paths, environment variables or tokens.
Only the seven configured tools are executed, with fixed help/version arguments.
"""
from concurrent.futures import ThreadPoolExecutor
import ctypes
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
from .docker_runtime import OpenMvsDocker, ColmapDocker, MVS_TOOLS

TOOL_NAMES = ("colmap", "blender", "InterfaceCOLMAP", "DensifyPointCloud",
              "ReconstructMesh", "RefineMesh", "TextureMesh")


def probe_tool(name, path, log_dir, timeout, *, command=None):
    if not path:
        return dict(tool=name, status="missing", reason="missing", help_available=False)
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen")
    if name == "colmap":
        env["QT_PLUGIN_PATH"] = str(Path(path).parent.parent / "plugins")
    try:
        if os.name == "nt":
            # Process-local error dialog suppression, not a Windows security change.
            previous = ctypes.windll.kernel32.GetErrorMode()
            ctypes.windll.kernel32.SetErrorMode(previous | 0x8003)
        command = command or [path, "--version" if name == "blender" else "-h"]
        log_dir.mkdir(parents=True, exist_ok=True)
        previous_logs = set(log_dir.glob("*.log"))
        result = subprocess.run(command, capture_output=True, timeout=timeout, env=env, cwd=log_dir,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
        if not output:
            output = "\n".join(p.read_text(encoding="utf-8", errors="replace")
                               for p in set(log_dir.glob("*.log")) - previous_logs)
        if name == "blender":
            help_available = bool(re.search(r"(?im)^Blender\s+\d+\.\d+", output))
        elif name == "colmap":
            help_available = "colmap " in output.lower() and "usage:" in output.lower()
        else:
            help_available = "openmvs " in output.lower() and "options" in output.lower()
        allowed_codes = {0} if name in {"blender", "colmap"} else {0, 1}
        healthy = result.returncode in allowed_codes and help_available
        return dict(tool=name, status="ready" if healthy else "error",
                    reason="ok" if healthy else ("startup_failed" if result.returncode not in allowed_codes else "invalid_help"),
                    exit=result.returncode, hex_exit=hex(result.returncode & 0xFFFFFFFF),
                    help_available=help_available, output=output[:1500])
    except subprocess.TimeoutExpired as error:
        return dict(tool=name, status="error", reason="timeout", help_available=False, detail=str(error))
    except Exception as error:
        return dict(tool=name, status="error", reason="launch_failed", help_available=False, detail=str(error))


def probe_configured_tool(settings, name, path, log_dir, timeout):
    colmap_docker = name == "colmap" and getattr(settings, "colmap_runtime", "native") == "docker"
    mvs_docker = name in MVS_TOOLS and getattr(settings, "openmvs_runtime", "native") == "docker"
    if not (colmap_docker or mvs_docker) or not path:
        return probe_tool(name, path, log_dir, timeout)
    try:
        log_dir.mkdir(parents=True, exist_ok=True)
        config = settings.colmap_docker if colmap_docker else settings.docker
        # Use the resolved Docker executable, just as native probes use resolved paths.
        from dataclasses import replace
        provider = ColmapDocker if colmap_docker else OpenMvsDocker
        prepared = provider(replace(config, binary=path)).prepare(name, ["-h"], log_dir)
        with prepared:
            result = probe_tool(name, path, log_dir, timeout, command=prepared.argv)
        if prepared.cleanup_error:
            return dict(tool=name, status="error", reason="cleanup_failed", help_available=False,
                        detail=prepared.cleanup_error)
        if result.get("exit") == 125:
            # This code comes from Docker run, not from a Windows native loader.
            # Keep daemon output private; the API exposes only this stable code.
            result["reason"] = "docker_start_failed"
        return result
    except Exception as error:
        return dict(tool=name, status="error", reason="launch_failed", help_available=False, detail=str(error))


def diagnose_tools(settings, log_dir, timeout):
    return [probe_configured_tool(settings, name, path, log_dir / name, timeout)
            for name, path in settings.executables().items()]


class NativeReadiness:
    """Non-blocking, single-flight cache. Failed/expired/unchecked results are never ready.

    A probe batch is bounded by one timeout because tools run concurrently in separate
    temporary directories. close() waits only for this instance's diagnostic batch.
    """
    def __init__(self, settings, *, timeout=60, ttl=300, probe=None, clock=time.monotonic):
        if not 1 <= timeout <= 120 or not 1 <= ttl <= 3600:
            raise ValueError("Invalid native diagnostic timeout or cache TTL")
        self.settings, self.timeout, self.ttl = settings, timeout, ttl
        self.probe = probe or (lambda *args: probe_configured_tool(self.settings, *args))
        self.clock = clock
        self.lock = threading.Lock()
        self.thread = None
        self.closed = False
        self.key = None
        self.results = {}
        self.checked_at = None
        self.expires_at = 0

    def snapshot(self):
        found = self.settings.executables()
        paths = {name: found.get(name) for name in TOOL_NAMES}
        identity = getattr(self.settings, "native_identity", lambda: None)()
        key = (tuple(paths.items()), identity)
        with self.lock:
            checking = self.thread is not None and self.thread.is_alive()
            valid = self.key == key and self.checked_at is not None and self.clock() < self.expires_at
            if not self.closed and not checking and not valid:
                self.key, self.results, self.checked_at = key, {}, None
                if any(paths.values()):
                    self.thread = threading.Thread(target=self._run, args=(key, paths),
                                                   name="spacefit-native-check", daemon=True)
                    self.thread.start()
                    checking = True
                else:
                    self.results = {name: dict(status="missing", reason="missing") for name in paths}
                    self.checked_at = datetime.now(timezone.utc).isoformat()
                    self.expires_at = self.clock() + self.ttl
                    valid = True
            checked = valid and not checking and not self.closed
            tools = {name: dict(self.results.get(name, {"status": "unchecked" if path else "missing",
                                                     "reason": "not_checked" if path else "missing"}))
                     if checked else dict(status="unchecked" if path else "missing",
                                          reason="not_checked" if path else "missing")
                     for name, path in paths.items()}
            return dict(tools={name: path is not None for name, path in paths.items()},
                        ready=checked and all(row["status"] == "ready" for row in tools.values()),
                        runtime=dict(checking=checking and not self.closed, checked=checked,
                                     checked_at=self.checked_at if checked else None, tools=tools))

    def _run(self, key, paths):
        results = {}
        try:
            # Docker must verify sharing on the same drive as real attempts.
            # A C:\\Temp help success must not authorize inaccessible E:\\data.
            # Only a new empty diagnostic child is mounted, never data_dir itself.
            diagnostic_parent = None
            if "docker" in (getattr(self.settings, "openmvs_runtime", "native"), getattr(self.settings, "colmap_runtime", "native")):
                diagnostic_parent = self.settings.data_dir
                diagnostic_parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix="spacefit-native-", dir=diagnostic_parent) as directory:
                with ThreadPoolExecutor(max_workers=len(TOOL_NAMES), thread_name_prefix="spacefit-tool") as executor:
                    futures = {name: executor.submit(self.probe, name, path, Path(directory) / name, self.timeout)
                               for name, path in paths.items()}
                    for name, future in futures.items():
                        try:
                            result = future.result()
                            # Whitelist only public diagnostic codes, never raw output/detail.
                            status = result.get("status")
                            reason = result.get("reason")
                            results[name] = dict(status=status if status in {"ready", "missing", "error"} else "error",
                                                 reason=reason if reason in {"ok", "missing", "timeout", "startup_failed", "docker_start_failed", "invalid_help", "launch_failed", "cleanup_failed"} else "launch_failed")
                            if isinstance(result.get("exit"), int):
                                results[name]["exit"] = result["exit"]
                                results[name]["hex_exit"] = hex(result["exit"] & 0xFFFFFFFF)
                        except Exception:
                            results[name] = dict(status="error", reason="launch_failed")
        except Exception:
            results = {name: dict(status="error", reason="launch_failed") for name in paths}
        finally:
            with self.lock:
                if not self.closed and self.key == key:
                    self.results = results
                    self.checked_at = datetime.now(timezone.utc).isoformat()
                    self.expires_at = self.clock() + self.ttl

    def close(self):
        with self.lock:
            self.closed = True
            thread = self.thread
        if thread:
            thread.join(timeout=self.timeout + 15)
