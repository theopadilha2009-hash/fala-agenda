export type SupabaseRest = {
  url: string;
  serviceKey: string;
  fetch: typeof fetch;
};

export async function rest<T>(
  db: SupabaseRest,
  path: string,
  init: RequestInit = {},
): Promise<{ status: number; json: T }> {
  const headers = new Headers(init.headers);
  headers.set("apikey", db.serviceKey);
  headers.set("Authorization", `Bearer ${db.serviceKey}`);
  headers.set("Content-Type", "application/json");
  const response = await db.fetch(`${db.url.replace(/\/$/, "")}/rest/v1/${path}`, {
    ...init,
    headers,
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) as T : (null as T);
  return { status: response.status, json };
}
