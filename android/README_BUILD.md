# COM11H Android v1.6.0

## Ghi chú bản 1.6.0 (so với 1.5.2)
- **Trang chủ – ô tìm kiếm:** thêm nút bấm 🔍 cạnh ô "Tìm món ăn..." (trước đó
  chỉ có ô nhập, không có cách nào để bấm tìm). Bấm nút hoặc bấm "Tìm kiếm"
  trên bàn phím sẽ mở màn Thực đơn và tự lọc theo từ khoá đã nhập. Màn Thực
  đơn cũng có sẵn ô tìm kiếm riêng để gõ lại/đổi từ khoá.
- **Trang chủ – banner giữa trang:** không còn banner tĩnh cố định trong code
  app. Banner giờ tải trực tiếp từ `api?action=banners`, dùng CHUNG bảng dữ
  liệu với **Admin > Banner trang chủ** (`admin/banners.php`) mà web đang
  dùng — Admin thêm/sửa/xoá/đổi thứ tự banner trên trang quản lý là app tự
  cập nhật theo ngay lần mở app kế tiếp, không cần build lại app. App hiển
  thị banner dạng slider tự chạy (đổi ảnh mỗi 4 giây) nếu có từ 2 banner trở
  lên, bấm vào banner sẽ mở đúng link đã cấu hình trên Admin (qua
  `banner_click.php` để thống kê lượt click giống web). Nếu Admin chưa tạo
  banner nào, app tự hiển thị lại banner mặc định như cũ.
- **Bắt buộc:** phải upload `api/index.php` mới (đã thêm action `banners`)
  lên server TRƯỚC KHI phát hành app 1.6.0, nếu không banner sẽ không tải
  được và app sẽ tự dùng lại banner mặc định.

## Ghi chú bản 1.4.2 (so với 1.4.1)
Chỉnh giao diện thẻ món ăn ở màn Thực đơn theo yêu cầu:
  - Ô ảnh to hơn: 88dp -> 128dp.
  - Logo COM11H nhỏ (26dp) ở góc trên-trái mỗi ảnh món.
  - Bấm vào ảnh món -> mở hộp thoại xem ảnh cỡ lớn (bấm "Đóng" hoặc
    bấm vào ảnh để tắt).
  - Nút "+ Thêm": cỡ chữ giảm còn một nửa (16f -> 8f), nền đổi sang
    vàng nhạt (thay vì nền mặc định của hệ thống).

## Ghi chú bản 1.4.1 (sửa lỗi so với 1.4.0)
Bản 1.4.0 trước đó bị build nhầm từ 1 bản `MainActivity.kt` cũ hơn cả v1.3.0
(bản đang có trên GitHub `main`), nên đã VÔ TÌNH MẤT phần hiển thị ảnh món ăn
(Thực đơn + Giỏ hàng) và phần thẻ đơn hàng đầy đủ 6 cột (giống `account.php`
bên web). Bản 1.4.1 này khôi phục lại toàn bộ phần đó, đồng thời giữ nguyên
các tính năng mới của 1.4.0 (xóa tài khoản, đăng xuất gọi API thu hồi token,
link chính sách quyền riêng tư, xử lý lỗi mạng rõ ràng hơn).
Đồng thời API (`api/index.php`) đã được sửa để trả URL ẢNH TUYỆT ĐỐI thay vì
đường dẫn tương đối — bắt buộc phải upload web/api mới TRƯỚC KHI phát hành
app 1.4.1 thì ảnh mới hiển thị đúng (xem HUONG_DAN_UPLOAD.txt).

## Build in Android Studio
1. Open the `android` folder as the project root.
2. Use JDK 17.
3. Use Gradle 8.9 (AGP 8.7.3), JDK 17, compileSdk/targetSdk 36.
4. Sync Gradle and run the `app` configuration on the phone.

## Build APK from GitHub
The repository root includes `.github/workflows/build-android.yml`.
After pushing to `main`, GitHub Actions builds:
`android/app/build/outputs/apk/debug/app-debug.apk`

The APK is published as the workflow artifact `COM11H-Android-v1.4.1-debug`.

## Verified business flow to preserve
Login -> Menu (with food images) -> Cart (with thumbnails) -> Order Preview -> Create Order -> QR -> Payment (auto-hide QR + refresh points on paid) -> Stock reduction -> Delivery -> Customer delivery confirmation -> Points/Lucky Code -> Order list (6-column card matching web's account.php).

Payment must be confirmed by the server before stock is reduced.
