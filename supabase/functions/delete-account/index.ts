import { createClient } from "npm:@supabase/supabase-js@2.111.0";

const jsonHeaders = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

function response(status: number, code: string): Response {
  return new Response(JSON.stringify({ code }), {
    status,
    headers: jsonHeaders,
  });
}

Deno.serve(async (request) => {
  if (request.method !== "DELETE") {
    return new Response(null, {
      status: 405,
      headers: { ...jsonHeaders, allow: "DELETE" },
    });
  }

  const authorization = request.headers.get("authorization") ?? "";
  if (!authorization.startsWith("Bearer ") || authorization.length > 16_384) {
    return response(401, "supabase_session_required");
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) {
    return response(503, "account_deletion_not_configured");
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const token = authorization.slice("Bearer ".length);
  const { data, error: identityError } = await admin.auth.getUser(token);
  if (identityError || !data.user) {
    return response(401, "supabase_session_invalid");
  }

  const { error: deletionError } = await admin.auth.admin.deleteUser(
    data.user.id,
  );
  if (deletionError) {
    return response(503, "account_deletion_failed");
  }

  return new Response(null, {
    status: 204,
    headers: { "cache-control": "no-store" },
  });
});
