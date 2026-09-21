const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const MAX_TOKEN_LENGTH = 16_384;
const MAX_JWKS_BYTES = 128 * 1024;
const MIN_FORCED_REFRESH_INTERVAL_MILLIS = 60_000;

export interface GoogleJsonWebKey extends JsonWebKey {
  kid?: string;
  alg?: string;
}

export interface GoogleJwkProvider {
  keys(forceRefresh?: boolean): Promise<GoogleJsonWebKey[]>;
}

export class RemoteGoogleJwkProvider implements GoogleJwkProvider {
  private cached: {
    values: GoogleJsonWebKey[];
    expiresAt: number;
    refreshedAt: number;
  } | null = null;

  constructor(
    private readonly fetcher: typeof fetch = fetch,
    private readonly nowMillis: () => number = Date.now,
  ) {}

  async keys(forceRefresh = false): Promise<GoogleJsonWebKey[]> {
    const now = this.nowMillis();
    if (
      this.cached !== null &&
      ((!forceRefresh && this.cached.expiresAt > now) ||
        (forceRefresh &&
          this.cached.refreshedAt + MIN_FORCED_REFRESH_INTERVAL_MILLIS > now))
    ) {
      return this.cached.values;
    }
    const response = await this.fetcher(GOOGLE_JWKS_URL, {
      method: "GET",
      redirect: "error",
      signal: AbortSignal.timeout(10_000),
      headers: { accept: "application/json" },
    });
    const bytes = await readBoundedResponse(response, MAX_JWKS_BYTES);
    if (
      !response.ok || bytes.byteLength === 0 ||
      bytes.byteLength > MAX_JWKS_BYTES
    ) {
      throw new Error("google_jwks_unavailable");
    }
    const parsed = JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(bytes),
    );
    if (
      parsed === null || typeof parsed !== "object" ||
      !Array.isArray(parsed.keys)
    ) {
      throw new Error("google_jwks_invalid");
    }
    const values = parsed.keys.filter((
      value: unknown,
    ): value is GoogleJsonWebKey =>
      value !== null && typeof value === "object" && !Array.isArray(value)
    );
    if (values.length === 0 || values.length > 20) {
      throw new Error("google_jwks_invalid");
    }
    this.cached = {
      values,
      expiresAt: now +
        cacheDurationMillis(response.headers.get("cache-control")),
      refreshedAt: now,
    };
    return values;
  }
}

export class GoogleOidcVerifier {
  constructor(
    private readonly audience: string,
    private readonly serviceAccountEmail: string,
    private readonly jwks: GoogleJwkProvider = new RemoteGoogleJwkProvider(),
    private readonly nowSeconds: () => number = () =>
      Math.floor(Date.now() / 1_000),
  ) {
    const audienceUrl = new URL(audience);
    if (
      audienceUrl.protocol !== "https:" || audienceUrl.username ||
      audienceUrl.password
    ) {
      throw new Error("invalid_oidc_audience");
    }
    if (!serviceAccountEmail.endsWith(".iam.gserviceaccount.com")) {
      throw new Error("invalid_oidc_service_account");
    }
  }

