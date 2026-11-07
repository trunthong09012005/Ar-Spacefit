# Chiến lược kiểm thử và cách chạy

## Cổng chất lượng

CI nền trên GitHub được mô tả trong [CI/CD](CI_CD.md): core/HTTP trên Windows và Android JVM/lint/build. CI này chưa chạy native/thiết bị, không thay thế cổng nghiệm thu đầy đủ bên dưới.

Mã kiểm thử tập trung trong [test/](../test/README.md): `test/backend/`, `test/android/unit/`, `test/android/instrumentation/`. Root `pytest.ini` khai báo discovery/Python path; Gradle `:app` ánh xạ hai source set sang thư mục chung. Chạy lệnh từ gốc repo. Output APK/JUnit vẫn dưới `apps/android/build/`, evidence QA vẫn `.tools/qa/`.

`AGENTS.md` yêu cầu test và cập nhật bằng chứng cho mọi thay đổi mã sau này. Test tự động không thay thế quét đồ/AR thật. Không tuyên bố “hoàn hảo” hoặc “100%” theo số test.

Từ 06/11/2026 Android nằm tại `apps/android/`, Gradle vẫn gọi `:app` từ gốc repo; Dockerfile ở `infra/docker/{colmap,openmvs}/`. Runner đã dùng đường dẫn output mới. `test_repository_documentation.py` kiểm liên kết local của tài liệu hiện hành; không quét dữ liệu thật hoặc viết lại đường dẫn trong báo cáo lịch sử. Xem [bảng di chuyển](REPOSITORY_STRUCTURE.md).

Chạy `tools/Test-All.ps1` từ PowerShell. Runner tạo `.tools/qa/run-<ngày>-<id>/`, lưu log/JUnit/summary và trả exit code khác 0 nếu một nhóm bắt buộc thất bại. Backend luôn dùng DB/thư mục riêng. Không xoá dữ liệu test cũ hay DB thật.

```powershell
.\tools\Test-All.ps1 -JavaHome 'DUONG_DAN_JDK' -BlenderBin 'D:\Blender\blender.exe'
# Thêm UI/native trên emulator QA đang bật (không nhận serial điện thoại thật):
.\tools\Test-All.ps1 -JavaHome 'DUONG_DAN_JDK' -BlenderBin 'D:\Blender\blender.exe' -EmulatorSerial emulator-5586
```

Tuỳ chọn `-SkipAndroid` chỉ để chẩn đoán backend, không tương đương full gate. `-Offline` dùng cache Gradle đã tải. Không có Blender hoặc emulator thì báo rõ SKIP, không cộng vào pass. `-RequireBlender` / `-RequireDevice` biến thiếu điều kiện thành FAIL khi nghiệm thu đầy đủ.

Thêm `-RequireNative` để kiểm tra khởi động COLMAP/OpenMVS/Blender qua doctor; missing/timeout/loader error làm gate FAIL. Nếu không thêm, nhóm này báo MANUAL, không phải PASS. Doctor không chạy reconstruction hay sửa chính sách Windows. Blocker runtime hiện tại: [NATIVE_RUNTIME.md](NATIVE_RUNTIME.md).

Sau khi chủ máy cho phép và đã cấu hình OpenMVS Docker, thêm `-RequireDocker`. Runner chạy riêng `test_openmvs_docker_integration.py` với config non-secret rõ ràng: 5 help native thật, WSL preflight thật, import scene COLMAP TXT tổng hợp, lỗi input và giữ log, container thật bị timeout/cancel, và HTTP thật kiểm tra đủ 7 startup nhưng không upload job. Không có config thì SKIP (hoặc FAIL với RequireDocker), không cộng pass. Core loại file này để không vô tình chạy Docker thật; mocked provider/setup/WSL contract vẫn thuộc core. Fixture scene/sleep chỉ kiểm tra native transport/lifecycle, không phải model vật thật.

Thêm `-RequireColmapDocker` sau khi đã cấu hình COLMAP Docker. Nhóm riêng `test_colmap_docker_integration.py` kiểm 5 CLI help, features/matches CPU vào SQLite fixture, mapper với 5 camera/200 correspondences tổng hợp, undistortion sang OpenMVS scene và cleanup timeout/cancel. Mapper fixture chỉ kiểm thực thi hình học, không chứng minh feature matching hay model vật thật; ảnh/điểm tổng hợp được ghi rõ trong test. HTTP startup dùng cả hai runtime Docker nếu cung cấp cả hai config QA. Core loại file integration thật này; contract installer/provider/cache vẫn thuộc core.

