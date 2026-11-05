import type { BridgeSettings } from "./config.js";

export class BridgeError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly retryable = false,
    public readonly details: Record<string, unknown> = {},
    public readonly httpStatus?: number,
  ) {
    super(message);
  }

  toJSON(): Record<string, unknown> {
    return {
      ok: false,
      error: {
        code: this.code,
        message: this.message,
        retryable: this.retryable,
        details: this.details,
      },
    };
  }
}

export class BridgeClient {
  private readonly baseUrl: string;
  private readonly token: string;

  constructor(settings: BridgeSettings) {
    this.baseUrl = settings.url.replace(/\/$/, "");
    this.token = settings.token;
  }

  info(): Promise<Record<string, unknown>> {
    return this.request("GET", "/v1/info", undefined, 15_000);
  }

  callTool(tool: string, args: Record<string, unknown>): Promise<Record<string, unknown>> {
    return this.request("POST", "/v1/operations", { tool, arguments: args }, 25_000);
  }

  operation(sessionId: string, operationId: string): Promise<Record<string, unknown>> {
    const query = new URLSearchParams({ world_session_id: sessionId });
    return this.request("GET", `/v1/operations/${encodeURIComponent(operationId)}?${query}`, undefined, 15_000);
  }

  cancel(sessionId: string, operationId: string): Promise<Record<string, unknown>> {
    return this.request(
      "POST",
      `/v1/operations/${encodeURIComponent(operationId)}/cancel`,
      { world_session_id: sessionId },
      25_000,
    );
  }

  errors(sessionId: string, operationId: string, cursor?: string): Promise<Record<string, unknown>> {
    const query = new URLSearchParams({ world_session_id: sessionId });
    if (cursor) query.set("cursor", cursor);
    return this.request(
      "GET",
      `/v1/operations/${encodeURIComponent(operationId)}/errors?${query}`,
      undefined,
      20_000,
    );
  }

  result(sessionId: string, operationId: string, cursor?: string): Promise<Record<string, unknown>> {
    const query = new URLSearchParams({ world_session_id: sessionId });
    if (cursor) query.set("cursor", cursor);
    return this.request(
      "GET",
      `/v1/operations/${encodeURIComponent(operationId)}/result?${query}`,
      undefined,
      25_000,
    );
  }

  private async request(
    method: string,
    path: string,
    body: unknown,
    timeoutMs: number,
  ): Promise<Record<string, unknown>> {
    let response: Response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${this.token}`,
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (error) {
      throw new BridgeError(
        "BRIDGE_UNAVAILABLE",
        "Cannot reach the Minecraft MCP bridge. Start the game/world and check the bridge credential file.",
        true,
        { sent: body !== undefined },
      );
    }
    const text = await response.text();
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      throw new BridgeError(
        "BRIDGE_RESPONSE_INVALID",
        "The Minecraft bridge returned a non-JSON response.",
        false,
        { http_status: response.status },
        response.status,
      );
    }
    if (!parsed || typeof parsed !== "object") {
      throw new BridgeError("BRIDGE_RESPONSE_INVALID", "The Minecraft bridge returned an invalid object.", false, {
        http_status: response.status,
      });
    }
    const record = parsed as Record<string, unknown>;
    if (record.ok === false) {
      const error = (record.error ?? {}) as Record<string, unknown>;
      throw new BridgeError(
        typeof error.code === "string" ? error.code : "INTERNAL_ERROR",
        typeof error.message === "string" ? error.message : "Minecraft bridge request failed",
        error.retryable === true,
        (error.details as Record<string, unknown> | undefined) ?? {},
        response.status,
      );
    }
    if (!response.ok) {
      throw new BridgeError("BRIDGE_RESPONSE_INVALID", `Unexpected HTTP ${response.status} from the bridge.`, false, {
        http_status: response.status,
      });
    }
    return record;
  }
}
