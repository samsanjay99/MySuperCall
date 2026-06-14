const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');
const fs = require('fs');

// Load .env for local development (Render injects env vars directly)
require('dotenv').config();

// Supabase (Phase 0+1): service_role client ONLY on server for call_id generation + privileged writes.
// Client (browser) uses only the anon key + Supabase Auth (RLS enforced).
// SECURITY: Never hardcode keys. If any credential was exposed, rotate immediately in Supabase dashboard.
const { createClient } = require('@supabase/supabase-js');

const app = express();
const server = http.createServer(app);

// === Supabase service client (server-only, service_role key) ===
let supabase = null;
if (process.env.SUPABASE_URL && process.env.SUPABASE_SERVICE_ROLE_KEY) {
  supabase = createClient(
    process.env.SUPABASE_URL,
    process.env.SUPABASE_SERVICE_ROLE_KEY,
    { auth: { autoRefreshToken: false, persistSession: false } }
  );
  console.log('[Supabase] Service client initialized (server only)');
} else {
  console.warn('[Supabase] SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY missing — onboard/call_id generation will be disabled until set.');
}

// One-time schema check / migration helper (Phase 0/1)
// If the profiles table doesn't exist, we log clear instructions so the user can apply supabase/schema.sql
// (Supabase service role can run most DDL, but easiest and recommended is the SQL Editor in the dashboard).
async function ensureDatabaseMigrated() {
  if (!supabase) return;

  try {
    const { error } = await supabase
      .from('profiles')
      .select('id')
      .limit(1);

    if (error && (error.code === '42P01' || /relation .* does not exist/i.test(error.message || ''))) {
      const projectRef = (process.env.SUPABASE_URL || '').match(/https:\/\/([^\.]+)\.supabase\.co/)?.[1] || 'YOUR-PROJECT-REF';
      console.log(`
╔════════════════════════════════════════════════════════════════════════════╗
║  DATABASE MIGRATION REQUIRED (Phase 0/1)                                    ║
║                                                                            ║
║  The 'profiles' table does not exist yet.                                  ║
║                                                                            ║
║  1. Open this link (your project):                                         ║
║     https://supabase.com/dashboard/project/${projectRef}/sql/new          ║
║  2. Copy the ENTIRE content of the file supabase/schema.sql                ║
║  3. Paste it into the editor and click "Run".                              ║
║                                                                            ║
║  This sets up the profiles table + call_id (S+7/8 format) + basic RLS.     ║
║                                                                            ║
║  After it succeeds, refresh or restart the server.                         ║
╚════════════════════════════════════════════════════════════════════════════╝
`);
    } else if (!error) {
      console.log('[Supabase] Database schema check passed (profiles table exists).');
    }
  } catch (e) {
    console.warn('[Supabase] Could not check schema on startup:', e.message);
  }
}

// Run the check on startup (non-blocking)
ensureDatabaseMigrated();

// Trust proxy (important for Render, Railway, and other platforms that terminate TLS)
app.set('trust proxy', 1);

const io = new Server(server, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"]
  },
  // Connection tuning for real-world networks and proxies
  pingTimeout: 60000,
  pingInterval: 25000
});

const PORT = process.env.PORT || 3000;

// Map of callId -> socket.id
const users = new Map(); // callId -> { socketId, socket }
const socketToCallId = new Map(); // socket.id -> callId

