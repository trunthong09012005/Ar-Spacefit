# CI/CD cho AR-SpaceFit

## Bản đầu: kiểm thử và APK thử nghiệm

Phạm vi được người dùng chọn ngày 07/11/2026: trước mắt chỉ build APK để tải về; chưa triển khai backend hoặc phát hành bản release.

Workflow [ci.yml](../.github/workflows/ci.yml) dùng GitHub Actions, chạy khi push các nhánh Gitflow, khi mở/cập nhật PR vào main/develop/release, hoặc chạy thủ công khi workflow đã có trên default branch. Không cần secret cho các job hiện tại.

| Job | Công việc |
|---|---|
| Backend tests | Python 3.14 trên Windows, cài requirements đã pin, chạy core/integration và HTTP black-box bằng DB/data fixture riêng. |
| Android tests and APK | Java 25, SDK 36.1, test JVM/Robolectric, lint, build APK debug và instrumentation APK. Chưa chạy instrumentation trên thiết bị. |
| CI gate | Chỉ đạt khi cả hai job trên thành công. Có thể chọn tên check này làm required status check sau lần chạy đầu. |

Chọn Windows vì test runner có regression PowerShell Windows. [Assert-JUnit.ps1](../tools/Assert-JUnit.ps1) từ chối report thiếu, rỗng, lỗi hoặc có test skip. Ba file native Blender/COLMAP/OpenMVS được loại rõ khỏi bộ CI nền vì cần runtime riêng; CI xanh không chứng minh quét thật/AR đạt. Vẫn cần cổng native/thiết bị theo [TESTING](TESTING.md).

Actions được pin full commit SHA, token chỉ `contents: read`, checkout không lưu credential, không lấy secret vào PR. Dùng [hướng dẫn an toàn GitHub Actions](https://docs.github.com/en/actions/reference/security/secure-use) và upstream [checkout](https://github.com/actions/checkout/releases/tag/v6.0.2), [setup-python](https://github.com/actions/setup-python/releases/tag/v6.0.0), [setup-java](https://github.com/actions/setup-java/releases/tag/v5.0.0), [upload-artifact](https://github.com/actions/upload-artifact/releases/tag/v6.0.0) làm nguồn kiểm pin. Đây không phải hook enforce commit hay cấu hình protection tự động.

## Bạn cần làm trên GitHub

Sau khi commit workflow vào `feature/ci-pipeline`, đưa develop nền và nhánh này lên remote đã kiểm đúng:

```powershell
git remote -v
git push -u origin develop
git push -u origin feature/ci-pipeline
```

Push là thao tác riêng; các lệnh trên là hướng dẫn, không có nghĩa đã chạy. Không push thẳng main vì main đang giữ mốc ban đầu. Tạo PR `feature/ci-pipeline` → `develop`, mở tab Actions xem lần chạy và sửa lỗi nếu có. Sau lần CI đầu, vào Settings → Rules → Rulesets (hoặc Branches tùy giao diện/quyền) và cấu hình main/develop yêu cầu PR cùng check `CI gate`. Tên/quyền bật ruleset phụ thuộc loại repository và gói tài khoản; kiểm trên repo thật trước khi bật. Khi đã merge develop, workflow tiếp tục theo Gitflow đến release/main.

Nếu Actions chưa chạy, kiểm Settings → Actions → General cho phép Actions và các action được pin; kiểm quyền tài khoản và hạn mức runner/billing hiện tại. Job chỉ chạy khi GitHub nhận commit có workflow. Chưa có kết quả remote thì chưa khẳng định CI chạy thành công.

Ở lần chạy, mục Artifacts có `backend-reports-*`, `android-reports-*` và `spacefit-debug-*` nếu Android thành công. Tải artifact APK debug về để thử; giữ 7 ngày. APK có thể xuất hiện khi Android đạt nhưng backend lỗi, nên cần xem thêm check `CI gate` trước khi dùng kết quả tổng hợp. Xem [cách tải artifact trên GitHub](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts).

APK debug dùng khóa debug của runner, có thể khác chữ ký bản đang cài trên điện thoại. Không gỡ app/xóa dữ liệu để vượt lỗi chữ ký; dùng cùng khóa ký khi cần cập nhật giữ dữ liệu. APK này không phải bản release ký chính thức. Không nhúng API key thật vào BuildConfig để phát hành.

## CD cần lựa chọn đích phát hành

Hiện workflow chỉ kiểm thử và cung cấp APK debug, chưa có deploy hoặc GitHub Release tự động. Để viết CD tiếp cần chọn:

- Phát hành app: GitHub Releases, kênh nội bộ hay Play Console; dùng khóa ký release được quản lý ngoài Git, quản lý versionCode/versionName và phê duyệt phát hành. Không dùng debug key làm khóa phát hành.
- Backend: máy chủ/VPS cụ thể, runtime Docker và tài nguyên tái dựng, HTTPS/domain, kho secret, volume dữ liệu, health check, backup và rollback. Backend laptop localhost/USB hiện tại không tự trở thành một dịch vụ cloud.

Chỉ cấu hình secret/environment và bước deploy sau khi có đích cụ thể. Native integration và nghiệm thu thiết bị cần môi trường QA riêng; không gắn runner tự host vào laptop có DB/token thật để chạy PR tùy ý.
