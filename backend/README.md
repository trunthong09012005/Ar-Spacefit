# Reconstruction backend

Backend FastAPI chạy trên laptop, một worker xử lý lần lượt các phiên quét. Ảnh, model và trạng thái nằm trong `backend/data/`; dữ liệu được giữ qua lần khởi động lại. Không mở dịch vụ này trực tiếp ra Internet.

## Thiết lập trên Windows

Từ thư mục gốc dự án:

```powershell
python -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install -r backend/requirements-dev.txt
backend/.venv/Scripts/python.exe backend/scripts/install_tools.py
```

Script tải **COLMAP 4.2.0 CPU** và **OpenMVS 2.4.0** từ GitHub chính thức, kiểm tra SHA-256, giải nén vào `.tools/`. Nó không sửa PATH hay registry. Cấu hình `backend/.env` có token ngẫu nhiên sẽ được tạo nếu chưa tồn tại; file đã có được giữ nguyên.

Blender 4.x trở lên cần cài riêng khi máy chủ tải xuống chặn truy cập tự động:

1. Tải bản Windows portable ZIP từ <https://www.blender.org/download/>.
2. Giải nén vào `.tools/blender/` hoặc thư mục bạn chọn.
3. Sửa `BLENDER_BIN` trong `backend/.env` thành đường dẫn đầy đủ đến `blender.exe`.
4. Kiểm tra: `backend/.venv/Scripts/python.exe backend/scripts/doctor.py`.

Các biến cấu hình được liệt kê trong `.env.example`. Python 3.14 đã được dùng để kiểm thử service. Không chép `.env` vào Git hoặc đưa token lên báo cáo.

Trên máy phát triển hiện tại, Blender 5.2.2 LTS đã được cài ở `D:\Blender\blender.exe` và cấu hình trong `.env`. Kiểm thử xuất GLB có texture, crop, xoay và scale đã đạt. Video thật đã được thử lại ngày 01/10/2026 nhưng chỉ đăng ký được 40/150 camera, chưa đạt ngưỡng 90; chưa có GLB của ghế thật. Cần thu thập lại đầu vào vật đứng yên, đủ góc; xem [kết quả và giới hạn](../docs/TEST_RESULTS.md).

Windows Code Integrity chặn DLL OpenMVS ngày 02/10 và DLL COLMAP ngày 03/10. Chủ máy đã chọn chạy cả COLMAP và OpenMVS bằng Linux CPU trong Docker, không tắt bảo vệ Windows; chỉ Blender còn native Windows. Chạy `backend/scripts/doctor.py --timeout 60` để kiểm tra startup thật; script trả exit 1 nếu có lỗi. `/capabilities.ready` dựa trên startup hợp lệ, không chỉ đường dẫn. Xem [runtime và giới hạn](../docs/NATIVE_RUNTIME.md).

## COLMAP Docker CPU

`backend/native-colmap-docker.json` trỏ image ID đã kiểm chứng của gói Ubuntu `colmap=3.9.1-2build2`. Bản Windows 4.2 được giữ nguyên nhưng không gọi trong Docker mode. COLMAP 3.9.1 dùng các cờ SIFT CPU riêng và giới hạn thread, được kiểm cả bốn stage; không hỗ trợ GPU trong adapter này.

```powershell
# Chỉ trên máy mới hoặc khi cần thiết lập, sau khi chủ máy cho phép:
.\backend\.venv\Scripts\python.exe -B backend/scripts/setup_colmap_docker.py --docker-bin 'DUONG_DAN_DOCKER_EXE'
# Config đã tồn tại không bị ghi đè; --force-update chỉ dùng sau khi xem lại cấu hình.
```

Start-Backend/doctor tự chọn Docker khi có config; override `SPACEFIT_COLMAP_RUNTIME=native` chỉ đổi COLMAP, không đổi OpenMVS. Không bật `SPACEFIT_USE_GPU=1` cho image CPU. Readiness kiểm startup trên ổ data thật; feature DB nằm riêng từng attempt. Chỉ chuyển giữa SQLite Windows/Linux khi không còn handle mở; backend sản phẩm không đọc feature DB bằng Windows trong lúc COLMAP Linux xử lý. Chi tiết provenance và giới hạn: [NATIVE_RUNTIME.md](../docs/NATIVE_RUNTIME.md).

