# AR-SpaceFit

Ứng dụng Android quét nội thất thật, dựng mô hình 3D trên laptop, lưu GLB và hỗ trợ bố trí trong phòng.

## Bắt đầu

- [Cấu trúc repository và quy trình phát triển](docs/REPOSITORY_STRUCTURE.md)
- [Hướng dẫn đóng góp](CONTRIBUTING.md)
- [Skill bắt buộc theo phạm vi dự án](docs/SKILLS.md)
- [Mục lục tài liệu](docs/README.md)
- [Hướng dẫn chạy và nghiệm thu](docs/RUNBOOK.md)
- [Backend: cài công cụ, ghép nối và API](backend/README.md)
- [Kiến trúc, dữ liệu và thuật toán](docs/ARCHITECTURE.md)
- [Báo cáo kỹ thuật và kịch bản demo](docs/reports/BAO_CAO_KY_THUAT.md)
- [Nội dung 12 slide thuyết trình](docs/reports/SLIDE_CONTENT.md)
- [Kết quả kiểm thử và phần còn cần nghiệm thu](docs/TEST_RESULTS.md)
- [Quy tắc làm việc cho những lần sửa sau](AGENTS.md)
- [Chạy unit/integration/black-box và checklist nghiệm thu](docs/TESTING.md)
- [Thư mục test tập trung](test/README.md)
- [Việc đang làm và còn dang dở](docs/WORK_STATUS.md)

## Chức năng đã triển khai trong mã

- Kotlin / Jetpack Compose / CameraX / Room Database v4.
- Furniture Scanner: chụp tay hoặc tự chụp 1,5 giây; nhập bộ ảnh JPEG/PNG hoặc video MP4; lịch sử phiên lưu cục bộ.
- Kiểm tra ảnh mờ, cảnh báo vật thể chạm mép qua ML Kit, hướng dẫn ba vòng chụp.
- Backend FastAPI có upload, job bền vững, progress, lỗi, cancel/retry, tải artifact.
- Pipeline OpenCV → COLMAP → OpenMVS → Blender → preview GLB → người dùng xác nhận crop/hướng/scale → GLB đơn vị mét.
- Xem GLB có texture bằng SceneView/Filament, xoay/zoom, số polygon; lưu My Furniture và dùng lại ngoại tuyến.
- Phòng 3D và mặt bằng có di chuyển, xoay, nhân bản, snap tường; lưu nhiều project.
- Đo phòng chữ nhật từ ba điểm sàn bằng ARCore; nhập chiều cao và đánh dấu cửa bằng tay.
- SpaceFit kiểm tra polygon xoay, chiều cao, khoảng cách đồ/tường, vùng cửa; tìm đồ vừa vùng chọn.
- Gợi ý bố trí theo quy tắc, giữ đồ khóa, chỉ trả phương án đủ đồ và hợp lệ.
- AR Preview neo thiết kế lên sàn, chỉnh hướng/dịch chuyển; Before/After và xuất PNG.
- Luồng nhận diện Gemini 5 góc và mô hình tham số cũ được giữ như lựa chọn riêng.

## Chạy Android

Android nằm ở `apps/android/`, vẫn là Gradle module `:app`. Mở Android Studio và chạy các lệnh dưới đây từ **gốc repository**. Backend ở `backend/`, image Docker ở `infra/docker/`, báo cáo ở `docs/reports/`; dữ liệu chạy và cache được Git bỏ qua.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2' # Thay bằng JDK thực sự có trên máy.
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat installDebug
```

APK nằm ở `apps/android/build/outputs/apk/debug/app-debug.apk`, package `com.spacefit.app`. Luồng reconstruction không cần Gemini API key. Chỉ luồng nhận diện 5 góc dùng `GEMINI_API_KEY` trong `.env` ở gốc dự án.

Backend có `.env` riêng, không dùng chung file API key Android. Chạy `tools/Start-Backend.ps1`, sau đó ghép nối trong **Quét 3D → biểu tượng laptop**. Hướng dẫn chi tiết và phần cần làm trên điện thoại nằm trong runbook.

## Giới hạn cần xác nhận bằng thực nghiệm

Mã triển khai và kiểm thử tự động không thay thế thử quét đồ thật. Kết quả reconstruction phụ thuộc ảnh, nền, ánh sáng và bề mặt. Cần kiểm tra/loại nền, chỉnh hướng, đo scale trước khi dùng GLB để đánh giá độ vừa.

Room scan hiện hỗ trợ phòng chữ nhật qua ba điểm; cửa và chiều cao nhập tay. AR Preview cần người dùng đặt tâm phòng và chỉnh hướng, chưa tự khớp căn phòng đã scan. Vùng cửa là vùng chữ nhật quy ước, chưa là cung mở cánh cửa hay kiểm tra toàn tuyến đi lại. Gợi ý bố trí là heuristic cục bộ, không phải AI tạo sinh hay nghiệm tối ưu toàn cục.

Backend dành cho laptop/mạng tin cậy, một worker. Bản debug cho phép HTTP localhost/LAN; release cần HTTPS. Các công cụ native và bộ ảnh quét thật phải sẵn sàng mới xác nhận được cột mốc hoàn chỉnh: **ảnh chiếc ghế thật → GLB đúng chiếc ghế → xem trên điện thoại**.
