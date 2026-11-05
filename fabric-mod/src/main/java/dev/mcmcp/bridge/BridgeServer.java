package dev.mcmcp.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcmcp.MinecraftMcpMod;
import dev.mcmcp.config.BridgeConfig;
import dev.mcmcp.operation.OperationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Authenticated, loopback-only HTTP bridge. This is an internal transport for the standalone MCP
 * server; it is not itself an MCP endpoint.
 */
public final class BridgeServer {
	private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
	private static final int MAX_QUERY_DECODED_BYTES = 16 * 1024;
	private static final long REQUEST_TIMEOUT_SECONDS = 15;
	private static final int RATE_LIMIT_PER_SECOND = 120;

	private final BridgeConfig config;
	private final OperationManager operations;
	private final byte[] tokenBytes;
	private HttpServer server;
	private ExecutorService executor;
	private final AtomicInteger inFlight = new AtomicInteger();
	private long rateWindow = System.nanoTime();
	private int rateCount;

	public BridgeServer(BridgeConfig config, OperationManager operations) {
		this.config = config;
		this.operations = operations;
		this.tokenBytes = config.token.getBytes(StandardCharsets.UTF_8);
	}

	public void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port), 16);
		executor = Executors.newFixedThreadPool(4, runnable -> {
			Thread thread = new Thread(runnable, "minecraft-mcp-http");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/v1/", this::handle);
		server.start();
	}

	public void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		try {
			if (!authorized(exchange)) {
				send(exchange, 401, new BridgeException("UNAUTHORIZED", 401, "missing or invalid bearer token",
						false, null).toJson());
				return;
			}
			String origin = exchange.getRequestHeaders().getFirst("Origin");
			if (origin != null && !origin.isBlank()) {
				send(exchange, 403, new BridgeException("FORBIDDEN", 403,
						"browser origins are not allowed", false, null).toJson());
				return;
			}
			if (!hostAllowed(exchange)) {
				send(exchange, 403, new BridgeException("FORBIDDEN", 403,
						"unexpected Host header", false, null).toJson());
				return;
			}
			if (!rateAllowed()) {
				send(exchange, 429, new BridgeException("QUEUE_FULL", 429,
						"bridge request rate limit exceeded", true, null).toJson());
				return;
			}
			if (inFlight.incrementAndGet() > 8) {
				inFlight.decrementAndGet();
				send(exchange, 429, new BridgeException("QUEUE_FULL", 429,
						"too many concurrent bridge requests", true, null).toJson());
				return;
			}
			try {
				route(exchange);
			} finally {
				inFlight.decrementAndGet();
			}
		} catch (BridgeException e) {
			send(exchange, e.httpStatus, e.toJson());
		} catch (Throwable t) {
			MinecraftMcpMod.LOGGER.error("Unhandled HTTP bridge error", t);
			try {
				send(exchange, 500, new BridgeException("INTERNAL_ERROR", 500,
						"internal bridge error", false, null).toJson());
			} catch (IOException ignored) {
			}
		} finally {
			exchange.close();
		}
	}

	private void route(HttpExchange exchange) throws IOException, BridgeException {
		URI uri = exchange.getRequestURI();
		String path = uri.getPath();
		String method = exchange.getRequestMethod();
		if (path.equals("/v1/info")) {
			requireMethod(method, "GET");
			requireNoQuery(uri);
			JsonObject args = new JsonObject();
			respond(exchange, 200, await(() -> operations.worldInfo(args)));
			return;
		}
		if (path.equals("/v1/operations")) {
			requireMethod(method, "POST");
			requireNoQuery(uri);
			JsonObject body = readJson(exchange);
			Json.only(body, Set.of("tool", "arguments"), "request");
			String tool = Json.string(body, "tool", true, null);
			JsonObject args = Json.object(body.get("arguments"), "arguments");
			JsonObject result;
			switch (tool) {
				case "get_block" -> result = await(() -> operations.getBlock(args));
				case "get_player_position" -> result = await(() -> operations.playerPosition(args));
				default -> result = await(() -> operations.acceptAsync(tool, args));
			}
			respond(exchange, result.has("operation") ? 202 : 200, result);
			return;
		}
		if (path.startsWith("/v1/operations/")) {
			String rest = path.substring("/v1/operations/".length());
			int slash = rest.indexOf('/');
			String operationId = slash < 0 ? rest : rest.substring(0, slash);
			String action = slash < 0 ? "" : rest.substring(slash + 1);
			if (operationId.isBlank() || operationId.contains("/")) {
				throw notFound();
			}
			Map<String, String> query = parseQuery(uri);
			if (action.isEmpty()) {
				requireMethod(method, "GET");
				requireQuery(query, Set.of("world_session_id"));
				JsonObject args = new JsonObject();
				args.addProperty("world_session_id", query.get("world_session_id"));
				args.addProperty("operation_id", operationId);
				respond(exchange, 200, operations.getOperation(args));
				return;
			}
			if (action.equals("cancel")) {
				requireMethod(method, "POST");
				requireNoQuery(uri);
				JsonObject body = readJson(exchange);
				Json.only(body, Set.of("world_session_id"), "request");
				String session = Json.string(body, "world_session_id", true, null);
				JsonObject args = new JsonObject();
				args.addProperty("world_session_id", session);
				args.addProperty("operation_id", operationId);
				respond(exchange, 200, await(() -> operations.cancelOperation(args)));
				return;
			}
			if (action.equals("errors")) {
				requireMethod(method, "GET");
				requireQuery(query, Set.of("world_session_id", "cursor"));
				JsonObject args = new JsonObject();
				args.addProperty("world_session_id", query.get("world_session_id"));
				args.addProperty("operation_id", operationId);
				if (query.containsKey("cursor")) {
					args.addProperty("cursor", query.get("cursor"));
				}
				respond(exchange, 200, operations.getErrors(args));
				return;
			}
			if (action.equals("result")) {
				requireMethod(method, "GET");
				requireQuery(query, Set.of("world_session_id", "cursor"));
				JsonObject args = new JsonObject();
				args.addProperty("world_session_id", query.get("world_session_id"));
				args.addProperty("operation_id", operationId);
				if (query.containsKey("cursor")) {
					args.addProperty("cursor", query.get("cursor"));
				}
				respond(exchange, 200, operations.getQueryResult(args));
				return;
			}
		}
		throw notFound();
	}

	private JsonObject await(OperationManager.ServerCall call) throws BridgeException {
		try {
			return operations.callOnServer(call).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			JsonObject details = new JsonObject();
			details.addProperty("accepted_unknown", true);
			throw new BridgeException("SERVER_TICK_TIMEOUT", 503,
					"server tick did not process the request in time; the request may still be accepted",
					true, details);
		} catch (java.util.concurrent.ExecutionException e) {
			if (e.getCause() instanceof BridgeException bridge) {
				throw bridge;
			}
			throw new BridgeException("INTERNAL_ERROR", 500, "internal bridge error", false, null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BridgeException("INTERNAL_ERROR", 500, "request interrupted", false, null);
		}
	}

	private void respond(HttpExchange exchange, int status, JsonObject body) throws IOException {
		send(exchange, status, body);
	}

	private JsonObject readJson(HttpExchange exchange) throws IOException, BridgeException {
		String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
			throw new BridgeException("INVALID_ARGUMENT", 400,
					"Content-Type must be application/json", false, null);
		}
		String length = exchange.getRequestHeaders().getFirst("Content-Length");
		if (length != null) {
			try {
				if (Long.parseLong(length) > MAX_REQUEST_BYTES) {
					throw new BridgeException("REQUEST_TOO_LARGE", 413, "request body exceeds 2 MiB", false, null);
				}
			} catch (NumberFormatException e) {
				throw BridgeException.invalid("invalid Content-Length");
			}
		}
		byte[] bytes;
		try (InputStream input = exchange.getRequestBody()) {
			bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
		}
		if (bytes.length > MAX_REQUEST_BYTES) {
			throw new BridgeException("REQUEST_TOO_LARGE", 413, "request body exceeds 2 MiB", false, null);
		}
		try {
			JsonObject parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
					.getAsJsonObject();
			if (parsed == null) {
				throw BridgeException.invalid("request body must be a JSON object");
			}
			return parsed;
		} catch (RuntimeException e) {
			throw BridgeException.invalid("request body is not a JSON object");
		}
	}

	private void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
		byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private boolean authorized(HttpExchange exchange) {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null || !header.startsWith("Bearer ")) {
			return false;
		}
		String provided = header.substring("Bearer ".length());
		return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), tokenBytes);
	}

	private boolean hostAllowed(HttpExchange exchange) {
		String host = exchange.getRequestHeaders().getFirst("Host");
		if (host == null) {
			return false;
		}
		return host.equalsIgnoreCase("127.0.0.1:" + config.port)
				|| host.equalsIgnoreCase("localhost:" + config.port);
	}

	private boolean rateAllowed() {
		long now = System.nanoTime();
		synchronized (this) {
			if (now - rateWindow >= 1_000_000_000L) {
				rateWindow = now;
				rateCount = 0;
			}
			rateCount++;
			return rateCount <= RATE_LIMIT_PER_SECOND;
		}
	}

	private Map<String, String> parseQuery(URI uri) throws BridgeException {
		Map<String, String> result = new HashMap<>();
		String raw = uri.getRawQuery();
		if (raw == null || raw.isEmpty()) {
			return result;
		}
		if (raw.length() > MAX_QUERY_DECODED_BYTES * 3) {
			throw BridgeException.invalid("query string is too long");
		}
		for (String part : raw.split("&")) {
			int equals = part.indexOf('=');
			if (equals < 1) {
				throw BridgeException.invalid("malformed query string");
			}
			String key = URLDecoder.decode(part.substring(0, equals), StandardCharsets.UTF_8);
			String value = URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
			if (key.isEmpty() || value.isEmpty() || result.putIfAbsent(key, value) != null) {
				throw BridgeException.invalid("query parameters must appear once with non-empty values");
			}
			if (result.get(key).getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_DECODED_BYTES) {
				throw BridgeException.invalid("query value is too long");
			}
		}
		return result;
	}

	private static void requireMethod(String actual, String expected) throws BridgeException {
		if (!actual.equals(expected)) {
			throw new BridgeException("INVALID_ARGUMENT", 400, "method is not allowed for this route", false, null);
		}
	}

	private static void requireNoQuery(URI uri) throws BridgeException {
		if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
			throw BridgeException.invalid("this route does not accept query parameters");
		}
	}

	private static void requireQuery(Map<String, String> query, Set<String> allowed) throws BridgeException {
		if (!allowed.containsAll(query.keySet())) {
			throw BridgeException.invalid("unknown query parameter");
		}
		if (!query.containsKey("world_session_id")) {
			throw BridgeException.invalid("world_session_id query parameter is required");
		}
	}

	private static BridgeException notFound() {
		return new BridgeException("INVALID_ARGUMENT", 404, "route not found", false, null);
	}
}
