import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.111.0";
import { GoogleAuth, type JWTInput } from "npm:google-auth-library@11.0.0";
import {
  dispatchPushBatch,
  FcmHttpSender,
  MAX_BATCH_SIZE,
  type PushCompletion,
  type PushDelivery,
  type PushOutbox,
} from "./core.ts";

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const jsonHeaders = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

class SupabasePushOutbox implements PushOutbox {
  constructor(private readonly client: SupabaseClient) {}

  async claim(
    limit: number,
    leaseId: string,
    nowIso: string,
  ): Promise<PushDelivery[]> {
    const { data, error } = await this.client.rpc(
      "beyoureyes_claim_push_batch",
      {
        p_limit: limit,
        p_lease_id: leaseId,
        p_now: nowIso,
      },
    );
    if (error !== null || !Array.isArray(data)) {
      throw new Error("push_claim_failed");
    }
    return data as PushDelivery[];
  }

  async complete(completion: PushCompletion): Promise<boolean> {
    const { data, error } = await this.client.rpc("beyoureyes_complete_push", {
      p_outbox_id: completion.outboxId,
      p_lease_id: completion.leaseId,
      p_success: completion.success,
      p_permanent_failure: completion.permanentFailure,
      p_error: completion.error,
      p_now: completion.nowIso,
    });
    if (error !== null || typeof data !== "boolean") {
      throw new Error("push_complete_failed");
    }
    return data;
  }
}

function response(status: number, value: unknown): Response {
  return new Response(JSON.stringify(value), { status, headers: jsonHeaders });
}

function environment(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing_${name.toLowerCase()}`);
  return value;
}

function serviceAccount(raw: string, projectId: string): JWTInput {
  const parsed: unknown = JSON.parse(raw);
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("invalid_firebase_service_account");
  }
  const value = parsed as Record<string, unknown>;
  if (
    value.type !== "service_account" || value.project_id !== projectId ||
    typeof value.client_email !== "string" ||
    !value.client_email.endsWith(".gserviceaccount.com") ||
    typeof value.private_key !== "string" ||
    !value.private_key.includes("BEGIN PRIVATE KEY")
  ) {
    throw new Error("invalid_firebase_service_account");
  }
  return value as JWTInput;
}

async function sameSecret(
  provided: string,
  expected: string,
): Promise<boolean> {
  const encoder = new TextEncoder();
  const [left, right] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(provided)),
    crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  ]);
  const a = new Uint8Array(left);
  const b = new Uint8Array(right);
  let difference = a.length ^ b.length;
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    difference |= (a[index] ?? 0) ^ (b[index] ?? 0);
  }
  return difference === 0;
}

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return new Response(null, {
      status: 405,
      headers: { ...jsonHeaders, allow: "POST" },
    });
  }
  try {
    const expectedSecret = environment("PUSH_DISPATCH_SECRET");
    const providedSecret = request.headers.get("x-beyoureyes-push-secret") ??
      "";
    if (
      providedSecret.length > 512 ||
      !await sameSecret(providedSecret, expectedSecret)
    ) {
      return response(401, { code: "internal_auth_required" });
    }
    const supabaseUrl = environment("SUPABASE_URL");
    const serviceRoleKey = environment("SUPABASE_SERVICE_ROLE_KEY");
    const projectId = environment("FIREBASE_PROJECT_ID");
    const credentials = serviceAccount(
      environment("FIREBASE_SERVICE_ACCOUNT_JSON"),
      projectId,
    );
    const auth = new GoogleAuth({
      credentials,
      projectId,
      scopes: [FCM_SCOPE],
    });
    const client = createClient(supabaseUrl, serviceRoleKey, {
      auth: {
        persistSession: false,
        autoRefreshToken: false,
        detectSessionInUrl: false,
      },
    });
    const result = await dispatchPushBatch({
      outbox: new SupabasePushOutbox(client),
      sender: new FcmHttpSender({
        projectId,
        accessToken: async () => {
          const token = await auth.getAccessToken();
          if (!token) throw new Error("fcm_auth_unavailable");
          return token;
        },
      }),
      leaseId: crypto.randomUUID(),
      nowIso: () => new Date().toISOString(),
      limit: MAX_BATCH_SIZE,
    });
    console.log(JSON.stringify({ kind: "push_dispatch", ...result }));
    return response(200, result);
  } catch (error) {
    const code =
      error instanceof Error && /^[-a-z0-9_]{1,120}$/.test(error.message)
        ? error.message
        : "push_dispatch_failed";
    console.error(JSON.stringify({ kind: "push_dispatch_error", code }));
    return response(503, { code });
  }
});