// IMPORTANT: This custom root handler MUST be registered BEFORE express.static
// so that requests to "/" are handled here (to inject the Supabase public config from .env).
// Static middleware would otherwise serve the raw public/index.html with placeholders.
app.get('/', (req, res) => {
  const indexPath = path.join(__dirname, 'public', 'index.html');
  let html = fs.readFileSync(indexPath, 'utf8');

  // Inject only the public values (anon key is designed to be public; service key stays server-only)
  const publicUrl = process.env.SUPABASE_URL || '';
  const publicAnon = process.env.SUPABASE_ANON_KEY || '';

  if (!publicUrl || !publicAnon) {
    console.warn('[MyCall] Warning: SUPABASE_URL or SUPABASE_ANON_KEY missing in env. Client will see placeholder config and show error.');
  }

  // Robust replacement for the injected config script (preferred method)
  html = html.replace(/__SUPABASE_URL__/g, publicUrl);
  html = html.replace(/__SUPABASE_ANON_KEY__/g, publicAnon);

  // Also replace any remaining old-style placeholders in the JS (defense in depth)
  html = html.replace(/https:\/\/YOUR-PROJECT\.supabase\.co/g, publicUrl);
  html = html.replace(/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.\.\./g, publicAnon);
  html = html.replace(/YOUR_ANON_KEY_HERE/g, publicAnon);

  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.send(html);
});

app.use(express.static(path.join(__dirname, 'public')));

// Note: The root handler (app.get('/')) is defined EARLIER in the file (before static)
// so that it can inject the Supabase config from .env before any static serving happens.

// API to get online users (optional for frontend)
app.get('/api/online', (req, res) => {
  const online = Array.from(users.keys());
  res.json({ online });
});

// Simple health check for Render / monitoring
app.get('/health', (req, res) => {
  res.json({ 
    status: 'ok', 
    timestamp: Date.now(),
    usersOnline: users.size 
  });
});

// === Phase 1: Onboard (create profile + permanent call_id) ===
// Frontend (after Supabase signUp/signIn) calls this with the access_token.
// Server validates using Supabase (anon/service), generates unique call_id (S+7/8 random alphanum),
// inserts profile using service_role (bypasses RLS for the generation step).
// Returns the profile (especially call_id) so the client can feed it into the existing 'register' flow.
app.post('/api/auth/onboard', express.json(), async (req, res) => {
  if (!supabase) {
    return res.status(500).json({ error: 'Supabase not configured on server' });
  }

  const { access_token } = req.body || {};
  if (!access_token || typeof access_token !== 'string') {
    return res.status(400).json({ error: 'Missing access_token. This usually happens if you tried to complete signup before email confirmation (or before the client received a session). Please confirm your email and then use the Log in form, or disable email confirmation in your Supabase project settings for development.' });
  }

  try {
    // Validate the token (get the authenticated user)
    const { data: { user }, error: userError } = await supabase.auth.getUser(access_token);
    if (userError || !user) {
      return res.status(401).json({ error: 'Invalid or expired token' });
    }

    // Check if profile already exists (idempotent for re-login)
    const { data: existing } = await supabase
      .from('profiles')
      .select('call_id, display_name')
      .eq('id', user.id)
      .single();

    if (existing && existing.call_id) {
      return res.json({ profile: { id: user.id, call_id: existing.call_id, display_name: existing.display_name } });
    }

    // Generate permanent public call ID (S + 7 random alphanum, server-side only, no user data in ID)
    const callId = await generateUniqueCallId(supabase, user.id, req.body.display_name || user.email?.split('@')[0] || 'User');

    // Fetch the freshly created profile
    const { data: profile } = await supabase
      .from('profiles')
      .select('id, call_id, display_name, username, status_message')
      .eq('id', user.id)
      .single();

    console.log(`[Onboard] User ${user.id} assigned permanent call_id ${callId}`);

    return res.json({ profile });
  } catch (err) {
    console.error('[Onboard] error', err);
    return res.status(500).json({ error: 'Failed to create profile' });
  }
});

// Temporary legacy bridge note:
// The 'register' Socket handler below continues to accept a plain string callId exactly as before.
// This allows Android clients and pre-auth sockets to keep working unchanged.
// Authenticated web clients will call the above onboard first, then still emit the existing 'register' with the permanent call_id string.

