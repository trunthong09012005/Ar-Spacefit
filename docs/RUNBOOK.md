# Chạy và nghiệm thu AR-SpaceFit

## Tự mở backend trên máy hiện tại bằng PowerShell

1. Mở Docker Desktop và chờ engine hoạt động. Cắm điện thoại USB, bật USB debugging và chấp nhận kết nối nếu được hỏi. Không restart Docker/WSL vì có thể còn dịch vụ khác.
2. Mở PowerShell (hoặc terminal PowerShell trong VS Code), chạy:

```powershell
Set-Location 'E:\ar-spacefit'
& 'C:\Users\GIGABYTE\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 11046644BW001504 reverse tcp:8000 tcp:8000
.\tools\Start-Backend.ps1 -ListenHost 127.0.0.1 -Port 8000
```

Giữ cửa sổ terminal này mở và không cho máy ngủ khi đang xử lý. Khi đổi điện thoại, dùng `adb devices` để chọn serial mới; sau khi rút/cắm USB hoặc khởi động lại máy, chạy lại lệnh reverse. Script dùng virtualenv/cấu hình native hiện có; không cần kích hoạt virtualenv bằng tay hoặc gõ token vào dòng lệnh.

3. Mở `http://127.0.0.1:8000/health` trên trình duyệt laptop: phải trả `service=AR-SpaceFit`, `status=ok`. Nếu báo cổng 8000 đã được sử dụng, kiểm health trước; không tự kill process hoặc mở thêm worker. Health chỉ xác nhận HTTP, không chứng minh pipeline native sẵn sàng.
4. Trong app: **Quét 3D → biểu tượng laptop**, URL `http://127.0.0.1:8000`. Người dùng tự lấy giá trị sau `SPACEFIT_TOKEN=` trong `backend/.env`, nhập vào ô token rồi chọn **Lưu và kiểm tra**. Không gửi token/nội dung `.env` vào chat hoặc báo cáo. Chờ native kiểm tra xong; chỉ tạo mô hình khi app báo sẵn sàng.

Muốn dừng backend, đợi tác vụ quét hoàn tất rồi nhấn `Ctrl+C` trong terminal; không đóng Docker/terminal giữa tác vụ. Startup có thể chạy các job `queued`/`calibration_queued`, đánh dấu job bị gián đoạn thành failed; không tự retry job failed. Nếu không chắc còn tác vụ nào, kiểm trong app trước khi dừng/khởi động lại. Không đổi ExecutionPolicy, App Control hoặc firewall để vượt lỗi; gửi thông báo lỗi đã che token để chẩn đoán. Cách USB này không cần mở backend ra LAN.

## Bản Android

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2' # Thay bằng JDK thực sự có trên máy.
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
# Khi có nhiều thiết bị: dùng adb -s SERIAL install -r với APK bên dưới.
```

APK: `apps/android/build/outputs/apk/debug/app-debug.apk`, package `com.spacefit.app`. Android tối thiểu 7.0 (API 24); xuất PNG cần Android 8 trở lên. AR cần thiết bị được ARCore hỗ trợ và Google Play Services for AR. Phòng 3D, kho và bố trí vẫn dùng được trên máy không có ARCore.

Cài bằng `adb -s SERIAL install -r apps/android/build/outputs/apk/debug/app-debug.apk` để cập nhật và giữ dữ liệu hiện có. Không uninstall hoặc clear data nếu chưa sao lưu model/project cần giữ. App không tự sao lưu ảnh quét, GLB và thông tin ghép nối lên cloud hoặc chuyển máy.

Nếu báo `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, bản APK và bản cài cũ có chữ ký khác nhau. Dừng cập nhật trên điện thoại thật; cần build bằng khoá cũ hoặc phương án sao lưu được chủ dự án xác nhận. Không gỡ app để vượt lỗi. Chỉ emulator `SpaceFitQA` dành riêng cho kiểm thử được phép thay bản cài và dữ liệu fixture.

