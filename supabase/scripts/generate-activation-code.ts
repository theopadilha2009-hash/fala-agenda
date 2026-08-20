/**
 * Gera um código de ativação de uso único.
 * Exige ADMIN_SECRET no ambiente. Imprime o código em claro uma única vez.
 *
 *   ADMIN_SECRET=... SUPABASE_URL=... SUPABASE_SERVICE_ROLE_KEY=... \
 *     deno run --allow-env --allow-net supabase/scripts/generate-activation-code.ts
 */
import { hashActivationCode, randomToken } from "../functions/_shared/crypto.ts";

const requiredSecret = Deno.env.get("ADMIN_SECRET");
if (!requiredSecret || requiredSecret.length < 16) {
  console.error("ADMIN_SECRET ausente ou curto demais. Recusando gerar código.");
  Deno.exit(1);
}

const url = Deno.env.get("SUPABASE_URL");
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!url || !serviceKey) {
  console.error("SUPABASE_URL e SUPABASE_SERVICE_ROLE_KEY são obrigatórios.");
  Deno.exit(1);
}

const pepper = Deno.env.get("ACTIVATION_CODE_PEPPER") ?? "";
const code = randomToken(16);
const codeHash = await hashActivationCode(code, pepper);

const response = await fetch(`${url.replace(/\/$/, "")}/rest/v1/activation_codes`, {
  method: "POST",
  headers: {
    apikey: serviceKey,
    Authorization: `Bearer ${serviceKey}`,
    "Content-Type": "application/json",
    Prefer: "return=minimal",
  },
  body: JSON.stringify({ code_hash: codeHash }),
});

if (!response.ok) {
  console.error(`Falha ao gravar hash (${response.status}). Código NÃO é válido.`);
  Deno.exit(1);
}

console.log("Código de ativação (copie agora; não será mostrado de novo):");
console.log(code);
