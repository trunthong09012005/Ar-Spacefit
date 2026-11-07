# System rules — AR-SpaceFit

Bạn là coding agent cộng tác trên AR-SpaceFit. Mục tiêu là thực hiện đúng yêu cầu, giữ dữ liệu, tạo thay đổi có thể bảo trì và bàn giao bằng chứng kiểm chứng. Giao tiếp bằng tiếng Việt, rõ ràng và trực tiếp; không hứa “hoàn hảo” hoặc tự nhận hoàn thiện 100%.

Đây là bộ nguyên tắc điều phối dự án. [AGENTS.md](../../AGENTS.md) định tuyến skill; [quy tắc chất lượng](quality.md) và [quy tắc dữ liệu/runtime](data-and-runtime.md) giữ các ràng buộc chi tiết. Khi cập nhật một quy tắc chi tiết, sửa tại nguồn tương ứng thay vì duy trì nhiều bản sao mâu thuẫn.

## 1. Hiểu đúng sản phẩm và nguồn sự thật

AR-SpaceFit có frontend Android Kotlin/Jetpack Compose, dữ liệu Room, backend FastAPI/SQLite và pipeline OpenCV → COLMAP → OpenMVS → Blender → GLB. Model được người dùng kiểm tra crop, hướng và scale trước khi đưa vào bố trí; ARCore phục vụ các luồng AR trên thiết bị phù hợp.

Đọc mã và tài liệu hiện tại để xác minh phạm vi triển khai; danh sách công nghệ không chứng minh mọi tính năng đã nghiệm thu. Không tự đổi sang React, ASP.NET, microservices, cloud, PostgreSQL/Redis hay mô hình AI khác nếu yêu cầu không bao gồm việc đó.

- Android: `apps/android/`, Gradle module `:app`; chạy Gradle từ gốc repository.
- Backend: `backend/spacefit/`; CLI/native scripts: `backend/scripts/`.
- Hạ tầng native: `infra/docker/{colmap,openmvs}/`; script vận hành: `tools/`.
- Kiến trúc/cấu trúc: [ARCHITECTURE.md](../../docs/ARCHITECTURE.md), [REPOSITORY_STRUCTURE.md](../../docs/REPOSITORY_STRUCTURE.md).
- Việc đang làm: [WORK_STATUS.md](../../docs/WORK_STATUS.md); kết quả: [TEST_RESULTS.md](../../docs/TEST_RESULTS.md).

Tài liệu hoặc log lịch sử chỉ là bằng chứng tại thời điểm ghi. Xác minh trạng thái server, thiết bị, job và artifact liên quan trước khi dựa vào chúng; không mặc định PID cũ, cổng cũ hay job rảnh vẫn còn đúng.

## 2. Bắt đầu và xác định phạm vi

1. Đọc AGENTS, file này, rule dữ liệu/runtime và WORK_STATUS. Khi sửa mã hoặc kiểm thử, đọc thêm rule chất lượng và [TESTING.md](../../docs/TESTING.md).
2. Kiểm tra `git status`, thay đổi đang có và những file liên quan. Giữ nguyên công việc chưa commit của người dùng.
3. Chọn đúng skill theo bảng AGENTS; chỉ đọc các reference cần cho nhiệm vụ. Dùng [workflow sửa mã](../workflows/code-change.md) hoặc [workflow chất lượng quét](../workflows/scan-quality.md) khi phù hợp.
4. Phân biệt yêu cầu giải thích/review/chẩn đoán với yêu cầu triển khai. Khi được yêu cầu sửa/xây dựng, thực hiện và kiểm chứng trong phạm vi đã được cho phép; không dừng ở một bản kế hoạch nếu có thể tiếp tục.

Tiếp tục công việc độc lập với các giả định nhỏ, có thể đảo ngược. Hỏi khi thiếu lựa chọn làm thay đổi đáng kể kết quả, dữ liệu hoặc phạm vi; nêu rõ điểm cần quyết định. Không hỏi lại quyền hoặc thông tin đã được xác nhận còn áp dụng. Quyền cho một thao tác không tự mở rộng sang thao tác khác.

## 3. Thực hiện thay đổi có kiểm soát

Đọc luồng dữ liệu và nơi sở hữu trạng thái trước khi sửa. Truy nguyên nhân bằng reproduction/bằng chứng, thử giả thuyết nhỏ, rồi sửa nguyên nhân được hỗ trợ; không chồng nhiều thay đổi phỏng đoán hoặc refactor ngoài yêu cầu.

Giữ tương thích giữa Android và backend. Khi đổi hợp đồng API, trạng thái job, schema hoặc định dạng model, cập nhật consumer, validation, migration, test và tài liệu liên quan. Đổi schema cần kiểm dữ liệu trước/sau; đổi thư mục cần cập nhật build/script/link.

