# Sửa lỗi hoặc phát triển chức năng

1. Đọc AGENTS, trạng thái và các rule được định tuyến; kiểm dirty worktree và trạng thái thật của thành phần liên quan.
2. Xác định luồng sở hữu và chọn skill Android/frontend/backend/Gen3D theo phạm vi; dùng DevOps khi sửa tooling/CI và Git workflow khi task yêu cầu Git/PR. Nếu yêu cầu là chẩn đoán, cung cấp nguyên nhân/bằng chứng; chỉ triển khai khi yêu cầu bao gồm sửa hoặc xây dựng.
3. Khi sửa mã, thêm regression tái hiện lỗi hoặc trường hợp thành công/đầu vào sai/biên của chức năng. Giữ thay đổi tập trung; cập nhật cả consumer Android/backend nếu hợp đồng chung đổi.
4. Chạy test liên quan rồi gate theo [verification](../skills/spacefit-verification/SKILL.md). Lưu bằng chứng mới và giữ các lượt FAIL.
5. Cập nhật WORK_STATUS/TEST_RESULTS và bàn giao hành vi thay đổi, bằng chứng, giới hạn còn lại. Build thành công không đồng nghĩa đã deploy hoặc nghiệm thu vật thật.

Các công thức lệnh ở [check](../commands/check.md), [test](../commands/test.md), [run](../commands/run.md). Chọn phần cần cho task; không tự đưa commit, publish, audit hoặc thao tác dữ liệu thật vào phạm vi.
