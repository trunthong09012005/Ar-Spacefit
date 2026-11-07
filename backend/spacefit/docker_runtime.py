"""Scoped, CPU-only OpenMVS containers. This adapter never mounts backend secrets.

Only commands constructed by the pipeline are accepted. An immutable image ID or
digest is mandatory; creating/building the image is a separate, owner-approved step.
"""
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
from uuid import uuid4

MVS_TOOLS = ("InterfaceCOLMAP", "DensifyPointCloud", "ReconstructMesh", "RefineMesh", "TextureMesh")
_CONTROL = re.compile(r"[\x00-\x1f\x7f]")
_IMAGE = re.compile(r"(?:sha256:[a-f0-9]{64}|[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[a-f0-9]{64})\Z")
_NAME = re.compile(r"spacefit-(?:mvs|colmap)-[a-f0-9]{32}\Z")


@dataclass(frozen=True)
class DockerConfig:
    binary: str
    image: str
    context: str = "desktop-linux"
    threads: int = 4
    memory_mb: int = 4096

    def __post_init__(self):
        if not isinstance(self.binary, str) or not self.binary.strip() or _CONTROL.search(self.binary):
            raise ValueError("Invalid Docker executable")
        if not isinstance(self.image, str) or not _IMAGE.fullmatch(self.image):
            raise ValueError("OpenMVS Docker image must be an immutable sha256 ID or digest")
        if "@" in self.image and ":" in self.image.split("@", 1)[0].rsplit("/", 1)[-1]:
            raise ValueError("Use an image digest without a mutable tag")
        if not isinstance(self.context, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", self.context):
            raise ValueError("Invalid Docker context")
        if type(self.threads) is not int or not 1 <= self.threads <= 64:
            raise ValueError("Invalid Docker CPU limit")
        if type(self.memory_mb) is not int or not 256 <= self.memory_mb <= 65536:
            raise ValueError("Invalid Docker memory limit")

    def identity(self):
        return (self.binary, self.image, self.context, self.threads, self.memory_mb)


class PreparedCommand:
    def __init__(self, config, argv, container_name, cwd):
        self.config, self.argv, self.container_name, self.cwd = config, argv, container_name, cwd
        self.cleaned = False
        self.cleanup_error = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.cleanup()
        return False

    def observed_oom(self):
        """Positive evidence only; absent/expired events do NOT disprove OOM.

        --rm removes inspect state. Query only this generated container's OOM
        events before cleanup, with a daemon-clock end (never a live subscription).
        Never expose daemon output/attributes, environment or paths to the API.
        """
        if not _NAME.fullmatch(self.container_name):
            return False
        try:
            # Docker Desktop's VM can be ahead of Windows by a fraction of a
            # second: a host-clock cutoff can omit the just-recorded OOM.
            clock = subprocess.run([self.config.binary, "--context", self.config.context,
                                    "info", "--format", "{{.SystemTime}}"], capture_output=True, timeout=5)
            if clock.returncode:
                return False
            until = clock.stdout.decode("ascii").strip()
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})", until):
                return False
            result = subprocess.run([
                self.config.binary, "--context", self.config.context, "events",
                # This UUID name belongs exclusively to this prepared command.
                # Query available bounded history without a skewed host start.
                "--since", "0", "--until", until,
                "--filter", "type=container", "--filter", "container=" + self.container_name,
                "--filter", "event=oom", "--format", "{{.Action}}",
            ], capture_output=True, timeout=5)
            return result.returncode == 0 and b"oom" in result.stdout.splitlines()
        except Exception:
            return False  # Diagnosis must not mask the original stage failure.

    def cleanup(self):
        if self.cleaned:
            return
        self.cleaned = True
        if not _NAME.fullmatch(self.container_name):
            self.cleanup_error = "Refusing to clean a container not owned by this command"
            return
        try:
            result = subprocess.run([self.config.binary, "--context", self.config.context,
                                     "container", "rm", "--force", self.container_name],
                                    capture_output=True, timeout=10)
            # --rm normally removed the container already. Any other failure is
            # recorded without masking a timeout/cancel/native error in flight.
            output = (result.stdout + result.stderr).decode("utf-8", errors="replace").lower()
            if result.returncode and "no such container" not in output:
                self.cleanup_error = "Docker could not confirm owned container removal"
        except Exception:
            self.cleanup_error = "Docker could not confirm owned container removal"


