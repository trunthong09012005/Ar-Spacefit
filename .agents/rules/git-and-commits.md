# Git, commit và file được chia sẻ

Áp dụng khi chuẩn bị commit/push hoặc thay quy tắc Git. Dùng cùng [skill Git workflow](../skills/spacefit-git-workflow/SKILL.md), [quy tắc dữ liệu](data-and-runtime.md) và [CONTRIBUTING.md](../../CONTRIBUTING.md). Không tự commit/push chỉ vì đọc file này.

## 1. Định dạng commit

Dùng Conventional Commits:

```text
type(scope): mô tả ngắn thay đổi
```

| Type | Khi dùng |
|---|---|
| `feat` | Thêm chức năng |
| `fix` | Sửa lỗi |
| `refactor` | Tổ chức lại mã, không đổi hành vi dự kiến |
| `perf` | Cải thiện hiệu năng có căn cứ kiểm chứng |
| `test` | Thêm/sửa test |
| `docs` | Tài liệu, hướng dẫn và rule dạng Markdown |
| `build` | Build tool/dependency/package |
| `ci` | Workflow CI |
| `chore` | Bảo trì/cấu hình như `.gitignore` |
| `revert` | Hoàn tác một thay đổi được xác định rõ |

Scope gợi ý: `android`, `ui`, `backend`, `api`, `gen3d`, `docker`, `db`, `qa`, `docs`, `repo`. Chọn theo thành phần bị tác động chính; không cố nhét toàn bộ đường dẫn vào scope.

Tiêu đề ngắn, cụ thể, mục tiêu không quá 72 ký tự, không chấm cuối câu. Tiếng Anh dạng hành động là lựa chọn mặc định; có thể dùng tiếng Việt khi yêu cầu/nhóm làm việc chọn như vậy. Tránh `update`, `fix bug`, `done`, `final` đứng một mình. Không tự bịa mã ticket.

```text
feat(android): add scan history filters
fix(gen3d): preserve dense scene between native stages
test(api): cover unauthorized artifact downloads
docs(repo): explain project startup
chore(repo): ignore local secrets and build artifacts
```

Một commit nên giải quyết một mục tiêu có thể review. Thêm body khi cần giải thích nguyên nhân, ảnh hưởng hoặc cách kiểm chứng. Chỉ ghi lệnh/kết quả thật; không ghi token, đường dẫn riêng tư không cần thiết hay tuyên bố test đạt khi chưa chạy. Breaking change dùng `!` và footer `BREAKING CHANGE:` mô tả ảnh hưởng/migration; không coi đó là quyền tự phá tương thích.

Đây là quy ước được AGENTS yêu cầu áp dụng. Chưa có hook/commitlint tự động được cấu hình chỉ bằng việc tạo file này.

## 2. Chuẩn bị commit và push

Kiểm `git status`, branch đích và diff trước. Stage theo file cụ thể đã review; tránh `git add .` trong worktree đang chứa nhiều thay đổi/untracked. Với chuyển thư mục, xác nhận file ở vị trí mới trước khi stage cả phần xóa/cũ và thêm/mới. Giữ thay đổi ngoài task của người dùng.

Đọc staged diff và danh sách file, kiểm conflict marker và chạy kiểm tra cần thiết theo [TESTING.md](../../docs/TESTING.md). Kết quả gate FAIL hoặc kiểm tra chưa chạy phải được ghi đúng. Khi được phép commit, không tự hiểu đó là được phép push; xác minh remote/branch trước thao tác remote và không force-push khi chưa được yêu cầu.

## 3. Không đưa lên Git

[.gitignore](../../.gitignore) loại khỏi việc thêm file chưa được track các nhóm:

- `.env`/biến thể môi trường chứa secret, token/API key và cấu hình riêng máy.
- Keystore/private key/chứng chỉ có private key; thông tin ký release.
- DB thật/cục bộ và journal; `backend/data/` gồm ảnh/video/model/job thật.
- `.venv`, cache, log/crash dump, thư mục build, APK/AAB và profiling output.
- `.tools/`, `artifacts/`, `archive/` và hai cấu hình native Docker riêng máy.

Không blanket-ignore toàn bộ ảnh/video/GLB, JAR hay SQL: resource ứng dụng, fixture đã làm sạch, Gradle wrapper và migration có thể là source cần quản lý. Dữ liệu riêng tư phải ở runtime/QA bị ignore; không đưa bản quét thật ra thư mục source rồi mặc định an toàn.

## 4. Những file vẫn phải quản lý

- Mã nguồn, test, Dockerfile và script vận hành.
- Gradle wrapper (`gradlew*`, `gradle/wrapper/gradle-wrapper.jar`), version catalog và build configuration chia sẻ.
- Room schema JSON, migration và fixture đã được phép chia sẻ.
- `.env.example`, `.env.*.example`, `.env.template`, `.env.sample` chỉ khi nội dung là placeholder/giá trị giả.
- `.agents/`, AGENTS và tài liệu được phép chia sẻ; file public certificate `.crt` không bị chặn chỉ vì là certificate.

Tên file ví dụ hoặc việc không bị ignore không chứng minh nội dung không có secret. Không tạo ngoại lệ rộng để đưa key/DB thật vào Git; ngoại lệ fixture nếu cần phải cụ thể và được review.

## 5. Giới hạn của .gitignore

`.gitignore` không bỏ track file đã commit/stage và không xóa secret khỏi lịch sử. Kiểm `git ls-files -ci --exclude-standard` để tìm đường dẫn đã track nhưng nay khớp ignore; không đọc/in nội dung bí mật.

Nếu phát hiện secret đã được đưa lên repository, dừng việc phát tán thêm, báo rõ và xử lý theo phạm vi được cho phép; việc thu hồi/đổi secret, bỏ track và làm sạch lịch sử là các thao tác riêng. Không tự chạy `git rm --cached`, amend/rebase, xóa lịch sử hoặc force-push chỉ để làm sạch một báo cáo.
