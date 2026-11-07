package com.example.reconstruction

/** Only an explicit backend OOM marker confirms memory exhaustion; 137 alone does not. */
internal fun scanFailureMessage(raw: String): String = when {
    raw.startsWith("NATIVE_OUT_OF_MEMORY:") ->
        "Docker xác nhận tiến trình dựng 3D bị dừng vì thiếu RAM (mã 137). " +
            "Cần điều chỉnh ngân sách bộ nhớ xử lý trên laptop trước khi thử lại. " +
            "Ảnh gốc vẫn được giữ; ứng dụng không tự chạy lại hoặc giảm chất lượng."
    raw.startsWith("NATIVE_KILLED:") || Regex("^[a-z_]+ failed \\(exit 137\\)\\.").containsMatchIn(raw) ->
        "Tiến trình dựng 3D bị buộc dừng (mã 137), chưa xác nhận có phải thiếu RAM. " +
            "Kiểm tra sự kiện Docker và bộ nhớ trên laptop trước khi thử lại. Ảnh gốc vẫn được giữ."
    else -> raw
}
