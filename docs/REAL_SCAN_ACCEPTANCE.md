# Thu thập lại và nghiệm thu vật thật

Cập nhật 03/10/2026. Đây là checklist chưa hoàn thành, không phải kết quả thực nghiệm đạt.

## Đầu vào đã thử

Ghế nhựa xanh trong video 9,42 giây, 480×848. Người dùng cung cấp rộng 15 cm và dài 25 cm; chưa có chiều cao. Lượt retry 01/10 chỉ đăng ký được 40/150 camera, cần 90. Không có GLB hoàn tất để đo sai số hay đánh giá texture.

Đường chạy OpenMVS Linux Docker đã được cấu hình. Lượt lịch sử `run-20261002-155146-f1aa238f` xác nhận đủ 7 native startup và 11 bài Docker integration đạt; xem [NATIVE_RUNTIME.md](NATIVE_RUNTIME.md) và [TEST_RESULTS.md](TEST_RESULTS.md). Lỗi DLL của bản OpenMVS Windows là lịch sử của runtime cũ, không còn là blocker mặc định của đường Docker. Kết quả startup không chứng minh chất lượng tái dựng; trước bộ mới vẫn phải kiểm tra readiness hiện tại, chỉ tạo job khi startup hợp lệ.

Ngày 03/10 COLMAP Windows cũng bị chặn DLL; chủ máy chọn chuyển thêm COLMAP sang Docker CPU 3.9.1. Fixture mapper/undistortion/OpenMVS đã chạy thật, nhưng video cũ chưa được retry và không có model ghế mới. Kết quả gate ở TEST_RESULTS mới nhất thay cho tình trạng runtime ngày 02/10; bộ ảnh/video mới vẫn cần vật đứng yên và số đo độc lập.

## Người dùng cần thu thập

1. Để vật đứng yên trong toàn bộ lượt quay. Người quay đi quanh vật; không dùng tay xoay/nhấc vật trong khung hình.
2. Ánh sáng ổn định, tránh gương, vật phản chiếu và người chuyển động. Vật cần đủ chi tiết bề mặt; nhựa trơn có thể vẫn khó tái dựng dù quay đủ góc.
3. Đi chậm đủ một vòng ở thấp, ngang và cao; giữ vật trọn khung, ảnh liên tiếp chồng lấp nhiều. Chú ý chân, mặt ngồi và lưng ghế. Có thể chụp khoảng 20–40 ảnh mỗi vòng thay vì video quá ngắn; ứng dụng nhận tổng 50–150 ảnh rõ.
4. Không đổi zoom/camera giữa lượt, không lia nhanh, kiểm tra ảnh rõ trước khi gửi. Không đưa mặt người hoặc thông tin riêng tư vào video.
5. Đo lại rộng/dài/cao phủ bì bằng thước và ghi điểm đo. Rộng 15 cm sẽ là tham chiếu scale; dài 25 cm chỉ dùng làm chiều đối chứng độc lập sau khi xác nhận lại, không nhập để ép model khớp cả hai chiều.

## Nghiệm thu sau khi có đầu vào mới

| Bước | Bằng chứng bắt buộc | Trạng thái hiện tại |
|---|---|---|
| Thu thập | Video/ảnh vật đứng yên, số đo gốc | BLOCKED — cần quay/chụp lại |
| Runtime | Startup hợp lệ trên ổ data đang dùng, đủ 7 công cụ | PASS ở gate `run-20261003-230251-d2231af7`, cả COLMAP/OpenMVS Docker; cần kiểm lại trước phiên thực tế mới |
| Reconstruction | Stage/log, camera đăng ký, preview đúng vật | FAIL với video cũ; BLOCKED bộ ảnh/video mới, chưa có preview đạt |
| Crop/hướng | Xem mesh/texture và phần nền dư | BLOCKED — chưa có preview đạt |
| Scale | GLB đơn vị mét, rộng theo tham chiếu | BLOCKED — chưa có model |
| Đối chứng | Dài/cao độc lập, sai số tuyệt đối/tương đối | BLOCKED — chưa có GLB, thiếu chiều cao |
| Android | Model/texture ngoại tuyến, kho → bố trí | Fixture renderer đã đạt; vật thật chưa đạt |
| AR | Máy tương thích, thước phòng, thời lượng/trôi anchor | BLOCKED — cần kiểm tra phòng thật |

Ghi cả lần thất bại. Không dùng fixture cyan hay model tham số để thay kết quả photogrammetry. Thời gian mapper thất bại khoảng 11,5 phút không phải thời gian tái dựng thành công; chưa có số liệu FPS, sai số hay tỷ lệ thành công để công bố.

## Phiếu ghi cho từng lần quét

