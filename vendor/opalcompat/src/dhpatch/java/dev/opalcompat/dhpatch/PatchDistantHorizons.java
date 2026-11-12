package dev.opalcompat.dhpatch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes a copy of the Distant Horizons 3.3.3 jar that selects Blaze3D in its own code rather than in its config, reports
 * Vulkan and calls no OpenGL of its own. The file name, the mod id and the version stay as they are, and so does every
 * entry of the jar these patches do not name.
 * <p>
 * 1.21.1 has no Blaze3D renderer: DH's {@code BlazeDh*} classes in this jar are empty and its {@code DependencySetup}
 * has no definition for that engine. The copy therefore only runs with Opal Compat on VulkanMod Reforged, whose
 * {@code DhDependencySetupMixin} hands DH the Vulkan renderer ahead of that code, and whose {@code DhStartMixin} takes
 * the Iris check at the start out of the way.
 * <p>
 * Every patch must find exactly what it expects, or nothing is written: the tool is only good for this one version.
 */
public final class PatchDistantHorizons {

	private static final String ENGINE = "com/seibel/distanthorizons/api/enums/config/EDhApiRenderingEngine";
	private static final String API = "com/seibel/distanthorizons/api/enums/config/EDhApiRenderingApi";

	private static final String NOTICE_ENTRY = "META-INF/OPALCOMPAT-PATCH.txt";
	private static final String NOTICE = """
			This is a modified copy of Distant Horizons 3.3.3, changed by Opal Compat's PatchDistantHorizons on 2026-11-09.
			Distant Horizons is licensed under the LGPL; see LICENSE.txt and LICENSE.LESSER.txt.

			Changed, in the NeoForge side of the jar only:
			- AbstractModInitializer_neoforge.setUnsupportedConfigsBasedOnMcVersion: the engine is forced to BLAZE_3D, not OPEN_GL
			- VersionConstants.getDefaultRenderingEngine: BLAZE_3D, not OPEN_GL
			- MinecraftRenderWrapper_neoforge.getMcRenderingApi: VULKAN, not OPEN_GL
			- LightMapWrapper_neoforge.uploadLightmap: no OpenGL copy of the light map
			- NeoforgeClientProxy.afterLevelRenderEvent: no OpenGL query of the bound framebuffer

			It needs Opal Compat on VulkanMod Reforged, which draws the far terrain on Vulkan.
			""";

	private static final Map<String, Consumer<ClassNode>> PATCHES = new LinkedHashMap<>();

	static {
		PATCHES.put("com/seibel/distanthorizons/common/AbstractModInitializer_neoforge.class",
				node -> swapConstant(method(node, "setUnsupportedConfigsBasedOnMcVersion"), ENGINE, "OPEN_GL", "BLAZE_3D"));
		PATCHES.put("com/seibel/distanthorizons/common/wrappers/VersionConstants.class",
				node -> swapConstant(method(node, "getDefaultRenderingEngine"), ENGINE, "OPEN_GL", "BLAZE_3D"));
		PATCHES.put("com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper_neoforge.class",
				node -> swapConstant(method(node, "getMcRenderingApi"), API, "OPEN_GL", "VULKAN"));
		PATCHES.put("com/seibel/distanthorizons/common/wrappers/misc/LightMapWrapper_neoforge.class",
				node -> emptyBody(method(node, "uploadLightmap")));
		PATCHES.put("com/seibel/distanthorizons/neoforge/NeoforgeClientProxy.class",
				node -> noFramebufferQuery(method(node, "afterLevelRenderEvent")));
	}

	private PatchDistantHorizons() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 2) throw new IllegalArgumentException("Usage: PatchDistantHorizons <original jar> <patched jar>");
		Path original = Path.of(args[0]);
		Path patched = Path.of(args[1]);
		Files.createDirectories(patched.getParent());
		Path partial = patched.resolveSibling(patched.getFileName() + ".part");
		Set<String> waiting = new HashSet<>(PATCHES.keySet());

		try (ZipFile in = new ZipFile(original.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(partial))) {
			for (Enumeration<? extends ZipEntry> entries = in.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				byte[] bytes;
				try (var stream = in.getInputStream(entry)) {
					bytes = stream.readAllBytes();
				}
				Consumer<ClassNode> patch = PATCHES.get(entry.getName());
				if (patch != null) {
					bytes = apply(patch, bytes);
					waiting.remove(entry.getName());
				}
				write(out, entry, bytes);
			}
			ZipEntry notice = new ZipEntry(NOTICE_ENTRY);
			out.putNextEntry(notice);
			out.write(NOTICE.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}

		if (!waiting.isEmpty()) {
			Files.deleteIfExists(partial);
			throw new IllegalStateException("Not in this jar: " + waiting);
		}
		Files.move(partial, patched, StandardCopyOption.REPLACE_EXISTING);
		System.out.println("Wrote " + patched);
	}

	private static byte[] apply(Consumer<ClassNode> patch, byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		patch.accept(node);
		// Frames and limits as they were: no patch adds a branch or needs more of the stack.
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Each entry as the original had it, down to the time and whether it is compressed. */
	private static void write(ZipOutputStream out, ZipEntry entry, byte[] bytes) throws IOException {
		ZipEntry copy = new ZipEntry(entry.getName());
		copy.setTime(entry.getTime());
		if (entry.getMethod() == ZipEntry.STORED) {
			CRC32 crc = new CRC32();
			crc.update(bytes);
			copy.setMethod(ZipEntry.STORED);
			copy.setSize(bytes.length);
			copy.setCompressedSize(bytes.length);
			copy.setCrc(crc.getValue());
		}
		out.putNextEntry(copy);
		out.write(bytes);
		out.closeEntry();
	}

	private static MethodNode method(ClassNode node, String name) {
		List<MethodNode> found = node.methods.stream().filter(method -> method.name.equals(name)).toList();
		if (found.size() != 1) throw new IllegalStateException(found.size() + " methods " + name + " in " + node.name);
		return found.get(0);
	}

	private static void swapConstant(MethodNode method, String owner, String from, String to) {
		int swapped = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
					&& field.owner.equals(owner) && field.name.equals(from)) {
				field.name = to;
				swapped++;
			}
		}
		if (swapped != 1) throw new IllegalStateException(swapped + " reads of " + from + " in " + method.name);
	}

	/** For a method of no result: its arguments stay as they are, it just returns. */
	private static void emptyBody(MethodNode method) {
		if (!method.desc.endsWith(")V")) throw new IllegalStateException(method.name + " returns something");
		method.instructions.clear();
		method.tryCatchBlocks.clear();
		method.localVariables = null;
		method.instructions.add(new InsnNode(Opcodes.RETURN));
		method.maxStack = 0;
	}

	/** {@code GL33.glGetInteger(GL_FRAMEBUFFER_BINDING)} becomes -1, what DH holds when it has not asked. */
	private static void noFramebufferQuery(MethodNode method) {
		int replaced = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& call.owner.equals("org/lwjgl/opengl/GL33") && call.name.equals("glGetInteger")) {
				if (!(call.getPrevious() instanceof LdcInsnNode constant) || !Integer.valueOf(36006).equals(constant.cst)) {
					throw new IllegalStateException("glGetInteger of something else in " + method.name);
				}
				method.instructions.remove(constant);
				method.instructions.set(call, new InsnNode(Opcodes.ICONST_M1));
				replaced++;
				break;
			}
		}
		if (replaced != 1) throw new IllegalStateException("No framebuffer query in " + method.name);
	}
}
