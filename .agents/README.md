# Cấu hình làm việc của agent

Đọc [AGENTS.md](../AGENTS.md) ở gốc trước. Thư mục này tổ chức hướng dẫn theo vai trò:

Bộ nguyên tắc điều phối chung nằm tại [system-rules.md](rules/system-rules.md); AGENTS yêu cầu đọc ở đầu mỗi lần làm. Các rule chất lượng và dữ liệu/runtime vẫn là nguồn chi tiết tương ứng.

| Thư mục | Nội dung |
|---|---|
| [commands](commands/check.md) | Công thức lệnh: kiểm trạng thái, chạy project, chạy test |
| [references](references/project-context.md) | Chỉ mục tới tài liệu và nguồn sự thật của project |
| [rules](rules/quality.md) | Quy tắc chất lượng, dữ liệu và runtime dùng chung |
| [skills](../docs/SKILLS.md) | Skill Android/frontend/backend, GitHub, DevOps, Gen3D và QA theo phạm vi |
| [workflows](workflows/code-change.md) | Quy trình sửa mã và đánh giá mô hình quét |

`commands`, `rules`, `references`, `workflows` là tài liệu Markdown được `AGENTS.md` định tuyến; không tự đăng ký slash command hoặc tự chạy lệnh. Skill cần giữ cấu trúc riêng `SKILL.md`, `agents/openai.yaml` và tài nguyên mà nó thực sự dùng. Các thư mục `agents/` hoặc `references/` bên trong một skill thuộc skill đó, không chuyển chúng lên cấp ngoài.

Không lưu secret, DB, log, ảnh/video thật hoặc output build ở đây. Trạng thái và kết quả thực thi tiếp tục nằm trong `docs/WORK_STATUS.md`, `docs/TEST_RESULTS.md` và thư mục bằng chứng QA, không sao chép thành nhiều nguồn trạng thái.
