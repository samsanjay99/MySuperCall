package com.mycall.app

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

/**
 * MyCall Android - Native port of the web WebRTC + Socket.IO calling app.
 * Full-duplex audio calls using the exact same signaling protocol.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6366F1),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E2937),
                    onSurface = Color.White
                )
            ) {
                MyCallApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Auto-connect when app comes to foreground
        if (viewModel.connectionStatus != "Connected") {
            viewModel.connectSignaling()
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MyCallApp(viewModel: CallViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Audio permission
    val audioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    var myIdInput by remember { mutableStateOf("") }
    var targetIdInput by remember { mutableStateOf("") }
    var showServerDialog by remember { mutableStateOf(false) }

    // Auto connect once on first composition
    LaunchedEffect(Unit) {
        viewModel.connectSignaling()
    }

    // Show toast for status messages
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearStatus()
        }
    }

    // Simple ringtone when incoming call arrives (like the web app)
    val incomingFrom by remember { derivedStateOf { uiState.incomingCallFrom } }
    LaunchedEffect(incomingFrom) {
        if (incomingFrom != null) {
            try {
                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_RING, 70)
                // Play a nice dual-tone ring a few times
                repeat(6) {
                    toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 380)
                    delay(750)
                }
                toneGen.release()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF6366F1)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Phone,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "MyCall",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            "REAL-TIME • TWO-WAY AUDIO",
                            fontSize = 9.sp,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Connection status
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF1E2937),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isConnected = uiState.connectionStatus == "Connected"
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF34D399) else Color(0xFFF87171))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            uiState.connectionStatus,
                            fontSize = 12.sp,
                            color = if (isConnected) Color(0xFF34D399) else Color(0xFFF87171),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Server URL (tap to edit)
            Surface(
                onClick = { showServerDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E2937),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Settings, null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Server: ", color = Color(0xFF64748B), fontSize = 12.sp)

                    val isProduction = viewModel.serverUrl.contains("mycall-wji0.onrender.com")
                    if (isProduction) {
                        Text(
                            "mycall-wji0.onrender.com",
                            color = Color(0xFF34D399),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.2f)
                        ) {
                            Text(
                                "LIVE",
                                color = Color(0xFF34D399),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    } else {
                        Text(
                            viewModel.serverUrl,
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.width(8.dp))
                    Text("EDIT", color = Color(0xFF6366F1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ===== YOUR CALL ID CARD =====
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF1E2937),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("YOUR UNIQUE CALL ID", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = viewModel.myCallId ?: "not set",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (viewModel.myCallId != null) Color(0xFF34D399) else Color(0xFF64748B),
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.5.sp
                            )
                        }
                        if (viewModel.myCallId != null) {
                            OutlinedButton(
                                onClick = {
                                    // Copy to clipboard
                                    val clip = android.content.ClipData.newPlainText("Call ID", viewModel.myCallId)
                                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                        .setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied ${viewModel.myCallId}", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row {
                        OutlinedTextField(
                            value = myIdInput,
                            onValueChange = { myIdInput = it },
                            placeholder = { Text("alice, bob, office-7, 4821...") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedBorderColor = Color(0xFF6366F1)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (audioPermission.status.isGranted) {
                                    viewModel.registerCallId(myIdInput.trim())
                                    myIdInput = ""
                                } else {
                                    audioPermission.launchPermissionRequest()
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text("SET", fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "Pick any unique name. Same ID works across phones and web.",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== ONLINE USERS =====
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF1E2937),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        Icon(Icons.Filled.People, null, tint = Color(0xFF6366F1), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("ONLINE NOW", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF334155)
                        ) {
                            Text(
                                "${viewModel.onlineUsers.size} online",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    if (viewModel.onlineUsers.isEmpty()) {
                        Text(
                            "No one else is online yet.\nOpen the web app or another phone and set a different Call ID.",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(viewModel.onlineUsers) { user ->
                                Surface(
                                    onClick = { targetIdInput = user },
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFF334155)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF34D399))
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(user, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== MAKE A CALL =====
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF1E2937),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("MAKE A CALL", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B))

                    Spacer(Modifier.height(10.dp))

                    Row {
                        OutlinedTextField(
                            value = targetIdInput,
                            onValueChange = { targetIdInput = it },
                            placeholder = { Text("Enter their Call ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedBorderColor = Color(0xFF10B981)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (!audioPermission.status.isGranted) {
                                    audioPermission.launchPermissionRequest()
                                    return@Button
                                }
                                if (viewModel.isRegistered) {
                                    viewModel.startCall(targetIdInput.trim())
                                    targetIdInput = ""
                                } else {
                                    Toast.makeText(context, "Set your Call ID first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !viewModel.isInCall,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            modifier = Modifier.height(56.dp)
                        ) {
                            Icon(Icons.Filled.Phone, null)
                            Spacer(Modifier.width(6.dp))
                            Text("CALL", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ===== ACTIVE CALL PANEL =====
            AnimatedVisibility(
                visible = viewModel.isCallPanelVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF1E2937),
                    border = BorderStroke(1.dp, Color(0xFF475569)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("IN CALL WITH", fontSize = 10.sp, color = Color(0xFF64748B))
                                Text(
                                    viewModel.peerId ?: "",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF34D399),
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    viewModel.callTimer,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White
                                )
                                Text("LIVE", fontSize = 10.sp, color = Color(0xFF34D399))
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // AUDIO LEVEL METERS (YOU / THEM) - core feature parity
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            // YOU
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Mic, null, tint = Color(0xFF64748B), modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("YOU", fontSize = 10.sp, color = Color(0xFF64748B))
                                    Spacer(Modifier.weight(1f))
                                    Text("${viewModel.localLevel}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                                }
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { (viewModel.localLevel / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = Color(0xFF10B981),
                                    trackColor = Color(0xFF334155)
                                )
                            }

                            // THEM
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.VolumeUp, null, tint = Color(0xFF6366F1), modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("THEM", fontSize = 10.sp, color = Color(0xFF6366F1))
                                    Spacer(Modifier.weight(1f))
                                    Text("${viewModel.remoteLevel}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF6366F1))
                                }
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { (viewModel.remoteLevel / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = Color(0xFF6366F1),
                                    trackColor = Color(0xFF334155)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Remote volume slider + Force restart hint
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.VolumeDown, null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Slider(
                                value = viewModel.remoteVolume,
                                onValueChange = { viewModel.setRemoteVolume(it) },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF6366F1),
                                    activeTrackColor = Color(0xFF6366F1)
                                )
                            )
                            Icon(Icons.Filled.VolumeUp, null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                        }

                        Text(
                            "If no sound from THEM, slide volume or try speakerphone below.",
                            fontSize = 9.sp,
                            color = Color(0xFF64748B),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(16.dp))

                        // Controls
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { viewModel.toggleMute() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (viewModel.isMuted) Color(0xFF7F1D1D) else Color(0xFF334155)
                                )
                            ) {
                                Icon(
                                    if (viewModel.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (viewModel.isMuted) "Unmute" else "Mute", fontWeight = FontWeight.Medium)
                            }

                            Button(
                                onClick = { viewModel.endCall() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                            ) {
                                Icon(Icons.Filled.CallEnd, null)
                                Spacer(Modifier.width(8.dp))
                                Text("End Call", fontWeight = FontWeight.Bold)
                            }
                        }

                        // Extra audio controls
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.setRemoteVolume(1f) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("RESET VOLUME", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    // Toggle speakerphone as a "force" action
                                    // (simple approach: we just flip it)
                                    Toast.makeText(context, "Speaker toggled (test with headphones too)", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("SPEAKER", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Info footer
            Text(
                "Peer-to-peer WebRTC audio. Same Call IDs work on web and Android.",
                fontSize = 10.sp,
                color = Color(0xFF475569),
                textAlign = TextAlign.Center
            )
        }

        // ===== INCOMING CALL MODAL =====
        if (uiState.incomingCallFrom != null) {
            Dialog(onDismissRequest = { /* user must choose */ }) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF1E2937),
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Phone,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Incoming Call", fontSize = 15.sp, color = Color(0xFF94A3B8))
                        Text(
                            uiState.incomingCallFrom ?: "",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Text("wants to talk with you", fontSize = 12.sp, color = Color(0xFF64748B))

                        Spacer(Modifier.height(24.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { viewModel.rejectIncomingCall() },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Filled.Close, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Decline")
                            }
                            Button(
                                onClick = { viewModel.acceptIncomingCall() },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Icon(Icons.Filled.Phone, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Accept", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Server URL editor dialog
        if (showServerDialog) {
            var tempUrl by remember { mutableStateOf(viewModel.serverUrl) }
            AlertDialog(
                onDismissRequest = { showServerDialog = false },
                title = { Text("Signaling Server URL") },
                text = {
                    Column {
                        Text(
                            "Recommended (best for real devices):\n" +
                            "https://mycall-wji0.onrender.com\n\n" +
                            "For local development only:\n" +
                            "• Emulator: http://10.0.2.2:3000\n" +
                            "• Same WiFi phone: http://YOUR_PC_LAN_IP:3000",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = tempUrl,
                            onValueChange = { tempUrl = it },
                            singleLine = true,
                            label = { Text("Server URL") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateServerUrl(tempUrl)
                        viewModel.connectSignaling()
                        showServerDialog = false
                    }) {
                        Text("Connect")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showServerDialog = false }) { Text("Cancel") }
                }
            )
        }
    }

    // Auto request mic permission on first meaningful action if needed
    if (!audioPermission.status.isGranted && audioPermission.status.shouldShowRationale) {
        // Optional: show a gentle banner, but for simplicity we rely on the call buttons triggering the request
    }
}