Fixture Docker dùng `docker_tmp_path`: trên Windows, tạo một thư mục `native-interop` mới bên trong tmp_path rồi cấp Modify kế thừa **chỉ cho SID người dùng đang chạy test**. pytest/Python tạo tmp_path bằng mode 0700 với ACL OWNER RIGHTS; file do Docker/DrvFS tạo có thể mang owner khác, khiến Windows không đọc lại được dù native command đã thành công. Helper không sửa ACL của data thật, repo, tmp_path gốc hoặc file bằng chứng cũ; không cấp Everyone, không đổi bảo vệ Windows và không giảm assertion. Lỗi nhận diện SID/cấp quyền phải FAIL. Regression helper và các bài COLMAP đọc lại SQLite/binary/OpenMVS xác nhận cả hai chiều giao tiếp; giữ cả run FAIL trước sửa.

WSL workspace tests dùng copied disposable repo và boundary mock rõ ràng; không mount/dừng WSL thật trong core. Bài integration gọi script thực để kiểm chứng serialization `wsl --exec`; có regression cho quoting từng gây exit 127. Backend Start chạy preflight khi một trong hai runtime là Docker; rollback cả hai về native thì không chạy bước này. Doctor tuần tự có ngân sách wrapper 520 giây (7×60 + 6×10 cleanup và margin); timeout/cancel không được bỏ rơi daemon container. Test SQLite phải đóng handle hẳn trước khi chuyển Windows/Linux; context transaction của sqlite3 không tự đóng connection. Không nới assertion khi lỗi fixture.

## Ma trận phạm vi

Regression RAM/137 bổ sung `test_refine_memory.py`: profile refine một luồng giữ độ phân giải, đồng bộ CPU/OpenMP, numeric/duplicate/tool scoping, exit code từ subprocess thật, phân biệt event OOM/unknown, query timeout/failure/sanitization, cancel/retry trong khi chẩn đoán và giữ input/không tạo model khi worker fail. Đây là unit/component với Docker mock, không phải thành công native. `ScanFailureTest` và Room + HTTP fixture kiểm thông báo Việt, legacy 137 không suy OOM và refresh không tự retry/xóa input.

Nhóm OpenMVS opt-in có **negative infrastructure fixture thật**, giới hạn riêng **256 MiB RAM và 256 MiB memory+swap** (không swap), không đụng cấu hình sản phẩm: một fixture cố cấp phát quá hạn, một fixture SIGKILL child rồi truyền exit 137. PID 1 có quy tắc signal riêng nên không giả định `kill -9 $$` sẽ chết. Chỉ test thay command trong prepared wrapper; provider sản phẩm vẫn từ chối shell/tùy chọn bất kỳ. Kiểm event OOM đúng container, dọn container, giữ input; lưu kết quả lỗi đã quan sát vào DB QA rồi khởi Uvicorn riêng để GET lỗi/auth/404 model/409 cancel qua HTTP thật. Đây là kiểm hợp đồng lỗi HTTP trên trạng thái fixture đã seed, **không phải upload/quét end-to-end hoặc bằng chứng bộ ảnh thật hết OOM**. Chuỗi native surface -> texture -> Blender fixture tiếp tục giữ assertion hình học/texture, dùng profile refine sản phẩm mới.

Regression OpenMVS ngày 05/10 bổ sung fixture bề mặt tổng hợp và camera đã biết: chạy các lệnh thật qua mesh -> refine -> texture, kiểm scene MVS không rỗng, OBJ có mặt/UV, ảnh texture có biến thiên pixel. Nhánh thứ hai chạy thêm DensifyPointCloud và Blender thật để kiểm preview GLB có geometry/material/texture nhúng, vẫn `needs_review` và chưa xác nhận scale. Các bước dùng chính orchestration của pipeline, không giả native success. Đây không phải COLMAP feature matching từ ảnh vật thật hoặc nghiệm thu render/AR. HTTP Docker có thêm image ID không tồn tại với `--pull never` để kiểm 125 qua server thật, không tải image hoặc upload job. Core kiểm archive binary, output thiếu/rỗng/directory, cancel, fail-closed và sanitization; Android JVM/HTTP/UI fixture kiểm thông báo Docker và không giữ thông báo cũ sau refresh.

