# GMS Keeper

Đưa Google Play Services (`com.google.android.gms`) và `com.google.android.gsf` vào whitelist Doze
qua Shizuku (không root) để FCM không bị trễ trên HyperOS.
Ngoài whitelist Doze, app còn đặt appops `RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND` = allow và standby bucket = `active` cho hai package này.

## Build không cần Android Studio
1. Tạo repo GitHub mới, push toàn bộ thư mục này lên nhánh `main`.
2. Tab **Actions** → workflow **Build & Release APK** tự chạy (hoặc bấm *Run workflow*).
3. Xong thì vào tab **Releases** (cột phải trang repo) → tải `gms-keeper-vX.Y.apk` ở bản mới nhất → cài lên máy.
   Cũng có thể lấy từ **Artifacts** trong run đó.

Mỗi lần push, release `v<versionName>` được tạo hoặc cập nhật tự động.
Muốn ra release mới thì tăng `versionCode` và `versionName` trong `app/build.gradle.kts`.

## Dùng
1. Cài Shizuku (Google Play), bật Wireless debugging, khởi động Shizuku.
2. Mở app → **Cấp quyền Shizuku** → **Áp dụng**.
3. Sau reboot: khởi động lại Shizuku, app tự áp dụng lại whitelist khi Shizuku lên.

## Ứng dụng bảo vệ thêm
Ngoài GMS/GSF, app áp dụng cùng bộ lệnh cho các app bạn chọn (mặc định có Zalo, `com.zing.zalo`).
Chạm vào một app để xem chẩn đoán: tiến trình, standby bucket, whitelist, appops, force-stop. Giữ để bỏ khỏi danh sách.

## Giữ nhịp (thử nghiệm)
Định kỳ gửi heartbeat tới Google Play Services qua Shizuku để kết nối FCM không bị nhà mạng cắt khi rảnh.
Chu kỳ 3/5/10 phút, có lịch theo khung giờ và ngày trong tuần (ngoài lịch thì không gửi, không đánh thức máy).
Dùng chi tiết **Kết nối FCM** để so sánh số lần kết nối bị đổi khi bật và tắt.

## Lưu ý HyperOS
- Bật **Tự khởi động (Autostart)** cho app này.
- Tiết kiệm pin của app này: **Không hạn chế**.
- Shizuku không root sẽ tắt sau reboot cho tới khi bạn bật lại.