class OpenMvsDocker:
    tool_names = MVS_TOOLS
    kind = "openmvs"
    prefix = "spacefit-mvs-"

    def __init__(self, config):
        self.config = config

    @staticmethod
    def _path(value, cwd):
        if not isinstance(value, str) or not value or _CONTROL.search(value):
            raise ValueError("Invalid OpenMVS path")
        # Linux absolute paths must not turn into drive-relative Windows paths.
        if value.startswith(("/", "\\")) and not Path(value).is_absolute():
            raise ValueError("OpenMVS path is outside the attempt")
        path = Path(value)
        resolved = (path if path.is_absolute() else cwd / path).resolve()
        try:
            relative = resolved.relative_to(cwd)
        except ValueError:
            raise ValueError("OpenMVS path is outside the attempt") from None
        return "/workspace" + ("/" + relative.as_posix() if relative.parts else "")

    def _arguments(self, name, args, cwd):
        if args == ["-h"]:
            return args.copy()
        path_flags = {"-i", "-o", "--image-folder"} if name == "InterfaceCOLMAP" else set()
        number_flags = {"--max-threads", "--archive-type"} if name != "InterfaceCOLMAP" else set()
        if name == "DensifyPointCloud":
            number_flags.add("--resolution-level")
        if name == "RefineMesh":
            number_flags.update({"--resolution-level", "--reduce-memory"})
        result, seen, positional = [], set(), 0
        index = 0
        while index < len(args):
            arg = args[index]
            if not isinstance(arg, str) or not arg or _CONTROL.search(arg):
                raise ValueError("Invalid OpenMVS argument")
            if arg in path_flags | number_flags | ({"--export-type"} if name == "TextureMesh" else set()):
                if arg in seen or index + 1 >= len(args):
                    raise ValueError("Duplicate or missing OpenMVS argument")
                seen.add(arg)
                value = args[index + 1]
                if arg in path_flags:
                    value = self._path(value, cwd)
                elif arg in number_flags:
                    if not isinstance(value, str) or not re.fullmatch(r"[0-9]+", value):
                        raise ValueError("Invalid OpenMVS numeric argument")
                    low, high = {"--resolution-level": (0, 0) if name == "RefineMesh" else (0, 4),
                                 "--reduce-memory": (1, 1), "--archive-type": (1, 1),
                                 "--max-threads": (1, 64)}[arg]
                    if not low <= int(value) <= high:
                        raise ValueError("Invalid OpenMVS numeric argument")
                elif value != "obj":
                    raise ValueError("Only OBJ texture export is supported")
                result.extend([arg, value])
                index += 2
            elif name != "InterfaceCOLMAP" and not arg.startswith("-") and positional == 0:
                if Path(arg).suffix.lower() != ".mvs":
                    raise ValueError("Expected an OpenMVS scene file")
                result.append(self._path(arg, cwd))
                positional += 1
                index += 1
            else:
                raise ValueError("Unsupported OpenMVS argument")
        if (name == "InterfaceCOLMAP" and not {"-i", "-o", "--image-folder"}.issubset(seen)) or (name != "InterfaceCOLMAP" and positional != 1):
            raise ValueError("Missing OpenMVS input/output arguments")
        return result

    def prepare(self, name, args, cwd):
        if name not in self.tool_names:
            raise ValueError("Unsupported Docker native tool")
        if not isinstance(args, list):
            raise ValueError("OpenMVS arguments must be an argv list")
        cwd = Path(cwd).resolve()
        if not cwd.is_dir() or cwd == Path(cwd.anchor) or "," in str(cwd) or _CONTROL.search(str(cwd)):
            raise ValueError("Invalid scoped OpenMVS working directory")
        translated = self._arguments(name, args, cwd)
        container_name = self.prefix + uuid4().hex
        c = self.config
        argv = [c.binary, "--context", c.context, "run", "--rm", "--pull", "never",
                "--name", container_name, "--label", f"ar-spacefit.native={self.kind}",
                "--network", "none", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=256m",
                "--memory", f"{c.memory_mb}m", "--cpus", str(c.threads),
                "--pids-limit", "256", "--ulimit", "core=0", "--env", f"OMP_NUM_THREADS={c.threads}",
                "--workdir", "/workspace", "--mount", f"type=bind,source={cwd},target=/workspace",
                c.image, name, *translated]
        return PreparedCommand(c, argv, container_name, cwd)


class ColmapDocker(OpenMvsDocker):
    """CPU COLMAP 3.9.1 argv, restricted to the pipeline's four commands.

    Shares only one attempt at the same /workspace path as OpenMVS. No GPU,
    shell, project config, database from another job, or arbitrary CLI is accepted.
    """
    tool_names = ("colmap",)
    kind = "colmap"
    prefix = "spacefit-colmap-"
    commands = {
        "feature_extractor": {"--database_path", "--image_path", "--ImageReader.camera_model", "--SiftExtraction.use_gpu", "--SiftExtraction.num_threads"},
        "exhaustive_matcher": {"--database_path", "--SiftMatching.use_gpu", "--SiftMatching.num_threads"},
        "mapper": {"--database_path", "--image_path", "--output_path", "--Mapper.num_threads"},
        "image_undistorter": {"--image_path", "--input_path", "--output_path", "--output_type", "--max_image_size"},
    }

    def _arguments(self, name, args, cwd):
        if args == ["-h"]:
            return args.copy()
        if not args or not isinstance(args[0], str) or args[0] not in self.commands:
            raise ValueError("Unsupported COLMAP command")
        if args[1:] == ["-h"]:
            return args.copy()
        allowed = self.commands[args[0]]
        if len(args) != 1 + 2 * len(allowed):
            raise ValueError("Missing or duplicate COLMAP arguments")
        result, seen = [args[0]], set()
        for flag, value in zip(args[1::2], args[2::2]):
            if not isinstance(flag, str) or flag not in allowed or flag in seen:
                raise ValueError("Unsupported or duplicate COLMAP argument")
            if not isinstance(value, str) or not value or _CONTROL.search(value):
                raise ValueError("Invalid COLMAP argument")
            seen.add(flag)
            if flag.endswith("_path"):
                value = self._path(value, cwd)
            elif flag.endswith(".use_gpu"):
                if value != "0":
                    raise ValueError("COLMAP Docker is CPU-only")
            elif flag.endswith(".num_threads"):
                if value != str(self.config.threads):
                    raise ValueError("COLMAP threads must match Docker limits")
            elif flag == "--max_image_size":
                if value != "2000":
                    raise ValueError("Unsupported COLMAP image size")
            elif value != ("SIMPLE_RADIAL" if flag == "--ImageReader.camera_model" else "COLMAP"):
                raise ValueError("Unsupported COLMAP camera or output type")
            result.extend([flag, value])
        return result
