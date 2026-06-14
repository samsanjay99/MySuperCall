const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');

const app = express();
const server = http.createServer(app);

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

app.use(express.static(path.join(__dirname, 'public')));

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

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

io.on('connection', (socket) => {
  console.log(`Socket connected: ${socket.id}`);

  // Register user with a unique call ID
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