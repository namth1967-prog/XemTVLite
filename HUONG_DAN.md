# Xem TV Lite – hướng dẫn build (không cần biết lập trình)

App Android / Android TV xem kênh truyền hình từ danh sách M3U. Nhẹ (khoảng 3–5 MB), mở nhanh, điều khiển bằng remote.

Mặc định app nạp danh sách công khai của iptv-org (kênh Việt Nam). Bạn có thể đổi sang danh sách M3U của bạn trong **Cài đặt**.

## Cách 1 (dễ nhất): GitHub tự build, không cài gì

1. Vào https://github.com, đăng ký/đăng nhập, bấm **New repository**, đặt tên `XemTVLite`, chọn **Public**, bấm Create.
2. Bấm **uploading an existing file**, kéo **toàn bộ nội dung bên trong thư mục XemTVLite** (kể cả thư mục `.github` – nếu máy ẩn thư mục này, hãy bật "hiện file ẩn") vào, rồi bấm **Commit changes**.
3. Mở tab **Actions** → chọn **Build APK** → **Run workflow**. Chờ khoảng 3–6 phút cho dấu tích xanh.
4. Bấm vào lần chạy vừa xong → kéo xuống mục **Artifacts** → tải `XemTVLite-apk` (file zip, bên trong có `app-release.apk`).
5. Chép APK sang TV/điện thoại và cài (bật "Cài từ nguồn không xác định").

## Cách 2: Android Studio (build trên máy tính)

1. Cài Android Studio: https://developer.android.com/studio
2. **Open** → chọn thư mục `XemTVLite`. Đợi Gradle Sync xong (lần đầu tải khá lâu).
3. Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**. Chọn variant **release** nếu hỏi (Build → Select Build Variant).
4. APK nằm ở `app/build/outputs/apk/release/app-release.apk`.

## Cách dùng trên TV

- **OK** vào kênh. **Giữ OK** trên một kênh (chỉ khi **nhấn và giữ** phím OK; bấm OK nhanh vẫn là mở kênh; trên điện thoại thì nhấn giữ) để mở menu: thêm/bỏ ★ yêu thích, **Di chuyển kênh** (sang nhóm khác), **Xoá kênh**. Kênh đã xoá/di chuyển được nhớ mãi; muốn hoàn lại vào **Cài đặt → Khôi phục**.
- Khi đang xem: **↑ / CH+** kênh kế tiếp, **↓ / CH−** kênh trước, **OK** hiện/ẩn tên kênh, **Back** thoát.
- **Cài đặt**: dán link M3U của bạn. Kênh lỗi sẽ tự thử lại 3 lần.

## Xử lý kênh chết (từ bản 1.2)

- Mỗi kênh gom link từ nhiều danh sách công khai (iptv-org, Free-TV). Link đầu là link chính, các link sau là dự phòng.
- Khi bấm vào kênh, app thử các link **song song** (đọc manifest HLS + tải thử đoạn video), chọn link chạy được trước. Đang xem mà lỗi thì tự đổi sang link dự phòng.
- **Chỉ hiện kênh chắc chắn sống (bản 1.4):** mở app, kênh được kiểm tra nền (12 kênh cùng lúc). Kênh nào có link tải được đoạn video đủ nhanh mới hiện ra, nên lần đầu danh sách hiện **dần dần** trong 1–2 phút. Kết quả được **lưu mãi** trên máy (xem bản 2.3), chỉ đổi khi bạn kiểm tra lại. Mỗi lần kiểm tra toàn bộ tốn khoảng vài chục MB mạng.
- Muốn kênh sống nhiều hơn: thêm link M3U của bạn (Cài đặt → Thêm nhóm). Link trùng tên kênh sẽ được gộp làm dự phòng.

## Nguồn link (bản 1.3)

- **Link gốc**: 25 link trong file `app/src/main/assets/live_extra.m3u` (lấy từ file bạn gửi) luôn được thử trước. Muốn bỏ/sửa thì mở file đó bằng Notepad.
- **Tự tìm trên GitHub**: Cài đặt → *Tìm nguồn mới*. App tìm các file .m3u công khai, chỉ giữ kênh có dấu hiệu Việt Nam (VTV, HTV, THVL, SCTV…) rồi gộp làm link dự phòng. Không có token thì GitHub chỉ cho khoảng 60 lượt/giờ nên sẽ dừng sớm; nhập **Token GitHub** (tự tạo miễn phí ở github.com/settings/tokens, không cần tick quyền nào) để tìm được nhiều hơn. Token chỉ lưu trên máy bạn.

## Bản 2.3

