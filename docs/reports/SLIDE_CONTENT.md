# Nội dung thuyết trình AR-SpaceFit

Bản nội dung 12 slide, khoảng 8–10 phút. Chưa xuất PowerPoint. Hình dùng ảnh ứng dụng và bộ scan thật, giữ nhãn nguồn. Không thay kết quả thực nghiệm còn thiếu bằng số minh họa.

## 1. AR-SpaceFit

Hệ thống quét nội thất và hỗ trợ bố trí không gian phòng.

Lời nói: giới thiệu sinh viên, giảng viên và mục tiêu. Điền thông tin cá nhân theo mẫu trường.

## 2. Bài toán bố trí

- Đồ vừa kích thước phòng vẫn có thể chắn cửa hoặc chồng đồ khác.
- Người dùng cần thử vị trí trước khi di chuyển đồ thật.
- Kho mô hình giúp dùng lại đồ đã quét.

Lời nói: minh họa một tình huống trong demo, không công bố lợi ích định lượng chưa đo.

## 3. Phạm vi hệ thống

- Android thu thập ảnh và hiển thị GLB.
- Laptop chạy photogrammetry.
- Phòng chữ nhật, cửa do người dùng xác nhận.
- ARCore cung cấp điểm sàn và anchor.

Lời nói: chiều cao phòng nhập tay, mesh toàn bộ tường/trần ngoài phạm vi.

## 4. Kiến trúc

Dùng sơ đồ triển khai trong ARCHITECTURE.md.

Lời nói: multipart với token, job trên SQLite, file trên đĩa. Android giữ GLB để xem offline. Nguồn: mã nguồn AR-SpaceFit.

## 5. Tái dựng

OpenCV chọn ảnh. COLMAP ước lượng camera. OpenMVS tạo bề mặt/texture. Blender chuẩn hóa và xuất GLB.

Lời nói: phân biệt thiết kế pipeline với bằng chứng chạy bộ ảnh thật. Nguồn: https://colmap.github.io/cli.html và https://github.com/cdcseacave/openMVS/wiki/Usage.

## 6. Hiệu chuẩn kích thước

`scale = số đo thật / extent model`

- Crop nền và chỉnh hướng trước khi xác nhận.
- Scale đồng đều giữ tỷ lệ.
- Đo sai số trên chiều độc lập với chiều hiệu chuẩn.

Lời nói: tham chiếu sửa scale nhưng không sửa biến dạng mesh. Chỉ thêm số liệu sau thực nghiệm.

## 7. Kho và phòng

Dùng ảnh GLB viewer và mặt bằng từ ứng dụng.

- My Furniture giữ model, thumbnail và metadata.
- Project lưu snapshot phòng/placement.
- Di chuyển, xoay và nhân bản giữ scale.

Lời nói: ảnh fixture phải ghi “Mô hình kiểm thử renderer”, không ghi “model quét thật”.

## 8. SpaceFit Engine

- SAT kiểm tra footprint xoay.
- Polygon clipping tính vùng cửa bị chắn.
- Khoảng cách hình học hỗ trợ clearance.
- Ràng buộc cứng kiểm tra cùng điểm số.

Lời nói: quay hai hộp dài để so sánh với AABB. Chưa chứng minh lối đi liên tục.

## 9. Tìm đồ và gợi ý

- Tâm vùng lựa chọn, góc 0° hoặc 90°.
- Gợi ý khảo sát lưới, giữ đồ khóa.
- Chỉ nhận phương án đầy đủ qua SpaceFit.

Lời nói: heuristic, kết quả rỗng chưa chứng minh không có nghiệm. Chưa dùng AI tạo sinh.

## 10. Kiểm thử

Dùng kết quả đã xác minh trong TEST_RESULTS.md lúc dựng slide.

Lời nói: tách unit/API/instrumentation khỏi đánh giá mesh thật. Thêm bảng W/D/H, thời gian và tỷ lệ thành công khi có.

## 11. Demo

Mở model, đặt vào phòng, chắn cửa, lưu thiết kế, thử AR trên máy tương thích.

Lời nói: video dự phòng phải quay bản chạy thật. Thông báo thiếu dữ liệu hoặc thiết bị trước demo.

## 12. Kết luận

Ứng dụng đã có mã nguồn các module scan, lưu trữ, bố trí và đánh giá hình học.

Blender đã cài và các test xuất/crop/scale đã đạt. Video thật đã thử nhưng thất bại ở đăng ký camera (40/150, cần 90); chưa có model ghế đạt yêu cầu. Nghiệm thu còn cần bộ ảnh/video mới của vật đứng yên, số đo đối chứng và máy ARCore. Ưu tiên scan thật trước khi mở rộng AI layout.

OpenMVS Windows từng bị Code Integrity chặn DLL. Đường Linux Docker đã được cấu hình và đạt startup/lifecycle trong gate lịch sử 02/10, không đổi chính sách Windows. Phải kiểm readiness hiện tại trước mỗi phiên; đủ công cụ khởi động không đồng nghĩa model đúng vật. Các con số test cuối phải lấy từ TEST_RESULTS.md sau khi chạy cổng bắt buộc.

Lời nói: cập nhật tình trạng sau thực nghiệm, không giữ tuyên bố chưa có bằng chứng.
