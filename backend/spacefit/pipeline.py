import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import threading
import time
from .images import prepare_images
from .native_runtime import NativeReadiness, TOOL_NAMES
from .docker_runtime import OpenMvsDocker, ColmapDocker


class Cancelled(Exception):
    pass


class NativeCommandError(RuntimeError):
    def __init__(self, stage, exit_code):
        self.stage, self.exit_code = stage, exit_code
        super().__init__(f"{stage} failed (exit {exit_code}). Download the job log for details.")


class Pipeline:
    def __init__(self, store, settings, native=None):
        self.store, self.settings = store, settings
        self.native = native
        self.stop = threading.Event()
        self.wakeup = threading.Event()
        self.thread = threading.Thread(target=self.loop, name="reconstruction", daemon=True)

    def start(self):
        self.store.recover()
        self.thread.start()

    def close(self):
        self.stop.set()
        self.wakeup.set()
        # Docker shutdown can need 5+5 seconds to stop its CLI, followed by up
        # to 10 seconds for owned daemon-side cleanup. Do not abandon the daemon
        # reconstruction thread while it can still mutate an attempt.
        self.thread.join(timeout=35 if self.native is not None else 90)
        if self.thread.is_alive():
            raise RuntimeError("Reconstruction worker did not finish owned-process cleanup")

    def check(self, job_id):
        job = self.store.get(job_id)
        # A cancel followed immediately by retry can already be back in queued.
        # The old subprocess must stop rather than continue under the new state.
        if self.stop.is_set() or not job or job["status"] not in {"processing", "calibrating"}:
            raise Cancelled()

    def loop(self):
        while not self.stop.is_set():
            for job in reversed(self.store.all()):
                if self.stop.is_set():
                    break
                if job["status"] in {"queued", "calibration_queued"}:
                    self.process(job)
            self.wakeup.wait(1)
            self.wakeup.clear()

    def run(self, job_id, stage, progress, command, cwd):
        self.check(job_id)
        if not self.store.transition(job_id, {"processing", "calibrating"}, stage=stage, progress=progress):
            raise Cancelled()
        env = dict(os.environ, QT_QPA_PLATFORM="offscreen",
                   OMP_NUM_THREADS="1" if stage == "refine" else str(self.settings.threads))
        if "colmap" in Path(command[0]).stem.lower():
            plugins = Path(command[0]).parent.parent / "plugins"
            if plugins.is_dir():
                env["QT_PLUGIN_PATH"] = str(plugins)
        # shell=False; all arguments are constructed by us, never a submitted command string.
        kwargs = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}
        # Windows OpenMVS writes its own log instead of stdout. Preserve its new
        # output in the downloadable pipeline log, including failed stages.
        native_logs = {path: path.stat().st_size for path in cwd.glob("*.log") if path.name != "pipeline.log"}
        with (cwd / "pipeline.log").open("ab") as log:
            log.write((f"\n--- {stage} ---\n").encode())
            log.flush()
            process = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=log, env=env, **kwargs)
            started = time.monotonic()
            try:
                while process.poll() is None:
                    self.check(job_id)
                    if time.monotonic() - started > self.settings.stage_timeout:
                        raise TimeoutError(f"{stage} exceeded stage timeout")
                    self.stop.wait(0.3)
                self.check(job_id)
                if process.returncode:
                    raise NativeCommandError(stage, process.returncode)
            finally:
                if process.poll() is None:
                    if os.name == "nt":
                        process.terminate()
                    else:
                        os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        if os.name == "nt":
                            process.kill()
                        else:
                            os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=5)
                for path in cwd.glob("*.log"):
                    if path.name == "pipeline.log":
                        continue
                    try:
                        previous = native_logs.get(path, 0)
                        if path.stat().st_size <= previous:
                            continue
                        with path.open("rb") as native:
                            native.seek(previous)
                            log.write((f"\n--- {path.name} ---\n").encode())
                            while chunk := native.read(65536):
                                log.write(chunk)
                    except OSError:
                        pass  # Never mask the original native-process failure.

    def process(self, job):
        job_id = job["id"]
        root = self.settings.data_dir / job_id
        root.mkdir(exist_ok=True)
        calibration = job["status"] == "calibration_queued"
        if not self.store.transition(job_id, {job["status"]}, status="calibrating" if calibration else "processing", error=None):
            return
        try:
            binaries = self.settings.executables()
            missing = [name for name, path in binaries.items() if path is None and (not calibration or name == "blender")]
            if missing:
                raise RuntimeError("Missing tools: " + ", ".join(missing) + ". Install tools and configure backend/.env, then retry.")
            self.preflight(job_id, calibration)
            if calibration:
                self.export(job, root, binaries["blender"], final=True)
                return
            # Each retry uses a fresh workspace, preserving previous attempts and their logs.
            work = root / f"attempt-{time.time_ns()}"
            work.mkdir()
            self.store.update(job_id, work_dir=work.name, stage="preparing_images", progress=2)
            sources = [root / "uploads" / filename for filename in job["files"]]
            metrics = prepare_images(sources, work / "images", self.settings, lambda: self.check(job_id))
            self.store.update(job_id, metrics=metrics)
            c = binaries["colmap"]
            db, images, sparse, dense = [str(work / name) for name in ("database.db", "images", "sparse", "dense")]
            Path(sparse).mkdir()
            # GPU option names changed in COLMAP 3.13; detect the supported interface.
            if self.settings.colmap_runtime == "docker":
                # Setup pins and verifies the CPU 3.9.1 CLI; no Windows DLL probe.
                help_text = "SiftExtraction.use_gpu"
            else:
                help_result = subprocess.run([c, "feature_extractor", "-h"], capture_output=True, text=True,
                                           timeout=20, env=dict(os.environ, QT_QPA_PLATFORM="offscreen"),
                                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
                help_text = help_result.stdout + help_result.stderr
            extract_gpu = "FeatureExtraction.use_gpu" if "FeatureExtraction.use_gpu" in help_text else "SiftExtraction.use_gpu"
            match_gpu = "FeatureMatching.use_gpu" if "FeatureExtraction.use_gpu" in help_text else "SiftMatching.use_gpu"
            gpu = "1" if self.settings.use_gpu else "0"
            extra_extract = ["--SiftExtraction.num_threads", str(self.settings.colmap_docker.threads)] if self.settings.colmap_runtime == "docker" else []
            extra_match = ["--SiftMatching.num_threads", str(self.settings.colmap_docker.threads)] if self.settings.colmap_runtime == "docker" else []
            self.run_colmap(job_id, "features", 12, ["feature_extractor", "--database_path", db, "--image_path", images,
                    "--ImageReader.camera_model", "SIMPLE_RADIAL", "--" + extract_gpu, gpu, *extra_extract], work)
            self.run_colmap(job_id, "matching", 25, ["exhaustive_matcher", "--database_path", db, "--" + match_gpu, gpu, *extra_match], work)
            mapper_threads = self.settings.colmap_docker.threads if self.settings.colmap_runtime == "docker" else self.settings.threads
            self.run_colmap(job_id, "camera_poses", 38, ["mapper", "--database_path", db, "--image_path", images,
                    "--output_path", sparse, "--Mapper.num_threads", str(mapper_threads)], work)
            candidates = list(Path(sparse).glob("*/images.bin"))
            if not candidates:
                raise RuntimeError("No camera reconstruction. Retake overlapping views of a static, textured object.")
            def registered(path):
                with path.open("rb") as f:
                    return struct.unpack("<Q", f.read(8))[0]
            best = max(candidates, key=registered)
            count = registered(best)
            metrics["registered_images"] = count
            self.store.update(job_id, metrics=metrics)
            if count < max(10, int(metrics["accepted_images"] * 0.6)):
                raise RuntimeError(f"Only {count}/{metrics['accepted_images']} images registered. Missing angles or insufficient overlap; please rescan.")
            self.run_colmap(job_id, "undistortion", 48, ["image_undistorter", "--image_path", images,
                    "--input_path", str(best.parent), "--output_path", dense, "--output_type", "COLMAP", "--max_image_size", "2000"], work)
            self.run_mvs(job_id, "mvs_import", 53, "InterfaceCOLMAP", ["-i", dense, "-o", "scene.mvs",
                    "--image-folder", str(Path(dense) / "images")], work)
            self.densify_scene(job_id, work)
            self.reconstruct_mesh(job_id, work)
            self.export(self.store.get(job_id), root, binaries["blender"], final=False)
        except Cancelled:
            self.store.transition(job_id, {"processing", "calibrating"}, status="failed", stage="interrupted", error="Processing interrupted; retry after restarting the server.")
        except Exception as error:
            self.store.transition(job_id, {"processing", "calibrating"}, status="failed", error=str(error)[:1500])

    def densify_scene(self, job_id, work):
        # v2.4 interface archives keep sparse points separate from the dense
        # PLY. Binary archives preserve the actual dense cloud in the scene.
        common = ["--archive-type", "1", "--max-threads", str(self.settings.threads)]
        self.run_mvs(job_id, "dense_cloud", 58, "DensifyPointCloud", ["scene.mvs", "--resolution-level", "1", *common], work)
        self.require_mvs_output(job_id, "dense_cloud", work, "scene_dense.mvs")

    def reconstruct_mesh(self, job_id, work):
        # Do not rely on OpenMVS's interface default: it can emit only a PLY,
        # while the next stage requires a scene containing the reconstructed mesh.
        common = ["--archive-type", "1", "--max-threads", str(self.settings.threads)]
        self.run_mvs(job_id, "mesh", 72, "ReconstructMesh", ["scene_dense.mvs", *common], work)
        self.require_mvs_output(job_id, "mesh", work, "scene_dense_mesh.mvs")
        # Refine allocates image/mesh scratch per concurrent image pair. A
        # single worker reduces that component without changing resolution,
        # neighbor views, subdivision or iterations. It may take longer and
        # is NOT a guarantee that every real scene fits the memory limit.
        self.run_mvs(job_id, "refine", 80, "RefineMesh", ["scene_dense_mesh.mvs",
                     "--archive-type", "1", "--max-threads", "1",
                     "--resolution-level", "0", "--reduce-memory", "1"], work)
        self.require_mvs_output(job_id, "refine", work, "scene_dense_mesh_refine.mvs")
        self.run_mvs(job_id, "texture", 88, "TextureMesh", ["scene_dense_mesh_refine.mvs", "--export-type", "obj", *common], work)
        self.require_mvs_output(job_id, "texture", work, "scene_dense_mesh_refine_texture.obj")

    def require_mvs_output(self, job_id, stage, work, filename):
        self.check(job_id)
        output = work / filename
        if output.is_symlink() or not output.is_file() or output.stat().st_size == 0:
            raise RuntimeError(f"{stage} did not produce {filename}. OpenMVS output is missing or empty; download the job log for details.")

    def run_colmap(self, job_id, stage, progress, args, cwd):
        if self.settings.colmap_runtime != "docker":
            return self.run(job_id, stage, progress, [self.settings.executables()["colmap"], *args], cwd)
        return self.run_container(job_id, stage, progress, "colmap", args, cwd, ColmapDocker, self.settings.colmap_docker)

    def run_mvs(self, job_id, stage, progress, name, args, cwd):
        if self.settings.openmvs_runtime != "docker":
            return self.run(job_id, stage, progress, [self.settings.executables()[name], *args], cwd)
        return self.run_container(job_id, stage, progress, name, args, cwd, OpenMvsDocker, self.settings.docker)

    def run_container(self, job_id, stage, progress, name, args, cwd, provider, config):
        # Container sees only this attempt, not sibling jobs, uploads or .env.
        expected_root = (self.settings.data_dir / job_id).resolve()
        if cwd.resolve().parent != expected_root or not cwd.name.startswith("attempt-"):
            raise ValueError("OpenMVS container must be scoped to a job attempt")
        from dataclasses import replace
        if name == "RefineMesh":
            config = replace(config, threads=1)  # Also bound Docker/OpenMP, not just CLI.
        docker_path = self.settings.executables().get(name)
        if not docker_path:
            raise RuntimeError("Docker executable is missing")
        prepared = provider(replace(config, binary=docker_path)).prepare(name, args, cwd)
        try:
            with prepared:
                try:
                    self.run(job_id, stage, progress, prepared.argv, cwd)
                except NativeCommandError as error:
                    if error.exit_code != 137:
                        raise
                    self.check(job_id)  # Cancellation/retry must not become an OOM error.
                    confirmed = prepared.observed_oom()
                    self.check(job_id)
                    detail = ("NATIVE_OUT_OF_MEMORY: Docker confirmed an out-of-memory event. "
                              if confirmed else
                              "NATIVE_KILLED: Process was killed; out-of-memory is not confirmed. ")
                    raise RuntimeError(detail + f"Stage {stage}, exit 137; container memory limit {config.memory_mb} MiB. "
                                       "Adjust the laptop processing memory budget before retrying. "
                                       "Original inputs are preserved; no automatic retry or quality fallback.") from error
        finally:
            if prepared.cleanup_error:
                try:
                    with (cwd / "pipeline.log").open("ab") as log:
                        log.write(b"\nWARNING: Docker could not confirm owned container removal. See native diagnostics.\n")
                except OSError:
                    pass  # Preserve the original failure/cancellation, not a logging error.
        if prepared.cleanup_error:
            raise RuntimeError(prepared.cleanup_error)

    def preflight(self, job_id, calibration):
        self.check(job_id)
        if not self.store.transition(job_id, {"processing", "calibrating"}, stage="checking_tools", progress=0):
            raise Cancelled()
        checker = self.native or NativeReadiness(self.settings)
        try:
            deadline = time.monotonic() + checker.timeout + 15
            while True:
                self.check(job_id)
                state = checker.snapshot()["runtime"]
                if state["checked"]:
                    needed = ("blender",) if calibration else TOOL_NAMES
                    failed = [name for name in needed if state["tools"][name]["status"] != "ready"]
                    if failed:
                        reasons = [f"{name}: {state['tools'][name]['reason']}"
                                   + (f" ({state['tools'][name]['hex_exit']})" if "hex_exit" in state['tools'][name] else "")
                                   for name in failed]
                        raise RuntimeError("Native tools failed startup: " + ", ".join(reasons)
                                           + ". See docs/NATIVE_RUNTIME.md; do not disable Windows protection.")
                    return
                if time.monotonic() >= deadline:
                    raise TimeoutError("Native startup check did not finish. See docs/NATIVE_RUNTIME.md.")
                self.stop.wait(.1)
        finally:
            if self.native is None:
                checker.close()

    def export(self, job, root, blender, final):
        work = root / job["work_dir"]
        models = sorted(work.glob("*texture*.obj"))
        if not models:
            raise RuntimeError("Textured OBJ missing; check the TextureMesh log")
        config = {"input": str(models[0]), "output": str(root / ("model.glb" if final else "preview.glb")),
                  "metadata_output": str(root / "model.json"), "final": final, "calibration": job.get("calibration"), "max_faces": 100000}
        config_path = work / "export.json"
        config_path.write_text(json.dumps(config), encoding="utf-8")
        self.run(job["id"], "calibrating" if final else "export_preview", 94,
                 [blender, "--background", "--factory-startup", "--python-exit-code", "1", "--python",
                  str(Path(__file__).resolve().parents[1] / "scripts" / "export_glb.py"), "--", str(config_path)], work)
        result = json.loads((root / "model.json").read_text(encoding="utf-8"))
        output = Path(config["output"])
        with output.open("rb") as f:
            if f.read(4) != b"glTF":
                raise RuntimeError("Exporter did not produce a valid GLB")
        self.check(job["id"])
        self.store.transition(job["id"], {"processing", "calibrating"}, status="completed" if final else "needs_review",
                              stage="completed" if final else "review_orientation_and_scale", progress=100, result=result)
