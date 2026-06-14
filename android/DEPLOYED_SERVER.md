# Using the Deployed Server (https://mycall-wji0.onrender.com)

Your Android app is now configured to use your live deployed server by default.

## Why this is great

- Works from anywhere (no same WiFi required)
- Android phones can call each other directly
- Android can call the web version on any browser
- Same Call IDs work across web + Android

## Default in the app

The Android app now starts with:

**Server: https://mycall-wji0.onrender.com**

You don't need to change anything to start testing between real devices.

## How to test right now

1. Open the Android app (it should auto-connect to the deployed server).
2. Set a unique Call ID (e.g. `alice-42`).
3. On another phone (or on the web at https://mycall-wji0.onrender.com):
   - Set a different Call ID (e.g. `bob-99`).
4. From one side, enter the other person's Call ID and tap CALL.
5. Accept on the receiving side.

You should get two-way audio + working level meters.

## Important: Free tier cold starts

Because you are on Render's free plan:

- The server goes to sleep after ~15 minutes of no activity.
- The **first** person who opens the app or makes a call after sleeping will experience a 20–40 second delay while it wakes up.
- After the server is awake, calls are fast and normal.

This is normal. For more serious use later you can upgrade to a paid plan on Render for always-on.

## Switching back to local dev

If you are developing and running `npm start` locally:

- In the Android app, tap the bar at the very top that shows the current server URL.
- Change it temporarily to:
  - Emulator: `http://10.0.2.2:3000`
  - Physical phone on same WiFi: `http://192.168.x.x:3000` (your computer's LAN IP)

When you're done testing locally, just switch it back to `https://mycall-wji0.onrender.com`.

## Already deployed web + Android = best experience

You now have a complete cross-platform calling system:
- Web frontend (any browser)
- Native Android app
- One shared signaling server

Both clients are fully compatible with each other.
