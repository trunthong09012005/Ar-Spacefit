# Kiểm thử AR-SpaceFit

Toàn bộ mã kiểm thử ứng dụng được đặt dưới `test/`:

```text
test/
├── backend/                 # pytest: unit, integration, HTTP black-box, native opt-in
├── android/
│   ├── unit/                # JVM/Robolectric: java/ và screenshots/
│   └── instrumentation/     # Instrumentation/UI trên SpaceFitQA: java/
└── README.md
```

Chạy từ gốc repository. `pytest.ini` ở gốc khai báo `test/backend` và Python package `backend/`; module Gradle `:app` trỏ source set test/androidTest tới hai thư mục Android tương ứng. Giữ package Kotlin và test runner hiện có.

```powershell
# Toàn bộ backend; native cần điều kiện/config được hướng dẫn trong TESTING
.\backend\.venv\Scripts\python.exe -B -m pytest test/backend

# Unit/integration JVM Android
.\gradlew.bat :app:testDebugUnitTest

# Cổng kiểm thử tổng hợp
.\tools\Test-All.ps1 -JavaHome 'DUONG_DAN_JDK' -BlenderBin 'DUONG_DAN_BLENDER'
```

Chỉ định `-EmulatorSerial` khi SpaceFitQA đã đủ điều kiện; không chạy test ghi dữ liệu trên điện thoại thật. Dùng [TESTING.md](../docs/TESTING.md) để chọn nhóm/cờ native/thiết bị và [TEST_RESULTS.md](../docs/TEST_RESULTS.md) để đọc kết quả/giới hạn.

Fixtures và helper Python nằm cạnh test sở hữu chúng. Schema Room vẫn tại `apps/android/schemas/` vì thuộc lịch sử dữ liệu ứng dụng. Probe debug-only trong `apps/android/src/debug/` là công cụ hỗ trợ bản debug, không phải bài test độc lập.

Output Gradle/JUnit/APK vẫn sinh tại `apps/android/build/`; output QA mới vẫn ở `.tools/qa/run-*/` và bị Git bỏ qua. Không chuyển log/report/baseline lịch sử hoặc dữ liệu quét thật vào thư mục mã test. Thêm bài test mới vào cây này, không tạo lại `backend/tests/` hay source test rải rác trong app.
