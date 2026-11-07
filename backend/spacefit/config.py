from dataclasses import dataclass, field
from pathlib import Path
import os
import shutil
import json
from .docker_runtime import DockerConfig, MVS_TOOLS


@dataclass
class Settings:
    data_dir: Path = field(default_factory=lambda: Path(os.getenv("SPACEFIT_DATA_DIR", str(Path(__file__).resolve().parents[1] / "data"))).resolve())
    token: str = field(default_factory=lambda: os.getenv("SPACEFIT_TOKEN", ""))
    min_images: int = field(default_factory=lambda: int(os.getenv("SPACEFIT_MIN_IMAGES", "50")))
    max_images: int = field(default_factory=lambda: int(os.getenv("SPACEFIT_MAX_IMAGES", "150")))
    max_upload_bytes: int = field(default_factory=lambda: int(os.getenv("SPACEFIT_MAX_UPLOAD_MB", "1024")) * 1024 * 1024)
    threads: int = field(default_factory=lambda: int(os.getenv("SPACEFIT_THREADS", "4")))
    stage_timeout: int = field(default_factory=lambda: int(os.getenv("SPACEFIT_STAGE_TIMEOUT", "7200")))
    use_gpu: bool = field(default_factory=lambda: os.getenv("SPACEFIT_USE_GPU", "0") == "1")
    blur_threshold: float = 35.0
    openmvs_runtime: str = field(default_factory=lambda: os.getenv("SPACEFIT_OPENMVS_RUNTIME", "native"))
    docker: DockerConfig | None = None
    colmap_runtime: str = field(default_factory=lambda: os.getenv("SPACEFIT_COLMAP_RUNTIME", "native"))
    colmap_docker: DockerConfig | None = None

    def __post_init__(self):
        if self.colmap_runtime not in {"native", "docker"}:
            raise ValueError("SPACEFIT_COLMAP_RUNTIME must be native or docker")
        if self.colmap_runtime == "docker":
            if self.use_gpu:
                raise ValueError("COLMAP Docker is CPU-only; disable SPACEFIT_USE_GPU")
            if self.colmap_docker is None:
                path = Path(os.getenv("SPACEFIT_COLMAP_DOCKER_CONFIG", str(Path(__file__).resolve().parents[1] / "native-colmap-docker.json")))
                config = json.loads(path.read_text(encoding="utf-8-sig"))
                self.colmap_docker = DockerConfig(binary=config["docker_bin"], image=config["image"],
                    context=config.get("context", "desktop-linux"), threads=config.get("threads", 4), memory_mb=config.get("memory_mb", 4096))
        if self.openmvs_runtime not in {"native", "docker"}:
            raise ValueError("SPACEFIT_OPENMVS_RUNTIME must be native or docker")
        if self.openmvs_runtime == "docker" and self.docker is None:
            path = Path(os.getenv("SPACEFIT_DOCKER_CONFIG", str(Path(__file__).resolve().parents[1] / "native-docker.json")))
            config = json.loads(path.read_text(encoding="utf-8-sig"))
            self.docker = DockerConfig(binary=config["docker_bin"], image=config["image"],
                                       context=config.get("context", "desktop-linux"),
                                       threads=config.get("threads", 4), memory_mb=config.get("memory_mb", 4096))

    def native_identity(self):
        return (self.openmvs_runtime, self.docker.identity() if self.docker else None,
                self.colmap_runtime, self.colmap_docker.identity() if self.colmap_docker else None,
                str(self.data_dir.resolve()) if "docker" in (self.openmvs_runtime, self.colmap_runtime) else None)

    def executables(self) -> dict[str, str | None]:
        mvs_dir = os.getenv("OPENMVS_BIN_DIR", "")
        names = {
            "colmap": os.getenv("COLMAP_BIN", "colmap"),
            "blender": os.getenv("BLENDER_BIN", "blender"),
            **{name: str(Path(mvs_dir) / name) if mvs_dir else name for name in
               MVS_TOOLS},
        }
        if self.openmvs_runtime == "docker":
            names.update({name: self.docker.binary for name in MVS_TOOLS})
        if self.colmap_runtime == "docker":
            names["colmap"] = self.colmap_docker.binary
        return {name: shutil.which(path) for name, path in names.items()}
