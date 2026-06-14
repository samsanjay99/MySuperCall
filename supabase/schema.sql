-- MyCall Phase 0/1 minimal schema (profiles + permanent call_id only)
-- Run this in the Supabase SQL Editor after creating your project.
-- SECURITY: If any credential was exposed, rotate the database password + service role key IMMEDIATELY in the Supabase dashboard.

create extension if not exists "pgcrypto";

-- profiles (1:1 with auth.users)
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  call_id text unique not null,                    -- permanent public ID e.g. "SABC2345" (S + 7/8 random alphanum)
  display_name text not null,
  username text unique,
  avatar_url text,
  status_message text,
  is_online boolean default false,
  last_seen timestamptz default now(),
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

-- Index for fast lookup by public call ID (used for search/targeting)
create index profiles_call_id_idx on public.profiles(call_id);

-- updated_at trigger helper
create or replace function public.handle_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger profiles_updated_at before update on public.profiles
  for each row execute procedure public.handle_updated_at();

-- RLS (client uses anon key + user session; server service_role bypasses RLS for generation)
alter table public.profiles enable row level security;

-- Authenticated users can read public profiles (for future discovery)
create policy "Public profiles viewable by authenticated users"
  on public.profiles for select
  to authenticated using (true);

-- Users can insert their own profile row (during onboarding)
create policy "Users can insert their own profile"
  on public.profiles for insert
  to authenticated with check (auth.uid() = id);

-- Users can update only their own profile
create policy "Users can update own profile"
  on public.profiles for update
  to authenticated using (auth.uid() = id);

-- NOTE for server (service_role only):
-- Use SUPABASE_SERVICE_ROLE_KEY on the Render server to generate call_id with uniqueness + insert.
-- Never expose service_role to browser. Client always uses SUPABASE_ANON_KEY + RLS.

-- (Later phases will add contacts, call_history, user_settings tables + more policies)

-- After running: Go to Authentication → Providers and enable Email if not already.
-- Then set the three env vars on Render (SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_SERVICE_ROLE_KEY).