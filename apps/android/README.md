# Android app

Ứng dụng Kotlin/Compose, Gradle module `:app`, application ID `com.spacefit.app`. Chạy Gradle và mở Android Studio từ **gốc repository**, nơi có `settings.gradle.kts`, wrapper và `local.properties`.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- `src/main/`: mã sản phẩm, manifest và Android resources.
- `src/debug/`: probe phục vụ kiểm thử, không đưa vào release.
- `test/android/unit/` tính từ gốc repo: unit/integration JVM và Robolectric.
- `test/android/instrumentation/` tính từ gốc repo: instrumentation trên SpaceFitQA.
- `schemas/`: lịch sử schema Room; giữ các phiên bản để kiểm migration.
- `build/`: output sinh tự động, bị Git bỏ qua.

Hai source set Gradle `test`/`androidTest` trỏ tới thư mục test chung ở gốc. Xem [hướng dẫn test](../../test/README.md); không tạo lại source test bên trong `apps/android/src/`.

APK debug: `apps/android/build/outputs/apk/debug/app-debug.apk` tính từ gốc repo. Cấu hình `.env` Android tiếp tục đặt ở gốc, backend dùng `.env` riêng. Xem [cấu trúc](../../docs/REPOSITORY_STRUCTURE.md), [runbook](../../docs/RUNBOOK.md) và [kiểm thử](../../docs/TESTING.md).

Khi ký release, nên đặt `KEYSTORE_PATH` là đường dẫn tuyệt đối tới keystore hiện có. Nếu dùng đường dẫn tương đối, Gradle tính từ thư mục module `apps/android/`; cần cập nhật cấu hình bên ngoài vốn tính từ `app/`. Việc sắp xếp này chỉ kiểm build debug, không tạo/đổi khóa ký hoặc xác nhận release.
