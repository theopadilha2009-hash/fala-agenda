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

Deno.test("activate-device troca código válido por token via RPC atômico", async () => {
  const code = "VALIDCODE1234";
  const pepper = "pepper";
  const codeHash = await hashActivationCode(code, pepper);
  let rpcBody = "";
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
          assertEquals(url.includes("/rest/v1/rpc/consume_activation_code"), true);
          assertEquals(init?.method, "POST");
          rpcBody = String(init?.body ?? "");
          assertEquals(rpcBody.includes(codeHash), true);
          return jsonResponse("inst-1");
        },
      },
    },
  );
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(typeof body.token, "string");
  assertEquals(body.token.length >= 32, true);
});

Deno.test("activate-device rejeita código já consumido", async () => {
  const res = await activateDevice(
    new Request("http://local/activate-device", {
      method: "POST",
      body: JSON.stringify({ code: "USEDCODE1234" }),
    }),
    {
      pepper: "",
      db: {
        url: "https://example.supabase.co",
        serviceKey: "s",
        fetch: async () => jsonResponse(null),
      },
    },
  );
  assertEquals(res.status, 400);
});
