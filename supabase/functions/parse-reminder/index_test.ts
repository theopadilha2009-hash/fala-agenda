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
      if (url.includes("/rest/v1/rpc/try_increment_ai_usage")) {
        return jsonResponse(1);
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
      if (url.includes("/rest/v1/rpc/try_increment_ai_usage")) {
        return jsonResponse(null);
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

Deno.test("parse-reminder rejeita token inválido", async () => {
  const token = "tok_unknown";
  const deps = mockDeps({
    fetch: async (input) => {
      const url = String(input);
      if (url.includes("/rest/v1/installations?token_hash")) {
        return jsonResponse([]);
      }
      throw new Error("não deveria continuar");
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
  assertEquals(res.status, 401);
});

Deno.test("parse-reminder devolve 502 se o provedor falha e não registra transcript", async () => {
  const token = "tok_fail";
  const tokenHash = await sha256Hex(token);
  const logs: string[] = [];
  const original = console.log;
  console.log = (...args: unknown[]) => {
    logs.push(args.map(String).join(" "));
  };
  try {
    const deps = mockDeps({
      tokenHash,
      fetch: async (input) => {
        const url = String(input);
        if (url.includes("/rest/v1/installations?token_hash")) {
          return jsonResponse([{ id: "inst-1" }]);
        }
        if (url.includes("/rest/v1/rpc/try_increment_ai_usage")) {
          return jsonResponse(1);
        }
        if (url.includes("/rest/v1/installations?id=")) {
          return jsonResponse([]);
        }
        if (url.includes("api.openai.com")) {
          return jsonResponse({ error: "boom" }, 500);
        }
        return jsonResponse([]);
      },
    });
    const res = await parseReminder(
      new Request("http://local/parse-reminder", {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        body: JSON.stringify({
          transcript: "segredo-nao-pode-ir-pro-log",
          now: "2026-08-20T10:00:00Z",
          timezone: "America/Sao_Paulo",
          locale: "pt-BR",
        }),
      }),
      deps,
    );
    assertEquals(res.status, 502);
    assertEquals(logs.some((line) => line.includes("segredo-nao-pode-ir-pro-log")), false);
  } finally {
    console.log = original;
  }
});

Deno.test("parse-reminder timeout do provedor vira 502", async () => {
  const token = "tok_timeout";
  const tokenHash = await sha256Hex(token);
  const deps = mockDeps({
    tokenHash,
    fetch: async (input, init) => {
      const url = String(input);
      if (url.includes("/rest/v1/installations?token_hash")) {
        return jsonResponse([{ id: "inst-1" }]);
      }
      if (url.includes("/rest/v1/rpc/try_increment_ai_usage")) {
        return jsonResponse(1);
      }
      if (url.includes("/rest/v1/installations?id=")) {
        return jsonResponse([]);
      }
      if (url.includes("api.openai.com")) {
        const err = new Error("aborted");
        err.name = "AbortError";
        throw err;
      }
      return jsonResponse([]);
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
  assertEquals(res.status, 502);
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
