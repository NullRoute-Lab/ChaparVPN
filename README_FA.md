# کلاینت اندروید GooseRelayVPN

این مخزن، کلاینت اندروید GooseRelayVPN است که هسته GooseRelay را از طریق Go mobile اجرا می‌کند و رابط کاربری کامل برای مدیریت VPN، پروفایل‌ها، لاگ‌ها و تنظیمات ارائه می‌دهد.

- پروژه اصلی (هسته): https://github.com/nullroute-lab/GooseRelayVPN
- این مخزن: پیاده‌سازی کلاینت اندروید

## این اپ چه کاری انجام می‌دهد؟

این اپ یک SOCKS5 محلی روی اندروید ایجاد می‌کند و ترافیک TCP را از معماری GooseRelay عبور می‌دهد:

1. ترافیک برنامه/مرورگر -> SOCKS5
2. فریم‌بندی رمزنگاری‌شده GooseRelay (با کلید AES)
3. عبور HTTPS از مسیر endpointهای گوگل (Apps Script)
4. سرور VPS شما خروجی واقعی را برقرار می‌کند

این مسیر با `VpnService` اندروید یکپارچه شده تا ترافیک کامل یا انتخابی از تونل عبور کند.

## امکانات اصلی

- یکپارچه‌سازی VPN اندروید (`VpnService` + tun2socks)
- پیکربندی مبتنی بر پروفایل
- وضعیت و تله‌متری در صفحه Home
- تب Logs برای عیب‌یابی Android/Core
- Split Tunneling و Internet Sharing

## مدل پیکربندی پروفایل

فیلدهای پروفایل با مدل کلاینت GooseRelay هماهنگ است:

```json
{
  "debug_timing": false,
  "socks_host": "127.0.0.1",
  "socks_port": 1080,
  "google_host": "216.239.38.120",
  "sni": ["www.google.com", "mail.google.com", "accounts.google.com"],
  "script_keys": [
    "REPLACE_WITH_DEPLOYMENT_ID",
    "OPTIONAL_SECOND_DEPLOYMENT_ID"
  ],
  "tunnel_key": "REPLACE_WITH_OUTPUT_OF_scripts_gen-key.sh"
}
```

نکات:
- در UI، `script_keys` باید خط‌به‌خط وارد شود.
- `tunnel_key` باید با سمت سرور یکی باشد.
- فرمت Import/Export در اپ: JSON

## جریان راه‌اندازی اصلی (الزامی)

قبل از استفاده از کلاینت اندروید، زیرساخت پروژه اصلی باید آماده باشد:

1. آماده‌سازی VPS و اجرای `goose-server`
2. Deploy کردن `apps_script/Code.gs` و گرفتن Deployment ID
3. ساخت کلید با `scripts/gen-key.sh`
4. وارد کردن `script_keys` و `tunnel_key` در پروفایل اندروید
5. اتصال در اپ و تنظیم پراکسی برای برنامه/مرورگر (در صورت نیاز)

راهنمای کامل زیرساخت در پروژه اصلی:
- https://github.com/nullroute-lab/GooseRelayVPN

## ساخت محلی اندروید

پیش‌نیازها:
- Android Studio
- JDK 17
- Go 1.22+
- Android SDK / NDK

ساخت AAR (پل Go mobile):

```bash
bash android/build_go_mobile.sh
```

ساخت APK دیباگ:

```bash
cd android
./gradlew :app:assembleDebug
```

## انتشار / CI

Workflowهای این مخزن:
- `.github/workflows/android-ci.yml`
- `.github/workflows/release-manual.yml`
- `.github/workflows/release.yml`

Secretهای لازم برای انتشار دستی امضاشده:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## عیب‌یابی

- اگر بعد از قطع اتصال، اتصال مجدد با خطای اشغال بودن پورت SOCKS مواجه شد:
  - چند ثانیه صبر کنید و دوباره وصل شوید.
  - مطمئن شوید اپ دیگری از همان پورت استفاده نمی‌کند.
- اگر اتصال روی حالت آماده‌سازی ماند:
  - `script_keys` و `tunnel_key` را بررسی کنید.
  - لاگ‌ها را در تب Logs ببینید.
- اگر ترافیک عبور نمی‌کند:
  - مجوز VPN اندروید را بررسی کنید.
  - تنظیمات Split Tunnel را بررسی کنید.

## Credits

1. پروژه اصلی:
   - https://github.com/nullroute-lab/GooseRelayVPN

2. پروژه‌ای که ایده پروژه اصلی از آن الهام گرفته شده است:
   - https://github.com/masterking32/MasterHttpRelayVPN