Lỗi chữ ký ngày 02/10 là lịch sử: ngày 04/10 chủ máy xác nhận bản thử không có dữ liệu cần giữ, tự gỡ và đã được cài mới APK thành công; không cần khóa cũ cho lần cài sạch này. Các lần cập nhật sau phải giữ khóa ký hiện tại, không gỡ/clear dữ liệu. COLMAP/OpenMVS hiện dùng Docker; xem [trạng thái công việc](WORK_STATUS.md) và [native runtime](NATIVE_RUNTIME.md) trước khi thử scan mới. Backend `/health` OK không chứng minh pipeline chạy được.

Chủ máy đã cho phép OpenMVS Docker và ngày 03/10 chọn thêm COLMAP Docker. Chạy `tools/Start-Backend.ps1` như cũ; hai config `backend/native-docker.json` và `backend/native-colmap-docker.json` tự chọn runtime tương ứng; preflight chỉ khôi phục chia sẻ riêng workspace nếu WSL mất mount. Muốn kiểm tra riêng: `tools/Ensure-OpenMvsDockerWorkspace.ps1`. Không tắt bảo vệ Windows hoặc restart Docker khi đang có dịch vụ khác. Nếu Docker Desktop chưa chạy, hãy mở nó trước; startup/import fixture chưa chứng minh video cũ đủ để quét. Gate đầy đủ thêm `-RequireColmapDocker` bên cạnh `-RequireDocker -RequireNative`; xem [TESTING.md](TESTING.md).

Ứng dụng hiện khởi động vào `SpaceFitWorkspace`. `MainAppContainer` cũ còn trong source để giữ đối chiếu bản trước và các kiểm thử UI cũ; không còn là màn hình khởi động.

## Bạn cần chuẩn bị

- Một điện thoại Android và cáp USB; bật USB debugging và chấp nhận thông báo ghép nối trên điện thoại.
- Blender portable đã cài; xem [hướng dẫn backend](../backend/README.md).
- Một chiếc ghế/bàn có bề mặt nhiều chi tiết, không bóng, không trong suốt. Dùng ánh sáng ổn định.
- Thước dây để đo kích thước tham chiếu và ba chiều đối chứng.
- Bộ ảnh/video do bạn chụp. Không cần huấn luyện mô hình AI.

## Demo 1: mô hình thật

1. Chạy backend, dùng `adb reverse`, kết nối app bằng URL + token.
2. Tạo scan, đo và nhập chiều rộng, chụp tối thiểu 50 ảnh. Khuyến nghị ba vòng ở độ cao thấp, ngang và cao, khoảng 20–40 ảnh/vòng.
3. Generate kiểm tra startup native trước upload. Nếu đang kiểm tra, đợi khoảng một phút rồi bấm lại; nếu thiếu/lỗi công cụ thì xử lý nguyên nhân theo NATIVE_RUNTIME.md, không tắt bảo vệ Windows. Quan sát stage sau upload; worker cũng kiểm tra tại checking_tools trước reconstruction.
4. Kiểm tra preview: có đúng vật thể không, có nền thừa không, chân hướng xuống chưa. Crop/orient và xác nhận scale.
5. Kiểm tra GLB cuối cùng, xoay và zoom. Ghi số polygon, dung lượng, ba chiều; đối chiếu thước đo.
6. Lưu My Furniture, đóng/mở lại app, tắt mạng và mở lại GLB để kiểm tra lưu cục bộ.

## Demo 2: bố trí