  async verifyAuthorization(value: string | null): Promise<boolean> {
    if (
      value === null || !value.startsWith("Bearer ") ||
      value.length > MAX_TOKEN_LENGTH
    ) {
      return false;
    }
    let parts: string[];
    let header: Record<string, unknown>;
    let claims: Record<string, unknown>;
    let signature: Uint8Array<ArrayBuffer>;
    try {
      const token = value.slice("Bearer ".length);
      parts = token.split(".");
      if (parts.length !== 3 || parts.some((part) => part.length === 0)) {
        return false;
      }
      header = object(JSON.parse(decodeBase64UrlText(parts[0]!)));
      claims = object(JSON.parse(decodeBase64UrlText(parts[1]!)));
      signature = decodeBase64Url(parts[2]!);
    } catch {
      return false;
    }
    if (
      header.alg !== "RS256" || typeof header.kid !== "string" ||
      header.kid.length === 0 || header.kid.length > 256 ||
      !validClaims(
        claims,
        this.audience,
        this.serviceAccountEmail,
        this.nowSeconds(),
      )
    ) return false;
    let keyValue = findKey(await this.jwks.keys(), header.kid);
    if (keyValue === undefined) {
      keyValue = findKey(await this.jwks.keys(true), header.kid);
    }
    if (keyValue === undefined) return false;
    let key: CryptoKey;
    try {
      key = await crypto.subtle.importKey(
        "jwk",
        keyValue,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"],
      );
    } catch {
      throw new Error("google_jwk_invalid");
    }
    try {
      return await crypto.subtle.verify(
        "RSASSA-PKCS1-v1_5",
        key,
        signature,
        new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
      );
    } catch {
      return false;
    }
  }
}

function findKey(
  values: GoogleJsonWebKey[],
  kid: string,
): GoogleJsonWebKey | undefined {
  return values.find((candidate) =>
    candidate.kid === kid && candidate.kty === "RSA" &&
    candidate.use !== "enc" &&
    (candidate.alg === undefined || candidate.alg === "RS256")
  );
}

function validClaims(
  claims: Record<string, unknown>,
  audienceValue: string,
  serviceAccountEmail: string,
  now: number,
): boolean {
  const audience = claims.aud;
  const audienceMatches = audience === audienceValue ||
    (Array.isArray(audience) && audience.length > 0 && audience.length <= 10 &&
      audience.every((item) => typeof item === "string") &&
      audience.includes(audienceValue));
  return (claims.iss === "https://accounts.google.com" ||
    claims.iss === "accounts.google.com") &&
    audienceMatches &&
    claims.email === serviceAccountEmail &&
    claims.email_verified === true &&
    typeof claims.sub === "string" && claims.sub.length > 0 &&
    claims.sub.length <= 255 &&
    typeof claims.iat === "number" && Number.isSafeInteger(claims.iat) &&
    typeof claims.exp === "number" && Number.isSafeInteger(claims.exp) &&
    claims.iat <= now + 60 && claims.exp > now &&
    claims.exp - claims.iat > 0 && claims.exp - claims.iat <= 3_900;
}

async function readBoundedResponse(
  response: Response,
  maximumBytes: number,
): Promise<Uint8Array> {
  const declaredLength = response.headers.get("content-length");
  if (
    declaredLength !== null &&
    (!/^\d+$/.test(declaredLength) || Number(declaredLength) > maximumBytes)
  ) throw new Error("google_jwks_unavailable");
  if (response.body === null) return new Uint8Array();
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > maximumBytes) {
        await reader.cancel();
        throw new Error("google_jwks_unavailable");
      }
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

function cacheDurationMillis(value: string | null): number {
  const match = value?.match(/(?:^|,)\s*max-age=(\d+)\s*(?:,|$)/i);
  const seconds = match === null || match === undefined
    ? 3_600
    : Number(match[1]);
  if (!Number.isSafeInteger(seconds)) return 3_600_000;
  return Math.min(24 * 60 * 60, Math.max(60, seconds)) * 1_000;
}

function decodeBase64UrlText(value: string): string {
  return new TextDecoder("utf-8", { fatal: true }).decode(
    decodeBase64Url(value),
  );
}

function decodeBase64Url(value: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid_base64url");
  const standard = value.replaceAll("-", "+").replaceAll("_", "/") +
    "=".repeat((4 - value.length % 4) % 4);
  const binary = atob(standard);
  const result = new Uint8Array(new ArrayBuffer(binary.length));
  for (let index = 0; index < binary.length; index += 1) {
    result[index] = binary.charCodeAt(index);
  }
  return result;
}

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("invalid_jwt_object");
  }
  return value as Record<string, unknown>;
}