| Yêu cầu | Unit / component | Integration | Black-box / thực nghiệm |
|---|---|---|---|
| Nhận diện cũ, mẫu đồ | parser, thông số, mesh | Room lưu tham số | Compose editor/kho; Gemini thật cần key và dữ liệu |
| Ảnh/video scan | sharpness, giới hạn, invalid video | OpenCV tách/loại frame; scan persistence | UI validation; camera/import/permission trên thiết bị |
| Upload / trạng thái | schema, store transition | FastAPI + multipart + SQLite | Uvicorn process riêng + HTTP thật: auth, upload, poll, lỗi, retry, persistence |
| Huỷ / khôi phục | transition race, recover | subprocess timeout/cancel, API state | API lỗi và restart; không gửi vào DB dịch vụ |
| Tái dựng thật | lệnh/guard pipeline | Blender thật: crop/rotate/scale/texture | COLMAP/OpenMVS từ video thật; hình/texture phải xem và đối chiếu |
| Mạng Android / GLB | URL và parse/validation | loopback HTTP client: upload/download/error/redirect | nạp GLB trên thiết bị, pixel native + UI |
| Kho / project / phòng | codec, dimension/placement validation | Room migration, save/reopen, CRUD | kho → GLB → phòng → floorplan → scanner trên emulator |
| SpaceFit / fit vùng / layout | SAT, clipping, clearance, rotation, locks, deterministic cases | dữ liệu project thành footprint | kéo/xoay/door trên UI, đối chiếu kích thước thật |
| AR / xuất ảnh | hình học dùng chung | PixelCopy native | tương thích ARCore, tracking/anchor, SAF chooser và mở PNG |
| Bảo mật / release | URL policy, finite bounds | auth, file path, body limits, GLB validation | HTTP không token, release HTTPS, ký release bởi chủ dự án |

Các test dùng fixture hay mock phải giữ tên/mô tả rõ ràng. Integration Blender không phải reconstruction end-to-end. Black-box backend dùng input lỗi có chủ đích để kiểm chứng từ HTTP; không giả tạo công cụ native thành công.

Emulator QA hiện có 10 bài instrumentation: GLB dialog, native cube đối chứng, hai đồ cùng phòng, kho → phòng → mặt bằng → scanner, validation scanner/phòng, chỉnh/nhân bản/xoá/lock/suggestion/recreate, chọn vùng → lọc kho → thêm lệch tâm → lưu/mở lại, xuất/huỷ PNG qua SAF thật, và ghép nối/Generate qua HTTP fixture phân biệt checking/DLL bị chặn. Bài native chỉ dùng token giả, xác minh IME không che dialog sau kiểm tra, khôi phục URL/ciphertext cũ, tạo/dọn scan QA riêng qua UI và không POST job khi native lỗi. Pixel probe loại palette tường xanh và crop theo boundsInWindow; ảnh chọn vùng còn kiểm nền hộp thoại đã vẽ kín để không nhận khung hình animation làm bằng chứng cuối. Phạm vi này không thay checklist thiết bị/AR/quét thật bên dưới.

`RegionFitTest` kiểm tìm lệch tâm, phần vùng còn trong phòng, tiếp xúc biên, kích thước sai, tường/trần/cửa và vật xoay; `WorkspaceIntegrationTest` kiểm Room + tìm vùng + ViewModel: lưu/mở lại, kho rỗng/sai/chưa scale, giới hạn 100, kết quả cũ sau sửa/xóa món hoặc đổi project, bấm lại và revalidation. UI chỉ seed kho fixture; thao tác chọn vùng, đặt và xác minh lưu lại đi qua giao diện thật, không gọi ViewModel để giả lập thành công. Lưới giới hạn không chứng minh mọi nghiệm liên tục đã được tìm thấy; khoảng cách/lối đi vẫn cần kiểm riêng.

Pixel của Dialog phải đổi bounds local-window sang tọa độ screen trước khi so với ảnh UiAutomation. Luôn lưu frame cuối của attempt kể cả khi hết hạn sau sleep, tránh kéo ảnh cũ rồi hiểu nhầm là ảnh của lần lỗi mới. Không hạ ngưỡng màu để vượt lỗi tọa độ.

Lịch sử sáng 04/10: App Control chặn DLL Robolectric trên Windows, full gate FAIL và JUnit lỗi được giữ. Kiểm lại 09:37 trong `jvm-recheck-993dba69953b4c909678a3f7cb5b8641` đã đạt 112/112 JVM, không đổi graphics/SQLite/test. Cần chạy lại gate đầy đủ để có kết quả gộp mới; không ghép các lượt riêng thành full PASS. Xem TEST_RESULTS và WORK_STATUS trước khi lặp test.

Kiểm tiếp ngày 05/10 `run-20261005-100533-ba1bb94b`: 474 test ngoài UI đạt, 7 native startup ready; gate vẫn exit 1 vì `-RequireDevice` nhưng không chọn emulator sau hai preflight ANR. Không suy máy ảo ổn từ `sys.boot_completed=1` hoặc ảnh launcher không dialog: phải kiểm cả tiến trình không phản hồi/events mới và trạng thái ổn định. Không dùng kết quả 10 UI ngày 04/10 để lấp lượt UI chưa chạy ngày 05/10. Chỉ đếm JUnit của các nhóm đã thực thi; negative fixture `test_test_runner.py` tạo báo cáo FAIL riêng có chủ đích, không ghép vào lượt sản phẩm.

