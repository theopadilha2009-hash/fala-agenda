import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { activateDevice } from "./index.ts";
import { hashActivationCode } from "../_shared/crypto.ts";

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.test("activate-device rejeita código curto", async () => {
  const res = await activateDevice(
    new Request("http://local/activate-device", {
      method: "POST",
      body: JSON.stringify({ code: "abc" }),
    }),
    {
      db: { url: "https://example.supabase.co", serviceKey: "s", fetch },
      pepper: "",
    },
  );
  assertEquals(res.status, 400);
});

Deno.test("activate-device troca código válido por token", async () => {
  const code = "VALIDCODE1234";
  const pepper = "pepper";
  const codeHash = await hashActivationCode(code, pepper);
  const res = await activateDevice(
    new Request("http://local/activate-device", {
      method: "POST",
      body: JSON.stringify({ code }),
    }),
    {
      pepper,
      db: {
        url: "https://example.supabase.co",
        serviceKey: "s",
        fetch: async (input, init) => {
          const url = String(input);
          if (url.includes("activation_codes?code_hash=")) {
            assertEquals(url.includes(codeHash), true);
            return jsonResponse([{ id: "code-1", used_at: null }]);
          }
          if (url.includes("/rest/v1/installations") && init?.method === "POST") {
            return jsonResponse([{ id: "inst-1" }], 201);
          }
          if (url.includes("activation_codes?id=eq.code-1")) {
            return jsonResponse([]);
          }
          return jsonResponse([]);
        },
      },
    },
  );
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(typeof body.token, "string");
  assertEquals(body.token.length >= 32, true);
});
