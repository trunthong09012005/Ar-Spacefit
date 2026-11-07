# Hướng dẫn chạy project

**Project: AR-SpaceFit — Android + backend dựng mô hình 3D trên laptop.**

Cập nhật: 05/10/2026. Hướng dẫn dành cho máy Windows hiện tại, project ở `E:\ar-spacefit` và điện thoại kết nối bằng USB.

File `.md` có thể mở bằng VS Code, Android Studio hoặc Notepad. Khi sao chép lệnh, chỉ lấy phần trong khung lệnh, không chép dấu `PS C:\...>` hoặc `>>` của terminal.

## 1. Cần mở những gì?

- **Docker Desktop** trên laptop: chạy các công cụ COLMAP/OpenMVS.
- **Backend** trên laptop: nhận ảnh/video và điều khiển quá trình dựng 3D.
- **Ứng dụng AR-SpaceFit** trên điện thoại: chụp/nhập ảnh, gửi quét và xem kết quả.
- **Cáp USB truyền dữ liệu** và USB debugging: nối điện thoại với backend.

Không cần mở giao diện Blender, Android Studio hoặc máy ảo để dùng bản app đã cài. Backend tự gọi Blender khi cần xuất mô hình. Gen3D này không cần Gemini API key; “Nhận diện 5 góc và mô hình tham số” là luồng khác.

Các đường dẫn đang dùng trên máy này:

| Thành phần | Đường dẫn |
|---|---|
| Project | `E:\ar-spacefit` |
| Script mở backend | `E:\ar-spacefit\tools\Start-Backend.ps1` |
| Python của project | `E:\ar-spacefit\backend\.venv\Scripts\python.exe` |
| ADB | `C:\Users\GIGABYTE\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Blender | `D:\Blender\blender.exe` |
| JDK dùng để build | `C:\Program Files\Java\jdk-25.0.2` |
| APK debug | `E:\ar-spacefit\apps\android\build\outputs\apk\debug\app-debug.apk` |

Máy hiện tại đã thiết lập các công cụ này; không cần cài lại mỗi lần chạy. Nếu chuyển sang máy khác, xem [thiết lập backend](../backend/README.md) và [cấu hình native/Docker](NATIVE_RUNTIME.md), không chép nguyên đường dẫn máy này rồi mặc định là đã đủ.

## 2. Bước 1 — Mở Docker Desktop

1. Mở **Docker Desktop** từ Start Menu.
2. Chờ Docker Engine chạy xong, không còn ở trạng thái đang khởi động.
3. Giữ Docker hoạt động trong suốt quá trình Generate và hiệu chỉnh mô hình.

Không cần tự mở container COLMAP/OpenMVS. Backend tạo và dọn container cho từng lệnh. Không tự restart Docker/WSL vì máy còn có thể đang chạy dịch vụ của project khác.

## 3. Bước 2 — Cắm điện thoại và kiểm tra USB

1. Cắm điện thoại vào laptop bằng cáp có truyền dữ liệu.
2. Mở khóa điện thoại; bật **Tùy chọn nhà phát triển → Gỡ lỗi USB / USB debugging** nếu chưa bật.
3. Khi điện thoại hỏi cho phép gỡ lỗi USB từ máy tính, chọn **Cho phép**.
4. Mở **PowerShell bình thường**, không cần mặc định chạy Administrator, rồi chạy:

```powershell
Set-Location 'E:\ar-spacefit'
& 'C:\Users\GIGABYTE\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```

Điện thoại hiện tại có serial `11046644BW001504`. Dòng thiết bị phải có trạng thái **device**.

- `unauthorized`: nhìn điện thoại và chấp nhận hộp thoại cấp quyền.
- `offline` hoặc không thấy thiết bị: kiểm tra cáp/cổng USB, mở khóa điện thoại, rút cắm lại rồi kiểm tra lại.
- Dùng điện thoại khác: thay serial trong các lệnh bên dưới bằng serial vừa liệt kê.

Tạo đường chuyển tiếp từ điện thoại tới cổng 8000 trên laptop:

