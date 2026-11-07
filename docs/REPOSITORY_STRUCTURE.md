# Cấu trúc repository

AR-SpaceFit dùng monorepo: ứng dụng Android, backend tái dựng và hạ tầng được quản lý cùng nhau. Cấu trúc tổ chức theo trách nhiệm; chỉ tách thêm service/module khi có ranh giới chức năng và nhu cầu thực tế.

```text
ar-spacefit/
├── .agents/                 # commands, references, rules, skills, workflows
├── apps/
│   └── android/              # Gradle module :app
│       ├── src/main/         # Kotlin, manifest, resources
│       ├── src/debug/        # Công cụ chỉ dành cho debug/QA
│       ├── schemas/          # Lịch sử schema Room; cần version control
│       └── build.gradle.kts
├── backend/                  # Một service FastAPI/reconstruction
│   ├── spacefit/             # Package Python của service
│   ├── scripts/             # CLI quản trị, chẩn đoán, Blender exporter
│   ├── requirements*.txt
│   └── README.md
├── test/                    # Toàn bộ mã kiểm thử ứng dụng
│   ├── backend/             # pytest: unit/integration/HTTP/native
│   └── android/
│       ├── unit/            # JVM/Robolectric, screenshot fixtures
│       └── instrumentation/ # UI/device tests trên SpaceFitQA
├── infra/
│   └── docker/
│       ├── colmap/          # Dockerfile và nguồn gốc image COLMAP
│       └── openmvs/         # Dockerfile và nguồn gốc image OpenMVS
├── tools/                   # Entry point PowerShell: chạy, setup, kiểm thử
├── docs/
│   ├── README.md            # Mục lục tài liệu
│   ├── reports/             # Báo cáo đồ án và nội dung slide
│   ├── evidence/            # Bằng chứng fixture được phép version control
│   └── *.md                 # Kiến trúc, runbook, nghiệm thu, trạng thái, kết quả
├── gradle/                  # Wrapper và version catalog dùng chung
├── artifacts/               # Log/artifact cục bộ; không commit
├── .tools/                  # Native tools, AVD, kết quả QA cục bộ; không commit
├── AGENTS.md                # Quy tắc dành cho coding agent
├── CONTRIBUTING.md          # Quy trình phát triển và bàn giao
├── README.md                # Điểm vào dành cho thành viên mới
├── settings.gradle.kts      # Ánh xạ :app → apps/android
├── pytest.ini               # Discovery/package path của test/backend
└── gradlew / gradlew.bat     # Luôn chạy Gradle từ gốc repository
```

## Quy ước phụ thuộc và test

- Android giao tiếp backend qua HTTP; không truy cập trực tiếp DB Python hoặc thư mục job.
- Backend chứa nghiệp vụ tái dựng. Dockerfile thuộc `infra/`, script setup vẫn nằm trong `tools/` và `backend/scripts/`.
- Mã test tập trung ở `test/`; Gradle source set và root pytest.ini trỏ tới cây này. Test native/thiết bị phải được phân biệt với fixture/mock. Output build và evidence runtime ở thư mục riêng bị ignore.
- Chưa tách Android thành nhiều Gradle module hoặc thêm microservice: đây là thay đổi kiến trúc cần đánh giá riêng, không phải điều kiện để có cấu trúc repository rõ ràng.
- Package Kotlin `com.example`, application ID `com.spacefit.app` và lịch sử Room được giữ để không trộn việc sắp thư mục với migration ứng dụng.

## Dữ liệu và cấu hình cục bộ

`backend/data/`, `backend/.venv/`, `backend/.env`, hai `backend/native-*-docker.json`, `.env` Android ở gốc, `local.properties`, `.tools/`, `archive/` và output build được giữ tại vị trí hiện có và bị Git bỏ qua. Không commit chúng. Virtualenv và dữ liệu thật không được di chuyển theo mã nguồn vì có thể chứa đường dẫn tuyệt đối hoặc đang được tiến trình sử dụng.

`artifacts/diagnostics/root-20261106/` lưu 26 file log từng nằm ở gốc. Chỉ di chuyển, không xóa; nội dung có thể chứa thông tin cục bộ, không đưa vào Git. Kết quả test mới vẫn ở `.tools/qa/run-*/`; không sửa vị trí của bằng chứng lịch sử.

Ngày 07/11/2026, chuyển `public/` (chỉ chứa `assets/aistudio/.gitignore`) và `metadata.json` vào `archive/unused-scaffolding-20261107/`: không có tham chiếu từ mã nguồn, build hoặc script vận hành hiện hành. Đây là phần scaffolding không dùng của project; bản gốc được giữ nguyên để khôi phục khi cần. Không di chuyển môi trường, dữ liệu, cache hay bằng chứng QA.

## Thay đổi đường dẫn ngày 06/11/2026

Ngày 07/11/2026, mã test tiếp tục được gom riêng: `backend/tests/` → `test/backend/`, hai source set Android → `test/android/{unit,instrumentation}/`, `backend/pytest.ini` → `pytest.ini` ở gốc. Bảng dưới đây ghi lần di chuyển module trước đó; cây hiện hành ở trên và [test/README.md](../test/README.md) là nguồn dùng cho lệnh mới.

| Trước | Sau |
|---|---|
| `app/` | `apps/android/` |
| `backend/docker/{Dockerfile,.dockerignore,README.md}` | `infra/docker/openmvs/` |
| `backend/docker/colmap/` | `infra/docker/colmap/` |
| `docs/BAO_CAO_KY_THUAT.md` | `docs/reports/BAO_CAO_KY_THUAT.md` |
| `docs/BAO_CAO_VA_DEMO.md` | `docs/reports/BAO_CAO_VA_DEMO.md` |
| `docs/SLIDE_CONTENT.md` | `docs/reports/SLIDE_CONTENT.md` |
| `*.log` ở gốc | `artifacts/diagnostics/root-20261106/` |

Lệnh Gradle `:app:*`, `tools/Start-Backend.ps1`, `tools/Test-All.ps1` và đường dẫn backend không đổi. APK mới nằm ở `apps/android/build/outputs/apk/debug/app-debug.apk`. Mở thư mục gốc bằng Android Studio rồi Gradle Sync; không mở riêng `apps/android` như một Gradle root khác.

`WORK_STATUS.md` và `TEST_RESULTS.md` giữ nguyên các đoạn bằng chứng cũ: đường dẫn `app/` hoặc `backend/docker/` trong lịch sử là đường dẫn tại thời điểm thực thi, không phải lệnh vận hành hiện tại. Không viết lại log/JUnit lịch sử để khớp cấu trúc mới.
