# Skill dành cho AR-SpaceFit

Skill là hướng dẫn làm việc cho coding agent. Bộ này được đặt tại `.agents/skills/` để đi cùng repository; không phải thư viện chạy app, mô hình AI được huấn luyện hay bằng chứng app đã hết lỗi. `AGENTS.md` quy định áp dụng bắt buộc theo phạm vi công việc. Cấu hình agent đầy đủ được chia thành `commands`, `references`, `rules`, `skills`, `workflows`; xem [mục lục .agents](../.agents/README.md).

## Bộ đã cài

| Skill | Khi dùng | Nguồn |
|---|---|---|
| `spacefit-android` | Kotlin/Compose, CameraX, Room migration, lifecycle, viewer GLB và AR | Viết riêng cho AR-SpaceFit |
| `spacefit-frontend` | Thiết kế UI/UX Compose, Material theme, accessibility, bàn phím, màn hình nhỏ và trạng thái lỗi/loading | Viết riêng cho AR-SpaceFit |
| `spacefit-backend` | FastAPI, job SQLite, auth/validation, upload, worker/cancel/retry | Viết riêng cho AR-SpaceFit |
| `api-design` | Hợp đồng REST, HTTP status/error, idempotency, tương thích Android | Thích nghi từ bộ skill người dùng cung cấp |
| `docker-patterns` | Dockerfile/context/isolation/resource limit/cleanup đúng phạm vi | Thích nghi từ bộ skill người dùng cung cấp |
| `systematic-debugging` | Tái hiện lỗi, truy nguyên nhân và thử giả thuyết có bằng chứng | Thích nghi từ bộ skill người dùng cung cấp |
| `test-driven-development` | Regression RED → GREEN → refactor cho hành vi sản phẩm | Thích nghi từ bộ skill người dùng cung cấp |
| `verification-before-completion` | Đối chiếu tuyên bố hoàn thành với bằng chứng đúng artifact/revision | Thích nghi từ bộ skill người dùng cung cấp |
| `spacefit-git-workflow` | Git/branch, review diff, conflict và chuẩn bị commit/PR trong phạm vi yêu cầu | Viết riêng cho AR-SpaceFit |
| `gh-address-comments` | Đọc và xử lý góp ý review trên PR GitHub | [OpenAI curated skill](https://github.com/openai/skills/tree/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/gh-address-comments) |
| `gh-fix-ci` | Chẩn đoán và sửa check PR thất bại trong GitHub Actions | [OpenAI curated skill](https://github.com/openai/skills/tree/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/gh-fix-ci) |
| `spacefit-devops` | Môi trường JDK/SDK/Python, Docker/build/tooling và CI/CD khi được yêu cầu | Viết riêng cho AR-SpaceFit |
| `spacefit-gen3d` | COLMAP/OpenMVS Docker, Blender, mesh/texture, crop, scale và kiểm vật thật | Viết riêng cho AR-SpaceFit |
| `spacefit-verification` | Chọn/chạy regression, unit/integration/black-box và đọc bằng chứng QA | Viết riêng cho AR-SpaceFit |
| `security-best-practices` | Khi có yêu cầu review bảo mật hoặc secure-by-default cho Python/FastAPI | [OpenAI curated skill](https://github.com/openai/skills/tree/main/skills/.curated/security-best-practices) |
| `security-threat-model` | Khi có yêu cầu phân tích nguy cơ, trust boundary và abuse path | [OpenAI curated skill](https://github.com/openai/skills/tree/main/skills/.curated/security-threat-model) |

Hai skill OpenAI được cài bằng helper `skill-installer` ngày 06/11/2026 từ `openai/skills`, ref `main`. Snapshot và checksum được ghi trong `.tools/qa/skills-validation-20261106-a61d9f/snapshot-sha256.json`; không tự coi `main` là ref bất biến. Giữ license/provenance đi kèm skill upstream. Các skill chuyên biệt dự án dùng mã và tài liệu đang có làm nguồn, không phụ thuộc một phiên bản model Codex cụ thể.

Sau khi tổ chức `.agents` theo mẫu người dùng, `security-best-practices` là bản thích nghi cho Python/FastAPI: đã bỏ 9 reference dành cho Go, JavaScript, Django và Flask, đồng thời thu hẹp mô tả kích hoạt. Giữ reference FastAPI, license và metadata. Bản gốc các reference được lưu có checksum trong `.tools/qa/agents-layout-71d1a1da0149414e86b4af7ecb784827/removed-references/`; có thể phục hồi từ đây. Snapshot cũ ở trên ghi thời điểm cài, không mô tả bản đã tinh gọn.

Ngày 07/11/2026 bổ sung 5 skill, tổng cộng **11 skill**. Hai skill GitHub được cài từ revision **`49f948faa9258a0c61caceaf225e179651397431`** của `openai/skills`; giữ license, tài nguyên và script upstream. Ba skill frontend/Git/DevOps được viết cho cấu trúc hiện tại. Bằng chứng và snapshot lượt này ở `.tools/qa/skills-expand-20261107-c83b/`. Thư mục `.agents` bị bảo vệ ghi trực tiếp nên các skill riêng được soạn/validate trong QA rồi cài bằng thao tác được cấp quyền; hai file routing cập nhật có bản gốc lưu tại QA.

Sau đó cùng ngày, theo ảnh bộ skill tham khảo, thêm **5 bản thích nghi**, tổng hiện tại **16 skill**. Nguồn local được tìm thấy tại `E:\AgriAI\.agents\skills\`; chỉ đọc các file skill/license cần thiết, không sửa project nguồn. Bản gốc `SKILL.md`/`LICENSE` được copy và đối chiếu checksum tại `.tools/qa/skills-from-reference-20261107-f27a/originals/`, manifest `source-manifest.json`. Các skill cài mới giữ nguyên license MIT/copyright và có phần hướng dẫn đã viết gọn cho AR-SpaceFit; không tuyên bố là bản upstream nguyên vẹn. Không mang script shell, context AgriVision, Playwright/xUnit hoặc các dependency web sang project này.

## Chọn từ bộ skill trong ảnh

| Nhóm trong ảnh | Quyết định |
|---|---|
| API design, Docker patterns, systematic debugging, TDD, verification before completion | Thêm bản thích nghi cho stack và quy tắc hiện tại |
| `gh-fix-ci`, `security-best-practices` | Đã có; không ghi đè bản đang dùng |
| `git-commit` | Đã được bao phủ bởi `spacefit-git-workflow`; không thêm quy tắc commit trùng |
| `coding-standards`, `qa-testing` | Bản nguồn có nhiều hướng dẫn TypeScript/web hoặc context riêng AgriVision; giữ rule và bộ QA/verification hiện có |
| `devops-iac-engineer` | Chưa dùng Terraform/Kubernetes/cloud IaC; đã có `spacefit-devops` cho công việc thực tế |
| ASP.NET Core, React, shadcn, Vercel composition, webapp-testing, GSAP | Chưa dùng trong Android/FastAPI nên không cài |
| PyTorch patterns | Chưa có pipeline PyTorch/train model trong sản phẩm |
| Design taste frontend, impeccable | Client đã có công cụ thiết kế khi cần; giao diện project dùng skill Compose riêng, không copy thêm bộ launcher/web stack |

API/Docker giữ nguyên hợp đồng và giới hạn hiện tại thay vì ép theo mẫu web. TDD không có quyền xóa mã đang có; chỉ thay đổi tài liệu/skill dùng validator phù hợp. Debugging không dump secret/environment hoặc tự đọc DB thật. Verification phân biệt bằng chứng lịch sử với lượt hiện tại và không bắt chạy lại suite nặng cho mỗi câu xác nhận.

## Cách dùng

Ở lượt tiếp theo, skill mới có thể được Codex nạp vào danh sách skill. Có thể gọi trực tiếp, ví dụ:

```text
$spacefit-android sửa lỗi màn hình lịch sử quét
$spacefit-backend kiểm tra lỗi retry bị tạo trùng job
$spacefit-frontend cải thiện form ghép nối khi bàn phím che nút
$spacefit-git-workflow kiểm tra thay đổi trước khi chuẩn bị commit
$gh-address-comments xử lý các góp ý đã chọn trên PR
$gh-fix-ci chẩn đoán check GitHub Actions thất bại
$spacefit-devops kiểm tra môi trường build và Docker
$api-design review hợp đồng API tải preview và model
$docker-patterns review mount và cleanup container native
$systematic-debugging điều tra lỗi trước khi sửa
$test-driven-development viết regression tái hiện hành vi lỗi
$verification-before-completion kiểm tra bằng chứng bàn giao
$spacefit-gen3d kiểm tra GLB bị tối và chất lượng texture
$spacefit-verification kiểm thử thay đổi vừa làm
```

Nếu client chưa làm mới danh sách, mở lại phiên làm việc trong thư mục project. Agent vẫn có bảng định tuyến và đường dẫn `SKILL.md` trong `AGENTS.md` để đọc trực tiếp; không dựa riêng vào việc tự chọn skill của client.

Khi task chạm nhiều phần, dùng các skill liên quan: thay API reconstruction thường cần backend, Android và verification; sửa native chain cần Gen3D và verification; thiết kế UI cần frontend rồi Android khi triển khai; tooling/CI dùng DevOps, thao tác Git dùng Git workflow. Bảo mật/threat model là quy trình chuyên biệt theo yêu cầu, không tự tạo báo cáo audit cho mọi thay đổi nhỏ.

`security-best-practices` bản trong project chỉ giữ hướng dẫn Python/FastAPI; không dùng nó để tuyên bố đã audit Kotlin. Frontend hiện là Android/Compose, nên chưa cài React/Next.js hoặc Playwright. Chỉ thêm bộ web khi có yêu cầu xây web app; không thêm cloud deployment hoặc Figma vào bộ bắt buộc khi chưa có nhu cầu tương ứng.

## Công nghệ và dịch vụ được bao phủ

| Nhu cầu | Skill/cách hỗ trợ |
|---|---|
| Backend HTTP/API, persistence, migration | Backend + verification + security khi đúng phạm vi; SQLite hiện có, không tự thêm PostgreSQL/Redis |
| Giao diện di động | Frontend UX + Android + QA emulator; không dùng browser test thay test Android |
| Git và GitHub PR/review | Git workflow + hai skill GitHub; cần đúng repository/PR và xác thực CLI khi thực sự dùng |
| CI/build/Docker | DevOps; GitHub Actions qua `gh-fix-ci`; native reconstruction thêm Gen3D |
| Bảo mật và giới hạn dữ liệu | Hai skill bảo mật và rule data/runtime |

Máy có GitHub CLI, nhưng lượt cài skill không kiểm đăng nhập hoặc quyền repository, không tạo CI, commit, push, PR hay gọi dịch vụ trả phí. Khi thực sự làm việc GitHub, kiểm xác thực ở bước cần dùng và không in token. Skill upstream có quy trình làm rõ review/plan khi cần; thông tin và quyền đã có trong task phải được dùng, không hỏi lại điều đã được người dùng chốt.

## Duy trì

Khi đổi cấu trúc, API, cách chạy test hoặc runtime native, cập nhật skill dự án nếu hướng dẫn của nó bị ảnh hưởng. Đổi mô tả kích hoạt đúng phạm vi, giữ tự chọn skill cho các skill riêng; không thêm xác nhận/phê duyệt cho thao tác vốn đã được người dùng cho phép.

Khi cập nhật skill bên ngoài, xem thay đổi `SKILL.md`, reference, script và tool/dependency trước khi áp dụng; không tự chạy script được tải về vào dữ liệu thật. Dùng installer với revision được chọn rõ ràng và không ghi đè skill đã có mà chưa kiểm tra khác biệt. Validator kiểm metadata/cấu trúc, không chứng minh chất lượng quyết định của agent.

Kết quả validate và giới hạn nằm trong [TEST_RESULTS.md](TEST_RESULTS.md); nhiệm vụ còn lại xem [WORK_STATUS.md](WORK_STATUS.md).
