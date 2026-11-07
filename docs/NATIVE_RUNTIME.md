# Runtime native Windows / OpenMVS Docker

## Phân biệt lỗi mới ngày 05/10/2026

- `exit 137` không tự nó chứng minh hết RAM. Bản sửa sau sự cố 22:12 truy vấn `oom` event chỉ của container UUID được tạo cho lệnh, trước cleanup. Lấy `SystemTime` của Docker làm điểm kết thúc cố định vì VM có thể đi trước Windows vài phần giây; dùng lịch sử còn lưu của đúng UUID để không bỏ sót do lệch đồng hồ. Mỗi lệnh info/events timeout 5 giây, không mở event stream vô hạn. Chỉ có bằng chứng dương tính mới trả marker `NATIVE_OUT_OF_MEMORY:`; event đã trôi khỏi lịch sử, clock/query lỗi hoặc bị kill không có OOM trả `NATIVE_KILLED:` với nguyên nhân chưa xác nhận. Không đưa raw daemon output/attributes/token ra API. Android dịch hai trường hợp riêng; lỗi 137 từ backend cũ vẫn nói chưa xác nhận OOM. Không tự retry hoặc bỏ refine.
- Refine dùng một worker cho native CLI, OpenMP và giới hạn CPU Docker; giữ **resolution-level 0**, reduce-memory 1, số view/iteration/subdivision mặc định, input và texture không thay. OpenMVS 2.4.0 vốn đã mặc định reduce-memory 1; thay đổi thực là giảm scratch của các tác vụ đồng thời, không phải bật một chế độ trước đó bị tắt. Có thể chậm hơn và **không bảo đảm mọi scene thật vừa RAM 4 GiB**. Chưa đo peak RAM hoặc nghiệm thu bộ ảnh thật sau sửa. Không tự tăng giới hạn RAM, tắt OOM killer, đổi image hoặc giảm độ phân giải. Nguồn đối chiếu: [RefineMesh v2.4.0](https://github.com/cdcseacave/openMVS/blob/v2.4.0/apps/RefineMesh/RefineMesh.cpp), [SceneRefine v2.4.0](https://github.com/cdcseacave/openMVS/blob/v2.4.0/libs/MVS/SceneRefine.cpp).
- `0x7d` là exit 125 của `docker run`, không đồng nghĩa DLL Windows bị chặn. Backend phân loại riêng `docker_start_failed` cho provider Docker; Android hướng dẫn kiểm Docker Desktop và quyền truy cập thư mục project. Không tự restart Docker/WSL (có thể có dịch vụ khác), không tắt bảo vệ Windows. `/health=ok` chỉ chứng minh HTTP, không thay `/capabilities.ready`. Cache kiểm native tối đa 5 phút; khi kiểm lại vẫn pending/error thì chưa upload/retry.
- `refine failed (exit 1)` với log thiếu `scene_dense_mesh.mvs` là lỗi hợp đồng file giữa các stage. OpenMVS 2.4 mặc định interface có thể xuất PLY nhưng không xuất scene mesh. Pipeline đã yêu cầu archive binary rõ ràng cho dense/mesh/refine/texture, kiểm output không rỗng trước khi chuyển bước. Densify cũng phải xuất binary để scene giữ **dense points**, không vô tình chuyển sparse points khi chỉ đổi archive của ReconstructMesh.
- Bản sửa mã chỉ có hiệu lực trong backend sau lần khởi động lại an toàn; không dừng worker khi chưa xác định job. Giữ input/attempt cũ, không tự retry. Xem TEST_RESULTS/WORK_STATUS để biết kết quả kiểm thử, trạng thái triển khai và phần vật thật/UI còn thiếu.

Kiểm tra cập nhật 04/10/2026, tách riêng khỏi unit/API/Blender test. Có file executable không đồng nghĩa công cụ khởi động được.

Ngày 04/10, COLMAP/OpenMVS Docker và Blender vẫn đạt 7/7 startup trong `run-20261004-084536-c7dc0f9e`. Full gate lại FAIL ở **Android JVM Robolectric**: Windows App Control chặn `libandroid_runtime.dll` (3033/3077), 33/112 JVM test lỗi. Đây là thư viện của môi trường kiểm thử Android, không phải native pipeline backend hoặc DLL của APK trên emulator. Không đổi `/capabilities` để phản ánh lỗi thư viện test, không tắt bảo vệ Windows hay bỏ các test. Xem [TEST_RESULTS.md](TEST_RESULTS.md) cho bằng chứng và tình trạng lựa chọn môi trường Linux/kiểm duyệt DLL.

Cập nhật 09:37 cùng ngày: kiểm lại JVM theo yêu cầu chủ máy trong `jvm-recheck-993dba69953b4c909678a3f7cb5b8641` đạt **112/112**, exit 0. Blocker DLL không còn tái hiện trong lượt mới; agent không đổi chính sách Windows. Chưa chạy lại full gate sau kết quả này, không sửa kết quả FAIL lịch sử hoặc kết luận quét/AR đã đạt.

## COLMAP cũng chuyển Docker theo lựa chọn chủ máy ngày 03/10

COLMAP Windows 4.2 bị Code Integrity chặn `Imath-3_2.dll` (`0xc0e90002`, event 3033/3077). Gate `run-20261003-221515-c928b6d6` giữ nguyên kết quả FAIL; không đổi policy Windows. Chủ máy đã chọn chuyển COLMAP sang Linux Docker. Hiện COLMAP **3.9.1 CPU**, OpenMVS **2.4.0 CPU** chạy trong hai image riêng, Blender tiếp tục Windows.

- Gói COLMAP `3.9.1-2build2` từ [kho Ubuntu 24.04](https://packages.ubuntu.com/noble/colmap), base Ubuntu pin digest. Đây là bản phân phối CPU, khác phiên bản Windows; không dùng tùy chọn GPU 4.x. Dependency apt phụ thuộc kho tại lúc build, chưa là build bit-for-bit tái lập.
- Image ID `sha256:a051794f3f8f5375029ebb052ff2ee7fe24604c36f221068cb33b5166c5d7d73`; config không bí mật `backend/native-colmap-docker.json` bị gitignore. Installer [setup_colmap_docker.py](../backend/scripts/setup_colmap_docker.py) chỉ gửi Dockerfile vào build context, kiểm 5 help, trích xuất/ghép fixture CPU và đọc feature thật trước khi atomically publish config. Lỗi bất kỳ giữ config cũ; không tự ForceUpdate.
- Container từng lệnh dùng image ID, `--pull never`, CPU/RAM/pids giới hạn, `network none`, read-only root, `core=0`, tmpfs riêng. Chỉ mount attempt/diagnostic mới; các stage COLMAP và OpenMVS dùng cùng `/workspace`. Không mount SQLite job/`.env`/socket/cả data dir. SQLite **feature database trong attempt** là dữ liệu pipeline, khác SQLite job của backend.
- 22 bài native integration độc lập đã đạt sau sửa fixture đóng hẳn handle SQLite Windows trước Linux: help, features/matches CPU, mapper 5 camera/200 correspondences tổng hợp, undistortion → OpenMVS scene, input lỗi và timeout/cancel cleanup. Không phải quét vật thật. Kết quả full gate mới xem [TEST_RESULTS.md](TEST_RESULTS.md).
- Start-Backend/doctor tự chọn COLMAP Docker khi config tồn tại và không có override; `SPACEFIT_COLMAP_RUNTIME=native` chỉ rollback COLMAP. Không phải cách bỏ qua readiness: bản Windows hiện vẫn bị chặn. `SPACEFIT_USE_GPU=1` bị từ chối cho COLMAP Docker CPU.

```powershell
# Máy mới, sau khi chủ máy cho phép cấu hình Docker:
.\backend\.venv\Scripts\python.exe -B backend/scripts/setup_colmap_docker.py --docker-bin 'DUONG_DAN_DOCKER_EXE'
# Nghiệm thu cả hai runtime Docker:
.\tools\Test-All.ps1 -JavaHome 'C:\Program Files\Java\jdk-25.0.2' -BlenderBin 'D:\Blender\blender.exe' -EmulatorSerial emulator-5586 -Offline -RequireBlender -RequireDevice -RequireNative -RequireDocker -RequireColmapDocker
```

## Lịch sử thiết lập OpenMVS trước khi chuyển COLMAP

### Docker đã được chủ máy cho phép

- Đã đóng gói bản Linux CPU chính thức OpenMVS v2.4.0, source revision `58117204c86bbb11a0b25b26a8987676cf11274d`. Archive SHA-256 `7104ae1ddd6ca38fbca9e0e4a70b20af59e21e0b497eb7181c864fbf38ca8d00`, base Ubuntu 24.04 pin digest. Image ID hiện tại `sha256:acf7a1eb016f19756b3c9bf9d510c391985fa325e23e960900dea87364c9b1c8`. Không publish image hoặc sửa chính sách Windows.
- Startup 5 tool thật đã đạt ở `.tools/qa/openmvs-docker-20261002-121020-43cf3fb6/`; ldd cả năm không thiếu thư viện. Config machine-local `backend/native-docker.json` không có token và bị gitignore. Script setup từ chối ghi đè nếu chưa chọn ForceUpdate; chỉ publish config sau 5 help và cleanup xác minh được.
- Lượt tiếp tục phát hiện WSL/Docker mất mount ổ E: dù Windows thấy thư mục, mount container báo source không tồn tại. Không restart Docker vì còn dịch vụ khác. Đã gắn riêng `E:\ar-spacefit` tại `/mnt/host/e/ar-spacefit` (DrvFs), không gắn toàn ổ E và không thay wsl.conf; chia sẻ truyền sang namespace engine. Đây là mount runtime, có thể cần khôi phục sau khi WSL khởi động lại.
- 11 integration Docker thật đạt trong full gate cuối `run-20261002-155146-f1aa238f`: WSL preflight thật, help cả năm, import scene COLMAP tổng hợp (3 camera/8 điểm), input lỗi giữ log, daemon container timeout/cancel được dọn và HTTP Uvicorn/DB/token giả xác nhận 7 startup ready mà không upload job. Doctor đủ 7 startup đạt; 399 tests tự động đạt, gate exit 0. Lượt UI ANR `145425` được giữ làm lịch sử, không đổi thành pass. Đây không phải tái dựng ghế/video thật; quét/AR vật thật còn MANUAL.
- API kiểm tra startup trong thư mục diagnostic trống mới bên trong `data_dir`, không Temp ổ C; không mount DB/uploads hoặc toàn data dir. `ready=false` khi ổ job chưa chia sẻ, image không còn, daemon không chạy, hoặc cleanup lỗi. COLMAP và Blender tiếp tục chạy Windows.

Script chuẩn: `tools/Setup-OpenMvsDocker.ps1` và `tools/Start-Backend.ps1`. Startup Docker gọi `tools/Ensure-OpenMvsDockerWorkspace.ps1`: nếu chia sẻ đã đúng thì no-op; nếu mất thì chỉ mount riêng workspace trên Fixed Drive. Script từ chối UNC/root/reparse, target Linux không rỗng/symlink/mount khác, và xác minh metadata sentinel không đọc nội dung. Không sửa wsl.conf hoặc restart Docker. Đây là khôi phục runtime mount, không phải mount persistent toàn ổ. Có thể chạy preflight riêng bằng `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/Ensure-OpenMvsDockerWorkspace.ps1`.

Setup cũng gọi preflight này với context `desktop-linux`, sau guard config/daemon và trước download/build. Context khác không tự sửa WSL của Docker Desktop. Preflight thất bại phải giữ config cũ và không tạo build/probe; 31 setup contract tests bao gồm kiểm thứ tự và các nhánh fail-closed. Việc chạy contract với boundary mock không có nghĩa đã build lại image; machine-local image/config hiện được giữ nguyên.

Thêm `-RequireDocker -RequireNative` vào Test-All. Xem [image/provenance](../infra/docker/openmvs/README.md), [Docker bind mounts](https://docs.docker.com/engine/storage/bind-mounts/) và [WSL automount](https://learn.microsoft.com/en-us/windows/wsl/wsl-config). Mount thư mục DrvFs được hỗ trợ trong [mã nguồn WSL 2.6.3](https://github.com/microsoft/WSL/blob/2.6.3/src/linux/init/drvfs.cpp).

## Bằng chứng native Windows trước khi chuyển Docker

- Blender 5.2.2 LTS chạy `--version` thành công; bốn integration test Blender đã đạt.
- `InterfaceCOLMAP -h` có help OpenMVS 2.4.0 và exit 1 (hành vi help của công cụ này).
- `DensifyPointCloud`, `ReconstructMesh`, `RefineMesh`, `TextureMesh` kết thúc với `0xc0e90002`, chưa có help.
- Log `Microsoft-Windows-CodeIntegrity/Operational` có sự kiện 3033 và 3077: các process OpenMVS bị chặn khi nạp `E:\ar-spacefit\.tools\openmvs-2.4.0\vc17\x64\Release\OpenEXR-3_3.dll`, không đạt signing level/chính sách Code Integrity.
- COLMAP từng timeout ở chẩn đoán help 20 giây; không tính timeout là pass. Lượt cuối với timeout 60 giây đã trả help COLMAP 4.2.0, exit 0.
- Lượt cuối `run-20261002-113308-7c6d5dae` lưu `native-reconstruction-tools.log`, `native-diagnostics/`, `code-integrity-evidence.json` và `capabilities-real.json` (API real-native riêng, không gửi job). DLL hiện `NotSigned`, SHA-256 `0EB3023C5F958457B04D1867F4C08216BDFE3E214456CA655E496005257F1B92`; hash/signature không tự chứng minh an toàn hoặc độc hại.

Microsoft mô tả 3077 là sự kiện chặn của chính sách App Control đang enforcement; xem [hướng dẫn chẩn đoán App Control](https://learn.microsoft.com/en-us/windows/security/application-security/application-control/app-control-for-business/operations/appcontrol-debugging-and-troubleshooting). Log hiện tại xác nhận một DLL bị chặn, không tự suy ra DLL là malware hoặc file hỏng.

## Cách kiểm tra lại

```powershell
.\backend\.venv\Scripts\python.exe -B backend/scripts/doctor.py --timeout 60
# Hoặc đưa vào cổng chất lượng bắt buộc:
.\tools\Test-All.ps1 -JavaHome 'C:\Program Files\Java\jdk-25.0.2' -BlenderBin 'D:\Blender\blender.exe' -EmulatorSerial emulator-5586 -Offline -RequireBlender -RequireDevice -RequireNative -RequireDocker
```

Doctor trả exit 1 khi thiếu binary, timeout, loader error hoặc không có help hợp lệ; không còn trả exit 0 chỉ vì đã in lỗi. Mock help trong test chỉ kiểm tra logic diagnostic, không phải bằng chứng OpenMVS thật chạy được.

## Nếu muốn quay lại native Windows

Cần kiểm tra nguồn/gói native tin cậy và chính sách cho phép chạy trên máy, hoặc chọn môi trường native phù hợp được chấp thuận. Không tự tắt Smart App Control/Code Integrity, sửa policy, thêm ngoại lệ rộng hay thay DLL ngẫu nhiên để vượt kiểm tra. Sau khi điều kiện được xử lý, chạy lại doctor và full gate; sau đó mới chạy bộ ảnh/video mới.

Ở lượt trước Docker daemon chưa chạy và chưa được chọn. Chủ máy sau đó đã cho phép cấu hình Docker; kết quả mới ở trên thay thế trạng thái chờ quyết định này. Native Windows vẫn bị chặn, không cần mở khoá DLL Windows để dùng bản Linux; không coi startup/import fixture là nghiệm thu pipeline từ vật thật.

`/health` backend chỉ chứng minh dịch vụ HTTP hoạt động. `/capabilities.ready` kiểm tra startup thật ở nền qua cùng helper với doctor; `tools` là thông tin tìm thấy binary (với OpenMVS Docker chỉ là Docker CLI), chưa chứng minh image/tool chạy được. `runtime.tools` là kết quả startup trong thư mục diagnostic mới dưới đúng `data_dir`. Lần đầu/hết hạn không trả ready cũ. Kiểm tra tối đa 60 giây/công cụ, cache 5 phút; API không trả raw output/path/token. Worker preflight trước xử lý ảnh và retry, calibration chỉ yêu cầu Blender. Không nghiệm thu reconstruction end-to-end khi native diagnostic còn lỗi, dù HTTP vẫn hoạt động.
