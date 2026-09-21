import {
  adminClient,
  boundedBody,
  entitlementResponse,
  verifiedUser,
} from "../_shared/website.ts";
const admin = adminClient();
Deno.serve(async (request) => {
  const headers = { "cache-control": "no-store" };
  if (request.method !== "POST") return new Response(null, { status: 405 });
  try {
    const user = await verifiedUser(request, admin);
    if (!user) {
      return Response.json({ error: "sign_in_required" }, {
        status: 401,
        headers,
      });
    }
    const body = JSON.parse(await boundedBody(request));
    if (body.action !== "status" || Object.keys(body).length !== 1) {
      return Response.json({ error: "invalid_request" }, {
        status: 400,
        headers,
      });
    }
    const { data, error } = await admin.rpc("beyoureyes_product_entitlement", {
      p_account_id: user.id,
    });
    if (error) throw error;
    return Response.json(entitlementResponse(data), { headers });
  } catch {
    return Response.json({ error: "entitlement_unavailable" }, {
      status: 503,
      headers,
    });
  }
});
