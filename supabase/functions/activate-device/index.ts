import { hashActivationCode, json, randomToken, sha256Hex } from "../_shared/crypto.ts";
import { rest, type SupabaseRest } from "../_shared/db.ts";

export type ActivateDeps = {
  db: SupabaseRest;
  pepper: string;
};

export async function activateDevice(req: Request, deps: ActivateDeps): Promise<Response> {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  let body: { code?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  const code = body.code?.trim() ?? "";
  if (code.length < 8) return json({ error: "invalid_code" }, 400);

  const codeHash = await hashActivationCode(code, deps.pepper);
  const found = await rest<Array<{ id: string; used_at: string | null }>>(
    deps.db,
    `activation_codes?code_hash=eq.${codeHash}&select=id,used_at`,
  );
  const row = found.json[0];
  if (!row || row.used_at) return json({ error: "invalid_code" }, 400);

  const token = randomToken(32);
  const tokenHash = await sha256Hex(token);
  const inserted = await rest<Array<{ id: string }>>(
    deps.db,
    "installations",
    {
      method: "POST",
      headers: { Prefer: "return=representation" },
      body: JSON.stringify({ token_hash: tokenHash }),
    },
  );
  const installation = inserted.json[0];
  if (!installation?.id) return json({ error: "activation_failed" }, 500);

  await rest(
    deps.db,
    `activation_codes?id=eq.${row.id}`,
    {
      method: "PATCH",
      body: JSON.stringify({
        used_at: new Date().toISOString(),
        used_by_installation_id: installation.id,
      }),
    },
  );

  return json({ token });
}

if (import.meta.main) {
  Deno.serve(async (req) => {
    const url = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const pepper = Deno.env.get("ACTIVATION_CODE_PEPPER") ?? "";
    if (!url || !serviceKey) return json({ error: "misconfigured" }, 500);
    return await activateDevice(req, {
      db: { url, serviceKey, fetch },
      pepper,
    });
  });
}
