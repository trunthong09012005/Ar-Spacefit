# SpaceFit AR

**Ứng dụng hỗ trợ bố trí nội thất bằng nhận diện đa góc nhìn và mô hình 3D tham số.**

## Mục tiêu đồ án

SpaceFit AR giúp người dùng kiểm tra một món nội thất có phù hợp với căn phòng trước khi mua:

1. Nhấn bắt đầu quét; ML Kit tìm vật thể nổi bật và khung theo dõi tự bám sát vật thể thay vì dùng khung cố định.
2. Quét đủ năm hướng của cùng một món nội thất: tổng thể, trái, phải, trên và dưới. Ảnh được cắt gần theo vùng vật thể đã khóa.
3. Gemini đối chiếu cả năm ảnh để nhận diện loại đồ, màu sắc, vật liệu và đề xuất thông tin ban đầu.
4. Xem mô hình đại diện, kéo để xoay 360°, sửa loại đồ, màu và hình dáng tham số. Đo và xác nhận lại kích thước phủ bì.
5. Lưu mô hình đã chỉnh vào kho hoặc đặt ngay vào phòng 3D; có thể mở “Chỉnh 3D” trong kho để sửa tiếp.
6. SpaceFit Engine kiểm tra ranh giới, va chạm, khoảng trống và diện tích chiếm dụng.
7. Ứng dụng hiển thị SpaceFit Score cùng cảnh báo có thể giải thích được.

## Phạm vi chính

- Sáu nhóm nội thất: sofa, ghế, bàn, giường, tủ và đèn.
- CameraX kết hợp ML Kit Object Detection để phát hiện và theo dõi vật thể thời gian thực.
- Quy trình bắt buộc năm hướng: tổng thể, trái, phải, trên và dưới.
- Nhận diện đa ảnh bằng Gemini; chỉ phân tích sau khi đã có đủ năm hướng.
- Xem trước 3D tương tác và sửa tên, loại đồ, màu, chất liệu, kích thước. Giữ phần thập phân, hỗ trợ dấu phẩy khi nhập cm.
- Ghế/sofa: chỉnh tỷ lệ cao mặt ngồi và tay vịn. Bàn: chỉnh độ dày mặt. Tủ: chỉnh 1–4 cánh. Các loại khác dùng hình dáng cơ bản.
- Mọi mô hình được chuẩn hóa theo kích thước phủ bì trước khi đặt vào phòng, để phần hiển thị thống nhất với hình học kiểm tra độ vừa.
- Nếu AI thiếu số đo, để trống cho người dùng nhập; không điền kích thước hay chất liệu giả. Kết quả chưa xác nhận cùng một món nội thất bị từ chối.
- Kho nội thất cục bộ bằng Room Database.
- Phòng có chiều rộng/chiều sâu chỉnh được.
- Đặt, di chuyển, xoay và đổi tỷ lệ vật thể.
- Phát hiện vật thể ra ngoài phòng hoặc va chạm với vật thể khác.
- SpaceFit Score được tính cục bộ và có unit test.

Thử quần áo, dựng lại mô hình 3D chính xác từ một ảnh và tự động thu thập dữ liệu website không thuộc phạm vi đồ án chính.

Đồ án không cần tự huấn luyện mô hình. Gemini đảm nhiệm nhận diện ảnh và gợi ý tham số; ứng dụng dựng mesh từ tham số đã xác nhận. Chỉ cần tự chụp một tập đồ thật nhỏ để đánh giá, không phải bộ dữ liệu train. Hai phần có thể trình bày rõ trong đồ án là quy trình chỉnh mô hình có người xác nhận và `SpaceFitEngine` có quy tắc hình học xác định, giải thích được, có kiểm thử.

Ứng dụng không nạp sẵn nội thất giả: kho và phòng 3D bắt đầu trống. Chỉ dữ liệu do người dùng quét hoặc tự nhập mới được lưu vào Room Database.

## SpaceFit Score

Điểm được tính xác định trong ứng dụng, không do AI tự tạo:

- 40 điểm: vật thể nằm hoàn toàn trong phòng.
- 25 điểm: đáp ứng khoảng cách khuyến nghị 60 cm.
- 20 điểm: không va chạm vật thể khác.
- 15 điểm: tỷ lệ chiếm diện tích hợp lý.