io.on('connection', (socket) => {
  console.log(`Socket connected: ${socket.id}`);

  // Register user with a (public) call ID.
  // === TEMPORARY LEGACY BRIDGE (Phase 1 only) ===
  // This handler must continue to accept a plain string exactly as it always has.
  // Authenticated clients (after /api/auth/onboard) will still call this with their permanent call_id.
  // Android and any older sockets rely on the bare-string shape + all downstream callId usage in Maps/payloads.
  // Do not change the signature, the ack shape, or the in-memory behavior for plain strings.
  socket.on('register', (callId, callback) => {
    if (!callId || typeof callId !== 'string') {
      return callback && callback({ success: false, error: 'Invalid Call ID' });
    }

    const trimmedId = callId.trim();
    if (trimmedId.length < 2 || trimmedId.length > 32) {
      return callback && callback({ success: false, error: 'Call ID must be 2-32 characters' });
    }

    // Check if ID already taken
    if (users.has(trimmedId)) {
      return callback && callback({ success: false, error: 'Call ID already in use. Choose another.' });
    }

    // If this socket had a previous ID, remove it
    const prevId = socketToCallId.get(socket.id);
    if (prevId) {
      users.delete(prevId);
    }

    // Register new
    users.set(trimmedId, { socketId: socket.id, socket });
    socketToCallId.set(socket.id, trimmedId);

    // Join a personal room for easy targeting
    socket.join(`user:${trimmedId}`);

    console.log(`Registered: ${trimmedId} (socket: ${socket.id})`);

    // Notify everyone of updated user list
    broadcastOnlineUsers();

    callback && callback({ success: true, callId: trimmedId });
  });

  // Get current online users (for the caller)
  socket.on('get-online-users', (callback) => {
    const currentCallId = socketToCallId.get(socket.id);
    const online = Array.from(users.keys()).filter(id => id !== currentCallId);
    callback({ users: online });
  });

  // Initiate a call
  socket.on('call-user', ({ targetId, fromId }) => {
    const callerId = socketToCallId.get(socket.id) || fromId;
    if (!callerId) {
      socket.emit('call-error', { message: 'You are not registered.' });
      return;
    }

    if (!targetId || targetId === callerId) {
      socket.emit('call-error', { message: 'Invalid target Call ID.' });
      return;
    }

    const targetUser = users.get(targetId);
    if (!targetUser) {
      socket.emit('call-error', { message: `User "${targetId}" is not online.` });
      return;
    }

    // Notify target of incoming call
    io.to(targetUser.socketId).emit('incoming-call', {
      fromId: callerId,
      fromSocketId: socket.id
    });

    // Tell caller we are ringing
    socket.emit('call-initiated', { targetId });
    console.log(`Call initiated: ${callerId} -> ${targetId}`);
  });

  // Callee answers or rejects
  socket.on('answer-call', ({ targetId, accept, fromId }) => {
    const myId = socketToCallId.get(socket.id);
    const targetUser = users.get(targetId);

    if (!targetUser) {
      socket.emit('call-error', { message: 'Caller disconnected.' });
      return;
    }

    if (accept) {
      // Both sides get ready for WebRTC
      io.to(targetUser.socketId).emit('call-accepted', {
        fromId: myId,
        targetId
      });
      socket.emit('call-accepted', {
        fromId: targetId,
        targetId: myId
      });
      console.log(`Call accepted: ${myId} <-> ${targetId}`);
    } else {
      io.to(targetUser.socketId).emit('call-rejected', {
        fromId: myId
      });
      socket.emit('call-rejected', { fromId: targetId });
      console.log(`Call rejected: ${myId} rejected ${targetId}`);
    }
  });

  // WebRTC signaling - Offer
  socket.on('offer', ({ targetId, offer, fromId }) => {
    const targetUser = users.get(targetId);
    if (targetUser) {
      io.to(targetUser.socketId).emit('offer', {
        fromId: socketToCallId.get(socket.id) || fromId,
        offer
      });
    }
  });

  // WebRTC signaling - Answer
  socket.on('answer', ({ targetId, answer, fromId }) => {
    const targetUser = users.get(targetId);
    if (targetUser) {
      io.to(targetUser.socketId).emit('answer', {
        fromId: socketToCallId.get(socket.id) || fromId,
        answer
      });
    }
  });

  // WebRTC signaling - ICE Candidate
  socket.on('ice-candidate', ({ targetId, candidate, fromId }) => {
    const targetUser = users.get(targetId);
    if (targetUser && candidate) {
      io.to(targetUser.socketId).emit('ice-candidate', {
        fromId: socketToCallId.get(socket.id) || fromId,
        candidate
      });
    }
  });

  // Hang up / end call
  socket.on('hangup', ({ targetId }) => {
    const myId = socketToCallId.get(socket.id);
    const targetUser = users.get(targetId);

    if (targetUser) {
      io.to(targetUser.socketId).emit('hangup', { fromId: myId });
    }
    socket.emit('hangup', { fromId: targetId || 'local' });

    console.log(`Call ended: ${myId} <-> ${targetId}`);
  });

  // Handle disconnect
  socket.on('disconnect', () => {
    const callId = socketToCallId.get(socket.id);
    if (callId) {
      users.delete(callId);
      socketToCallId.delete(socket.id);
      console.log(`User disconnected: ${callId}`);

      // Notify others that this user is gone (in case of active call)
      io.emit('user-left', { callId });

      broadcastOnlineUsers();
    }
  });
});

