# GMS Keeper

Đưa Google Play Services (`com.google.android.gms`) và `com.google.android.gsf` vào whitelist Doze
qua Shizuku (không root) để FCM không bị trễ trên HyperOS.
Ngoài whitelist Doze, app còn đặt appops `RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND` = allow và standby bucket = `active` cho hai package này.

## Build không cần Android Studio
1. Tạo repo GitHub mới, push toàn bộ thư mục này lên nhánh `main`.
2. Tab **Actions** → workflow **Build APK** tự chạy (hoặc bấm *Run workflow*).
3. Xong thì vào run đó → **Artifacts** → tải `gms-doze-whitelist-apk` → giải nén ra `gms-keeper-debug.apk`.
4. Cài APK lên máy.

## Dùng
1. Cài Shizuku (Google Play), bật Wireless debugging, khởi động Shizuku.
2. Mở app → **Cấp quyền Shizuku** → **Áp dụng**.
3. Sau reboot: khởi động lại Shizuku, app tự áp dụng lại whitelist khi Shizuku lên.

## Ứng dụng bảo vệ thêm
Ngoài GMS/GSF, app áp dụng cùng bộ lệnh cho các app bạn chọn (mặc định có Zalo, `com.zing.zalo`).
Chạm vào một app để xem chẩn đoán: tiến trình, standby bucket, whitelist, appops, force-stop. Giữ để bỏ khỏi danh sách.

## Lưu ý HyperOS
- Bật **Tự khởi động (Autostart)** cho app này.
- Tiết kiệm pin của app này: **Không hạn chế**.
- Shizuku không root sẽ tắt sau reboot cho tới khi bạn bật lại.
