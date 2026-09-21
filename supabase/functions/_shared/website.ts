import { createClient } from "npm:@supabase/supabase-js@2.111.0";

export function required(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing_${name.toLowerCase()}`);
  return value;
}

export function adminClient() {
  return createClient(
    required("SUPABASE_URL"),
    required("SUPABASE_SERVICE_ROLE_KEY"),
    {
      auth: {
        autoRefreshToken: false,
        persistSession: false,
        detectSessionInUrl: false,
      },
    },
  );
}

export async function verifiedUser(
  request: Request,
  admin: ReturnType<typeof adminClient>,
) {
  const header = request.headers.get("authorization") ?? "";
  if (!header.startsWith("Bearer ") || header.length > 16_384) return null;
  const { data, error } = await admin.auth.getUser(header.slice(7));
  if (error || !data.user?.email_confirmed_at) return null;
  return data.user;
}

export async function boundedBody(
  request: Request,
  limit = 8192,
): Promise<string> {
  if (!request.body) throw new Error("body_required");
  const reader = request.body.getReader();
  const parts: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > limit) throw new Error("body_too_large");
      parts.push(value);
    }
  } finally {
    await reader.cancel();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    bytes.set(part, offset);
    offset += part.length;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

export function entitlementResponse(
  value: { state: string; expires_at: string | null },
  now = Date.now(),
) {
  const expiry = value.expires_at ? Date.parse(value.expires_at) : NaN;
  const active = [
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED",
    "WEBSITE_PASS_ACTIVE",
    "WEBSITE_TRIAL_ACTIVE",
  ].includes(value.state) && expiry > now;
  // Website leases refresh frequently, limiting refund latency without changing Play leases.
  const until = active ? Math.min(expiry, now + 60 * 60 * 1000) : null;
  return {
    product_id: "be_your_eye_pro",
    active,
    state: value.state,
    expires_at: until === null ? null : new Date(until).toISOString(),
    refresh_after: until === null
      ? null
      : new Date(Math.min(until, now + 5 * 60 * 1000)).toISOString(),
  };
}
