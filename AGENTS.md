# AR-SpaceFit — quy tắc làm việc lâu dài

## Bắt đầu mỗi lần làm

- Đọc file này và `docs/WORK_STATUS.md`; đọc thêm `docs/TESTING.md` khi sửa mã hoặc kiểm thử.
- Đọc `.agents/rules/system-rules.md` và `.agents/rules/data-and-runtime.md` ở đầu mỗi lần làm; đọc `.agents/rules/quality.md` khi sửa mã hoặc kiểm thử. Đây là các quy tắc bắt buộc được định tuyến từ file này, không phải tài liệu tùy chọn.
- Kiểm tra `git status`, giữ nguyên thay đổi/dữ liệu của người dùng. Xác nhận trạng thái thật của server, thiết bị và job; không suy ra từ báo cáo cũ.
- Giao tiếp bằng tiếng Việt. Tiếp tục việc trong phạm vi yêu cầu; báo rõ phần cần người dùng thao tác, không hứa “hoàn hảo” hoặc tự nhận hoàn tất khi chưa có bằng chứng.

## Skill dự án — bắt buộc theo phạm vi

Skill được lưu cùng repository tại `.agents/skills/`. Đọc `SKILL.md` tương ứng trước khi làm phần thuộc phạm vi bên dưới; không cần tải cả bộ cho mọi nhiệm vụ. Khi môi trường chưa đưa skill vào danh sách, vẫn đọc trực tiếp file được trỏ ở đây. Đọc thêm `docs/SKILLS.md` khi cài/cập nhật skill.

| Công việc | Skill phải đọc và áp dụng |
|---|---|
| Sửa/chẩn đoán Kotlin, Compose, CameraX, Room, GLB viewer, ARCore | `.agents/skills/spacefit-android/SKILL.md` |
| Thiết kế/review/cải thiện UI/UX Compose, accessibility, trạng thái màn hình | `.agents/skills/spacefit-frontend/SKILL.md`; thêm Android khi triển khai mã |
| Sửa/chẩn đoán FastAPI, SQLite job, API upload/artifact, worker | `.agents/skills/spacefit-backend/SKILL.md` |
| Thiết kế/review hợp đồng REST, status/error/pagination và tương thích Android | `.agents/skills/api-design/SKILL.md`; thêm backend khi triển khai |
| Git, branch, review diff, conflict, chuẩn bị commit/PR theo yêu cầu | `.agents/skills/spacefit-git-workflow/SKILL.md` và `.agents/rules/git-and-commits.md` |
| Xử lý góp ý review của PR GitHub | `.agents/skills/gh-address-comments/SKILL.md` |
| Chẩn đoán/sửa check GitHub Actions thất bại | `.agents/skills/gh-fix-ci/SKILL.md` |
| Môi trường phát triển, Docker/build/tooling và cấu hình CI được yêu cầu | `.agents/skills/spacefit-devops/SKILL.md` |
| Thiết kế/review Dockerfile, context, mount, resource limit và lifecycle container | `.agents/skills/docker-patterns/SKILL.md`; thêm Gen3D khi đổi native contract |
| Điều tra nguyên nhân bug, lỗi test, kết quả bất thường | `.agents/skills/systematic-debugging/SKILL.md` cùng skill thành phần liên quan |
| Thay đổi hành vi/sửa lỗi mã sản phẩm: regression RED-GREEN | `.agents/skills/test-driven-development/SKILL.md` |
| Tái dựng/texture/scale/crop, Blender, COLMAP/OpenMVS, Docker native | `.agents/skills/spacefit-gen3d/SKILL.md` |
| Thay đổi mã sản phẩm hoặc yêu cầu kiểm thử/nghiệm thu | `.agents/skills/spacefit-verification/SKILL.md` |
| Kiểm tra bằng chứng trước khi báo hoàn tất/fix/build/test đạt | `.agents/skills/verification-before-completion/SKILL.md`; không thay thế test runner |
| Yêu cầu review bảo mật hoặc triển khai secure-by-default cho Python/FastAPI | `.agents/skills/security-best-practices/SKILL.md` và reference FastAPI phù hợp |
| Yêu cầu threat model, phân tích trust boundary/abuse path | `.agents/skills/security-threat-model/SKILL.md` |

Skill hỗ trợ quy trình, không phải chứng nhận chất lượng hay dependency chạy app. Giữ đúng phạm vi task và ưu tiên chỉ dẫn người dùng; không dùng skill làm lý do tự mở rộng sang audit, deploy, thao tác dữ liệu thật hoặc cài công cụ ngoài yêu cầu. Bộ skill bảo mật upstream không có hướng dẫn Kotlin chuyên biệt. Chỉ cài/cập nhật skill không yêu cầu chạy lại toàn bộ test sản phẩm; phải validate skill và ghi rõ kiểm thử đã/chưa chạy.

## Điều hướng cấu hình agent

Xem `.agents/README.md` để hiểu cấu trúc. Khi bắt đầu triển khai/sửa lỗi, đọc `.agents/workflows/code-change.md`; khi đánh giá quét thật, đọc `.agents/workflows/scan-quality.md`. Chọn `.agents/commands/{check,run,test}.md` khi cần công thức lệnh và `.agents/references/project-context.md` khi cần xác định nguồn tài liệu. Các file commands là hướng dẫn Markdown, không tự đăng ký slash command hoặc tự cấp quyền chạy thao tác.

## Bản đồ ngắn

- Android: `apps/android/src/main/java/com/example/`; test JVM `test/android/unit/`; test thiết bị `test/android/instrumentation/`. Gradle vẫn dùng module `:app` từ gốc repo.
- Backend: `backend/spacefit/`; native scripts `backend/scripts/`; test `test/backend/`.
- Mọi bài test mới đặt trong `test/` ở gốc, chia backend và Android; không tạo lại thư mục test rải rác trong source ứng dụng. Giữ output build/evidence runtime ở vị trí bị ignore riêng.
- Hạ tầng Docker: `infra/docker/{openmvs,colmap}/`; script vận hành: `tools/`. Xem `docs/REPOSITORY_STRUCTURE.md` trước khi thêm module/thư mục mới.
- Báo cáo: `docs/reports/`; log rời đã lưu: `artifacts/diagnostics/` (bị gitignore); bằng chứng QA: `.tools/qa/`. Giữ nguyên đường dẫn và dữ liệu `backend/data/`, `.venv`, `.env`.
- Thiết lập: `docs/RUNBOOK.md`, `backend/README.md`. Kiến trúc: `docs/ARCHITECTURE.md`.
- Không tự nâng đồng loạt dependency, publish/deploy hoặc tạo commit nếu nhiệm vụ không cần.
