# Gitflow cho AR-SpaceFit

## Vai trò và luồng nhánh

- `main`: lịch sử bản đã phát hành. Hiện vẫn là commit ban đầu, chưa phải bản được nghiệm thu.
- `develop`: tích hợp các phần đang phát triển; không commit tính năng trực tiếp vào đây.
- `feature/<muc-tieu>`: tách từ `develop`, hoàn thành một mục tiêu rồi review và merge về `develop`.
- `release/<version>`: tách từ `develop` khi chuẩn bị phát hành; chỉ sửa lỗi và hoàn thiện tài liệu phiên bản. Khi đủ điều kiện, merge vào `main`, tạo tag `v<version>`, rồi merge lại `develop`.
- `hotfix/<muc-tieu>`: tách từ `main` để sửa lỗi bản đã phát hành; sau kiểm chứng merge về `main` và `develop` (và release đang mở nếu cần).

Chỉ tạo release/hotfix khi có phiên bản hoặc lỗi phát hành cụ thể. Không tạo tag hoặc đưa bản chưa nghiệm thu vào main. Backend và Android vẫn là một sản phẩm, không phải hai nhánh tồn tại vĩnh viễn.

Dùng Git CLI có sẵn; mô hình này không yêu cầu cài extension `git flow`. Commit theo [rule của project](../.agents/rules/git-and-commits.md) và [skill Git](../.agents/skills/spacefit-git-workflow/SKILL.md). Chưa cấu hình branch protection hoặc CI bắt buộc trên GitHub.

## Chia phần đang có

Các nhánh ban đầu cùng xuất phát từ commit gốc. Khi nhập khối thay đổi cũ, mỗi nhánh tiếp theo lấy mốc develop đã tích hợp phần trước. Thay đổi chưa commit nằm trong worktree chung; đổi tên nhánh không tự chia chúng. Dùng danh sách file riêng cho từng commit, review staged diff rồi merge `--no-ff` về develop. Có thể dùng worktree tích hợp riêng để không phải bỏ dở phần mã còn chưa commit trong worktree chính.

| Thứ tự | Nhánh | Phạm vi commit dự kiến |
|---|---|---|
| 1 | `feature/repository-foundation` | `.gitignore` và bỏ metadata scaffolding không dùng; chưa đổi đường dẫn mã Android ở bước này. |
| 2 | `feature/backend-gen3d` | `backend/spacefit/`, `backend/scripts/`, requirements và `.env.example` backend đã kiểm placeholder; test backend tương ứng nếu chạy được độc lập. |
| 3 | `feature/native-docker` | `infra/`, script setup/workspace/startup Docker trong `tools/`; test tương ứng. Phụ thuộc backend contract. |
| 4 | `feature/android-app` | Chuyển `app/` sang `apps/android/`, chuyển test Android sang `test/android/`, ánh xạ settings/source sets, script icon và thay đổi hành vi Android, schema Room/migration. Đây là nhập baseline Android đã phát triển, không phải commit chỉ đổi tên thư mục. Phụ thuộc hợp đồng backend. |
| 5 | `feature/quality-tests` | Phần test tích hợp/black-box/native còn lại trong `test/`, `pytest.ini`, Test-All và Test-Support; không gom mọi regression vào đây nếu chúng thuộc commit chức năng trước. |
| 6 | `feature/project-governance` | `.agents/`, AGENTS, CONTRIBUTING, README và tài liệu hiện hành, báo cáo/ảnh fixture được phép chia sẻ. |

Đây là nhóm nhập baseline cũ theo thành phần, không phải tái tạo lịch sử từng tính năng chưa từng được commit. Một file có thể chứa thay đổi của nhiều mục tiêu: với công việc mới, dùng `git add -p` hoặc chuẩn bị patch riêng thay vì đưa cả file vào một commit không đúng phạm vi. Di chuyển file phải stage cả đường dẫn cũ và mới. Không dùng lệnh khôi phục ghi đè bản đang làm.

Không mô tả baseline Android này là một commit chỉ chuyển thư mục: bản mới chứa cả tính năng chưa có trong commit gốc. Không dùng `git reset --hard`, `git clean`, `git add .` hoặc tự stash toàn bộ để vượt công việc dang dở. Kiểm chứng kết hợp trên snapshot cuối của develop; từng commit nhập baseline trung gian chưa được nghiệm thu độc lập. Giữ rõ blocker Docker/UI trong mô tả thay vì xem việc commit là dấu hiệu test đã đạt.

## Công thức cho tính năng mới sau khi worktree đã sạch

Ví dụ sau dành cho một lỗi riêng sau khi đã tích hợp các phần nền; không chạy nguyên khối trên worktree hiện đang có nhiều phần chưa commit:

```powershell
git status --short
git switch develop
git switch -c feature/scan-progress
# Sửa mã và thêm regression, chạy kiểm tra theo docs/TESTING.md.
git add -- path/to/reviewed-source path/to/related-test
git diff --cached --stat
git diff --cached --check
git diff --cached
git commit -m "fix(android): correct scan progress state"
# Sau review, kiểm chứng và khi việc tích hợp đã được cho phép:
git switch develop
git merge --no-ff feature/scan-progress -m "chore(repo): merge scan progress fix"
```

Thay các đường dẫn ví dụ bằng file thật đã review. Mỗi commit dùng `type(scope): subject` (mục tiêu ≤72 ký tự), ví dụ:

```text
refactor(repo): organize Android module and test sources
feat(backend): add persistent reconstruction jobs
build(docker): configure native reconstruction containers
feat(android): integrate furniture reconstruction workflow
test(qa): add isolated pipeline and HTTP checks
docs(repo): define Gitflow and project development rules
```

Kiểm staged diff, conflict marker, dữ liệu riêng tư và kết quả kiểm thử trước commit. Không commit `.env`, token/key, DB, dữ liệu quét, `.venv`, native binary, build/APK hoặc QA output. Xem [TESTING](TESTING.md) và [kết quả thực tế](TEST_RESULTS.md); Docker/UI chưa đạt không được ghi thành PASS trong commit.

Push, tạo PR, merge, tag và phát hành là các thao tác riêng cần đúng phạm vi cho phép. Chưa push nhánh không có nghĩa nhánh đã có trên GitHub. Không force-push hoặc sửa lịch sử dùng chung.
