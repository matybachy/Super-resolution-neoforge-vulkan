package dev.opalcompat.terrain;

import dev.opalcompat.OpalCompat;
import dev.opalcompat.mixin.ChunkStatusMapAccessor;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.vulkanmod.render.chunk.ChunkStatusMap;
import net.vulkanmod.render.chunk.WorldRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * When a chunk section may be built: vanilla's rule where VulkanMod Reforged's own would leave it unbuilt.
 * <p>
 * VulkanMod Reforged builds a section only once {@code ChunkStatusMap.chunkRenderReady} says its column and all eight around it
 * have their blocks ({@code DATA_READY}, from {@code ClientLevel.onChunkLoaded}) and their light ({@code LIGHT_READY}, from
 * {@code ClientPacketListener.applyLightData}). Otherwise {@code RenderSection.createCompileTask} returns null and the section
 * stays unbuilt, however often it is marked dirty: its blocks are in the level, Jade names them and their outline is drawn, and
 * nothing is drawn of them, with a shader pack or without one. The user's logs show both halves of it around the place they join
 * a server: 21 sections ready every frame and none built ({@code rQ 21}, {@code prep 64x}, {@code build 0x}, 2026-11-11 17:46),
 * and a column whose neighbours were all loaded while three of the nine never got their light (17:55).
 * <p>
 * Vanilla does not wait for either: {@code SectionRenderDispatcher.RenderSection.hasAllNeighbors} asks only that the chunks on the
 * four sides exist, and a light update marks the sections dirty again ({@code ClientPacketListener.readSectionList}). So where
 * VulkanMod Reforged says no and vanilla would say yes, the section is built, at once where the column has its light, and
 * otherwise once the game has no light left to apply or five seconds have gone by, so that a section that is about to get its
 * light is not built twice. Off with {@code chunkFlow=false}, as the rest of {@link ChunkFlow}.
 */
public final class ChunkReadiness {

	private static final long LIGHT_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
	private static final int MOST_LOGGED = 10;

	private static final Long2LongOpenHashMap WAITING = new Long2LongOpenHashMap();
	private static int logged;
	private static long built;

	private ChunkReadiness() {
	}

	/**
	 * Render thread, from {@code RenderSection.createCompileTask}, for every section about to be built.
	 *
	 * @param vulkanMod what VulkanMod Reforged's map said
	 */
	public static boolean ready(int chunkX, int chunkZ, boolean vulkanMod) {
		if (vulkanMod || !ChunkFlow.ENABLED) return vulkanMod;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return false;
		ClientChunkCache chunks = level.getChunkSource();
		// Vanilla's rule. Short of it is the edge of the loaded area, where the chunks are still to come.
		if (!has(chunks, chunkX, chunkZ) || !has(chunks, chunkX - 1, chunkZ) || !has(chunks, chunkX + 1, chunkZ)
				|| !has(chunks, chunkX, chunkZ - 1) || !has(chunks, chunkX, chunkZ + 1)) {
			return false;
		}
		long column = ChunkPos.asLong(chunkX, chunkZ);
		long now = System.nanoTime();
		boolean lit = chunks.getLightEngine().lightOnInSection(SectionPos.of(chunkX, 0, chunkZ));
		if (!lit && !level.isLightUpdateQueueEmpty()) {
			if (!WAITING.containsKey(column)) WAITING.put(column, now);
			if (now - WAITING.get(column) < LIGHT_WAIT_NANOS) {
				// Asked again next frame, whether or not the player moves.
				WorldRenderer renderer = WorldRenderer.getInstance();
				if (renderer != null) renderer.scheduleGraphUpdate();
				return false;
			}
		}
		WAITING.remove(column);
		if (WAITING.size() > 4096) WAITING.clear();
		built++;
		if (logged < MOST_LOGGED) {
			logged++;
			OpalCompat.LOGGER.info("[chunks] chunk {} {} built as vanilla would build it, which VulkanMod Reforged held back: its flags {}, {}{}",
					chunkX, chunkZ, flags(chunkX, chunkZ), around(level, chunks, chunkX, chunkZ),
					logged == MOST_LOGGED ? "; no more of these lines are written" : "");
		}
		return true;
	}

	/** Sections built that VulkanMod Reforged held back, since the start. */
	public static long built() {
		return built;
	}

	private static boolean has(ClientChunkCache chunks, int chunkX, int chunkZ) {
		return chunks.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
	}

	private static String flags(int chunkX, int chunkZ) {
		ChunkStatusMap status = ChunkStatusMap.INSTANCE;
		if (status == null) return "-";
		int value = ((ChunkStatusMapAccessor) status).opalcompat$map().get(ChunkPos.asLong(chunkX, chunkZ)) & 0xFF;
		return Integer.toBinaryString(value) + " of " + Integer.toBinaryString(ChunkStatusMap.ALL_FLAGS);
	}

	/** Which of the nine columns the game has not got, and which it has without light. */
	private static String around(ClientLevel level, ClientChunkCache chunks, int chunkX, int chunkZ) {
		List<String> missing = new ArrayList<>();
		List<String> unlit = new ArrayList<>();
		for (int x = chunkX - 1; x <= chunkX + 1; x++) {
			for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
				if (!has(chunks, x, z)) {
					missing.add(x + " " + z);
				} else if (!chunks.getLightEngine().lightOnInSection(SectionPos.of(x, 0, z))) {
					unlit.add(x + " " + z);
				}
			}
		}
		return "of the nine around it not loaded " + missing + ", loaded without light " + unlit
				+ (level.isLightUpdateQueueEmpty() ? ", no light left to apply" : ", light still being applied");
	}
}
