# MyCall - Fully Working Two-Way Web Calling App

Real-time peer-to-peer audio calling using **unique Call IDs** + **WebRTC**.

Both parties can speak at the same time (full duplex), exactly like a normal phone call.

## Features
- Unique Call IDs (pick any name like `alice`, `bob`, `office-7`)
- Live "who is online" list
- Incoming call with Accept / Decline
- True simultaneous two-way audio (WebRTC)
- Mute / Unmute
- Real-time audio level meters (your mic + their voice)
- Call duration timer
- Remote volume control
- "Force Play Audio" button (handles browser autoplay rules)
- Works across tabs, devices, and networks (when deployed)

## Local Development & Testing

1. Install dependencies:
   ```bash
   npm install
   ```

2. Start the server:
   ```bash
   npm start
   ```

3. Open **http://localhost:3000** in your browser.

4. **To test a call properly** (very important):
   - Open **two different tabs** (or better: two different browsers / incognito + normal, or two devices).
   - In Tab/Device 1: Enter a Call ID (e.g. `alice`) → click **SET**.
   - In Tab/Device 2: Enter a different Call ID (e.g. `bob`) → click **SET**.
   - From `alice`, type `bob` and press **CALL**.
   - Accept on `bob`'s side.

5. **Critical for hearing sound when testing on the same computer**:
   - Use **headphones/earbuds** on at least one of the "users".
   - Otherwise the microphone on the same machine will pick up the speakers and the OS/browser echo cancellation can make it sound like no one is speaking.
   - Best experience: Test with two phones, or one laptop + one phone, or two separate browser profiles.

The two real level meters (YOU / THEM) will show actual audio energy — you will see the bars move when someone speaks.

## Why you don't need a database (Neon / Supabase / etc.)

**You do NOT need any database.**

- The app uses in-memory maps on the Node server to track who is currently online.
- A user only exists while their browser tab is connected via WebSocket.
- When they close the tab or lose connection, they are automatically removed.
- This is the correct and simplest architecture for a real-time calling app.

Only add a database later if you want:
- User accounts + passwords
- Permanent contact lists
- Call history / recordings

## Deploy for Easy Cross-Network Testing (Recommended)

The easiest free way to test between phones/laptops on different networks is to deploy the server publicly (HTTPS + public URL).

### Recommended: Render.com (Free tier)

1. Push this folder to a **new GitHub repository**:
   ```bash
   git init
   git add .
   git commit -m "Initial MyCall"
   git remote add origin https://github.com/YOUR_USERNAME/mycall.git
   git branch -M main
   git push -u origin main
   ```

2. Go to [https://render.com](https://render.com) → Sign up with GitHub.

3. Click **"New +"** → **Web Service**.

4. Connect your GitHub repo.

5. Configure:
   - **Environment**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
   - **Plan**: Free (it will sleep after ~15 min inactivity — fine for testing)

6. Click **Deploy**.

7. Once deployed, Render will give you a public URL like:
   `https://mycall-abc123.onrender.com`

8. Open that URL on **two different devices** (or one device + laptop) and test calling.

### Other easy platforms
- Railway.app
- Fly.io
- Koyeb

**Note about free tiers**: They usually sleep after inactivity. The first visitor after sleeping will experience a ~20-30s cold start. For serious use you would pay for always-on.

## How the Calling Works (Technical)

- Socket.IO handles signaling (who is online, call requests, offer/answer, ICE candidates).
- Once a call is accepted, a direct **WebRTC** peer connection is established between the two browsers.
- Audio streams flow peer-to-peer (the Node server is not in the media path).
- STUN servers help with NAT traversal.

## Files
- `server.js` — Express + Socket.IO signaling server
- `public/index.html` — Complete frontend + WebRTC logic (self-contained)
- No external services or paid APIs required

Enjoy making calls! If sound still doesn't work after these fixes, open the browser DevTools Console (F12) on both sides during a call and send me any errors you see.

---

## Android App

A fully native Android version of MyCall is included in the `android/` folder.

**Features**:
- Identical functionality to the web app (unique Call IDs, online list, incoming calls, full-duplex WebRTC audio, level meters, mute, timer, volume, etc.)
- **Cross-platform calling works**: Android ↔ Web, Android ↔ Android
- Uses the **exact same** `server.js` (no backend changes)

**Quick start**:
1. Open the `android/` folder in Android Studio.
2. Sync Gradle.
3. Run on a phone or emulator.
4. The app now defaults to your deployed server: **https://mycall-wji0.onrender.com**
   - This is the easiest and recommended option for real cross-device testing (Android ↔ Android or Android ↔ Web).
   - Tap the top "Server:" bar only if you want to switch to a local server during development.

See [android/README.md](android/README.md) and [android/DEPLOYED_SERVER.md](android/DEPLOYED_SERVER.md) for testing instructions.

**Free tier note**: Your Render server sleeps after inactivity. First connection after sleeping can take 20-40s (normal cold start).

The Android client is written in Kotlin + Jetpack Compose + WebRTC + Socket.IO client and mirrors the web signaling protocol perfectly.