Sao chép bảng này vào thư mục bằng chứng runtime riêng của từng lần thử. Giá trị chưa đo ghi `CHƯA ĐO`, không ghi 0 và không ước lượng. Giữ video/ảnh gốc ở thư mục bị gitignore; tài liệu chia sẻ chỉ liên kết bằng chứng đã lọc thông tin cá nhân.

| Nhóm | Trường cần ghi | Giá trị của lần thử mới |
|---|---|---|
| Định danh | Mã vật, scan ID, job ID, attempt, ngày/giờ, người thực hiện | CHƯA CÓ |
| Phiên bản | APK hash/phiên bản mã, model điện thoại, Android, image OpenMVS ID/digest | CHƯA GHI |
| Thu thập | Số ảnh/frame, số vòng/độ cao, ánh sáng, vật liệu/bề mặt, xác nhận vật đứng yên | CHƯA THU THẬP |
| Tham chiếu | Rộng phủ bì, điểm đo, thước/độ chia, đơn vị cm; trục dùng để scale | Người dùng đã nêu 15 cm; cần xác nhận lại cho bộ mới |
| Đối chứng | Dài/sâu và cao phủ bì, điểm đo, đơn vị cm; không dùng để ép scale | Đã nêu dài 25 cm; chưa có cao, cần xác nhận lại |
| Runtime | Thời điểm readiness/startup, kết quả 7 công cụ, vị trí diagnostic | CHƯA KIỂM CHO PHIÊN MỚI |
| Reconstruction | Ảnh nhận/hợp lệ/đăng ký, component được chọn, stage kết thúc, lỗi, thời gian từng stage | CHƯA CHẠY |
| Chất lượng | Ảnh trước/sau crop, hướng chuẩn, đủ chân/mặt/lưng, nền dư, lỗ, texture đúng vùng | CHƯA ĐÁNH GIÁ |
| Model | GLB hash, kích thước MB, số tam giác, W×D×H cm, texture nhúng | CHƯA CÓ GLB |
| Android | Thời gian mở đo được, xoay/zoom, offline, kho → project → mở lại, PNG xuất/mở | CHƯA KIỂM VẬT THẬT |
| Kết luận | PASS/FAIL/BLOCKED theo tiêu chí đã chọn, lý do và liên kết ảnh/log | BLOCKED ĐẦU VÀO |

Chiều tham chiếu dùng để scale phải được tách khỏi đánh giá sai số. Với chiều độc lập có số đo thước dương: `AE_cm = |d_model_cm - d_thuoc_cm|`, `RE_% = AE_cm / d_thuoc_cm × 100`. Nếu lặp nhiều lần, ghi từng kết quả trước khi tổng hợp; mẫu thất bại vẫn nằm trong tổng số lượt đã thử. Chưa chọn ngưỡng chấp nhận thì chỉ báo số đo, không tự kết luận đạt độ chính xác.

## Phiếu kiểm AR và điện thoại

Trước khi thử, cần APK cuối tương thích chữ ký hoặc phương án cập nhật giữ dữ liệu. Blocker chữ ký hiện được theo dõi trong [WORK_STATUS.md](WORK_STATUS.md); không gỡ app thật để bỏ qua lỗi `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Chạy các test ghi kho/project trên emulator QA; thử ghi dữ liệu trên điện thoại thật cần phạm vi được người dùng cho phép riêng.

| Phép thử | Dữ liệu và bằng chứng cần ghi | Trạng thái |
|---|---|---|
| Khả dụng AR | Model máy, Android, khả dụng ARCore/quyền camera và thông báo khi không hỗ trợ | CHƯA NGHIỆM THU |
| Đo phòng | Ba điểm A–B–C trên cùng sàn, W/D AR, W/D thước độc lập, chiều cao nhập tay, AE/RE | CHƯA ĐO |
| Neo và scale | Model đã hiệu chỉnh, vị trí neo ban đầu/cuối, khoảng cách thước đối chứng, thời lượng quan sát, drift đo được | CHƯA ĐO |
| Vòng đời/tracking | Đưa nền/mở lại, xoay máy, ánh sáng yếu, tracking lost/recovered, đặt lại neo; ảnh/log và kết quả từng tình huống | CHƯA KIỂM |
| Dùng ngoại tuyến | Sau tải xong ngắt mạng, mở lại GLB/texture/project và thử PNG ở cả hai view | CHƯA KIỂM TRÊN APK CUỐI/ĐIỆN THOẠI |

Phòng hiện được suy ra từ ba điểm sàn thành hình chữ nhật; chiều cao/cửa do người dùng nhập, không phải mesh quét toàn bộ tường/trần. Pixel GLB fixture trên emulator hoặc probe renderer điện thoại cũ không chứng minh AR tracking, sai số đo phòng hay scale của ghế thật. Ma trận đầy đủ 15 module: [REQUIREMENTS_ACCEPTANCE.md](REQUIREMENTS_ACCEPTANCE.md).
