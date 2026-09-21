import {
  createClient,
  type SupabaseClient,
} from "npm:@supabase/supabase-js@2.111.0";
import {
  type AssistantModelClient,
  BailianChatClient,
  createMonitorAssistantHandler,
} from "./core.ts";
import { hasActiveProEntitlement } from "../_shared/entitlement.ts";

function requiredEnvironment(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing_${name.toLowerCase()}`);
  return value;
}

class SupabaseIdentity {
  constructor(private readonly client: SupabaseClient) {}

  async accountId(request: Request): Promise<string | null> {
    const authorization = request.headers.get("authorization") ?? "";
    if (
      !authorization.startsWith("Bearer ") ||
      authorization.length > 16_384
    ) return null;
    const token = authorization.slice("Bearer ".length);
    const { data, error } = await this.client.auth.getUser(token);
    return error === null && data.user ? data.user.id : null;
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
const dashscopeKey = Deno.env.get("DASHSCOPE_API_KEY")?.trim() ?? "";
const dashscopeBaseUrl = Deno.env.get("DASHSCOPE_BASE_URL")?.trim() ?? "";
const client: AssistantModelClient | null =
  dashscopeKey.length === 0 || dashscopeBaseUrl.length === 0
    ? null
    : new BailianChatClient({
      apiKey: dashscopeKey,
      baseUrl: dashscopeBaseUrl,
    });

const handler = createMonitorAssistantHandler({
  authenticate: (request) => identity.accountId(request),
  authorize: (accountId) => hasActiveProEntitlement(admin, accountId),
  client,
  log: (record) => console.log(JSON.stringify(record)),
});

Deno.serve(handler);