1. Nhập phòng chữ nhật hoặc đo ba góc A–B–C trên cùng mặt sàn bằng ARCore. A–B là chiều rộng, B–C là chiều sâu; hai cạnh phải gần vuông góc. Chiều cao nhập bằng thước.
2. Đánh dấu cửa đi/cửa sổ trong thiết lập phòng: tường, khoảng cách từ đầu tường, rộng/cao, bệ cửa và vùng trống trước cửa.
3. Thêm model đã scan vào phòng. Di chuyển trên mặt bằng, chỉnh góc, snap vào tường, nhân bản, bỏ khỏi phòng. Mọi đồ vật đặt trên sàn, không tự đổi kích thước thật.
4. Cho hai đồ chồng lên nhau, vượt tường hoặc chặn vùng cửa để quan sát cảnh báo. Khi xoay chéo, kiểm tra va chạm theo polygon quay.
5. Chọn “Đồ nào vừa đây?”, kéo một vùng và chọn món có vị trí thử hợp lệ. Ứng dụng xét tâm hợp lệ gần tâm vùng nhất và lưới 11×11 ở góc 0°/90°, không chỉ giữa vùng; kiểm tường/trần/đồ/cửa nhưng chưa bảo đảm khoảng cách khuyến nghị hay toàn tuyến đi lại. Món chưa xác nhận scale, sai kích thước hoặc chưa tìm được vị trí sẽ bị vô hiệu hóa kèm lý do. Kết quả được kiểm lại trước thêm; nếu dữ liệu đổi, chọn lại vùng.
6. Khóa một món, tạo gợi ý bố trí, kiểm tra đồ khóa giữ vị trí. Thuật toán chỉ trả phương án đủ đồ, hợp lệ với cửa và khoảng cách giữa đồ.
7. Lưu thiết kế, tạo thiết kế thứ hai, mở lại thiết kế đầu. Mỗi thiết kế giữ bản chụp số đo phòng và model metadata lúc lưu.

## Demo 3: AR và hình ảnh

1. Mở AR, quét sàn cho đến khi tracking ổn định.
2. Chạm **tâm phòng** để neo thiết kế; xoay/dịch để khớp hướng và vị trí. Hiện chưa tự đăng ký mô hình phòng với căn phòng thật.
3. Đổi vị trí quan sát; kiểm tra tỷ lệ bằng vật/thước thực. Tracking có thể trôi, không dùng thay dụng cụ đo.
4. Chọn ảnh Before, xuất PNG của góc nhìn thiết kế, mở Before/After. After là ảnh thiết kế đã xuất gần nhất.

Khung PNG được chụp trước khi mở hộp thoại lưu, giữ bản tạm trong cache và ghi đúng ảnh đó sau khi chọn nơi lưu. Huỷ hộp thoại không tạo After. Ảnh tạm được dọn sau xử lý; bộ kiểm thử xác minh PNG đã ghi qua SAF cho cả 3D và mặt bằng trên emulator QA. Vòng đời AR dài và thiết bị thật vẫn cần nghiệm thu riêng.

Ảnh 3D được lấy trực tiếp từ SurfaceView của renderer; ảnh mặt bằng được lấy từ vùng canvas. Chờ model hiển thị trước khi xuất. Trên emulator, app có chế độ GPU tương thích tắt hậu xử lý; không dùng màu ảnh emulator để đánh giá màu texture. Điện thoại giữ hậu xử lý bình thường.

## Phạm vi nghiệm thu trung thực

- Kiểm thử tự động xác nhận hợp đồng dữ liệu, hình học, trạng thái job và lưu trữ; không chứng minh mesh quét giống vật thật.
- Đếm ảnh và ML Kit cảnh báo chạm mép không chứng minh đủ 360°. Tỷ lệ camera COLMAP đăng ký được là một kiểm tra bổ sung, vẫn cần xem model.
- Room scan hiện là phòng chữ nhật từ ba điểm AR; chưa tự dựng toàn bộ tường, trần hoặc phòng không vuông.
- Cửa được đánh dấu tay. Vùng mở cửa là vùng chữ nhật giữ trống; chưa mô phỏng cung mở cánh cửa hoặc tìm đường đi liên tục.
- Auto Layout là thuật toán tìm vị trí cục bộ có quy tắc, không phải mô hình AI tạo sinh. Luồng Gemini 5 ảnh vẫn có riêng và cần API key; reconstruction không cần Gemini.
- Muốn phát hành production cần bổ sung quản trị người dùng, hàng đợi nhiều worker, HTTPS/cơ chế ghép nối production và kiểm thử tải.
