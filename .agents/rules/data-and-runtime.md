# Dữ liệu và runtime

Các quy tắc dưới đây được chuyển từ `AGENTS.md`; đọc ở đầu mỗi lần làm.

- Không đọc/in token, API key hoặc commit `.env`, video cá nhân, DB thật, log có bí mật. Chỉ dùng token giả trong test.
- Backend test dùng data dir riêng và loopback port riêng; không dùng `backend/data/jobs.sqlite3`. Không xoá input gốc hay job thật. Không dừng worker đang chạy mà chưa xác định job/process.
- Test có ghi kho/project chỉ chạy emulator QA; không clear/uninstall app trên điện thoại thật. Test thiết bị thật chỉ dùng probe không ghi DB, trừ khi người dùng cho phép riêng.
- Trước UI test xác nhận emulator QA boot xong, không ANR/error dialog và đã ổn định. Lỗi môi trường vẫn là FAIL: giữ ảnh/log, chỉ phục hồi AVD QA không wipe rồi chạy lại; không nới assertion để vượt dialog hệ thống.
- `install -r` lỗi khác chữ ký: giữ bản cài/dữ liệu điện thoại, tìm khoá ký cũ; không gỡ app để vượt lỗi. Không tự tắt bảo vệ Windows, sửa Code Integrity/App Control hay thêm ngoại lệ rộng để chạy native tool.
- COLMAP/OpenMVS Docker dùng image ID/digest bất biến và mount riêng từng attempt/diagnostic; không mount DB job, `.env`, socket Docker hay cả ổ đĩa. Feature DB COLMAP chỉ ở attempt riêng. Không restart Docker/WSL khi còn dịch vụ khác. Startup phải kiểm tra ổ chứa data, không chỉ Temp; timeout/cancel/shutdown phải dọn đúng container của lệnh. Test chuyển SQLite giữa Windows/Linux phải đóng hẳn handle trước; transaction context không thay cho đóng connection.
- Test runner tạo thư mục run mới duy nhất. Không truyền thư mục có sẵn cho pytest `--basetemp` (pytest có thể xoá nó).
- Giữ migration DB không mất dữ liệu; sửa schema phải thêm migration và test dữ liệu trước/sau nâng cấp.
- Tất cả file GLB dùng bố trí phải có scale đã xác nhận; đơn vị xuất mét, kích thước UI cm. Preview photogrammetry chưa scale không phải kích thước thực.
- Chất lượng quét thật và AR cần ảnh/thiết bị thật; không tự bịa số đo, sai số, texture, thời gian hoặc tỷ lệ thành công.
