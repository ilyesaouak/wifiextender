# Wi-Fi Extender Android App

Turns an old Android phone into a Wi-Fi extender, no root needed.

**How it works:** the old phone stays connected to your router and creates its own
Wi-Fi Direct network (it looks like a normal password-protected Wi-Fi).
A built-in proxy forwards the iPhone's traffic to the router. HTTPS stays end-to-end
encrypted; the old phone only passes the bytes along.

Works on Android 6.0+. A fixed network name and password need Android 10+; older
phones pick their own, and the app shows them.

---

## 1. Build & install (Android Studio)

1. Open **Android Studio → File → Open** and select the `WifiExtender` folder.
   Wait for the Gradle sync to finish. If it suggests upgrading the Android Gradle
   Plugin, accept.
2. On the old phone, turn on **Developer options** (Settings → About phone → tap
   *Build number* 7 times), then turn on **USB debugging**.
3. Plug the phone into your laptop, select it at the top of Android Studio and press **▶ Run**.

*No cable?* Use **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, copy
`app/build/outputs/apk/debug/app-debug.apk` to the phone, and open it. Allow
"Install unknown apps" when asked.

## 2. Set up the old Android phone

1. Put it **halfway** between the router and your room, where it still has a good
   signal. Keep it **plugged in**.
2. Connect it to your home Wi-Fi.
3. Open **Wi-Fi Extender** → **Start extender** → allow the permissions.
   Choose "Allow" on the battery prompt so Android doesn't kill the app overnight.
4. The screen now shows the **network name**, the **password** and the **proxy address**.

## 3. Set up the iPhone (one time)

**Quick way (QR codes):**
1. Point the iPhone camera at **QR code 1** in the app → tap **Join**.
2. Point the camera at **QR code 2** → open the link → **Allow** → then
   **Settings → Profile Downloaded → Install**. This sets the proxy automatically.
   iOS will label the profile "Not Verified" because it's a self-made profile; that's expected.

**Manual way:**
1. **Settings → Wi-Fi** → join `DIRECT-WX-Extender` (or the name shown) with the password.
2. Tap **(i)** next to it → scroll down → **Configure Proxy → Manual**
   - Server: `192.168.49.1`
   - Port: `8282`
   - Authentication: off
   → **Save**.

   *Alternative:* choose **Automatic** and enter the URL `http://192.168.49.1:8282/proxy.pac`.
3. Open Safari to test. iOS may show "No Internet Connection" under the network name.
   Ignore it: browsing works through the proxy.

The iPhone remembers the network and proxy, so next time just press Start on the Android phone.

---

## Getting the maximum speed

**The hard limit:** the phone has one Wi-Fi radio. Everything is received from the router
and then sent again to the device on the same radio, so the iPhone gets at most about **half**
of the old phone's own speed. The app is built to get as close to that as possible:

| What the app does | Why it's faster |
|---|---|
| Puts the extender network on the **same channel as the router** (Android 10+) | Two different channels force the radio to keep switching, which can cut speed by more than half |
| Disables Wi-Fi power saving (high-performance + low-latency Wi-Fi locks) | Power saving adds delay and lowers throughput |
| **Turbo mode:** screen stays on, dimmed | Keeps Android's low-latency Wi-Fi mode active and stops Doze from throttling the phone |
| Fast proxy: 64 KB buffers, high-priority threads, TCP_NODELAY, kernel buffer auto-tuning | Forwarding runs as fast as with no proxy at all (tested at more than 1 GB/s), so the radio stays the only bottleneck |
| DNS cache + quick fallback between IPv4/IPv6 | Pages start loading sooner |

**What you can do (this matters most):**
1. **Placement:** use the live **Router link** line in the app. Aim for **−65 dBm or better**
   (e.g. −58 is better than −70). The signal to the router matters more than the signal to your room.
2. **Use the router's 5 GHz network** on the old phone if the signal at that spot is still
   −67 dBm or better. 5 GHz is usually 2–4× faster than 2.4 GHz.
3. **Find the bottleneck:** on the iPhone, open `http://192.168.49.1:8282` in Safari and run
   the built-in test (it measures the iPhone ↔ old phone link only). Then run fast.com.
   - Built-in test much faster than fast.com → move the old phone **closer to the router**.
   - Built-in test about the same or slower → move the **iPhone closer to the old phone**.
4. Try the **channel options** in the app (Same as router / 5 GHz / 2.4 GHz). Different phones'
   Wi-Fi chips behave differently, so compare with the live speed meter.
5. Keep **Turbo mode** on and the phone plugged in.

## What works / what doesn't

✅ Safari, Chrome, YouTube, Instagram, WhatsApp/Telegram messages, App Store, iCloud,
most apps (anything using normal web connections, which honour the iOS proxy setting).

⚠️ May not work: voice/video calls (FaceTime, WhatsApp calls), online games, and some
VPN apps. These use UDP or skip the system proxy. A proxy can't forward that without root.

Speed is usually about half of what the old phone itself gets, because it receives
and re-sends everything over the same radio.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Wi-Fi is busy" | Make sure Wi-Fi is on. On Android 12 or older, also turn **Location** on. Then retry. |
| "Doesn't support Wi-Fi Direct" | That phone's hardware can't do it. Use a plug-in repeater instead. |
| iPhone connects, but nothing loads | Check the proxy settings in step 3.2. Check that the Android screen doesn't show the red "not connected to your home Wi-Fi" warning. |
| Stops after a while | Settings → Apps → Wi-Fi Extender → Battery → **Unrestricted**. On Xiaomi/Huawei/Samsung also allow "Autostart" / disable "Put app to sleep". |
| Slow | Move the Android phone closer to the router. Signal to the router matters more than signal to your room. |

## Code overview

- `ExtenderService.kt`: foreground service. Creates the Wi-Fi Direct group, holds Wi-Fi/CPU locks, starts the proxy.
- `ProxyServer.kt`: HTTP + HTTPS (CONNECT) proxy. Resolves DNS and sends all traffic through the home Wi-Fi network. Only accepts clients from the Wi-Fi Direct subnet.
- `MainActivity.kt`: Start/Stop screen with network details and live stats.