```powershell
& 'C:\Users\GIGABYTE\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 11046644BW001504 reverse tcp:8000 tcp:8000
& 'C:\Users\GIGABYTE\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 11046644BW001504 reverse --list
```

Kết quả danh sách cần có `tcp:8000 tcp:8000`; lệnh tạo có thể in `8000`. Sau khi rút/cắm USB hoặc khởi động lại điện thoại/máy tính, kiểm tra và tạo lại chuyển tiếp nếu bị mất.

## 4. Bước 3 — Kiểm tra backend trước khi mở thêm

Backend có thể đã chạy nền dù bạn không thấy cửa sổ terminal. Trong PowerShell, chạy:

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health' -TimeoutSec 5
```

Kết quả đúng có:

```text
service      AR-SpaceFit
api_version  1
status       ok
```

**Nếu có kết quả đúng: backend đang trả lời, bỏ qua bước 4 và sang bước 5. Không mở backend thứ hai.**

Nếu không kết nối được hoặc timeout, kiểm tra cổng:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8000 -ErrorAction SilentlyContinue |
    Select-Object LocalAddress, LocalPort, OwningProcess
```

- **Không có dòng nào và lệnh không báo lỗi:** cổng chưa có dịch vụ lắng nghe; có thể sang bước 4.
- **Có PID nhưng health không trả lời, hoặc trả dịch vụ khác:** chưa được coi backend sẵn sàng. Không chạy thêm server, không tự kill theo PID cũ; xem mục xử lý lỗi.
- Nếu lệnh kiểm tra quyền/cổng báo lỗi: chưa đủ thông tin để mở thêm backend; cần kiểm tra lỗi trước.

`health=ok` chỉ chứng minh máy chủ HTTP trả lời. Còn phải kiểm tra công cụ Gen3D ở bước 5.

## 5. Bước 4 — Mở backend nếu chưa chạy

Trước khi mở lại, bảo đảm không có một lượt quét khác cần giữ đang xử lý. Khởi động backend có thể tiếp tục job đang chờ; job bị gián đoạn trước đó có thể chuyển sang lỗi. Không tự động thử lại job đã lỗi.

Trong PowerShell:

```powershell
Set-Location 'E:\ar-spacefit'
.\tools\Start-Backend.ps1 -ListenHost 127.0.0.1 -Port 8000
```

Các dòng khởi động thường thấy:

```text
Application startup complete.
Uvicorn running on http://127.0.0.1:8000
```

Giữ cửa sổ PowerShell này mở. Việc nó không trả lại dấu nhắc `PS ...>` là bình thường: server đang chạy. Dùng **một cửa sổ PowerShell khác** để kiểm `/health` như bước 3.

Không cần kích hoạt virtualenv thủ công, gõ token vào terminal hay mở Blender bằng tay. Script dùng Python/cấu hình hiện có và kiểm tra chia sẻ thư mục Docker. Không xóa/tạo lại `.env` chỉ để khởi động.

## 6. Bước 5 — Ghép nối trong app điện thoại

1. Mở **AR-SpaceFit** trên điện thoại.
2. Vào **Quét 3D / Quét nội thất 3D**.
3. Bấm **biểu tượng laptop** hoặc **Cấu hình / kiểm tra laptop**.
4. Nhập URL máy chủ:

   ```text
   http://127.0.0.1:8000
   ```

5. Trên laptop, tự mở `E:\ar-spacefit\backend\.env` bằng trình soạn thảo. Tìm dòng bắt đầu bằng `SPACEFIT_TOKEN=` và nhập **chỉ phần giá trị sau dấu `=`** vào ô mã ghép nối trong app. Đây là mã bí mật, không phải mật khẩu điện thoại.
6. Bấm **Lưu và kiểm tra**.

Kết quả cần thấy:

```text
Đã kết nối. Các công cụ native khởi động được.
```

Nếu báo công cụ đang được kiểm tra, đợi rồi bấm **Lưu và kiểm tra** lại; lần kiểm đầu có thể cần khoảng một phút. Nếu báo thiếu/lỗi công cụ thì xử lý lỗi trước khi Generate.

