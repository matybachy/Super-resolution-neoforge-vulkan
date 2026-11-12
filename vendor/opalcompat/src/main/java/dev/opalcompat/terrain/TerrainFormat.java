package dev.opalcompat.terrain;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * VulkanMod Reforged's compressed terrain format with the elements shader packs read appended.
 * <p>
 * VulkanMod fixes the terrain stride when its classes initialise ({@code DrawBuffers.VERTEX_SIZE},
 * {@code POS_OFFSET}) and compares the format by identity in several places, so the extension is
 * decided once and is installed as {@code CustomVertexFormat.COMPRESSED_TERRAIN} itself. That happens
 * while VulkanMod starts, before this mod is constructed, so nothing of the mod's may be asked here.
 * It is on whenever there is a pack in {@code shaderpacks/} to turn on, whether one is selected or not:
 * a pack can then go on and off in the game, at the price of a wider mesh without one. A game started
 * with an empty folder, or with {@code -Dopalcompat.compactTerrain=true}, or the test runs that name no
 * pack, have VulkanMod's own format, and a pack added later needs a restart.
 * <p>
 * Element types are the combinations VulkanMod's {@code GraphicsPipeline} can turn into attribute
 * formats, because its own terrain pipeline is built against this format too.
 */
public final class TerrainFormat {

	/** Whether the mesh carries the extension this session. Read once. */
	public static final boolean EXTENDED = packsAvailable();

	public static final int BASE_STRIDE = 20;
	public static final int STRIDE = 40;

	public static final int OFFSET_BLOCK_ID = 20;
	public static final int OFFSET_MID_TEX = 24;
	public static final int OFFSET_NORMAL = 28;
	public static final int OFFSET_TANGENT = 32;
	public static final int OFFSET_MID_BLOCK = 36;

	private TerrainFormat() {
	}

	private static boolean packsAvailable() {
		if (Boolean.getBoolean("opalcompat.compactTerrain")) return false;
		String forced = System.getProperty("opalcompat.pack");
		if (forced != null) return !forced.isBlank();
		try (Stream<Path> files = Files.list(FMLPaths.GAMEDIR.get().resolve("shaderpacks"))) {
			return files.anyMatch(path -> Files.isDirectory(path.resolve("shaders"))
					|| path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"));
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** The compressed format VulkanMod built, with the five elements appended. */
	public static VertexFormat extend(VertexFormat base) {
		VertexFormat.Builder builder = VertexFormat.builder();
		for (String name : base.getElementAttributeNames()) {
			builder.add(name, base.getElements().get(base.getElementAttributeNames().indexOf(name)));
		}
		builder.add("BlockId", new VertexFormatElement(8, 0, VertexFormatElement.Type.INT, VertexFormatElement.Usage.GENERIC, 1));
		builder.add("MidTexCoord", new VertexFormatElement(9, 0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.UV, 2));
		builder.add("Normal", new VertexFormatElement(10, 0, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.NORMAL, 4));
		builder.add("Tangent", new VertexFormatElement(11, 0, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.NORMAL, 4));
		builder.add("MidBlock", new VertexFormatElement(12, 0, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.POSITION, 4));
		VertexFormat extended = builder.build();
		if (extended.getVertexSize() != STRIDE) {
			throw new IllegalStateException("Extended terrain format is " + extended.getVertexSize() + " bytes, expected " + STRIDE);
		}
		return extended;
	}
}