## OpenMVS Docker

Máy này đã có `backend/native-docker.json` (không có token, bị gitignore) trỏ đến image ID bất biến của bản OpenMVS v2.4.0 CPU chính thức. `Start-Backend.ps1` và doctor tự chọn Docker khi có config; đặt `SPACEFIT_OPENMVS_RUNTIME=native` rõ ràng để dùng lại Windows. Không đổi `.env` hoặc xoá native tools cũ.

Thiết lập trên máy mới cần sự đồng ý của chủ máy, Docker Desktop Linux đang chạy và đủ dung lượng:

```powershell
.\tools\Setup-OpenMvsDocker.ps1
# Config có sẵn không bị ghi đè, trừ khi chủ máy chọn -ForceUpdate.
.\tools\Start-Backend.ps1
```

Provisioner chỉ đưa Dockerfile và archive OpenMVS đã kiểm SHA-256 vào build context riêng; không gửi code, video, DB hoặc `.env`. Base Ubuntu được pin digest; các package apt chưa đóng băng theo snapshot. Runtime dùng image ID/digest, `--pull never`, CPU/RAM giới hạn (4 CPU/4096 MB mặc định), network none, cap-drop ALL và read-only root. Chỉ một attempt mới được mount ghi tại `/workspace`; đường dẫn Windows được chuyển sang đường dẫn trong mount, không cho escape/junction sang thư mục khác.

Với context `desktop-linux`, Setup kiểm tra/khôi phục mount riêng workspace sau khi xác nhận daemon Linux và trước khi tải archive hoặc build. Nếu preflight lỗi thì dừng, không publish config; config cũ vẫn giữ nguyên cả khi đã chọn `-ForceUpdate`. Context khác không chạy thao tác WSL Docker Desktop này, nhưng vẫn phải qua đủ 5 startup/cleanup thực tế trước khi publish.

Mỗi lệnh có container name UUID riêng. Cancel/timeout/shutdown dọn đúng container đó; lỗi cleanup không bị coi là ready và không che lỗi stage gốc. Các file scene lưu đường dẫn tương đối, mọi stage giữ cùng `/workspace`. Diagnostic của API tạo thư mục trống mới bên trong `data_dir` để kiểm tra đúng ổ lưu job, không mount toàn `data_dir`. Thay image/context/giới hạn/data dir làm cache cũ không còn hợp lệ.

Nếu WSL mất chia sẻ ổ sau khởi động lại, `Start-Backend.ps1` chạy `Ensure-OpenMvsDockerWorkspace.ps1` để khôi phục riêng workspace, không toàn ổ. Script no-op khi đã chia sẻ, từ chối target không rỗng/symlink/mount khác. Không đổi `--mount` sang `-v` để tự tạo thư mục trống, không restart Docker khi có dịch vụ khác. Xem hướng dẫn ở [NATIVE_RUNTIME.md](../docs/NATIVE_RUNTIME.md).

## Kết nối Android

USB là cách thử đơn giản, không cần thay đổi firewall:

```powershell
.\tools\Start-Backend.ps1
# Trong terminal thứ hai, khi điện thoại đã bật USB debugging và chấp nhận kết nối:
adb devices
adb -s SERIAL_DIEN_THOAI reverse tcp:8000 tcp:8000
```

Thay `SERIAL_DIEN_THOAI` bằng serial từ `adb devices`, đặc biệt khi vừa có điện thoại vừa có emulator. Trong app: **Quét 3D → biểu tượng laptop → URL `http://127.0.0.1:8000`**. Nhập token trong `backend/.env` và chọn **Lưu và kiểm tra**. Nếu không có `adb` trên PATH, dùng `platform-tools/adb.exe` của Android SDK.

Wi-Fi LAN: chạy `tools/Start-Backend.ps1 -ListenHost 0.0.0.0`, nhập `http://IP-LAN-LAPTOP:8000` trong app. Điện thoại và laptop phải cùng mạng. Nếu Windows hỏi quyền mạng, chỉ cho phép mạng Private đáng tin cậy. Không cần mở cổng router. HTTP nội bộ được bật trong **APK debug**; bản release cần HTTPS. Token ghép nối trên Android được mã hóa bằng Android Keystore.