**Không gửi token, nội dung `.env` hoặc ảnh chụp để lộ token vào chat/Git/báo cáo.** Không mở `/capabilities` trực tiếp bằng trình duyệt rồi coi HTTP 401 là server hỏng: API này yêu cầu mã ghép nối.

Cách USB dùng `127.0.0.1` nhờ `adb reverse`; không cần mở firewall ra mạng hoặc đổi URL sang IP Wi-Fi.

## 7. Chạy Gen3D: ảnh/video → mô hình thật

### Chuẩn bị và chụp

1. Đặt vật **đứng yên**; người cầm điện thoại đi quanh vật, không vừa quay vừa nhấc/xoay vật bằng tay.
2. Dùng ánh sáng ổn định, giữ vật trọn khung và ảnh rõ. Vật bóng, trong suốt hoặc quá trơn có thể khó tái dựng.
3. Đo một chiều phủ bì bằng thước để làm tham chiếu; ghi thêm các chiều khác để đối chiếu kết quả sau này.
4. Trong app chọn **Bắt đầu phiên quét**, nhập tên, loại vật, trục và số đo tham chiếu bằng **cm**.
5. Chọn một cách lấy dữ liệu:
   - **Mở camera chụp quanh vật thể**;
   - **Nhập bộ ảnh có sẵn**;
   - **Nhập video quay vòng quanh**.

Backend mặc định yêu cầu **50–150 ảnh hợp lệ sau lọc mờ/trùng**. **150 là mức tối đa, không phải bắt buộc chụp đúng 150.** Với tự chụp, có thể bấm **Dừng tự chụp → Lưu và quay lại** trước khi chạm giới hạn. Cần đủ góc thấp, ngang và cao, không chỉ nhiều ảnh của cùng một mặt.

Video sẽ được trích ảnh tự động nhưng vẫn phải đạt kiểm tra chất lượng. Nếu phiên có video đã nhập, Generate dùng video đó thay cho bộ ảnh. Muốn thay dữ liệu sau khi đã gửi/khóa phiên, tạo **phiên quét mới**.

### Gửi xử lý

1. Sau khi ghép nối báo sẵn sàng, bấm **Generate 3D trên laptop**.
2. Giữ laptop thức, Docker/backend đang chạy, USB kết nối; không đóng terminal giữa lượt quét.
3. Theo dõi giai đoạn trên app. Tiến độ là các mốc xử lý, không phải thời gian còn lại; có bước lâu hơn bước khác.
4. Nếu rời màn hình rồi quay lại, chọn phiên trong lịch sử và bấm **Cập nhật / tải lại kết quả** khi có nút này. Không tạo nhiều phiên chỉ vì tiến độ chưa đổi.

Luồng xử lý: lọc ảnh → tìm/ghép đặc trưng → khôi phục vị trí camera → dựng điểm/bề mặt/texture → xuất bản xem trước.

### Kiểm tra và lưu kết quả

1. Khi app báo **Cần kiểm tra hướng và scale**, xem model có đúng vật thể, đủ hình dạng và texture không.
2. Chỉnh hướng đứng, vùng cắt nền nếu cần. Bản xem trước **chưa có kích thước thực được xác nhận**.
3. Chỉ đánh dấu xác nhận sau khi đã kiểm hình dạng, hướng và số đo.
4. Bấm **Hiệu chỉnh và xuất GLB theo số đo thực**.
5. Khi hoàn tất, xem W × D × H bằng cm và đối chiếu thêm chiều đo độc lập bằng thước.
6. Bấm **Lưu vào My Furniture**, sau đó dùng model để xem/bố trí trong phòng.

Đủ số ảnh hoặc đủ 7 công cụ không bảo đảm mô hình sẽ đúng. Nếu lỗi khôi phục camera vì thiếu góc/ảnh không phù hợp, cần thu thập lại; không lặp “Thử xử lý lại” liên tục với cùng đầu vào chưa sửa.

## 8. Dùng các phần còn lại của project

