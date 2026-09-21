import type { SupabaseClient } from "npm:@supabase/supabase-js@2.111.0";

export const PRO_PRODUCT_ID = "be_your_eye_pro";
/** Fail-closed server gate shared by paid AI endpoints. */
export async function hasActiveProEntitlement(
  admin: SupabaseClient,
  accountId: string,
  now = new Date(),
): Promise<boolean> {
  const { data, error } = await admin.rpc(
    "beyoureyes_account_has_active_entitlement",
    {
      p_account_id: accountId,
      p_at: now.toISOString(),
    },
  );
  return error === null && data === true;
}
