# Phát triển AR-SpaceFit

Đọc [README](README.md), [cấu trúc repository](docs/REPOSITORY_STRUCTURE.md), [kiến trúc](docs/ARCHITECTURE.md) và [trạng thái hiện tại](docs/WORK_STATUS.md) trước khi sửa. Quy tắc chi tiết dành cho agent nằm trong [AGENTS.md](AGENTS.md).

Agent dùng [bộ skill dự án](docs/SKILLS.md) theo bảng phạm vi trong `AGENTS.md`: Android, backend, Gen3D và verification. Skill bảo mật áp dụng khi có yêu cầu bảo mật/threat model tương ứng.

## Vị trí thay đổi

- Android: mã sản phẩm/debug ở `apps/android/`; source set test nằm ở `test/android/unit/` và `test/android/instrumentation/`.
- Backend: `backend/spacefit/`; kiểm thử trong `test/backend/`; CLI quản trị và export trong `backend/scripts/`.
- Image native: `infra/docker/`; orchestration PowerShell dùng `tools/`.
- Tài liệu dùng chung: `docs/`; bản thảo học thuật: `docs/reports/`.

Mọi bài test mới đặt trong [test/](test/README.md), chia Android/backend; giữ fixture/helper cạnh nhóm sở hữu. Không tạo lại `backend/tests/` hoặc `apps/android/src/test/`.

Thêm thành phần vào thư mục sở hữu phù hợp; tránh tạo `utils`, `common`, `shared` chung cho mọi thứ khi chưa có nhu cầu dùng chung rõ ràng. Không tách microservice/Gradle module hoặc đổi application ID chỉ để thay hình thức cây thư mục.

## Quy trình thay đổi

Áp dụng [Gitflow](docs/GITFLOW.md): `main` dành cho phát hành, `develop` tích hợp, `feature/*` cho từng mục tiêu; chỉ tạo `release/*` và `hotfix/*` khi có nhu cầu cụ thể. Tài liệu này có bảng chia phần chưa commit và các giới hạn với worktree đang có nhiều thay đổi.

Commit theo `type(scope): mô tả`, ví dụ `fix(gen3d): preserve dense scene between native stages`. Type, scope, cách stage/push và file không được đưa lên Git nằm trong [quy tắc Git/commit](.agents/rules/git-and-commits.md). Quy tắc này chưa phải hook/commitlint tự động.

1. Kiểm tra `git status`; bảo toàn thay đổi đang có của người khác.
2. Giữ thay đổi tập trung vào một mục tiêu. Khi đổi đường dẫn, cập nhật build, script, test và tài liệu sử dụng đường dẫn đó.
3. Bổ sung regression test cho lỗi; chức năng mới cần đường thành công, lỗi và đầu vào biên. DB cần migration có kiểm tra dữ liệu cũ/mới.
4. Chạy test liên quan rồi [cổng kiểm thử](docs/TESTING.md). Ghi đúng nhóm chạy, pass/fail/skip và điều kiện môi trường chưa đáp ứng.
5. Cập nhật `docs/WORK_STATUS.md` và `docs/TEST_RESULTS.md`; bàn giao kèm giới hạn còn lại. Không tự commit, publish hay deploy nếu chưa có yêu cầu.

## Chạy từ thư mục gốc

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\tools\Start-Backend.ps1 -ListenHost 127.0.0.1 -Port 8000
.\tools\Test-All.ps1 -JavaHome 'DUONG_DAN_JDK' -BlenderBin 'DUONG_DAN_BLENDER'
```

Các lệnh là thao tác riêng: kiểm tra server/job trước khi mở backend; chỉ cài/chạy instrumentation trên emulator QA đủ điều kiện. Cấu hình JDK, SDK và Python theo [runbook](docs/RUNBOOK.md). APK ở `apps/android/build/outputs/apk/debug/`.

Không commit `.env`, private key/keystore, DB thật, ảnh/video cá nhân, native binaries, cache, thư mục build hay output QA. `schemas/` của Room và các `.env.example` dùng placeholder phải được quản lý cùng mã nguồn. Test dùng token giả và data dir riêng; không dùng DB dịch vụ thật.