Service mặc định chỉ nghe `127.0.0.1`; chạy đúng **một** worker Uvicorn. `Start-Backend.ps1` dùng `spacefit.api:create_app --factory --workers 1`. Import module API không mở DB hay tạo worker; test dùng data dir riêng. Chưa hỗ trợ nhiều tiến trình worker hay nhiều người dùng độc lập.

## Quy trình thực

1. Tạo phiên trong app, đặt tên/loại và nhập một kích thước đo bằng thước.
2. Chụp 50–150 ảnh đi vòng quanh vật đứng yên, giữ trọn vật thể và nhiều vùng chồng lấn. Hoặc nhập một video MP4 tối đa 10 phút / 1 GB. Nếu đã nhập video, Generate dùng video thay cho bộ ảnh.
3. Generate tải multipart lên laptop. ID yêu cầu ổn định giúp gửi lại khi mất mạng mà không tạo thêm job.
4. OpenCV đọc ảnh/video, lọc ảnh mờ và ảnh trùng, thu nhỏ tối đa 2400 px. Frame video được lấy đều toàn bộ thời lượng. Đây không phải kiểm định đủ góc quét.
5. COLMAP chạy feature extraction, matching, mapper, undistortion. Cần ít nhất 60% ảnh hữu dụng đăng ký được và ít nhất 10 camera.
6. OpenMVS chạy import, dense cloud, mesh, refinement, texture.
7. Blender xuất `preview.glb`; app ở trạng thái **Cần kiểm tra hướng và scale**. Không đưa bản xem trước chưa hiệu chỉnh vào kho.
8. Người dùng kiểm tra hướng đứng, cắt vùng nền bằng bounding box nếu cần, xác nhận số đo tham chiếu. Blender crop, rotate, scale đồng đều, đưa đáy model về mặt sàn và xuất `model.glb` đơn vị mét.
9. App tải model/thumbnail, hiển thị texture, polygon count và W×D×H, lưu vào My Furniture để xem và bố trí ngoại tuyến.

Không có nhánh trả model giả khi pipeline thất bại. Tái dựng đồ phản chiếu, trong suốt, trơn ít hoa văn hoặc cảnh thay đổi dễ thất bại. Nền phức tạp có thể dính vào mesh; crop hộp không thay thế phân đoạn vật thể. Trường hợp khó cần chỉnh mesh trong Blender trước khi dùng để đo. Một số đo chỉ xác định scale đồng đều, không chứng minh ba chiều đều chính xác.

## API

`GET /health` công khai chỉ trả tên dịch vụ. Các API còn lại cần `Authorization: Bearer TOKEN`.

| API | Chức năng |
| --- | --- |
| `GET /capabilities` | Startup/DLL check nền, trạng thái công cụ và giới hạn upload |
| `POST /jobs` | Multipart `metadata` JSON + `files`; trả ID job |
| `GET /jobs`, `GET /jobs/{id}` | Danh sách / trạng thái bền vững |
| `POST /jobs/{id}/cancel` | Hủy job chờ hoặc đang chạy |
| `POST /jobs/{id}/retry` | Thử lại job lỗi / đã hủy |
| `POST /jobs/{id}/calibrate` | Crop, rotate, scale từ model gốc; yêu cầu `reviewed=true` |
| `GET /jobs/{id}/artifacts/{kind}` | `preview`, `model`, `thumbnail`, `log` |

Ví dụ `metadata`:

```json
{"name":"Ghế gỗ","category":"CHAIR","reference_axis":"width","reference_cm":68}
```

Calibration nhận `reference_axis`, `reference_cm`, `rotation_x/y/z` độ, `crop_min/max` là bộ ba tỷ lệ 0–1 theo hộp bao **model gốc trước khi xoay**, `reviewed=true`. Có thể hiệu chỉnh lại sau khi hoàn tất hoặc sau lỗi crop. Mỗi lần hiệu chỉnh bắt đầu từ OBJ gốc nên không nhân scale liên tiếp.