- **Nhóm kênh**: nhóm **VTV** chỉ có kênh VTV. Nhóm **Thiết yếu** (ngay sau VTV) gồm ANTV, QPVN, TVB, Vietnam Today và các kênh nhà nước khác (VTC, Quốc hội…).
- **Lưu mãi**: kênh nào đã kiểm tra là sống thì app nhớ mãi, không tự hết hạn. Chỉ thay đổi khi bạn bấm **Cài đặt → Kiểm tra lại tất cả** / **Thử lại** (kênh chết), hoặc khi kênh lỗi lúc đang xem.
- **Nhóm Phim** chỉ giữ kênh tiếng Việt (app không dịch tức thì được).
- **Link tốt nhất làm mặc định**: mỗi link có điểm chất lượng (tốc độ tải đoạn video + băng thông khai báo). Khi mở kênh, app đo lại vài link hàng đầu và phát link điểm cao nhất; các link còn lại là dự phòng, xếp theo điểm. Link lỗi 1 lần chưa bị loại, lỗi 2 lần liên tục mới coi là chết. Hết link thì app tự đo lại 1 lượt trước khi báo kênh không dùng được.
- **Bổ sung nguồn cho kênh chết**: sau khi kiểm tra, nếu còn kênh không có link sống, app tự tìm thêm playlist trên GitHub (tối đa 12 giờ/lần, có thể tắt ở Cài đặt). Link tìm được ưu tiên gộp vào đúng các kênh đang chết, kể cả kênh thể thao quốc tế. Tìm rộng hơn (thêm file .m3u8, nhiều từ khóa) và tự bỏ nguồn tìm được đã bị xóa (lỗi 404). Nên nhập Token GitHub để tìm được nhiều hơn.

## Bản 2.4

- **Nhóm VTV** chỉ có 12 kênh: VTV1…VTV10, VTV5 Tây Nam Bộ, VTV5 Tây Nguyên. Kênh nước ngoài tên giống (HTV Houston, VTV…) không còn lọt vào VTV hay Địa phương.
- **Chống trùng kênh**: các cách viết khác nhau của cùng một kênh (Da Nang TV 1 / Đà Nẵng TV1 / Truyền hình Đà Nẵng 01…) được gộp làm một, ưu tiên hiện tên có dấu.
- **Logo**: kênh không có logo hiện ô màu kèm chữ cái đầu. Muốn tự đặt logo, mở `app/src/main/assets/logos.txt` bằng Notepad, thêm mỗi dòng `Tên kênh|link ảnh logo`, rồi build lại.

## Bản 3.3

- **Cân bằng âm lượng**: app tự chỉnh mức tiếng về mức vừa phải, kênh quá to được giảm nhanh, kênh quá nhỏ được tăng từ từ; vào kênh âm lượng tăng dần trong nửa giây. Tắt/bật ở **Cài đặt → Cân bằng âm lượng giữa các kênh**.
- **Khởi động nhanh hơn**: danh sách kênh lần trước được lưu sẵn nên mở app là hiện ngay.
- **Lỗi thoát bất thường**: nếu app thoát đột ngột, mở **Cài đặt** sẽ thấy mục "Lần thoát bất thường gần nhất" ghi nguyên nhân. Chụp màn hình mục này để được hỗ trợ.

## Bản 3.4

- **Chống trùng kênh mở rộng**: tên viết tắt và tên đầy đủ (ANTV / An Ninh TV / ANTV - TH Công an nhân dân, QPVN / QPVN - TH Quân đội…), tên có thêm ghi chú chất lượng (1080i…) hoặc có phần mô tả đi kèm (ON Movies / ON Movies - You TV) được gộp làm một kênh. Muốn thêm cặp viết tắt mới, sửa bảng `ALIAS_PREFIXES` trong `Data.kt`.
- **Thông báo tiến trình** ghi rõ đang quét gì: "Lượt 1/2: kênh Việt Nam", "Lượt 2/2: Thể thao, Phim & nguồn tự tìm", "Đang đo lại link chập chờn".
- **Âm lượng** chuẩn to hơn khoảng 30%.

## Bản 3.5

- **Phím Back trả focus về chỗ cũ**: đang ở nút nhóm (ví dụ "VTV"), bấm vào một kênh để xem, rồi Back → focus về lại nút "VTV". Đang ở trong danh sách kênh bấm Back → focus về nút nhóm đã đứng trước đó (bấm Back lần nữa mới thoát app).
- **Thêm nhóm không cần link**: bỏ trống link để tạo nhóm trống, rồi giữ OK trên một kênh → Di chuyển kênh… để đưa vào. Nhóm tự thêm luôn hiện trên thanh nhóm kể cả khi đang trống.
- **Gộp kênh trùng giữa các nhóm**: kênh trùng ở nhóm khác nhau được gộp làm một, giữ mọi link, đặt ở nhóm ưu tiên cao hơn. Nhóm do bạn tự thêm không bị gộp chéo.

