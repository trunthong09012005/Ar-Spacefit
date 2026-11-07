# Chất lượng và điều kiện bàn giao

Các quy tắc dưới đây được chuyển từ `AGENTS.md`; đọc trước khi thay đổi mã hoặc kiểm thử.

- Mỗi sửa lỗi phải có regression test tái hiện; mỗi chức năng mới phải có test đường thành công, đầu vào sai/biên và lỗi liên quan.
- Unit test: thuật toán/validation/codec. Integration test: DB, HTTP client/API, pipeline và trao đổi giữa các thành phần. Black-box: hành vi quan sát được qua HTTP hoặc giao diện, không gọi nội bộ để giả lập kết quả thành công.
- Chạy test liên quan trong lúc làm; trước bàn giao thay đổi mã chạy `tools/Test-All.ps1` (xem tuỳ chọn trong `docs/TESTING.md`). Nếu thiếu công cụ/thiết bị, ghi chính xác test chưa chạy và cách chạy lại; skip không phải pass.
- Không nới assertion, tắt test, che lỗi hoặc thay bằng mô hình giả chỉ để có màu xanh. Fixture phải ghi rõ là fixture; native render cần kiểm tra pixel, không chỉ callback “loaded”.
- Không báo 100% coverage khi chưa đo; tổng số test không chứng minh toàn bộ sản phẩm đúng. Phải phân biệt unit, integration có mock, black-box, Blender thật và quét/AR vật thật.
- Nghiệm thu pipeline phải thêm `-RequireNative`: kiểm tra khởi động/DLL thật, không chỉ tìm thấy binary. Lỗi môi trường là blocker, không đổi thành PASS hoặc bỏ qua âm thầm.
- `/capabilities.ready` phải dựa trên startup hợp lệ, không dựa vào đường dẫn tồn tại. Kết quả đang kiểm tra/hết hạn/lỗi không cho Android upload; worker phải preflight cả khi retry trực tiếp. Calibration chỉ cần Blender, không ép OpenMVS khi chỉ xuất lại model.
- Cập nhật `docs/TEST_RESULTS.md` với ngày, lệnh, số pass/fail/skip, nơi lưu bằng chứng; cập nhật `docs/WORK_STATUS.md` với việc còn lại và blocker trước khi kết thúc.

Chỉ thay đổi tài liệu/cấu hình agent: kiểm tra liên kết và validate skill bị ảnh hưởng; không cần chạy lại toàn bộ suite sản phẩm. Cách chọn và đọc bằng chứng chi tiết nằm trong [skill verification](../skills/spacefit-verification/SKILL.md) và [TESTING.md](../../docs/TESTING.md).
