export const PARSED_TASK_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: [
    "title",
    "local_date",
    "local_time",
    "recurrence",
    "confidence",
    "ambiguous",
    "missing_fields",
    "notes",
  ],
  properties: {
    title: { type: "string" },
    local_date: { type: ["string", "null"] },
    local_time: { type: ["string", "null"] },
    recurrence: {
      type: "object",
      additionalProperties: false,
      required: ["kind", "week_days", "day_of_month", "month_of_year"],
      properties: {
        kind: {
          type: "string",
          enum: ["NONE", "DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY", "YEARLY"],
        },
        week_days: { type: "array", items: { type: "string" } },
        day_of_month: { type: ["integer", "null"] },
        month_of_year: { type: ["integer", "null"] },
      },
    },
    confidence: { type: "number" },
    ambiguous: { type: "boolean" },
    missing_fields: { type: "array", items: { type: "string" } },
    notes: { type: "array", items: { type: "string" } },
  },
} as const;

export type OpenAIDeps = {
  fetch: typeof fetch;
  apiKey: string;
  model: string;
  timeoutMs: number;
};

export async function parseWithOpenAI(
  deps: OpenAIDeps,
  input: { transcript: string; now: string; timezone: string; locale: string },
): Promise<unknown> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), deps.timeoutMs);
  try {
    const response = await deps.fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      signal: controller.signal,
      headers: {
        Authorization: `Bearer ${deps.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: deps.model,
        input: [
          {
            role: "system",
            content:
              "Extraia um rascunho de tarefa em pt-BR. Nunca invente data ou horário ausentes; use null e liste o campo em missing_fields. Não copie o transcript nas notes.",
          },
          {
            role: "user",
            content: JSON.stringify({
              transcript: input.transcript,
              now: input.now,
              timezone: input.timezone,
              locale: input.locale,
            }),
          },
        ],
        text: {
          format: {
            type: "json_schema",
            name: "parsed_task_draft",
            strict: true,
            schema: PARSED_TASK_SCHEMA,
          },
        },
      }),
    });
    if (!response.ok) {
      throw new Error(`openai_${response.status}`);
    }
    const body = await response.json() as {
      output_text?: string;
      output?: Array<{ content?: Array<{ text?: string }> }>;
    };
    const text = body.output_text ??
      body.output?.flatMap((o) => o.content ?? []).map((c) => c.text ?? "").join("") ??
      "";
    if (!text) throw new Error("openai_empty");
    return JSON.parse(text);
  } finally {
    clearTimeout(timer);
  }
}