## Bản 3.6

Giữ OK trên một kênh để mở menu, nay có thêm:
- **Chọn link phát…**: liệt kê các link của kênh kèm trạng thái (tốt / chết / chưa đo). Link bạn chọn được thử đầu tiên. Đang xem kênh, nhấn và giữ OK cũng mở hộp này. Link đang phát được cũng tự trở thành mặc định cho lần sau.
- **Đổi số kênh…**: nhập số mới hoặc dùng nút −10/−1/+1/+10; các kênh khác tự dời chỗ. "Bỏ số riêng" để về số mặc định.
- **Gộp vào kênh khác…**: chọn kênh đích (có gợi ý kênh tên giống, hoặc chọn theo nhóm); mọi link của kênh bị gộp được nhập vào kênh đích. "Tách … kênh đã gộp" để hoàn lại.
- **Cài đặt → Khôi phục** hoàn lại cả kênh đã xoá, di chuyển, gộp và số kênh riêng.
- Ô kênh trong lưới thấp hơn khoảng 1/4; âm lượng chuẩn to hơn 50%.

## Bản 3.7

- **Gộp kênh**: giữ OK trên kênh muốn gộp → **Gộp vào kênh khác…**. Menu đóng lại, bạn di chuyển đến kênh đích (kênh chính) trên lưới rồi nhấn **OK** là xong; Back để hủy. Kênh đang gộp có viền vàng.
- **Back khi đang xem kênh**: thoát màn hình phát thì focus quay về đúng ô của kênh đó.
- **Logo**: app tự tìm logo kênh Việt Nam trên GitHub cho kênh còn thiếu. Kênh nào vẫn thiếu: giữ OK → **Đặt logo…** rồi dán link ảnh.
- **Chọn link phát** có ghi độ phân giải: 720p HD, 1080p FHD, 4K… ("chưa rõ ĐPG" nếu chưa biết; sẽ có sau khi link được đo hoặc phát thử).

## Bản 3.8

- **Xoá link**: trong **Chọn link phát**, mỗi link có nút **Xoá**. Link đã xoá không hiện lại khi nạp danh sách; hoàn lại ở **Cài đặt → Khôi phục**.
- **Nút nhóm** (thanh nhóm phía trên): giữ OK trên một nút nhóm để **Đổi tên**, **Dời sang trái/phải** (thứ tự này cũng quyết định số kênh), hoặc **Xoá nhóm**. Nhóm do bạn tự thêm: xoá cả nhóm và các kênh của nó. Nhóm dựng sẵn: nút biến mất và kênh chuyển sang "Khác" (nhóm "Khác" không xoá được). "Tất cả" và "Yêu thích" cố định.

## Bản 3.9

- **Thêm link nguồn mới**: app báo ngay kết quả ("Nhóm X: nạp được N kênh") hoặc lý do không ra kênh (link không tồn tại, bị từ chối, trả về trang web, file không có kênh…). **Cài đặt** cũng ghi số kênh hoặc cảnh báo lỗi của từng nhóm.
- Link GitHub dạng trang (`github.com/…/blob/…`) tự đổi sang link file thô. Danh sách chỉ có URL trơn, hoặc link là một luồng phát trực tiếp (`.m3u8`), cũng đọc được. Tên kênh tiếng Trung, Thái, Ả Rập, Nga… không còn bị loại.

## Bản 4.0

- **Banner** ứng dụng trên màn hình chính Android TV đổi theo hình "Lê Thu TV" (`app/src/main/res/drawable-nodpi/banner.png`, 960×540). Muốn đổi hình khác chỉ cần thay file này (giữ tên, tỷ lệ 16:9).
- **Logo từ `logos.txt`**: khớp theo tên kênh, kể cả khi tên trong file chỉ là phần đầu (VD dòng `VTV3|…` áp cho "VTV3 Thể thao"). **Cài đặt** cho biết khớp bao nhiêu dòng, dòng nào không khớp kênh nào, và logo nào tải lỗi (kèm tên máy chủ + lý do). App đọc được cả logo dạng SVG.

## Vì sao nhẹ và mượt

- Kotlin + Jetpack Compose, trình phát Media3 ExoPlayer chỉ nạp HLS.
- Bộ đệm nhỏ để vào kênh nhanh, ít tốn RAM; R8 nén code + bỏ tài nguyên thừa; chỉ giữ ngôn ngữ vi/en.
- Danh sách kênh được lưu cache, mở app là hiện ngay rồi mới cập nhật ngầm.

## Lưu ý

- Chỉ dùng nguồn bạn có quyền xem. Mã này không đi kèm kênh/phim cố định nào.
- APK ký bằng khóa debug để cài được ngay. Muốn đăng lên Google Play phải tạo khóa riêng.
