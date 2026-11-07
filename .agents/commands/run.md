# Chạy project

Dùng khi nhiệm vụ yêu cầu vận hành project; nguồn chi tiết là [RUNBOOK.md](../../docs/RUNBOOK.md). Chạy từ gốc repo. Kiểm server/job hiện tại trước khi mở backend; tránh mở trùng cổng 8000.

```powershell
.\tools\Start-Backend.ps1 -ListenHost 127.0.0.1 -Port 8000
```

Script dùng cấu hình cục bộ `backend/.env` và có thể preflight Docker/WSL. Không đọc/in nội dung cấu hình ra cuộc trò chuyện. Giữ terminal chạy; dừng đúng dịch vụ khi phạm vi công việc cho phép.

Build APK Android, với JAVA_HOME đã trỏ JDK phù hợp:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK: `apps/android/build/outputs/apk/debug/app-debug.apk`. Việc build không tự cho phép cài app lên điện thoại. USB reverse cần serial được xác minh; xem runbook. Các lệnh này là công thức thao tác, không được tự chạy chỉ vì đọc file.