- **Kho / My Furniture:** xem các món đã lưu; model dùng bố trí phải đã xác nhận scale.
- **Thiết kế phòng:** nhập kích thước phòng, cửa, thêm đồ từ kho; kiểm vị trí/cảnh báo va chạm trên mặt bằng và phòng 3D, rồi lưu thiết kế.
- **Xuất PNG:** chờ mô hình hiển thị rồi chọn xuất và nơi lưu; mở lại ảnh để kiểm tra kết quả.
- **AR:** là chức năng riêng, cần thiết bị tương thích và Google Play Services for AR. Không mặc định việc mở app hoặc Gen3D chạy được có nghĩa AR đã dùng được.

Xem kịch bản đầy đủ trong [RUNBOOK](RUNBOOK.md). Không cần chạy máy ảo để dùng các chức năng trên điện thoại thật.

## 9. Dừng project an toàn

1. Chờ các lượt upload/dựng/hiệu chỉnh hoàn tất. Nếu muốn hủy, dùng **Hủy xử lý trên laptop** trong app và đợi trạng thái đã hủy.
2. Nếu backend do bạn mở ở terminal bước 4, nhấn **Ctrl+C một lần** trong đúng cửa sổ đó để dừng, rồi chờ shutdown xong.
3. Nếu backend chạy nền, không có cửa sổ terminal: không tắt ngẫu nhiên tiến trình Python, không dùng PID ghi trong báo cáo cũ. Cần xác định đúng tiến trình và trạng thái job trước khi dừng.
4. Chỉ thoát Docker khi chắc chắn không còn dịch vụ khác cần nó. Không dùng tắt Docker/WSL làm cách hủy Gen3D.

Không xóa `backend/data`, ảnh/video gốc, `.env` hoặc gỡ app để “làm sạch lỗi”. Giữ khóa ký hiện tại cho các lần cập nhật app sau này.

## 10. Lỗi thường gặp

| Hiện tượng | Kiểm tra và xử lý |
|---|---|
| App không tới được laptop | Kiểm `adb devices`, cấp quyền USB, chạy lại reverse và kiểm `/health` trên laptop. |
| Health timeout nhưng cổng 8000 có PID | Tiến trình có thể đang kẹt; không mở server thứ hai. Ghi lỗi, xác minh tác vụ trước mọi khởi động lại. Không mặc định do chọn chữ terminal. |
| Cổng 8000 đã được sử dụng | Kiểm health; nếu đúng AR-SpaceFit và `ok`, dùng backend hiện có. Nếu không, xác định dịch vụ đang chiếm cổng, không kill mò. |
| `Invalid pairing token` / HTTP 401 trong app | Kiểm lại mã ghép nối và URL, không nhập cả tiền tố `SPACEFIT_TOKEN=`. Không gửi mã cho người khác. |
| Công cụ đang kiểm tra | Đợi rồi bấm Lưu và kiểm tra lại. Chưa Generate khi chưa báo sẵn sàng. |
| Thiếu/lỗi COLMAP, OpenMVS hoặc Blender | Kiểm Docker đang chạy và đường dẫn cấu hình; xem `docs/NATIVE_RUNTIME.md`. Không tắt App Control/antivirus hay thêm ngoại lệ rộng. |
| Docker báo chia sẻ thư mục lỗi | Dừng tại lỗi và đối chiếu NATIVE_RUNTIME; không mount cả ổ hoặc restart WSL khi còn dịch vụ khác. |
| PowerShell chặn chạy script | Ghi lại lỗi để kiểm tra chính sách máy. Không tự dùng `Set-ExecutionPolicy`/`Bypass` hoặc tắt bảo vệ để thử mò. |
| Generate báo thiếu ảnh | Cần đủ ảnh hợp lệ sau lọc mờ/trùng, không chỉ số lần bấm chụp. |
| Lỗi camera pose / ít ảnh đăng ký | Xem lại vật có đứng yên, ảnh rõ và đủ góc/chồng lấn hay không; tạo bộ dữ liệu và phiên mới nếu cần. |
| App chỉ thấy bản xem trước | Chưa hoàn tất hiệu chỉnh/xác nhận scale; thực hiện bước xuất GLB cuối cùng. |
| AR không khả dụng | Kiểm riêng hỗ trợ ARCore/dịch vụ AR; không cài APK AR không rõ nguồn để ép chạy. |
| Máy ảo báo System UI không phản hồi | Không chạy test UI tiếp hoặc coi skip là pass. Giữ bằng chứng, xử lý riêng môi trường QA. |
| Cập nhật APK báo khác chữ ký | Dừng cập nhật và tìm đúng khóa ký; không gỡ app hoặc xóa dữ liệu để vượt lỗi. |

