# GMS Keeper

Giữ thông báo không bị trễ trên HyperOS (ROM Trung Quốc, không root) bằng Shizuku:
đưa **Google Play Services** và các app bạn chọn (mặc định Zalo) vào whitelist Doze, cho chạy nền,
bật Tự khởi động của Xiaomi và đặt standby bucket `active`.

## Build không cần Android Studio
1. Tạo repo GitHub mới, push toàn bộ thư mục này lên nhánh `main`.
2. Tab **Actions** → workflow **Build & Release APK** tự chạy (hoặc bấm *Run workflow*).
3. Tải `gms-keeper-vX.Y.apk` ở tab **Releases** (hoặc Artifacts của run) rồi cài lên máy.

Mỗi lần push, release `v<versionName>` được tạo hoặc cập nhật. Muốn ra release mới thì tăng
`versionCode` và `versionName` trong `app/build.gradle.kts`.

## Dùng
1. Cài Shizuku (Google Play), bật Wireless debugging, khởi động Shizuku.
2. Mở app → **Cấp quyền Shizuku** → **Áp dụng**.
3. Thêm app cần bảo vệ bằng **＋ Thêm ứng dụng**. Chạm một app để xem chẩn đoán, giữ để bỏ.
4. Sau reboot: bật lại Shizuku, app tự áp dụng lại khi Shizuku lên.

## Kết nối FCM
Dòng **Kết nối FCM** cho biết GMS có đang giữ kết nối tới máy chủ Google không (qua `/proc/net/tcp`),
kèm mốc "thấy lần đầu" và số lần kết nối bị đổi giữa các lần bạn mở app, để biết kết nối có bị ngắt giữa chừng không.

## Log
**Hiện log** mở nhật ký chia theo giai đoạn, mỗi giai đoạn có nút **Sao chép** riêng.

## Lưu ý HyperOS
- Bật **Tự khởi động** cho app này, pin **Không hạn chế**.
- Shizuku không root sẽ tắt sau reboot cho tới khi bạn bật lại.