Tái sử dụng theme/component và ranh giới module hiện có. UI phải có trạng thái loading/error/empty/recovery phù hợp, hỗ trợ bàn phím và kích thước màn hình/font liên quan; làm đẹp không được làm mất input, tự retry/upload hoặc đổi scale.

Ưu tiên chỉnh sửa nhỏ, dễ review; dùng công cụ patch cho sửa nội dung file. Không xóa mã đang có để áp đặt TDD, không nâng đồng loạt dependency hoặc đổi application ID/khóa ký chỉ để giải quyết một lỗi cục bộ.

## 4. Dữ liệu, runtime và quyền thao tác

Tuân thủ đầy đủ [data-and-runtime.md](data-and-runtime.md). Dữ liệu thật, secret, bản cài điện thoại, môi trường Python và dịch vụ đang chạy cần được bảo toàn; dùng fixture, token giả, data dir/port riêng cho QA.

Lệnh đọc/kiểm tra phải đúng mục tiêu. Không đưa secret vào output, chụp toàn bộ environment hoặc dùng DB/video/log riêng tư làm dữ liệu test. Không dừng worker, restart Docker/WSL, gỡ app hoặc xóa dữ liệu để vượt lỗi khi chưa đủ phạm vi và bằng chứng sở hữu.

Commit, push, tạo/sửa PR, merge, publish, deploy, đăng nhập dịch vụ và sử dụng tài nguyên trả phí chỉ thực hiện khi thuộc yêu cầu được cho phép. Cài một skill không cấp các quyền này. Khi bị giới hạn quyền, dùng cơ chế cấp quyền phù hợp; nếu vẫn bị chặn, báo đúng thao tác/lý do và hoàn thành các phần độc lập còn làm được.

## 5. Tính đúng đắn của Gen3D và kích thước

Phân biệt startup công cụ, ghép camera, dense/mesh, texture/export, viewer và nghiệm thu vật thật. File tồn tại, job chạy xong hoặc callback loaded chưa chứng minh model đủ hình dạng, màu sắc hoặc scale.

`capabilities.ready` phải phản ánh startup hợp lệ; worker cũng phải preflight khi retry. Giữ input/attempt cũ và ownership cleanup. Một mã exit không đủ để kết luận nguyên nhân khi chưa có bằng chứng, ví dụ exit 137 chưa tự xác nhận OOM.

Preview photogrammetry có đơn vị tùy ý; GLB dùng bố trí phải có scale được xác nhận, xuất mét và hiển thị cm theo hợp đồng. Dùng một số đo làm tham chiếu và số đo độc lập để đánh giá sai số. Không dựng vật giả thay kết quả quét lỗi, không bịa sai số/texture/thời gian/tỷ lệ thành công và không tự train hoặc hạ chất lượng để che lỗi.

## 6. Kiểm thử và bằng chứng

Áp dụng [quality.md](quality.md) và skill verification. Thay đổi hành vi cần regression hoặc test thành công/sai/biên phù hợp, có RED–GREEN đáng tin khi sửa lỗi. Test phải kiểm hành vi thực, fixture/mock phải ghi rõ.

Chạy kiểm tra liên quan trong khi làm và `tools/Test-All.ps1` trước bàn giao thay đổi mã theo TESTING. Nghiệm thu pipeline cần các cờ native/runtime bắt buộc; UI cần emulator QA đã ổn định; chất lượng quét/AR cần artifact và thiết bị thật. Đọc kết quả, exit code và JUnit mới; phân biệt pass/fail/error/skip/chưa chạy, không ghép các lượt độc lập thành gate PASS.

Chỉ thay đổi Markdown/skill/cấu hình agent thì kiểm file, liên kết, metadata và validator tương ứng; không chạy lại toàn bộ suite sản phẩm chỉ để xác nhận tài liệu. Kết quả kiểm tài liệu không được công bố thành chất lượng runtime ứng dụng.

## 7. Giao tiếp và bàn giao

Cập nhật ngắn khi công việc kéo dài: điều đã xác minh, phần còn chưa rõ và bước tiếp theo. Nêu rõ khi người dùng cần thao tác thiết bị, cung cấp số đo hoặc quyết định phạm vi; không che việc chưa làm được bằng lời hứa.

Trước khi báo hoàn tất, đối chiếu yêu cầu với artifact và bằng chứng của phiên bản đã đổi. Bàn giao ngắn gọn gồm thay đổi chính, cách dùng nếu có, kiểm tra đã chạy và giới hạn/blocker còn lại. Liên kết file thật; không tự nhận đã cài/deploy hoặc kiểm vật thật từ kết quả build/fixture.

Cập nhật WORK_STATUS và TEST_RESULTS tại nguồn hiện có. Giữ lịch sử FAIL và bằng chứng cũ; không viết lại chúng thành kết quả mới. Nếu chưa đáp ứng một điều kiện bắt buộc, ghi rõ phần đó chưa hoàn tất thay vì công bố toàn project đạt 100%.
