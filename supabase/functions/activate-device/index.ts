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
  const token = randomToken(32);
  const tokenHash = await sha256Hex(token);
  const consumed = await rest<string | null>(
    deps.db,
    "rpc/consume_activation_code",
    {
      method: "POST",
      body: JSON.stringify({ p_code_hash: codeHash, p_token_hash: tokenHash }),
    },
  );
  if (consumed.status >= 400 || !consumed.json) {
    return json({ error: "invalid_code" }, 400);
  }
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
