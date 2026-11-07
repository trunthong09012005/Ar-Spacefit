# Chẩn đoán chất lượng mô hình quét

Đọc [skill Gen3D](../skills/spacefit-gen3d/SKILL.md) và [checklist vật thật](../../docs/REAL_SCAN_ACCEPTANCE.md).

1. Xác định đúng phiên, artifact và phiên bản từ bằng chứng được phép xem. Tách thông tin trên ảnh người dùng khỏi trạng thái runtime mới; không lấy lịch sử DB/log cũ làm trạng thái hiện tại.
2. Phân loại vấn đề: startup/container, ghép camera, dense/mesh, texture/export, viewer hay crop/scale. Dùng artifact đúng tầng; ảnh tối đơn lẻ chưa xác định được lỗi mesh.
3. Khi có GLB được phép kiểm, xem cùng file trong renderer độc lập; kiểm UV/texture/material và render trung tính trên bản sao QA. Giữ đầu vào gốc. Đánh giá hình dạng trước khi xác nhận kích thước.
4. Nếu cần sửa pipeline/viewer, chuyển sang [workflow sửa mã](code-change.md), kiểm native/pixel tương ứng. Chỉ retry dữ liệu thật hoặc đổi chất lượng khi phạm vi nhiệm vụ cho phép.
5. Với kết luận đo lường, dùng một chiều scale và một chiều đối chứng độc lập, ghi sai số thực đo và khuyết tật nhìn thấy. Nếu chưa đủ artifact/thiết bị, ghi chính xác điều chưa xác minh; không biến fixture hay mô hình AI suy đoán thành bản quét đạt.