`/capabilities.tools` chỉ cho biết tìm thấy binary (với OpenMVS Docker chỉ là Docker CLI), chưa chứng minh image/tool chạy được; `ready=true` yêu cầu cả bảy startup check đạt. `runtime` có `checking`, `checked`, `checked_at`, `tools` với `status`/`reason`/exit code. Lần đầu/hết cache trả ngay `ready=false` và bắt đầu kiểm tra nền, không giữ HTTP request chờ native. Mỗi công cụ timeout 60 giây, chạy song song trong thư mục riêng; Docker diagnostic dùng thư mục mới dưới đúng `data_dir`, không Temp ổ khác. Kết quả cache 5 phút. Khi hết hạn hoặc đường dẫn/config/data dir đổi, không dùng kết quả thành công cũ. Không trả raw output, đường dẫn, môi trường hay token trong API.

Android phân biệt thiếu binary, đang kiểm tra và startup lỗi; backend cũ chỉ kiểm tra file không đủ để Generate. Đợi khoảng một phút rồi thử lại nếu đang kiểm tra. Worker dùng cùng cache trước `preparing_images` và cả retry; lỗi tại `checking_tools` giữ nguyên input/attempt cũ, chưa tạo workspace reconstruction mới. Xuất/calibration chỉ yêu cầu Blender dù OpenMVS lỗi. Startup đạt vẫn chưa chứng minh reconstruction thành công.

Trạng thái: `queued → processing → needs_review → calibration_queued → calibrating → completed`. Nhánh lỗi: `failed`, `cancelled`. Job đang chạy khi server dừng chuyển sang `failed/interrupted` khi khởi động lại; ảnh được giữ để retry. Job chờ tiếp tục được xử lý. Progress là các mốc giai đoạn, không phải dự đoán thời gian còn lại.

## Kiểm thử và chẩn đoán

Xem [chiến lược kiểm thử](../docs/TESTING.md). Có thể chạy một lệnh từ gốc dự án: `tools/Test-All.ps1 -SkipAndroid -BlenderBin 'D:\Blender\blender.exe'`. Runner tách core, HTTP black-box qua Uvicorn thật, Blender và OpenMVS Docker; lưu báo cáo vào thư mục run mới. Không đưa fixture vào DB dịch vụ. Thêm `-RequireDocker -RequireNative` khi nghiệm thu runtime này.

```powershell
backend/.venv/Scripts/python.exe -m pytest test/backend -q
backend/.venv/Scripts/python.exe backend/scripts/doctor.py
```

`doctor.py` và API/worker dùng chung logic startup, kiểm tra banner đúng công cụ (Python `-h` không được nhận nhầm là COLMAP). OpenMVS có thể trả mã 1 cho `-h` và ghi trợ giúp vào log; xem `help_available`, không chỉ mã thoát. Doctor không in token. Startup check chưa thay thế reconstruction end-to-end.

Bốn test tích hợp chạy Blender thật được bật khi đặt `BLENDER_BIN` trong môi trường chạy pytest (không tự đọc `.env`):

```powershell
$env:BLENDER_BIN = 'D:\Blender\blender.exe'
backend/.venv/Scripts/python.exe -m pytest test/backend -q
```

Các test này dùng mô hình hộp có texture tự sinh trong thư mục tạm; không phải bằng chứng chất lượng quét đồ thật.

Mã test đã được gom vào `test/backend/`; cấu hình pytest nằm ở gốc repository. Chạy các lệnh kiểm thử từ gốc để dùng đúng đường dẫn/package. Xem [cấu trúc test](../test/README.md).

Log tổng nằm trong `backend/data/<job>/attempt-*/pipeline.log`; bản Windows OpenMVS còn ghi log riêng trong cùng thư mục attempt. Backend ghép phần log mới của các công cụ vào log tổng khi stage kết thúc, kể cả thất bại. Có thể tải log tổng bằng API. Timeout mặc định là 2 giờ **cho mỗi giai đoạn**, có thể cấu hình.

Nguồn CLI: [COLMAP](https://colmap.github.io/cli.html), [OpenMVS](https://github.com/cdcseacave/openMVS/wiki/Usage), [Blender glTF](https://docs.blender.org/manual/en/latest/addons/import_export/scene_gltf2.html).
