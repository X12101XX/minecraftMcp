import { readFileSync } from "node:fs";

export interface BridgeSettings {
  url: string;
  token: string;
  allowWrites: boolean;
}

/**
 * Resolve the bridge URL/token without ever printing the secret. The preferred source is the
 * credentials file written by the Fabric mod.
 */
export function loadSettings(env: NodeJS.ProcessEnv = process.env): BridgeSettings {
  const configPath = env.MINECRAFT_MCP_CONFIG;
  if (configPath) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(readFileSync(configPath, "utf8"));
    } catch (error) {
      throw new Error(`Cannot read MINECRAFT_MCP_CONFIG at ${configPath}: ${(error as Error).message}`);
    }
    if (!parsed || typeof parsed !== "object") {
      throw new Error("Minecraft MCP config must be a JSON object");
    }
    const record = parsed as Record<string, unknown>;
    const url = typeof record.url === "string" ? record.url : env.MINECRAFT_MCP_URL;
    const token = typeof record.token === "string" ? record.token : env.MINECRAFT_MCP_TOKEN;
    if (!url || !token) {
      throw new Error("Minecraft MCP config must contain url and token");
    }
    return {
      url: normalizeUrl(url),
      token,
      allowWrites: record.allow_writes !== false,
    };
  }
  const token = env.MINECRAFT_MCP_TOKEN;
  if (!token) {
    throw new Error(
      "No bridge credentials. Set MINECRAFT_MCP_CONFIG to the credentials file written by the Fabric mod, or set MINECRAFT_MCP_TOKEN.",
    );
  }
  return {
    url: normalizeUrl(env.MINECRAFT_MCP_URL ?? "http://127.0.0.1:8765"),
    token,
    allowWrites: env.MINECRAFT_MCP_READ_ONLY !== "1",
  };
}

function normalizeUrl(value: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error("Bridge URL is not a valid URL");
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("Bridge URL must use http or https");
  }
  if (url.hostname !== "127.0.0.1" && url.hostname !== "localhost" && url.hostname !== "::1") {
    throw new Error("Bridge URL must point at loopback; remote bridges are not supported");
  }
  return url.toString().replace(/\/$/, "");
}
