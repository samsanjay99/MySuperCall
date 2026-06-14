# Keep WebRTC classes
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Socket.IO
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# JSON
-keep class org.json.** { *; }

# Keep our signaling payloads
-keep class com.mycall.app.** { *; }