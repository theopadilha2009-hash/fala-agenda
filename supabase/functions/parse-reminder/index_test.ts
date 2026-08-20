import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { parseReminder } from "./index.ts";
import { sha256Hex } from "../_shared/crypto.ts";

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.test("parse-reminder rejeita sem token", async () => {
  const res = await parseReminder(
    new Request("http://local/parse-reminder", {
      method: "POST",
      body: JSON.stringify({
        transcript: "x",
        now: "2026-08-20T10:00:00Z",
        timezone: "America/Sao_Paulo",
        locale: "pt-BR",
      }),
    }),
    mockDeps(),
  );
  assertEquals(res.status, 401);
});

Deno.test("parse-reminder chama OpenAI mockado e incrementa cota", async () => {
  const token = "tok_test";
  const tokenHash = await sha256Hex(token);
  const calls: string[] = [];
  const deps = mockDeps({
    tokenHash,
    fetch: async (input, init) => {
      const url = String(input);
      calls.push(url + " " + (init?.method ?? "GET"));
      if (url.includes("/rest/v1/installations?token_hash")) {
        return jsonResponse([{ id: "inst-1" }]);
      }
      if (url.includes("/rest/v1/ai_usage?installation_id")) {
        return jsonResponse([]);
      }
      if (url.includes("/rest/v1/ai_usage") && init?.method === "POST") {
        return jsonResponse([], 201);
      }
      if (url.includes("/rest/v1/installations?id=")) {
        return jsonResponse([]);
      }
      if (url.includes("api.openai.com")) {
        const body = JSON.parse(String(init?.body ?? "{}"));
        assertEquals(body.model, "gpt-5-nano");
        assertEquals(body.text.format.strict, true);
        return jsonResponse({
          output_text: JSON.stringify({
            title: "Tomar remédio",
            local_date: "2026-08-21",
            local_time: "09:00",
            recurrence: { kind: "NONE", week_days: [], day_of_month: null, month_of_year: null },
            confidence: 0.8,
            ambiguous: false,
            missing_fields: [],
            notes: [],
          }),
        });
      }
      return jsonResponse([]);
    },
  });
  const res = await parseReminder(
    new Request("http://local/parse-reminder", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        transcript: "tomar remédio amanhã às 9h",
        now: "2026-08-20T10:00:00Z",
        timezone: "America/Sao_Paulo",
        locale: "pt-BR",
      }),
    }),
    deps,
  );
  assertEquals(res.status, 200);
  const payload = await res.json();
  assertEquals(payload.title, "Tomar remédio");
  assertEquals(payload.local_date, "2026-08-21");
  assertEquals(calls.some((c) => c.includes("api.openai.com")), true);
});

Deno.test("parse-reminder respeita cota diária 30", async () => {
  const token = "tok_quota";
  const tokenHash = await sha256Hex(token);
  const deps = mockDeps({
    tokenHash,
    fetch: async (input) => {
      const url = String(input);
      if (url.includes("/rest/v1/installations?token_hash")) {
        return jsonResponse([{ id: "inst-1" }]);
      }
      if (url.includes("/rest/v1/ai_usage?installation_id")) {
        return jsonResponse([{ id: "u1", use_count: 30 }]);
      }
      throw new Error("não deveria chamar openai");
    },
  });
  const res = await parseReminder(
    new Request("http://local/parse-reminder", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        transcript: "x",
        now: "2026-08-20T10:00:00Z",
        timezone: "America/Sao_Paulo",
        locale: "pt-BR",
      }),
    }),
    deps,
  );
  assertEquals(res.status, 429);
});

function mockDeps(overrides: { tokenHash?: string; fetch?: typeof fetch } = {}) {
  const fetchImpl = overrides.fetch ?? fetch;
  return {
    db: {
      url: "https://example.supabase.co",
      serviceKey: "service",
      fetch: fetchImpl,
    },
    openaiKey: "sk-test",
    openaiModel: "gpt-5-nano",
    timeoutMs: 2000,
    fetch: fetchImpl,
    today: "2026-08-20",
  };
}
