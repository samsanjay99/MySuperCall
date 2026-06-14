# MyCall Upgrade Plan: From Demo to Polished Consumer Calling Platform

**Status**: Draft for user review (created during planning phase). User will analyze and direct exact next steps / revisions.

**Date**: 2026-06
**Core Objective**: Incrementally evolve the existing MyCall (Render-hosted Node/Express + Socket.IO + WebRTC + single-file public/index.html) into a production-ready, trustworthy one-to-one audio calling product with real accounts, permanent public call IDs (e.g. SABC2345), discovery, favorites, history, realtime presence, and premium mobile-first UX — **without breaking the existing call functionality, Android compatibility contract, or Render deployment**.

See the full detailed plan content below (also saved internally). This is the authoritative reference.

---

## Executive Summary + Non-Negotiables

(See the internal plan.md for the complete version with architecture rationale, full schema, phased roadmap, code change outlines, risks, etc.)

**Must preserve**:
- All current WebRTC + signaling logic (createPeerConnection, offer/answer/ICE flow, currentCall state, audio robustness hacks, meters, cleanup, etc.).
- Socket event names and shapes for core calling (`register` / evolve carefully, `call-user`, `answer-call`, `offer`, `answer`, `ice-candidate`, `hangup`, `incoming-call`, `call-accepted`, etc.). Android SignalingClient.kt depends on them 1:1.
- The ability to test "two tabs/devices → set ID (now via login) → CALL" with real two-way audio.
- Simple Render deployment (static HTML + server.js, health check).
- No hardcoded secrets.

**Chosen high-level architecture** (follows query guidance):
- Supabase = Auth (email/password + sessions) + Postgres (profiles, contacts, call_history, etc.) + RLS.
- Render Node server = continues to own Socket.IO signaling + presence coordination + call ID generation + history writes + rate limiting. Uses service_role key for privileged ops.
- Frontend (still single polished index.html for now) = Supabase client (via CDN for no-build compatibility) for login + queries + profile management. All existing call UI/WebRTC code stays largely intact.
- Hybrid realtime: Socket.IO for low-latency call events + authoritative busy/online broadcasts; Supabase for persistent data and optional profile subscriptions.
- Permanent call IDs generated server-side only (readable format, collision retry on unique constraint).

**Key new user-visible things**:
- Real signup/login (persisted sessions).
- Automatic permanent public call ID on signup (format e.g. S + 7 readable alphanum).
- Searchable user directory + contact cards with status.
- Favorites (starred contacts).
- Call history with "call back" + missed indicators.
- Profile management (edit name, view/share your call ID + QR).
- Much richer call states, better incoming call experience, toasts, empty states, loading.
- Server-enforced presence (is_online, last_seen, busy during call).

---

## Database Schema (Run in Supabase SQL Editor)

Full schema, indexes, updated_at trigger, and RLS policies are defined in the detailed internal plan (and will be provided as `supabase/schema.sql` during implementation).

Core tables (as specified + minimal practical fields):
- `profiles` (id = auth.users.id, call_id unique permanent, display_name, username, avatar_url, status_message, is_online, last_seen, timestamps)
- `contacts` (owner_id, contact_id, is_favorite)
- `call_history` (caller/receiver ids + call_ids, direction, status, started/ended, duration)
- `call_events` (JSONB for extensibility)
- `user_settings`

RLS examples (viewable profiles for discovery, owner-only writes, participant-only history reads, etc.).

Call ID generation helper + collision retry lives in server code.

---

## High-Level Phased Roadmap (Incremental Only)

**Phase 0** — Prep (deps, envs, schema file, Supabase client init on server, extra CSS for new components, Supabase CDN script in HTML). Zero user-visible behavior change.

**Phase 1** — Auth + Permanent Identity
- Supabase email/password signup/login + logout.
- Post-signup: Server generates unique permanent call_id + creates profile row.
- New auth screen (forms) + switch to main app shell after login.
- Auto-identify the user over Socket (send Supabase token; server validates + associates socket with call_id + sets online).
- Legacy anonymous "register" kept temporarily for Android compat.
- "My Call ID" is now shown automatically (copy button prominent).
- myCallId in client state now comes from profile (not manual input).

