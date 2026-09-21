const BAILIAN_BASE_PATH = "/compatible-mode/v1";
const BEIJING_HOST_SUFFIX = ".cn-beijing.maas.aliyuncs.com";

/** Resolve one Beijing workspace endpoint without accepting a generic or cross-region URL. */
export function bailianChatCompletionsEndpoint(baseUrl: string): string {
  if (baseUrl.length < 1 || baseUrl.length > 2_048) {
    throw new Error("invalid_dashscope_base_url");
  }
  let parsed: URL;
  try {
    parsed = new URL(baseUrl);
  } catch {
    throw new Error("invalid_dashscope_base_url");
  }
  const workspace = parsed.hostname.slice(
    0,
    -BEIJING_HOST_SUFFIX.length,
  );
  if (
    parsed.protocol !== "https:" || parsed.username !== "" ||
    parsed.password !== "" || parsed.search !== "" || parsed.hash !== "" ||
    parsed.port !== "" || parsed.pathname.replace(/\/$/, "") !==
      BAILIAN_BASE_PATH ||
    !parsed.hostname.endsWith(BEIJING_HOST_SUFFIX) ||
    !/^[a-z0-9][a-z0-9-]{0,62}$/i.test(workspace)
  ) {
    throw new Error("invalid_dashscope_base_url");
  }
  return `https://${parsed.hostname}${BAILIAN_BASE_PATH}/chat/completions`;
}

export async function boundedUtf8ResponseText(
  response: Response,
  maximumBytes: number,
): Promise<string> {
  if (!Number.isInteger(maximumBytes) || maximumBytes < 1) {
    throw new TypeError("maximumBytes must be positive");
  }
  if (response.body === null) return "";
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
        throw new Error("provider_response_too_large");
      }
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new Error("provider_response_not_utf8");
  }
}