Trước instrumentation, kiểm tra AVD đúng SpaceFitQA, `sys.boot_completed=1`, boot animation dừng, không có System UI ANR/error window và dịch vụ sau boot đã ổn. Nếu ANR môi trường làm fail, giữ log/ảnh/summary FAIL; chỉ phục hồi AVD QA không wipe rồi chạy lại nguyên nhóm, không nới pixel/SAF assertion hoặc tính lần lỗi là pass. Lượt Docker 02/10 ghi cả hai run trong TEST_RESULTS. Diagnostic timeout SAF hiện có thể bị lỗi thiếu Compose hierarchy che thông báo gốc; ảnh hệ thống vẫn cần xem, chưa sửa nhánh diagnostic này trong công việc Docker.

Native-readiness test bao gồm cache single-flight, expiry/path change/in-flight race, lỗi/timeout/thiếu công cụ, raw-output/token sanitization, worker preflight và cancel. HTTP black-box có binary sai thật (Python/executable copies) như negative fixture; không phải giả lập reconstruction thành công. API `ready` có nghĩa startup check, không phải model đúng hình/scale.

`ScanViewModelIntegrationTest` dùng Room in-memory và OkHttp qua HTTP loopback thật, với video/GLB/response ghi rõ là fixture. Kiểm cả đường thành công, lỗi download/GLB, preview chưa scale, mất phản hồi upload rồi khởi tạo lại ViewModel, input bất biến khi retry, đổi laptop trong lúc capabilities đang chờ và validation/native chưa ready. Đây là integration Android có backend fixture, không phải black-box backend photogrammetry hay thử offline trên điện thoại thật. `backendUrl` không rỗng là dấu bền vững input đã khóa trước POST; không xóa dấu này để cho phép đổi input với cùng idempotency key.

## Checklist hộp đen thủ công (ghi PASS/FAIL/BLOCKED và bằng chứng)

1. Cài/update APK giữ dữ liệu, mở lại: kho/project cũ vẫn còn. Tạo hai project, đổi qua lại, force-stop/mở lại kiểm tra toạ độ/rotation/lock và ảnh before/after.
2. Scanner: từ chối camera rồi cấp lại; tên trống, số đo 0/âm/NaN, dưới 50 ảnh, ảnh mờ, video hỏng; thông báo rõ, không mất input.
3. Ghép nối sai token/URL, mất mạng khi upload/poll/download, bấm lại: không tạo job trùng hoặc lưu file tải dở. Đổi laptop không làm lộ token qua redirect.
4. Quét vật đứng yên 360° nhiều tầng, đo 1 chiều scale và 1 chiều độc lập. Ghi thời gian, hình/texture, W/D/H và sai số tuyệt đối/tương đối. Không điền số liệu ước đoán.
5. Crop trống/scale sai bị từ chối; preview chưa xác nhận không được đưa vào kho như đồ có scale thật. Sau lưu, tắt mạng vẫn xem được model/texture.
6. Hai GLB và đồ tham số cùng phòng đều hiện; di chuyển/xoay/nhân bản/xoá/khóa; vượt tường, chồng đồ, chắn cửa, cao hơn trần đều cảnh báo đúng.
7. Fit vùng và auto-layout không làm mất đồ/di chuyển đồ khóa; trường hợp không đủ chỗ báo không có nghiệm, không trả thiết kế thiếu đồ.
8. AR trên thiết bị tương thích: đo phòng bằng thước đối chứng, anchor ổn định, đưa app nền/mở lại, xoay máy, thiếu sáng, tracking lost. Ghi sai số/drift và thời lượng, không thay bằng test emulator.
9. Xuất PNG cả phòng 3D và mặt bằng qua hộp thoại chọn nơi lưu: mở lại file, ảnh không đen/trắng, không lẫn controls. Huỷ chọn nơi lưu không crash.
10. Màn hình nhỏ, font lớn, app offline, thiếu dung lượng, GLB lớn, release HTTPS và release signature. Chưa có môi trường thì ghi BLOCKED, không đánh PASS.

## Tái lập và báo cáo

- Cài Python theo `backend/requirements-dev.txt`; native theo `backend/README.md`; JDK/SDK theo Gradle.
- JUnit backend và JVM là nguồn đếm test. Instrumentation phải có `OK (N tests)` và không có FAILED/INSTRUMENTATION_FAILED; chỉ exit code adb=0 chưa đủ.
- Đọc log của từng nhóm nếu runner lỗi. Sau sửa chạy lại cả nhóm liên quan; không thay báo cáo lịch sử bằng kết quả chưa chạy.
- Bằng chứng không chứa token, video/phòng/người dùng cá nhân. Ảnh fixture được phép lưu `docs/evidence/`; dữ liệu thật chỉ để ở thư mục runtime bị gitignore.
