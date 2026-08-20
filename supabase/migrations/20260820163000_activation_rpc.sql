-- Consumo atômico do código (impede dupla ativação concorrente)
-- e incremento atômico da cota diária.

create or replace function public.consume_activation_code(
  p_code_hash text,
  p_token_hash text
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_install_id uuid;
  v_code_id uuid;
begin
  insert into public.installations (token_hash)
  values (p_token_hash)
  returning id into v_install_id;

  update public.activation_codes
  set used_at = now(),
      used_by_installation_id = v_install_id
  where code_hash = p_code_hash
    and used_at is null
  returning id into v_code_id;

  if v_code_id is null then
    delete from public.installations where id = v_install_id;
    return null;
  end if;

  return v_install_id;
end;
$$;

create or replace function public.try_increment_ai_usage(
  p_installation_id uuid,
  p_used_on date,
  p_limit integer
)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_count integer;
begin
  insert into public.ai_usage (installation_id, used_on, use_count)
  values (p_installation_id, p_used_on, 1)
  on conflict (installation_id, used_on)
  do update set use_count = public.ai_usage.use_count + 1
  where public.ai_usage.use_count < p_limit
  returning use_count into v_count;

  return v_count;
end;
$$;

revoke all on function public.consume_activation_code(text, text) from public, anon, authenticated;
revoke all on function public.try_increment_ai_usage(uuid, date, integer) from public, anon, authenticated;
grant execute on function public.consume_activation_code(text, text) to service_role;
grant execute on function public.try_increment_ai_usage(uuid, date, integer) to service_role;