function broadcastOnlineUsers() {
  const online = Array.from(users.keys());
  io.emit('online-users', { users: online });
}

// === Phase 1 helper: generate permanent public call ID ===
// Format: "S" + 7 (or 8) random characters from readable alphabet.
// Pure random — never derives any part from name/email.
// Server-side only (service_role), with collision retry on unique constraint.
async function generateUniqueCallId(supabaseClient, userId, displayNameFallback) {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // readable, excludes I O 0 1
  const length = 7; // results in S + 7 = 8 char ID (or change to 8 for S+8)

  for (let attempt = 0; attempt < 5; attempt++) {
    let callId = 'S';
    for (let i = 0; i < length; i++) {
      callId += alphabet[Math.floor(Math.random() * alphabet.length)];
    }

    // Insert profile with the generated call_id.
    // We use a minimal payload here; the client can update display_name etc later if needed.
    // If the row already partially exists (rare race), we still set the call_id.
    const { data, error } = await supabaseClient
      .from('profiles')
      .upsert({
        id: userId,
        call_id: callId,
        display_name: displayNameFallback || 'User'
      }, { onConflict: 'id' })
      .select('call_id')
      .single();

    if (!error && data && data.call_id) {
      return data.call_id;
    }

    const msg = (error?.message || '').toLowerCase();
    if (error && !msg.includes('duplicate') && error.code !== '23505') {
      // Real error (not collision) — surface it
      throw error;
    }
    // Collision on call_id unique — retry with fresh random
  }

  throw new Error('Failed to generate unique call ID after multiple retries');
}

server.listen(PORT, () => {
  console.log(`
╔════════════════════════════════════════════════════════════╗
║                                                            ║
║   📞  MyCall - WebRTC Two-Way Calling App                  ║
║                                                            ║
║   Server running at:  http://localhost:${PORT}               ║
║                                                            ║
║   Open http://localhost:${PORT} in multiple browser tabs    ║
║   to test calls between different users.                   ║
║                                                            ║
║   How to use:                                              ║
║   1. Enter a unique Call ID (e.g. "alice" or "bob")       ║
║   2. Click "Set Call ID"                                   ║
║   3. On another tab, use a different ID                    ║
║   4. Enter the other person's ID and press CALL            ║
║                                                            ║
╚════════════════════════════════════════════════════════════╝
  `);
});