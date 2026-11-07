# Hạ tầng

- `docker/openmvs/`: image CPU OpenMVS chính thức, provenance/checksum và Dockerfile.
- `docker/colmap/`: image CPU COLMAP, phiên bản gói và Dockerfile.

Thiết lập từ gốc repo bằng `tools/Setup-OpenMvsDocker.ps1` và `backend/scripts/setup_colmap_docker.py`; đọc README của từng image trước khi build. Script tạo build context riêng chỉ chứa file cần thiết; không chạy `docker build .` ở gốc repo. Cấu hình máy cục bộ vẫn nằm trong `backend/native-docker.json` và `backend/native-colmap-docker.json`, không nằm trong version control.

Không lưu dữ liệu job hoặc bí mật trong image. Quy tắc mount, pin image và cleanup ở [AGENTS.md](../AGENTS.md) và [native runbook](../docs/NATIVE_RUNTIME.md).