Gemini được dùng để hiểu ảnh và tạo lời khuyên. Các kết luận hình học luôn do `SpaceFitEngine` tính toán.

## Cấu trúc Android chính

```text
app/src/main/java/com/example/
├── ai/          # Nhận diện ảnh bằng Gemini
├── ar/          # Viewport và mô hình hình học 3D
├── camera/      # CameraX
├── data/        # Room Database và kho nội thất
├── spacefit/    # Luật không gian và SpaceFit Score
└── ui/          # Scanner, kho nội thất và màn hình hỗ trợ
```

Repository hiện là dự án Android thuần Kotlin/Jetpack Compose; mã nguồn và cấu hình Flutter cũ đã được loại bỏ.

## Chạy đúng phiên bản

Phiên bản được duy trì và kiểm thử nằm trong module Android `app`, có application ID `com.spacefit.app`.

Để cài đúng bản debug lên thiết bị đang kết nối:

```powershell
.\gradlew.bat installDebug
```

Sau đó mở ứng dụng **SpaceFit AR** thuộc gói `com.spacefit.app`.

## Cấu hình

Tạo `.env` ở thư mục gốc:

```properties
GEMINI_API_KEY=your_api_key
```

Không commit API key thật. Với sản phẩm phát hành công khai, nên gọi dịch vụ AI qua backend thay vì đặt khóa bí mật trong ứng dụng.

## Kiểm thử

Mở thư mục gốc bằng Android Studio và chạy cấu hình test của module `app`, hoặc dùng PowerShell:

```powershell
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

Bộ kiểm thử bao gồm hình học SpaceFit; đủ năm ảnh trước khi gọi AI; phân tích JSON không tự tạo dữ liệu còn thiếu; kích thước phủ bì và biến đổi mesh; chỉnh/xác nhận số đo trên giao diện; lưu/mở lại tham số; nâng cấp Room từ v2 lên v3 không mất dữ liệu người dùng. Để xuất ảnh giao diện kiểm thử, chạy `./gradlew.bat testDebugUnitTest -Proborazzi.test.record=true`; ảnh trình chỉnh nằm ở `app/build/reports/model-editor*.png`.

Các kiểm thử này không đo độ chính xác nhận diện đồ thật và không thay thế thử camera trên thiết bị. Xem [kịch bản demo và cách đánh giá](docs/BAO_CAO_VA_DEMO.md).

## Giới hạn được công bố

- Kích thước Gemini trả về chỉ là ước lượng tham khảo; người dùng phải xác nhận lại.
- ML Kit bám theo vật thể nổi bật trong camera; Gemini mới là thành phần xác định loại nội thất sau khi đủ năm hướng.
- Năm hướng hiện được hướng dẫn theo từng bước, chưa được xác minh bằng pose/IMU. Đánh giá cùng vật thể của Gemini cũng có thể sai; không quảng cáo là tái dựng đa góc chính xác.
- View camera hiện là lớp nền thử nghiệm cho viewport 3D, chưa phải plane tracking/anchor hoàn chỉnh của ARCore; giao diện vì vậy ghi rõ “3D” hoặc “mô phỏng”.
- Mô hình là hình học tham số đại diện, chưa có texture lấy từ ảnh, mesh xuất GLB/OBJ hay tái dựng photogrammetry. Tham số không quan sát được dùng mẫu cơ bản và được công bố ngay trong trình chỉnh.
- SpaceFit hiện dùng bao chữ nhật trục song song trên mặt sàn để kiểm tra va chạm; khi xoay chéo có thể cảnh báo bảo thủ. Khoảng trống đang là khoảng cách tới tường, chưa phải kiểm tra toàn tuyến đi lại, cánh cửa mở hay va chạm mesh 3D chi tiết.
- Bản demo vẫn gọi Gemini từ Android và cần mạng/API key; trước khi phát hành công khai phải đưa khóa sang backend.

Các giới hạn này là hướng phát triển tiếp theo, không được trình bày như chức năng đã hoàn thiện trong báo cáo.