**Phase 2** — Presence + Discovery
- Server keeps profiles.is_online + last_seen in sync on socket connect/disconnect.
- New "Discover" tab: search bar (by call_id / name / username) + rich contact cards (avatar with initials/gradient, status badge, last seen, quick CALL + favorite star).
- Real-time updates to cards via Socket presence events (or Supabase sub).
- Replace old simple online pills.

**Phase 3** — Favorites + Call History
- Contacts table + star UI.
- On call lifecycle end (hangup, cleanup, reject, timeout): record in call_history (status = missed/accepted/completed/etc., duration).
- History tab/panel with list + "Call back" (re-uses existing startCall flow).
- Missed call visual treatment.

**Phase 4** — Call Polish & States (minimal changes to core)
- Richer call status in active panel (dialing/ringing/connecting/connected/reconnecting + quality hints via simple getStats).
- Enhanced incoming modal + animations.
- Speaker + other controls if easy.
- Spam protection (server rate limits on call-user).
- All existing audio/WebRTC robustness untouched.

**Phase 5** — Profile/Settings/Onboarding/Trust
- Profile drawer: edit name/status, big call ID display, QR, copy/share.
- Basic settings.
- First-login onboarding highlight for the permanent ID.
- Toasts, skeletons, validation, clear errors everywhere.
- Server + client input validation on all sensitive paths.
- Socket payload whitelisting.

**Phase 6** — Docs, Safety, Deploy Polish, Android Note
- Full RLS + server authority.
- Update README (Supabase setup, env vars on Render, new flows, migration from old anonymous mode).
- Update render.yaml env declarations.
- Test cold starts, reconnects, multi-device, history accuracy.
- Note that Android will eventually need Supabase login + token on connect to enjoy new features (legacy mode can stay for the old server contract for a while).

**Implementation style**: Small search_replace steps on server.js and (mostly) the JS sections inside public/index.html. New files only for schema, env example, perhaps a small supabase/ folder. Never touch the WebRTC core functions unless absolutely required for state hooks (history/status). Keep the file self-contained.

---

## Environment Variables (Required)

Add these in Render dashboard (and .env locally):

- SUPABASE_URL
- SUPABASE_ANON_KEY
- SUPABASE_SERVICE_ROLE_KEY   (server-only)
- (Existing PORT / NODE_ENV continue to work)

render.yaml will be updated with placeholders + comments.

---

## Risks & How We Mitigate

- Breaking cross-platform calls or Android → Strict preservation of event names/payloads for the actual call media setup. Legacy register path supported during transition.
- Auth/Cold start complexity → Patient waits + clear "waking up / connecting" UI already exist and will be extended. Health endpoint stays.
- Single-file HTML becoming unwieldy → Heavy use of section comments and small pure functions (Avatar, ContactCard, showToast, etc.). Full component extraction can be a later phase.
- RLS / security mistakes → Server always has final say on signaling/presence/history. RLS policies provided and tested.
- CDN Supabase client reliability → Guards + fallback messaging. Size is acceptable for this stage.

---

## How to Proceed (User Directive Expected)

The full detailed version of this plan (architecture deep-dives, exact function names to touch, sample code sketches for generateCallId, identify handler, UI view switching, etc.) lives in the internal planning artifact.

**User instructions per the query response**:
- I (AI) created the plan file.
- User will look at it, analyze, and tell exactly what to do next (e.g. "start with Phase 0 and 1, only touch these files, revise X section of the plan first").

Please review `UPGRADE_PLAN.md` (this file) + the more exhaustive internal version if referenced. Then reply with:
- Approval / requested revisions to the plan.
- Which phase or specific first increment to execute (e.g. "implement auth skeleton + Supabase schema setup first").
- Any preferences on call ID exact format, UI layout details, whether to extract any JS early, etc.

Once directed, we will proceed with **careful, incremental, testable edits** using the plan as the north star, always verifying that two-party audio calls continue to function after each change.

This will result in a real, shippable, enjoyable calling product on the existing Render URL.

Ready when you are.