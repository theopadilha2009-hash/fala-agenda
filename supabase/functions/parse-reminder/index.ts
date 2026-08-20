import { json, readBearer, sha256Hex } from "../_shared/crypto.ts";
import { rest, type SupabaseRest } from "../_shared/db.ts";
import { parseWithOpenAI } from "../_shared/openai.ts";

const DAILY_LIMIT = 30;

export type ParseDeps = {
  db: SupabaseRest;
  openaiKey: string;
  openaiModel: string;
  timeoutMs: number;
  fetch: typeof fetch;
  today?: string;
};

export async function parseReminder(req: Request, deps: ParseDeps): Promise<Response> {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const token = readBearer(req);
  if (!token) return json({ error: "unauthorized" }, 401);

  let body: { transcript?: string; now?: string; timezone?: string; locale?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  if (!body.transcript || !body.now || !body.timezone || !body.locale) {
    return json({ error: "missing_fields" }, 400);
  }

  const tokenHash = await sha256Hex(token);
  const inst = await rest<Array<{ id: string }>>(
    deps.db,
    `installations?token_hash=eq.${tokenHash}&select=id`,
  );
  const installation = inst.json[0];
  if (!installation) return json({ error: "unauthorized" }, 401);

  const today = deps.today ?? new Date().toISOString().slice(0, 10);
  const usage = await rest<Array<{ id: string; use_count: number }>>(
    deps.db,
    `ai_usage?installation_id=eq.${installation.id}&used_on=eq.${today}&select=id,use_count`,
  );
  const current = usage.json[0];
  if ((current?.use_count ?? 0) >= DAILY_LIMIT) {
    return json({ error: "quota_exceeded" }, 429);
  }

  if (current) {
    await rest(deps.db, `ai_usage?id=eq.${current.id}`, {
      method: "PATCH",
      body: JSON.stringify({ use_count: current.use_count + 1 }),
    });
  } else {
    await rest(deps.db, "ai_usage", {
      method: "POST",
      body: JSON.stringify({
        installation_id: installation.id,
        used_on: today,
        use_count: 1,
      }),
    });
  }

  await rest(deps.db, `installations?id=eq.${installation.id}`, {
    method: "PATCH",
    body: JSON.stringify({ last_seen_at: new Date().toISOString() }),
  });

  try {
    const parsed = await parseWithOpenAI(
      {
        fetch: deps.fetch,
        apiKey: deps.openaiKey,
        model: deps.openaiModel,
        timeoutMs: deps.timeoutMs,
      },
      {
        transcript: body.transcript,
        now: body.now,
        timezone: body.timezone,
        locale: body.locale,
      },
    );
    return json(parsed);
  } catch {
    console.log("parse-reminder openai_failed");
    return json({ error: "parse_failed" }, 502);
  }
}

if (import.meta.main) {
  Deno.serve(async (req) => {
    const url = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const openaiKey = Deno.env.get("OPENAI_API_KEY") ?? "";
    const openaiModel = Deno.env.get("OPENAI_MODEL") ?? "gpt-5-nano";
    const timeoutMs = Number(Deno.env.get("OPENAI_TIMEOUT_MS") ?? "8000");
    if (!url || !serviceKey || !openaiKey) return json({ error: "misconfigured" }, 500);
    return await parseReminder(req, {
      db: { url, serviceKey, fetch },
      openaiKey,
      openaiModel,
      timeoutMs,
      fetch,
    });
  });
}
