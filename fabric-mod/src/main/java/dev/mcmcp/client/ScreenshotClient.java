package dev.mcmcp.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.mcmcp.screenshot.ScreenshotPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Captures the requested client's current render target without changing its screen. */
public final class ScreenshotClient {
	private static final long TIMEOUT = 25_000_000_000L;
	private static final long MAX_PIXELS = 8_000_000L;
	private Pending pending;
	private final ThreadPoolExecutor encoder = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
		Thread thread = new Thread(runnable, "mcp-screenshot-encoder"); thread.setDaemon(true); return thread;
	});
	private static final class Pending {
		final String session, id; final ClientPacketListener connection; final ClientLevel level; final long deadline;
		int offset; boolean reading; byte[] bytes;
		Pending(ScreenshotPayload.Request request, Minecraft client) { session = request.session(); id = request.id(); connection = client.getConnection(); level = client.level; deadline = System.nanoTime() + TIMEOUT; }
	}
	public void register() {
		ClientPlayNetworking.registerGlobalReceiver(ScreenshotPayload.Request.TYPE, (request, context) -> context.client().execute(() -> receive(context.client(), request)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> clear(client)));
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}
	private void receive(Minecraft client, ScreenshotPayload.Request request) {
		if (request.command() == ScreenshotPayload.Command.CANCEL) {
			if (pending != null && pending.id.equals(request.id()) && pending.session.equals(request.session())) clear(client);
			return;
		}
		if (client.level == null || client.player == null || client.getConnection() == null || pending != null) {
			reply(request.session(), request.id(), ScreenshotPayload.Response.ERROR); return;
		}
		pending = new Pending(request, client);
	}
	private void tick(Minecraft client) {
		Pending p = pending; if (p == null) return;
		if (client.getConnection() != p.connection || client.level != p.level || client.player == null) { clear(client); return; }
		if (System.nanoTime() >= p.deadline) { reply(p.session, p.id, ScreenshotPayload.Response.TIMEOUT); clear(client); return; }
		if (p.bytes != null) {
			for (int sent = 0; sent < 4 && p.offset < p.bytes.length; sent++) {
				int end = Math.min(p.offset + ScreenshotPayload.CHUNK, p.bytes.length);
				ClientPlayNetworking.send(new ScreenshotPayload.Reply(p.session, p.id, ScreenshotPayload.Response.DATA, p.bytes.length, p.offset, Arrays.copyOfRange(p.bytes, p.offset, end)));
				p.offset = end;
			}
			if (p.offset == p.bytes.length) clear(client);
			return;
		}
		if (p.reading) return;
		p.reading = true;
		RenderTarget target = client.gameRenderer.mainRenderTarget();
		if (target.width <= 0 || target.height <= 0 || (long) target.width * target.height > MAX_PIXELS) { reply(p.session, p.id, ScreenshotPayload.Response.ERROR); clear(client); return; }
		try { Screenshot.takeScreenshot(target, image -> {
			try { encoder.execute(() -> encode(client, p, image)); }
			catch (RejectedExecutionException e) {
				image.close();
				client.execute(() -> { if (pending == p) { reply(p.session, p.id, ScreenshotPayload.Response.ERROR); clear(client); } });
			}
		}); }
		catch (RuntimeException e) { reply(p.session, p.id, ScreenshotPayload.Response.ERROR); clear(client); }
	}
	private void encode(Minecraft client, Pending p, NativeImage image) {
		byte[] bytes = null;
		try (image) {
			int w = image.getWidth(), h = image.getHeight();
			if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) throw new IOException("image too large");
			BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) copy.setRGB(x, y, image.getPixel(x, y));
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			OutputStream bounded = new OutputStream() {
				@Override public void write(int value) throws IOException { if (output.size() >= ScreenshotPayload.MAX_IMAGE) throw new IOException("image too large"); output.write(value); }
				@Override public void write(byte[] value, int offset, int length) throws IOException { if (length > ScreenshotPayload.MAX_IMAGE - output.size()) throw new IOException("image too large"); output.write(value, offset, length); }
			};
			try (MemoryCacheImageOutputStream memory = new MemoryCacheImageOutputStream(bounded)) {
				if (!ImageIO.write(copy, "png", memory)) throw new IOException("PNG encoder unavailable");
				memory.flush();
			}
			bytes = output.toByteArray();
		} catch (IOException | RuntimeException ignored) { }
		byte[] result = bytes;
		client.execute(() -> {
			if (pending != p || client.getConnection() != p.connection || client.level != p.level || System.nanoTime() >= p.deadline) return;
			if (result == null || result.length == 0) { reply(p.session, p.id, ScreenshotPayload.Response.ERROR); clear(client); }
			else p.bytes = result;
		});
	}
	private void reply(String session, String id, ScreenshotPayload.Response response) {
		if (Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(ScreenshotPayload.Reply.TYPE)) ClientPlayNetworking.send(new ScreenshotPayload.Reply(session, id, response, 0, 0, new byte[0]));
	}
	private void clear(Minecraft client) { pending = null; }
}
