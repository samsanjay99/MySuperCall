# MyCall Android

Native Android version of the MyCall WebRTC audio calling app.

**Full feature parity** with the web version:
- Same unique Call IDs (works across web + Android)
- Live online users list
- Incoming call Accept / Decline
- True full-duplex peer-to-peer WebRTC audio
- Real-time audio level meters ("YOU" and "THEM")
- Mute / Unmute
- Remote volume slider
- Call timer
- Works with the **exact same** Node.js + Socket.IO server (`server.js`)

No changes needed on the backend.

---

## Project Structure

```
android/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/mycall/app/
│       │   ├── MainActivity.kt
│       │   ├── CallViewModel.kt
│       │   ├── SignalingClient.kt
│       │   └── WebRTCClient.kt
│       └── res/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md   ← you are here
```

---

## How to Build & Run

### Recommended: Android Studio

1. Open **Android Studio** (Hedgehog or newer recommended).
2. Click **File → Open** and select the `android` folder inside your `MyCall` project.
3. Let Gradle sync (it will download WebRTC + Socket.IO + Compose).
4. Select a device:
   - **Physical Android phone** (best for real calls)
   - **Emulator** (use for testing with the web version)
5. Click **Run**.

### Using Command Line (optional)

```bash
cd android
./gradlew assembleDebug
```

APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

---

## Connecting to the Server (Important!)

### Recommended: Use the deployed server (easiest & best experience)

Your web app is already deployed at:

**`https://mycall-wji0.onrender.com`**

This is now the **default** in the Android app.

See [DEPLOYED_SERVER.md](DEPLOYED_SERVER.md) for dedicated instructions on using the live deployed server.

With this URL:
- Any Android phone can call any other Android phone
- Android can call the web version from anywhere (laptop, another phone, etc.)
- No need for same WiFi or LAN IPs

**Just open the Android app and start using it** — it should already point to the deployed server.

> **Note about free Render tier**: The server sleeps after ~15 minutes of inactivity. The first connection or call after sleeping can take 20–40 seconds (cold start). Subsequent calls are fast.

---

### Local development (only if you are modifying the server)

| Scenario                                | Server URL to use in app                          |
|-----------------------------------------|---------------------------------------------------|
| Android Emulator → your dev machine     | `http://10.0.2.2:3000`                            |
| Real phone on same WiFi as dev machine  | `http://YOUR_PC_LAN_IP:3000` (e.g. 192.168.1.34)  |
| Production / real devices               | `https://mycall-wji0.onrender.com` (recommended)  |

**How to find your LAN IP** (Windows):
- Open PowerShell and run `ipconfig`
- Look for "IPv4 Address" under your Wi-Fi or Ethernet adapter.

### Production

Use the same public URL you use for the web app (Render, Railway, etc.).

The Android app has a small "Server: ..." bar at the top — tap it to change the URL at any time.

---

## Testing Calls

### Best experience (recommended)

1. Deploy the server once to a public URL (Render free tier is fine).
2. Install the Android app on **two phones**, or one phone + web on laptop.
3. On Phone 1: Set Call ID → `alice`
4. On Phone 2 (or web): Set Call ID → `bob`
5. From `alice` enter `bob` and press CALL.
6. Accept on the other side.

You should hear each other with working level meters.

### Testing on one computer (emulator + web) — for development only

- Run the web app at `http://localhost:3000`
- In Android emulator, tap the top "Server:" bar and temporarily set it to `http://10.0.2.2:3000`
- Use **headphones** on the emulator side (otherwise you get heavy echo/feedback).
- Set two different Call IDs and call.

**For normal daily use and real testing between phones, just leave the server as the deployed URL (`https://mycall-wji0.onrender.com`).**

---

## Permissions

The app requests:
- `RECORD_AUDIO` (microphone)
- Internet + network state

Microphone permission is asked the first time you try to register or make a call.

---

## Architecture Notes

- Uses **Jetpack Compose** + Material 3 (modern, clean UI)
- **Socket.IO** client for signaling (identical events to web frontend)
- **org.webrtc:google-webrtc** for audio calling
- Local level meter uses a lightweight `AudioRecord` RMS thread
- Remote level meter polls WebRTC `getStats()` (inbound-rtp audioLevel)
- Same STUN servers as the web version

The signaling protocol is 100% compatible, so Android ↔ Web calls work out of the box.

---

## Known Limitations / Tips

- First connection after a cold start on free Render deploys can take 15–30s.
- On some devices you may want to toggle speakerphone (the app gives you a SPEAKER button).
- The app is portrait-only (phone call UX).
- Background calls / notifications are **not** implemented in this first version (the call must stay in foreground like the web tab).

---

## Next Possible Improvements (if you want)

- Persistent last-used Call ID + server URL (DataStore already partially ready)
- Call history
- Push notifications for incoming calls when app is in background (requires Firebase or custom signaling + WorkManager)
- Better speaker/earpiece switching + proximity sensor
- Video calling (easy to add since we already have WebRTC)

---

Enjoy making calls on Android!

The experience should feel almost identical to the beautiful web UI you already had.
