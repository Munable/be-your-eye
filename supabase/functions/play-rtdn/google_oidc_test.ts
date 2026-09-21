import {
  type GoogleJsonWebKey,
  type GoogleJwkProvider,
  GoogleOidcVerifier,
  RemoteGoogleJwkProvider,
} from "../_shared/google_oidc.ts";

function equal(actual: unknown, expected: unknown) {
  if (actual !== expected) throw new Error(`${actual} != ${expected}`);
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(
    /=+$/,
    "",
  );
}

Deno.test("Google OIDC verifier binds signature, audience and service account", async () => {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const publicJwk = await crypto.subtle.exportKey(
    "jwk",
    pair.publicKey,
  ) as GoogleJsonWebKey;
  publicJwk.kid = "test-key";
  publicJwk.alg = "RS256";
  const provider: GoogleJwkProvider = { keys: async () => [publicJwk] };
  const audience = "https://project.supabase.co/functions/v1/play-rtdn";
  const email = "play-rtdn@project.iam.gserviceaccount.com";
  const header = base64Url(
    new TextEncoder().encode(JSON.stringify({ alg: "RS256", kid: "test-key" })),
  );
  const payload = base64Url(new TextEncoder().encode(JSON.stringify({
    iss: "https://accounts.google.com",
    aud: audience,
    email,
    email_verified: true,
    sub: "123456789012345678901",
    iat: 1_000,
    exp: 4_600,
  })));
  const input = `${header}.${payload}`;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    pair.privateKey,
    new TextEncoder().encode(input),
  );
  const token = `${input}.${base64Url(new Uint8Array(signature))}`;
  const verifier = new GoogleOidcVerifier(
    audience,
    email,
    provider,
    () => 1_100,
  );
  equal(await verifier.verifyAuthorization(`Bearer ${token}`), true);
  const corruptedSignature = new Uint8Array(signature);
  corruptedSignature[0] ^= 1;
  equal(
    await verifier.verifyAuthorization(
      `Bearer ${input}.${base64Url(corruptedSignature)}`,
    ),
    false,
  );
  equal(
    await new GoogleOidcVerifier(
      audience,
      "other@project.iam.gserviceaccount.com",
      provider,
      () => 1_100,
    )
      .verifyAuthorization(`Bearer ${token}`),
    false,
  );
});

Deno.test("unknown Google kid forces one JWKS refresh", async () => {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const publicJwk = await crypto.subtle.exportKey(
    "jwk",
    pair.publicKey,
  ) as GoogleJsonWebKey;
  publicJwk.kid = "rotated-key";
  publicJwk.alg = "RS256";
  const calls: Array<boolean | undefined> = [];
  const provider: GoogleJwkProvider = {
    keys: async (forceRefresh) => {
      calls.push(forceRefresh);
      return forceRefresh ? [publicJwk] : [];
    },
  };
  const audience = "https://project.supabase.co/functions/v1/play-rtdn";
  const email = "play-rtdn@project.iam.gserviceaccount.com";
  const header = base64Url(
    new TextEncoder().encode(
      JSON.stringify({ alg: "RS256", kid: "rotated-key" }),
    ),
  );
  const payload = base64Url(new TextEncoder().encode(JSON.stringify({
    iss: "accounts.google.com",
    aud: audience,
    email,
    email_verified: true,
    sub: "123456789012345678901",
    iat: 1_000,
    exp: 4_600,
  })));
  const input = `${header}.${payload}`;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    pair.privateKey,
    new TextEncoder().encode(input),
  );
  const token = `${input}.${base64Url(new Uint8Array(signature))}`;
  equal(
    await new GoogleOidcVerifier(audience, email, provider, () => 1_100)
      .verifyAuthorization(`Bearer ${token}`),
    true,
  );
  equal(JSON.stringify(calls), JSON.stringify([undefined, true]));
});

Deno.test("JWKS response is streamed through a strict byte ceiling", async () => {
  const provider = new RemoteGoogleJwkProvider(
    (async () =>
      new Response(new Uint8Array(128 * 1024 + 1), {
        status: 200,
        headers: { "content-type": "application/json" },
      })) as typeof fetch,
  );
  let failed = false;
  try {
    await provider.keys();
  } catch {
    failed = true;
  }
  equal(failed, true);
});
