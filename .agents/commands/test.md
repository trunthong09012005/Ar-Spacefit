# Chạy kiểm thử

Đọc [TESTING.md](../../docs/TESTING.md) và [skill verification](../skills/spacefit-verification/SKILL.md). Chạy từ gốc repo; thay đường dẫn JDK/Blender bằng vị trí thực tế.

Gate thông thường, chưa nghiệm thu UI/native:

```powershell
.\tools\Test-All.ps1 -JavaHome 'DUONG_DAN_JDK' -BlenderBin 'DUONG_DAN_BLENDER'
```

Nghiệm thu pipeline và UI khi đã preflight được SpaceFitQA:

```powershell
.\tools\Test-All.ps1 -JavaHome 'DUONG_DAN_JDK' -BlenderBin 'DUONG_DAN_BLENDER' -RequireBlender -RequireNative -RequireDocker -RequireColmapDocker -RequireDevice -EmulatorSerial emulator-NNNN
```

`emulator-NNNN` là placeholder cho serial QA đã xác minh, không phải serial điện thoại. Kiểm hành vi nạp config của runner/doctor và yêu cầu cô lập trước native diagnostics; standalone doctor có `--no-config`. Không đổi assertion hay bỏ điều kiện để che lỗi môi trường. Kết quả mới nằm ở `.tools/qa/run-*/`; không dùng thư mục đã tồn tại làm pytest basetemp.

Chỉ sửa Markdown/skill: validate skill bị ảnh hưởng và kiểm liên kết tài liệu. Không cần khởi động native, emulator hoặc chạy lại toàn bộ suite sản phẩm.
