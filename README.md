# Xem Gia Phả — Android offline

Ứng dụng Android native Java/Gradle trong Android Studio cho `family-tree-viewer`. Ứng dụng không dùng WebView, server, Firebase, analytics hay quyền Internet. Toàn bộ import, giải mã, tìm kiếm và render cây chạy trên thiết bị.

## Build

- Android Studio hiện đại, JDK 11
- Gradle wrapper 9.6.0, Android Gradle Plugin 9.4.1
- `minSdk 28`, `targetSdk 37`

```bash
./gradlew assembleDebug
```

APK debug tạo tại `app/build/outputs/apk/debug/app-debug.apk`.

## Tương thích Web Viewer

Các file đã đọc và port trực tiếp:

- `family-tree-viewer/crypto.js`: envelope `v: 1`, `AES-GCM`, PBKDF2-HMAC-SHA256, 210.000 vòng mặc định, AES-256, IV 12 byte và GCM tag 128 bit.
- `family-tree-viewer/auth.js`: giải mã trước, kiểm tra `auth.username` sau đó mới mở family; các tham chiếu member được kiểm tra như Web Viewer.
- `family-tree-viewer/tree.js`: quan hệ cha/mẹ/con, spouse, sibling, generation 1-based, `siblingOrder`, `generationOffset`, filter dòng họ và generation navigation.
- `family-tree-viewer/member-image.js`: bỏ dấu tiếng Việt, hạ chữ, bỏ ký tự ngoài `[a-z0-9]`, nối năm sinh và dùng `.webp`.
- `family-tree-viewer/sibling-role.js`: quy tắc họ, family role và sibling grouping.
- `index.html` và `styles.css`: palette heritage tối, card, border, hierarchy và các tương tác được thiết kế lại thành native Android.

Android đọc schema plaintext sau giải mã trực tiếp, không tạo schema `.enc` mới. Các trường chính gồm `auth`, `family`, `members`, `fatherId`, `motherId`, `spouseIds`, `siblingIds`, `siblingOrder`, `generation`, `familyRole` và các field profile.

## Luồng sử dụng

1. Chọn file dữ liệu `.enc` bắt buộc.
2. Có thể chọn thêm `.zip` ảnh; ZIP chỉ nhận ảnh ở root (`webp`, `jpg/jpeg`, `png`).
3. Ảnh được validate bằng `BitmapFactory`, giải nén vào thư mục tạm rồi commit atomic vào `files/families/<sha256>/images/`. ZIP không được đọc lại ở lần mở app sau.
4. Nhập username/password được lưu trong file sau khi giải mã.
5. Viewer hỗ trợ Canvas tree, pan, zoom, pinch, tap/double-tap, fit, generation rail, filter, tìm kiếm không dấu, profile bottom sheet và bấm quan hệ để focus.

Ảnh thiếu hoặc hỏng dùng initials; không tạo broken-image icon. Các family khác nhau có namespace fingerprint riêng nên không dùng nhầm ảnh.

## Remember login và bảo mật

Password không được lưu. Khi bật **Ghi nhớ đăng nhập**, Android derive AES key bằng đúng PBKDF2 của Web Viewer, sau đó mã hóa key đó bằng AES-GCM key trong Android Keystore và chỉ lưu ciphertext/IV, username và fingerprint. Session chỉ restore nếu fingerprint file hiện tại khớp. Đổi `.enc` sẽ xóa session cũ và yêu cầu đăng nhập lại.

Logout xóa session nhớ và object family trong memory nhưng không xóa `data.enc` hoặc ảnh local. Ảnh nằm trong private app storage, không xuất hiện trong Gallery. Gỡ app sẽ xóa local storage theo Android.

## Giới hạn kiểm thử

Project đã được xác minh bằng `:app:compileDebugJavaWithJavac` và `:app:assembleDebug`. Workspace có `family-tree-viewer/data.enc`, nhưng không chứa password kiểm thử; vì vậy không thể thực hiện đăng nhập thực tế vào payload mẫu trong môi trường này. Khi có password hợp lệ, nên kiểm tra parity member/generation/relationships giữa web và Android trên cùng file.
