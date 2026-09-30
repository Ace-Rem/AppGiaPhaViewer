# Xem Gia Phả — Android offline

Ứng dụng Android native Java/Gradle cho `family-tree-viewer`. Dữ liệu được tải bằng HTTPS, giải mã và đọc hoàn toàn trên thiết bị; app vẫn hoạt động offline sau lần tải đầu tiên.

## Cấu hình Google Drive

App chỉ cần biết một URL cố định: URL công khai của `update.txt`. Thay duy nhất hằng số `UPDATE_URL` trong [DataSyncConfig.java](app/src/main/java/com/android/acerem/xemgp/data/DataSyncConfig.java):

```java
public static final String UPDATE_URL =
        "https://drive.google.com/uc?export=download&id=UPDATE_FILE_ID";
```

Không đặt URL/file ID của `data.enc` hoặc `images.zip` trong APK. Hai URL này nằm trong `update.txt` và có thể thay đổi mà không cần build APK mới.

Nội dung `update.txt`:

```text
# Dòng bắt đầu bằng # là comment
version=1
data=https://drive.google.com/uc?export=download&id=DATA_FILE_ID
images=https://drive.google.com/uc?export=download&id=IMAGES_FILE_ID
```

Nếu phiên bản không có ảnh, để trống:

```text
version=2
data=https://drive.google.com/uc?export=download&id=DATA_FILE_ID
images=
```

Mỗi file cần được chia sẻ công khai với quyền Viewer. Bộ tải chuyển URL Google Drive về request tải file, xử lý redirect/confirmation nếu Google yêu cầu, chống cache cho `update.txt` và từ chối HTML/trang lỗi.

Không cần Google API, API key, OAuth hay backend. `data.enc` phải là file blob/encrypted file thông thường; không dùng Google Docs/Sheets/Slides export.

## Luồng cập nhật

Khi mở app, `DataSyncManager` kiểm tra mạng, tải `update.txt` với request no-cache và query chống cache, parse version cùng hai URL rồi so sánh với version local.

- Remote version bằng hoặc thấp hơn local: dùng dữ liệu local.
- Remote version cao hơn: tải `data.enc` và `images.zip` vào file tạm.
- Chỉ sau khi envelope, ZIP và quá trình giải mã/xử lý hợp lệ mới cài dữ liệu và ghi version local.
- Nếu lỗi, file tạm bị xóa và dữ liệu local cũ vẫn được giữ nguyên.
- Nếu `images=`, ảnh local hiện tại được giữ lại khi chuyển sang fingerprint dữ liệu mới.
- Không có Internet vẫn dùng dữ liệu local hợp lệ; cài mới không có dữ liệu sẽ yêu cầu kết nối.

Để phát hành bản cập nhật: upload file mới lên Google Drive, lấy link tải công khai, sửa các dòng `data=`/ `images=` trong `update.txt`, tăng `version`, rồi lưu `update.txt`. Không cần backend hoặc server chạy 24/7.

## Build

- Android Studio hiện đại, JDK 11
- Gradle wrapper 9.6.0, Android Gradle Plugin 9.4.1
- `minSdk 28`, `targetSdk 37`

```bash
./gradlew assembleDebug
```

## Bảo toàn dữ liệu hiện tại

Cơ chế envelope AES-GCM, PBKDF2, mật khẩu, schema plaintext sau giải mã, database/model, Login, Viewer và xử lý ảnh root-level (`webp`, `jpg/jpeg`, `png`) được giữ nguyên. `DataRepository` vẫn quản lý fingerprint local và commit file tải xuống an toàn.

Ảnh vẫn được lưu ở `files/families/<sha256>/images/`; session ghi nhớ vẫn bị vô hiệu khi fingerprint dữ liệu đổi.
