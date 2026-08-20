-- Fala Agenda — schema de ativação e cota de IA.
-- O cliente Android NÃO acessa estas tabelas; só as Edge Functions (service role).

create extension if not exists pgcrypto;

create table if not exists public.activation_codes (
  id uuid primary key default gen_random_uuid(),
  code_hash text not null unique,
  created_at timestamptz not null default now(),
  used_at timestamptz,
  used_by_installation_id uuid
);

create table if not exists public.installations (
  id uuid primary key default gen_random_uuid(),
  token_hash text not null unique,
  activated_at timestamptz not null default now(),
  last_seen_at timestamptz
);

create table if not exists public.ai_usage (
  id uuid primary key default gen_random_uuid(),
  installation_id uuid not null references public.installations(id) on delete cascade,
  used_on date not null,
  use_count integer not null default 0,
  unique (installation_id, used_on)
);

alter table public.activation_codes
  add constraint activation_codes_used_fk
  foreign key (used_by_installation_id) references public.installations(id);

alter table public.activation_codes enable row level security;
alter table public.installations enable row level security;
alter table public.ai_usage enable row level security;

-- Sem políticas para anon/authenticated: acesso só via service role nas functions.
revoke all on public.activation_codes from anon, authenticated;
revoke all on public.installations from anon, authenticated;
revoke all on public.ai_usage from anon, authenticated;
