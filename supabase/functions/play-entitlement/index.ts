import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.111.0";
import {
  createPlayEntitlementHandler,
  type EntitlementStore,
  type EntitlementWrite,
  mayReplaceStoredPurchase,
  type StoredEntitlement,
} from "./core.ts";
import {
  GooglePlaySubscriptionVerifier,
  parseGoogleServiceAccount,
} from "./google_play.ts";

function requiredEnvironment(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing_${name.toLowerCase()}`);
  return value;
}

class SupabaseIdentity {
  constructor(private readonly client: SupabaseClient) {}

  async accountId(request: Request): Promise<string | null> {
    const authorization = request.headers.get("authorization") ?? "";
    if (!authorization.startsWith("Bearer ") || authorization.length > 16_384) {
      return null;
    }
    const { data, error } = await this.client.auth.getUser(
      authorization.slice("Bearer ".length),
    );
    if (error !== null) {
      if (error.status === 401 || error.status === 403) return null;
      throw error;
    }
    return data.user?.id ?? null;
  }
}

class SupabaseEntitlementStore implements EntitlementStore {
  constructor(private readonly admin: SupabaseClient) {}

  async read(accountId: string): Promise<StoredEntitlement | null> {
    const { data, error } = await this.admin
      .from("account_entitlements")
      .select("product_id,subscription_state,expires_at")
      .eq("account_id", accountId)
      .maybeSingle();
    if (error) throw error;
    return data === null ? null : {
      productId: data.product_id,
      state: data.subscription_state,
      expiresAt: data.expires_at,
    };
  }

  async write(value: EntitlementWrite): Promise<boolean> {
    const payload = {
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
    };
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const { data: current, error: readError } = await this.admin
        .from("account_entitlements")
        .select("purchase_token_sha256,expires_at")
        .eq("account_id", value.accountId)
        .maybeSingle();
      if (readError) throw readError;
      if (current === null) {
        const { error } = await this.admin.from("account_entitlements")
          .insert(payload);
        if (error === null) {
          await this.removeReplacedEntitlement(value);
          return true;
        }
        if (error.code === "23505") continue;
        throw error;
      }
      if (
        !mayReplaceStoredPurchase(
          current.purchase_token_sha256,
          current.expires_at,
          value,
        )
      ) return false;
      const { data, error } = await this.admin.from("account_entitlements")
        .update(payload)
        .eq("account_id", value.accountId)
        .eq("purchase_token_sha256", current.purchase_token_sha256)
        .lte("verified_at", value.verifiedAt)
        .select("account_id")
        .maybeSingle();
      if (error) throw error;
      if (data !== null) {
        await this.removeReplacedEntitlement(value);
        return true;
      }
    }
    return false;
  }

  private async removeReplacedEntitlement(
    value: EntitlementWrite,
  ): Promise<void> {
    if (value.replacesPurchaseTokenSha256 === null) return;
    const { error } = await this.admin.from("account_entitlements")
      .delete()
      .eq("purchase_token_sha256", value.replacesPurchaseTokenSha256)
      .neq("account_id", value.accountId);
    if (error) throw error;
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
const identity = new SupabaseIdentity(admin);
const rawServiceAccount =
  Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")?.trim() ?? "";
const verifier = rawServiceAccount.length === 0
  ? null
  : new GooglePlaySubscriptionVerifier(
    parseGoogleServiceAccount(rawServiceAccount),
  );

Deno.serve(createPlayEntitlementHandler({
  authenticate: (request) => identity.accountId(request),
  store: new SupabaseEntitlementStore(admin),
  verifier,
  log: (record) => console.log(JSON.stringify(record)),
}));