## 11. Build/cài lại app — chỉ khi cần phát triển

Nếu bản app đang mở bình thường thì bỏ qua mục này. Đây không phải thao tác cần làm mỗi lần dùng Gen3D.

Trong PowerShell:

```powershell
Set-Location 'E:\ar-spacefit'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --offline
```

Chỉ cài sau khi build báo thành công. `--offline` dùng dependency đã tải trên máy hiện tại; máy mới cần thiết lập dependency trước, không phải lỗi app.

```powershell
Set-Location 'E:\ar-spacefit'
& 'C:\Users\GIGABYTE\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 11046644BW001504 install -r '.\apps\android\build\outputs\apk\debug\app-debug.apk'
```

`-r` là cập nhật giữ dữ liệu khi chữ ký tương thích. Nếu báo `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, dừng lại; không tự uninstall/clear app. Có thể mở project trong Android Studio để sửa mã, nhưng không cần mở IDE khi chỉ chạy bản đã cài.

## 12. Chạy kiểm thử — dành cho lúc kiểm tra/sửa mã

Quy tắc và các tùy chọn nằm trong [docs/TESTING.md](TESTING.md). Không chạy test ghi kho/project trên điện thoại cá nhân.

Khi **chưa có máy ảo QA đủ điều kiện**, có thể chạy các nhóm ngoài UI:

```powershell
Set-Location 'E:\ar-spacefit'
.\tools\Test-All.ps1 -JavaHome 'C:\Program Files\Java\jdk-25.0.2' -BlenderBin 'D:\Blender\blender.exe' -Offline -RequireBlender -RequireNative -RequireDocker -RequireColmapDocker
```

Lệnh này có thể báo UI **SKIP** do không chọn emulator; đó **không phải nghiệm thu đầy đủ**. Để yêu cầu cả UI, cần khởi động đúng **SpaceFitQA**, xác nhận boot xong, không ANR/dialog lỗi và ổn định, rồi thêm `-RequireDevice -EmulatorSerial emulator-5586` (thay serial theo emulator QA thực tế). Không thay bằng serial điện thoại thật. Thêm `-RequireDevice` nhưng thiếu emulator sẽ làm gate **FAIL** đúng thiết kế.

Báo cáo mỗi lượt nằm trong `.tools/qa/run-.../`. Không tái sử dụng thư mục đã có cho pytest `--basetemp`, không xóa dữ liệu thật để chạy test.

## 13. Checklist trước khi bấm Generate

- [ ] Docker Desktop đang chạy.
- [ ] Điện thoại được ADB nhận ở trạng thái `device`.
- [ ] Reverse có `tcp:8000 tcp:8000`.
- [ ] Chỉ một backend hoạt động, `/health` trả đúng AR-SpaceFit / `ok`.
- [ ] App báo đã kết nối và các công cụ native khởi động được.
- [ ] Bộ ảnh/video có vật đứng yên, rõ, đủ góc và số đo tham chiếu thật.
- [ ] Laptop không sleep; không đóng backend/Docker hoặc rút USB giữa quá trình xử lý.

**Tình trạng đã biết khi viết tài liệu:** backend và đường USB đã kiểm tra được; kiểm thử tự động ngoài UI đã đạt ở lượt ghi trong [TEST_RESULTS](TEST_RESULTS.md). UI QA vẫn có blocker môi trường, và chưa nghiệm thu mô hình vật thật/AR. Tài liệu này là hướng dẫn vận hành, không phải xác nhận toàn bộ sản phẩm hoàn thiện. Trạng thái mới nhất xem [WORK_STATUS](WORK_STATUS.md).
