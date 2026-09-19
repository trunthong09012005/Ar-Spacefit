# Khung báo cáo và kịch bản demo SpaceFit

## Tuyên bố đúng phạm vi

Tên đề tài đề xuất: **Xây dựng ứng dụng hỗ trợ bố trí nội thất bằng nhận diện đa góc nhìn và mô hình 3D tham số**.

- Sản phẩm hiện tại là ứng dụng Android Jetpack Compose có tracking vật thể bằng ML Kit, nhận diện nội thất từ năm hướng bằng Gemini, kho nội thất cục bộ và bộ máy đánh giá không gian 3D.
- Không tự huấn luyện mô hình và không cần thu thập bộ dữ liệu lớn.
- Kích thước do AI trả về chỉ là gợi ý; người dùng bắt buộc kiểm tra và sửa trước khi tính điểm.
- Camera hiện là nền thử nghiệm. Plane detection và anchor ARCore là hướng phát triển, không phải tính năng đã hoàn thiện.
- Mô hình 3D là hình học tham số đại diện: xoay xem 360°, đổi loại, màu, kích thước, tỷ lệ mặt ngồi/tay vịn, độ dày mặt bàn và số cánh tủ. Không phải bản sao 3D dựng tự động từ ảnh.
- Ứng dụng yêu cầu năm lượt ảnh theo hướng dẫn; chưa xác minh góc thật bằng cảm biến/ước lượng pose. Gemini kiểm tra tính nhất quán ở mức suy luận, không bảo đảm tuyệt đối.

## Điểm mới nên nhấn mạnh

Luồng chính: ảnh đồ thật → AI nhận diện/gợi ý tham số → người dùng sửa trên mô hình 3D và xác nhận số đo → lưu cùng tham số → thử bố trí. Cùng một cấu hình mesh được dùng cho xem trước và phòng. Không gọi việc này là tái dựng ảnh thành bản sao 3D.

`SpaceFitEngine` tách phần suy luận AI khỏi kết luận hình học. Điểm số 0–100 được tính xác định từ bốn thành phần:

1. Vật thể nằm trong ranh giới phòng: 40 điểm.
2. Khoảng trống tới tường/lối đi: tối đa 25 điểm.
3. Không va chạm với nội thất khác: 20 điểm.
4. Tỷ lệ diện tích chiếm dụng hợp lý: tối đa 15 điểm.

Nhờ đó cùng một đầu vào luôn cho cùng một kết quả, có cảnh báo giải thích được và có thể kiểm thử tự động.

Giới hạn hình học: kiểm tra va chạm hiện dùng bao chữ nhật trục song song (AABB), nên đồ xoay chéo có thể bị báo va chạm sớm. Khoảng trống được tính tới tường, chưa xác minh tuyến đi lại giữa đồ đạc. Điểm số là quy tắc của bản thử nghiệm, không phải tiêu chuẩn thiết kế hay kết luận an toàn.

## Kịch bản demo 3–5 phút

1. Mở cài đặt phòng, nhập chiều rộng và chiều sâu thực tế.
2. Nhấn bắt đầu quét và cho thấy khung nhận diện tự bám sát vật thể, không dùng vùng ngắm cố định.
3. Quét đủ năm hướng của cùng một sofa hoặc ghế: tổng thể, trái, phải, trên và dưới. Cho thấy Gemini chưa chạy khi bộ quét chưa hoàn tất.
4. Sau hướng thứ năm, cho Gemini đối chiếu cả năm ảnh để nhận diện loại, màu và vật liệu.
5. Xoay xem mô hình, chỉnh loại đồ nếu cần, bật/tắt tay vịn hoặc đổi độ dày mặt bàn/số cánh tủ. Nhập số đo thật và đánh dấu xác nhận.
6. Lưu vào kho, mở lại “Chỉnh 3D” cho thấy tham số được giữ nguyên, rồi đưa vật thể sang phòng 3D.
7. Di chuyển vật thể tới vị trí hợp lệ để hiển thị điểm cao.
8. Kéo vật thể ra ngoài biên hoặc chồng lên vật khác để điểm giảm và cảnh báo thay đổi.
9. Kết thúc bằng kết quả unit test và giới hạn ARCore đã công bố.

## Dữ liệu đánh giá tối thiểu

Mốc khả thi đầu tiên: tự chụp **12 món đồ thật**, ưu tiên bàn, ghế, sofa và tủ; mỗi món năm hướng, kèm số đo phủ bì bằng thước. Khi luồng ổn định, mở rộng lên 30–50 món/bộ ảnh nếu có điều kiện. Ghi nguồn và xin phép khi chụp tại nhà/cửa hàng của người khác, tránh người và thông tin riêng tư trong ảnh. Đây là tập đánh giá, không phải tập train. Số lượng này là kế hoạch, chưa phải dữ liệu đã có hay bảo đảm đủ để khái quát hóa.

Với mỗi món, ghi loại thật, số đo thật, đặc điểm nhìn rõ (tay vịn, số cánh, độ dày mặt), kết quả AI trước sửa, các thay đổi người dùng, thời gian và lỗi. Đo:

- độ chính xác nhận diện loại nội thất;
- tỷ lệ màu/vật liệu hợp lý;
- sai số kích thước tham khảo so với số đo thật;
- thời gian phản hồi và tỷ lệ lỗi API.
- tỷ lệ tham số nhìn rõ được AI gợi ý đúng trước khi người dùng sửa;
- số thao tác và thời gian để có mô hình đại diện sử dụng được;
- tỷ lệ hoàn tất luồng quét → chỉnh → lưu → đặt vào phòng.

Không tính số đo sau khi nhập bằng thước là “AI đo chính xác”. Báo riêng kết quả AI không trả được số đo, không loại khỏi báo cáo để làm đẹp tỷ lệ. Dùng thêm ảnh mờ, khác vật và lặp góc để kiểm tra khả năng báo lỗi; ghi nhận cả những lần hệ thống chấp nhận nhầm. Đánh giá SpaceFit riêng bằng các tình huống có số đo/vị trí biết trước (vượt biên, chạm cạnh, chồng lấn, xoay).

Không báo cáo độ chính xác nếu chưa có bảng kết quả thực nghiệm. Không dùng dữ liệu giả để thay cho phản hồi API bị lỗi.

## Checklist trước khi nộp

- Chạy `./gradlew lintDebug testDebugUnitTest assembleDebug` và lưu ảnh kết quả.
- Quay video dự phòng của luồng demo để tránh phụ thuộc mạng tại buổi bảo vệ.
- Xóa khóa API khỏi gói mã nguồn nộp; chỉ giữ `.env.example`.
- Không đưa thư mục `build/`, `.gradle/`, `.idea/` hoặc `archive/` vào tệp nộp.
- Báo cáo đúng “3D/mô phỏng”; chỉ dùng “ARCore hoàn chỉnh” sau khi đã có plane tracking và anchor thật.
