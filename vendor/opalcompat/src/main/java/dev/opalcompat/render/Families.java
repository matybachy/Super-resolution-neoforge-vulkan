package dev.opalcompat.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.RenderStage;

import java.util.List;

/**
 * Which program of the pack a vanilla draw belongs to: the shader the game has set, and what the level
 * is doing at the moment. The table follows Iris 1.21.1 ({@code mixin/MixinGameRenderer},
 * {@code pipeline/programs/ShaderKey}), read by shader name because on VulkanMod Reforged the draw
 * is intercepted where it happens and the game's shader lookups are left alone.
 */
public final class Families {

	/** What the level is drawing, set by the mixins around each part of it. */
	public enum Phase {
		LEVEL, SKY, BLOCK_ENTITIES, HAND, PARTICLES, WEATHER, CLOUDS, SHADOW, SHADOW_BLOCK_ENTITIES;

		boolean light() {
			return this == SHADOW || this == SHADOW_BLOCK_ENTITIES;
		}
	}

	/**
	 * @param program the bare program name the pack is asked for
	 * @param bound   the vertex elements declared, only for the families that declare by format
	 */
	record Piece(String program, AlphaTest alpha, VertexInputs inputs, List<String> bound) {
	}

	/** Which of the sky's buffers is being drawn: only the order the game draws them in says. */
	static final class SkyOrder {
		private int textured;
		private int plainAfterTextured;

		void reset() {
			this.textured = 0;
			this.plainAfterTextured = 0;
		}
	}

	private Families() {
	}

	/** What {@code renderStage} reads during this draw. */
	static RenderStage stage(String shader, VertexFormat format, Phase phase, SkyOrder sky) {
		return switch (phase) {
			case SKY -> {
				if (shader.equals("position_tex_color")) yield RenderStage.CUSTOM_SKY;
				if (shader.equals("position_color")) yield RenderStage.SUNSET;
				if (format.contains(VertexFormatElement.UV0)) yield sky.textured++ == 0 ? RenderStage.SUN : RenderStage.MOON;
				if (sky.textured == 0) yield RenderStage.SKY;
				yield sky.plainAfterTextured++ == 0 ? RenderStage.STARS : RenderStage.VOID;
			}
			case BLOCK_ENTITIES -> RenderStage.BLOCK_ENTITIES;
			case HAND -> HandPass.opaque() ? RenderStage.HAND_SOLID : RenderStage.HAND_TRANSLUCENT;
			case PARTICLES -> RenderStage.PARTICLES;
			case WEATHER -> RenderStage.RAIN_SNOW;
			case CLOUDS -> RenderStage.CLOUDS;
			case SHADOW, SHADOW_BLOCK_ENTITIES -> RenderStage.NONE;
			case LEVEL -> {
				if (shader.startsWith("rendertype_lines")) yield RenderStage.OUTLINE;
				if (shader.equals("rendertype_crumbling")) yield RenderStage.DESTROY;
				if (shader.equals("position_tex")) yield RenderStage.WORLD_BORDER;
				if (shader.equals("position_color") || shader.equals("position")) yield RenderStage.DEBUG;
				yield RenderStage.ENTITIES;
			}
		};
	}

