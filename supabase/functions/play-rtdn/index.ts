import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.111.0";
import { GoogleOidcVerifier } from "../_shared/google_oidc.ts";
import type { EntitlementWrite } from "../play-entitlement/core.ts";
import {
  GooglePlaySubscriptionVerifier,
  parseGoogleServiceAccount,
} from "../play-entitlement/google_play.ts";
import {
  createPlayRtdnHandler,
  type RtdnEntitlementStore,
  rtdnTakeoverTokenHashes,
} from "./core.ts";

function requiredEnvironment(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing_${name.toLowerCase()}`);
  return value;
}

class SupabaseRtdnStore implements RtdnEntitlementStore {
  constructor(private readonly admin: SupabaseClient) {}

  async accountIdForObfuscatedId(value: string): Promise<string | null> {
    const { data, error } = await this.admin.from("account_entitlements")
      .select("account_id")
      .eq("obfuscated_account_id", value)
      .maybeSingle();
    if (error) throw error;
    return data?.account_id ?? null;
  }

  async write(value: EntitlementWrite): Promise<boolean> {
    const { data, error } = await this.admin.from("account_entitlements")
      .update({
        account_id: value.accountId,
        tier: "pro",
        source: "google_play",
        product_id: value.productId,
        obfuscated_account_id: value.obfuscatedAccountId,
        purchase_token_sha256: value.purchaseTokenSha256,
        subscription_state: value.state,
        expires_at: value.expiresAt,
        verified_at: value.verifiedAt,
        updated_at: value.verifiedAt,
      })
      .eq("account_id", value.accountId)
      .in("purchase_token_sha256", rtdnTakeoverTokenHashes(value))
      .lte("verified_at", value.verifiedAt)
      .select("account_id")
      .maybeSingle();
    if (error) throw error;
    return data !== null;
  }
}

const admin = createClient(
  requiredEnvironment("SUPABASE_URL"),
  requiredEnvironment("SUPABASE_SERVICE_ROLE_KEY"),
  {
    auth: {
      autoRefreshToken: false,
      persistSession: false,
      detectSessionInUrl: false,
    },
  },
);
const audience = Deno.env.get("GOOGLE_PLAY_RTDN_AUDIENCE")?.trim() ?? "";
const serviceAccountEmail =
  Deno.env.get("GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL")?.trim() ?? "";
const oidc = audience.length === 0 || serviceAccountEmail.length === 0
  ? null
  : new GoogleOidcVerifier(audience, serviceAccountEmail);
const rawServiceAccount =
  Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")?.trim() ?? "";
const verifier = rawServiceAccount.length === 0
  ? null
  : new GooglePlaySubscriptionVerifier(
    parseGoogleServiceAccount(rawServiceAccount),
  );

Deno.serve(createPlayRtdnHandler({
  authenticate: (request) => {
    if (oidc === null) throw new Error("play_rtdn_not_configured");
    return oidc.verifyAuthorization(request.headers.get("authorization"));
  },
  verifier,
  store: new SupabaseRtdnStore(admin),
  log: (record) => console.log(JSON.stringify(record)),
}));
