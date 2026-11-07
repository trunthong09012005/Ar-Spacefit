# Kiểm tra trạng thái

Đọc [quy tắc dữ liệu/runtime](../rules/data-and-runtime.md). Chọn probe liên quan tới nhiệm vụ, chạy từ gốc repo:

```powershell
git status --short
Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction Stop
Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health' -TimeoutSec 5
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
docker --context desktop-linux ps --format '{{.Names}} {{.Status}}'
```

Các probe độc lập, không phải script bắt buộc chạy toàn bộ. Nếu Docker/ADB không ở PATH, dùng vị trí đã xác minh trên máy. Lỗi quyền, timeout, không có listener hoặc Engine chưa chạy phải được ghi đúng; không suy job rảnh từ lỗi kết nối hoặc số đếm cũ. `adb devices` có thể tự mở ADB daemon. Không đọc `.env` hoặc DB để hoàn thành bước kiểm tra này.

Khi cần kiểm authenticated capabilities, dùng luồng ghép nối trong app hoặc cơ chế đã được cho phép; không in token. Không tự restart dịch vụ từ kết quả probe.