	/** @param blending whether the game has blending on, which tells a hand's or a particle's translucent half */
	static Piece resolve(String shader, VertexFormat format, Phase phase, boolean blending) {
		List<String> names = format.getElementAttributeNames();
		// The glow's silhouette belongs in the game's outline target, which it composes after the final
		// pass: VulkanMod draws it there, as it does without a pack.
		if (shader.equals("rendertype_outline")) return null;
		if (phase.light()) {
			return shadow(shader, names, phase == Phase.SHADOW_BLOCK_ENTITIES);
		}

		if (shader.startsWith("rendertype_lines")) {
			return new Piece("gbuffers_line", AlphaTest.OFF, VertexInputs.LINES, VertexInputs.LINES.elements());
		}
		if (shader.contains("glint")) {
			return new Piece("gbuffers_armor_glint", AlphaTest.NON_ZERO, VertexInputs.GLINT, VertexInputs.GLINT.elements());
		}
		if (shader.equals("rendertype_crumbling")) {
			return new Piece("gbuffers_damagedblock", AlphaTest.ONE_TENTH, VertexInputs.CRUMBLING, VertexInputs.CRUMBLING.elements());
		}
		if (shader.startsWith("rendertype_text") || shader.equals("position_color_tex_lightmap")) {
			return switch (phase) {
				case HAND -> new Piece("gbuffers_hand", AlphaTest.NON_ZERO, VertexInputs.GLYPH, names);
				case BLOCK_ENTITIES -> new Piece("gbuffers_block_translucent", AlphaTest.ONE_TENTH, VertexInputs.GLYPH, names);
				default -> new Piece("gbuffers_entities_translucent", AlphaTest.ONE_TENTH, VertexInputs.GLYPH, names);
			};
		}
		if (phase == Phase.HAND) {
			return mesh(HandPass.opaque() ? "gbuffers_hand" : "gbuffers_hand_water", AlphaTest.ONE_TENTH);
		}
		if (shader.equals("particle")) {
			if (phase == Phase.WEATHER) {
				return new Piece("gbuffers_weather", AlphaTest.ONE_TENTH, VertexInputs.PARTICLE, VertexInputs.PARTICLE.elements());
			}
			return new Piece(blending ? "gbuffers_particles_translucent" : "gbuffers_particles", AlphaTest.ONE_TENTH,
					VertexInputs.PARTICLE, VertexInputs.PARTICLE.elements());
		}
		if (phase == Phase.SKY) {
			boolean textured = names.contains("UV0");
			return new Piece(textured ? "gbuffers_skytextured" : "gbuffers_skybasic",
					textured ? AlphaTest.OFF : AlphaTest.NON_ZERO, VertexInputs.SKY, names);
		}

		return switch (shader) {
			case "position" -> mesh("gbuffers_basic", AlphaTest.OFF);
			case "position_color" -> mesh("gbuffers_basic", AlphaTest.NON_ZERO);
			case "position_tex" -> mesh("gbuffers_textured", AlphaTest.NON_ZERO);
			case "position_tex_color" -> mesh("gbuffers_textured", AlphaTest.ONE_TENTH);
			case "rendertype_clouds" -> mesh("gbuffers_clouds", AlphaTest.ONE_TENTH);
			case "rendertype_leash" -> mesh("gbuffers_basic", AlphaTest.OFF);
			case "rendertype_lightning" -> mesh("gbuffers_lightning", AlphaTest.OFF);
			case "rendertype_beacon_beam" -> mesh("gbuffers_beaconbeam", AlphaTest.OFF);
			// Mekanism's own shaders, by name whichever namespace they are in. What Iris gives them
			// (pathways/LightningHandler, pipeline/programs/ShaderAccess): its flame, its fusion reactor, its suit.
			case "rendertype_flame" -> mesh("gbuffers_spidereyes", AlphaTest.ONE_TENTH);
			case "rendertype_sps" -> mesh("gbuffers_spidereyes", AlphaTest.OFF);
			case "rendertype_mekasuit" -> mesh("gbuffers_entities_translucent", AlphaTest.ONE_TENTH);
			// What a chunk layer's shader draws here is a block on the move: a piston, a falling block, a mod's
			// model through a block render type. Under Iris that is MOVING_BLOCK, whoever owns it.
			case "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent",
					"rendertype_translucent_moving_block", "rendertype_tripwire" -> mesh("gbuffers_block", AlphaTest.ONE_TENTH);
			case "rendertype_entity_alpha" -> mesh("gbuffers_entities", AlphaTest.ONE_TENTH);
			case "rendertype_eyes" -> phase == Phase.BLOCK_ENTITIES ? mesh("gbuffers_block", AlphaTest.ONE_TENTH)
					: mesh("gbuffers_spidereyes", AlphaTest.NON_ZERO);
			// Blended, and all the same in the opaque program.
			case "rendertype_energy_swirl", "rendertype_entity_shadow" ->
					mesh(phase == Phase.BLOCK_ENTITIES ? "gbuffers_block" : "gbuffers_entities", AlphaTest.ONE_TENTH);
			case "rendertype_water_mask" -> phase == Phase.BLOCK_ENTITIES ? mesh("gbuffers_block", AlphaTest.ONE_TENTH)
					: mesh("gbuffers_entities", AlphaTest.OFF);
			case "rendertype_entity_translucent_emissive" ->
					mesh(phase == Phase.BLOCK_ENTITIES ? "gbuffers_block" : "gbuffers_spidereyes", AlphaTest.ONE_TENTH);
			default -> world(shader, phase, blending);
		};
	}

	/** The entities and every model-shaped piece: which of them depends on who is being drawn. */
	private static Piece world(String shader, Phase phase, boolean blending) {
		boolean translucent = shader.contains("translucent") || blending;
		String program = switch (phase) {
			case BLOCK_ENTITIES -> translucent ? "gbuffers_block_translucent" : "gbuffers_block";
			default -> translucent ? "gbuffers_entities_translucent" : "gbuffers_entities";
		};
		boolean solid = phase != Phase.BLOCK_ENTITIES && shader.contains("solid") && !blending;
		return mesh(program, solid ? AlphaTest.OFF : AlphaTest.ONE_TENTH);
	}

	private static Piece shadow(String shader, List<String> names, boolean blockEntity) {
		if (shader.contains("glint")) {
			return null; // Iris draws no glint into the map.
		}
		// Iris casts no light beam either (mixin/shadows/MixinBeaconRenderer).
		if (shader.equals("particle") || shader.startsWith("rendertype_lines") || shader.equals("rendertype_beacon_beam")) {
			return null;
		}
		if (shader.startsWith("rendertype_text") || shader.equals("position_color_tex_lightmap")) {
			return new Piece("shadow_entities", AlphaTest.NON_ZERO, VertexInputs.GLYPH, names);
		}
		return switch (shader) {
			case "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped" -> mesh("shadow_cutout", AlphaTest.ONE_TENTH);
			case "rendertype_translucent", "rendertype_translucent_moving_block", "rendertype_tripwire" ->
					mesh("shadow_water", AlphaTest.ONE_TENTH);
			case "rendertype_lightning" -> mesh("shadow_lightning", AlphaTest.OFF);
			case "rendertype_leash", "position" -> mesh("shadow", AlphaTest.OFF);
			case "position_color", "position_tex" -> mesh("shadow", AlphaTest.NON_ZERO);
			case "position_tex_color", "rendertype_clouds" -> mesh("shadow", AlphaTest.ONE_TENTH);
			case "rendertype_energy_swirl", "rendertype_entity_shadow", "rendertype_water_mask", "rendertype_eyes" ->
					mesh("shadow_entities", AlphaTest.ONE_TENTH);
			default -> mesh(blockEntity ? "shadow_block" : "shadow_entities", AlphaTest.ONE_TENTH);
		};
	}

	/** The entity prologue declares everything a vanilla format can carry; what this one has not got is a constant. */
	private static Piece mesh(String program, AlphaTest alpha) {
		return new Piece(program, alpha, VertexInputs.ENTITY, VertexInputs.ENTITY.elements());
	}
}
