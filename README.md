# Xem Gia Phả — Android

Ứng dụng tải dữ liệu bằng HTTPS, giải mã và validate hoàn toàn trên thiết bị. Mỗi lần mở app, flow đồng bộ là:

```text
Cloudflare Worker /version
    ↓ dataVersion/version
Cloudflare Worker /data?version=<VERSION>
    ↓ thất bại ở bất kỳ bước nào
Google Drive update.txt → data.enc
    ↓ thất bại
Error → Thử lại
```

Android chỉ gọi Worker public tại `https://family-tree-api.acerem.workers.dev`; APK không chứa R2 access key, secret, Cloudflare API token, publish token hay credential quản trị.

## Cloudflare Worker

`GET /version` phải trả metadata theo contract Worker, gồm `dataVersion` hoặc `version`, cùng `contentHash` và tùy chọn `dataSize`. App dùng `dataVersion` (fallback `version`) để gọi chính xác:

```text
GET https://family-tree-api.acerem.workers.dev/data?version=<VERSION>
```

App kiểm tra HTTP status, kích thước khi có `dataSize`, SHA-256, `contentHash`/`version`, encrypted envelope và format AES-GCM trước khi cài dữ liệu. R2 không được truy cập trực tiếp.

## Google Drive fallback

Google Drive chỉ được thử sau khi Cloudflare thất bại. App tải manifest công khai từ `UPDATE_URL` trong [DataSyncConfig.java](app/src/main/java/com/android/acerem/xemgp/data/DataSyncConfig.java):

```text
version=1
data=https://drive.google.com/uc?export=download&id=DATA_FILE_ID
```

Google Drive là fallback ảnh thứ hai: chỉ khi Cloudflare không lấy được bất kỳ ảnh usable nào, Android mới đọc `images=` trong manifest và tải/giải nén ZIP ảnh. Nếu Cloudflare lấy được dù chỉ một ảnh, Google Drive sẽ không được gọi. Có thể thêm `dataSize=` và `contentHash=` để kiểm tra integrity cho Google Drive. Bộ tải xử lý redirect/confirmation của Drive, chống cache, từ chối HTML/trang lỗi và chỉ nhận các file ảnh khớp tên thành viên. Không có file picker hoặc nhập `data.enc`/ZIP thủ công. Sau khi data.enc và ảnh được tải, chúng được lưu trong bộ nhớ riêng của app để Viewer hoạt động offline.

## Ảnh thành viên từ Cloudflare R2

Android tạo filename bằng `ImageFilenameResolver`, sau đó tải ảnh công khai qua:

```text
https://family-tree-api.acerem.workers.dev/images/<filename>.webp
```

Để tránh tạo một request kiểm tra cho từng thành viên, Worker nên cung cấp thêm
`GET /images/manifest` với dạng `{ "images": [{ "filename": "...webp", "size": 48231, "sha256": "..." }] }`.
Android sẽ dùng manifest để so sánh local và chỉ tải ảnh mới/thay đổi. Nếu
endpoint này chưa được triển khai, Android vẫn có fallback `HEAD` từng ảnh;
fallback này chỉ là tương thích tạm thời và không ảnh hưởng hiển thị offline.

UI không tạo URL Worker và không request ảnh khi đang xem cây/profile. Sau khi data.enc được giải mã, Viewer mở ngay; ImageRepository tải ảnh âm thầm bằng sáu worker nền và phát tín hiệu để cây/profile tự cập nhật khi file sẵn sàng. TreeCanvasView và profile chỉ gọi ImageRepository.loadLocal(), decode thumbnail theo kích thước cần thiết bằng executor/LRU memory cache; thiếu file thì dùng initials/avatar. Vì file nằm trong app-specific internal storage, cây và profile vẫn hiển thị offline.

## Mã hóa và đăng nhập

Envelope được giữ nguyên:

```json
{
  "v": 1,
  "algorithm": "AES-GCM",
  "kdf": "PBKDF2-SHA-256",
  "iterations": 210000,
  "salt": "...",
  "iv": "...",
  "ciphertext": "..."
}
```

Password viewer chỉ tồn tại ở Android và không bao giờ được gửi tới Worker, R2 hoặc Google Drive. Tải source thành công nhưng password sai chỉ hiển thị lỗi đăng nhập; không kích hoạt fallback source.

`DataRepository` chỉ giữ dữ liệu đã đồng bộ để Login/Viewer và ảnh hiện tại có thể đọc trong phiên ứng dụng. Nó không được dùng để chọn source, fallback offline hoặc bỏ qua hai nguồn online.

## Build

- Android Studio hiện đại, JDK 11
- Gradle wrapper 9.6.0, Android Gradle Plugin 9.4.1
- `minSdk 28`, `targetSdk 37`

```bash
./gradlew assembleDebug
```
