package dev.matybachy.srvmr.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * How the entities moved since the frame DLSS last upscaled, as boxes for {@link MotionPass}.
 * <p>
 * The game draws an entity at its position between two ticks, {@code getPosition(partialTick)}; between two frames
 * every point of it moves by the difference of those positions. A pixel whose point of the world lies inside an
 * entity's box is given that move: mobs, players, items on the ground, arrows, minecarts, boats and Create's contraptions
 * are put back where they were, not smeared over what they pass. What an entity does in place, a turning head or swinging
 * legs, is not in the box's move; particles are not entities and are not in it either.
 * <p>
 * Boxes are the entity's hit box at the position it is drawn at, grown sideways and upwards by a margin, since models
 * stick out of it, but not downwards, so that the ground a mob walks on keeps still. Only entities that moved and are
 * in view are listed, nearest first, at most {@link #MAX}, relative to the camera. The camera's own entity is left out
 * in first person: its box holds the camera, and with it whatever is right in front of it.
 * <p>
 * Every entity is looked at every frame, so this allocates nothing for an entity it has seen before.
 */
final class EntityMotion {

	static final int MAX = 256;
	/** Floats a box takes: min, max and move, each a vec4. */
	static final int FLOATS = 12;
	/** Further than this an entity is not worth a box; a jump further than this is a teleport, not a move. */
	private static final double FAR = 128.0;
	private static final double TELEPORT = 32.0;

	/** Per entity id: where it is drawn now (0..2), where it was drawn in the last upscaled frame (3..5), whether it was (6), the frame it was last seen (7). */
	private static final Int2ObjectMap<double[]> RECORDS = new Int2ObjectOpenHashMap<>();
	private static long frame;

	/** Moved entities of this frame: distance, then min, max and move. */
	private static double[] candidates = new double[64 * 10];
	private static int[] order = new int[64];
	private static int candidateCount;

	static final float[] BOXES = new float[MAX * FLOATS];
	static int count;

	private EntityMotion() {
	}

	/** At the start of drawing the world: where every entity is drawn, and the boxes of those that moved. */
	static void capture(ClientLevel level, Entity camera, boolean firstPerson, float partialTick, double cameraX,
			double cameraY, double cameraZ, Matrix4f modelView, Matrix4f projection) {
		frame++;
		candidateCount = 0;
		Frustum frustum = new Frustum(modelView, projection);
		frustum.prepare(cameraX, cameraY, cameraZ);
		for (Entity entity : level.entitiesForRendering()) {
			Vec3 drawn = entity.getPosition(partialTick);
			double[] record = RECORDS.get(entity.getId());
			if (record == null) {
				record = new double[8];
				RECORDS.put(entity.getId(), record);
			}
			record[0] = drawn.x;
			record[1] = drawn.y;
			record[2] = drawn.z;
			record[7] = frame;
			if (record[6] == 0.0 || firstPerson && entity == camera) continue;
			double dx = drawn.x - record[3];
			double dy = drawn.y - record[4];
			double dz = drawn.z - record[5];
			double move = dx * dx + dy * dy + dz * dz;
			if (move < 1.0e-8 || move > TELEPORT * TELEPORT) continue;
			double ox = drawn.x - cameraX;
			double oy = drawn.y - cameraY;
			double oz = drawn.z - cameraZ;
			double distance = ox * ox + oy * oy + oz * oz;
			if (distance > FAR * FAR) continue;
			Vec3 at = entity.position();
			double margin = 0.25 + 0.15 * Math.max(entity.getBbWidth(), entity.getBbHeight());
			AABB hit = entity.getBoundingBox().move(drawn.x - at.x, drawn.y - at.y, drawn.z - at.z);
			AABB box = new AABB(hit.minX - margin, hit.minY - 0.02, hit.minZ - margin,
					hit.maxX + margin, hit.maxY + margin, hit.maxZ + margin);
			if (!frustum.isVisible(box)) continue;
			add(distance, box, dx, dy, dz);
		}
		order = ensure(order, candidateCount);
		for (int i = 0; i < candidateCount; i++) order[i] = i;
		IntArrays.quickSort(order, 0, candidateCount, (a, b) -> Double.compare(candidates[a * 10], candidates[b * 10]));
		count = Math.min(candidateCount, MAX);
		for (int i = 0; i < count; i++) {
			int from = order[i] * 10;
			int at = i * FLOATS;
			BOXES[at] = (float) (candidates[from + 1] - cameraX);
			BOXES[at + 1] = (float) (candidates[from + 2] - cameraY);
			BOXES[at + 2] = (float) (candidates[from + 3] - cameraZ);
			BOXES[at + 3] = 0.0F;
			BOXES[at + 4] = (float) (candidates[from + 4] - cameraX);
			BOXES[at + 5] = (float) (candidates[from + 5] - cameraY);
			BOXES[at + 6] = (float) (candidates[from + 6] - cameraZ);
			BOXES[at + 7] = 0.0F;
			BOXES[at + 8] = (float) candidates[from + 7];
			BOXES[at + 9] = (float) candidates[from + 8];
			BOXES[at + 10] = (float) candidates[from + 9];
			BOXES[at + 11] = 0.0F;
		}
	}

	private static void add(double distance, AABB box, double dx, double dy, double dz) {
		int at = candidateCount * 10;
		if (at + 10 > candidates.length) candidates = Arrays.copyOf(candidates, candidates.length * 2);
		candidates[at] = distance;
		candidates[at + 1] = box.minX;
		candidates[at + 2] = box.minY;
		candidates[at + 3] = box.minZ;
		candidates[at + 4] = box.maxX;
		candidates[at + 5] = box.maxY;
		candidates[at + 6] = box.maxZ;
		candidates[at + 7] = dx;
		candidates[at + 8] = dy;
		candidates[at + 9] = dz;
		candidateCount++;
	}

	private static int[] ensure(int[] array, int size) {
		return array.length >= size ? array : new int[Math.max(size, array.length * 2)];
	}

	/** The frame was upscaled: its positions are what the next frame moves from; entities not seen are forgotten. */
	static void commit() {
		ObjectIterator<Int2ObjectMap.Entry<double[]>> entries = RECORDS.int2ObjectEntrySet().iterator();
		while (entries.hasNext()) {
			double[] record = entries.next().getValue();
			if (record[7] != frame) {
				entries.remove();
				continue;
			}
			record[3] = record[0];
			record[4] = record[1];
			record[5] = record[2];
			record[6] = 1.0;
		}
	}

	/** The frame was not upscaled: the next one starts afresh, with nothing moving. */
	static void forget() {
		RECORDS.clear();
		count = 0;
	}
}
