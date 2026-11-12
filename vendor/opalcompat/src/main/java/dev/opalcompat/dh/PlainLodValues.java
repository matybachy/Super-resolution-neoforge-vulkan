package dev.opalcompat.dh;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogDirection;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.FogRenderParamFactory;
import com.seibel.distanthorizons.core.util.RenderUtil;
import dev.opalcompat.render.DistantPlain;

import java.awt.Color;

/**
 * DH's settings for its far terrain with no pack, read as its {@code GlDhTerrainShaderProgram},
 * {@code GlDhFogShader} and {@code GlDhFarFadeShader} read them.
 */
final class PlainLodValues {

	private static final DistantPlain.Values VALUES = new DistantPlain.Values();

	private PlainLodValues() {
	}

	static DistantPlain.Values read(RenderParams params) {
		DistantPlain.Values values = VALUES;
		values.worldYOffset = params.worldYOffset;
		values.microOffset = 0.01F;
		float curve = Config.Client.Advanced.Graphics.Experimental.earthCurveRatio.get();
		values.earthRadius = curve < -1.0F || curve > 1.0F ? 6371000.0F / curve : 0.0F;
		float clip = RenderUtil.getNearClipPlaneInBlocks();
		if (!Config.Client.Advanced.Debugging.lodOnlyMode.get()) clip += 16.0F;
		values.clipDistance = clip;
		values.dither = Config.Client.Advanced.Graphics.Quality.ditherDhFade.get();
		values.whiteWorld = Config.Client.Advanced.Debugging.enableWhiteWorld.get();
		values.noise = Config.Client.Advanced.Graphics.NoiseTexture.enableNoiseTexture.get();
		values.noiseSteps = Config.Client.Advanced.Graphics.NoiseTexture.noiseSteps.get();
		values.noiseIntensity = Config.Client.Advanced.Graphics.NoiseTexture.noiseIntensity.get();
		values.noiseDropoff = Config.Client.Advanced.Graphics.NoiseTexture.noiseDropoff.get();
		readFog(params, values);
		float far = RenderUtil.getFarClipPlaneDistanceInBlocks();
		values.fadeStart = far * 0.5F;
		values.fadeEnd = far * 0.9F;
		return values;
	}

	private static void readFog(RenderParams params, DistantPlain.Values values) {
		// As LodRenderer decides it.
		Boolean forced = Config.Client.Advanced.Graphics.Fog.enableDhFog.getApiValue();
		values.fog = forced != null ? forced : Config.Client.Advanced.Graphics.Fog.enableDhFog.get() || params.vanillaFogEnabled;
		if (!values.fog) return;

		var fog = FogRenderParamFactory.getRenderParam(params).getFogRenderParam();
		Color colour = fog.getFogColor();
		values.fogRed = colour.getRed() / 255.0F;
		values.fogGreen = colour.getGreen() / 255.0F;
		values.fogBlue = colour.getBlue() / 255.0F;
		values.fogScale = 1.0F / (Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() * 16);
		values.fogVerticalScale = 1.0F / params.clientLevelWrapper.getMaxHeight();
		values.fogFalloff = fog.getFarFogFalloff().value;
		values.farFogStart = fog.getFarFogStartPercent();
		values.farFogLength = fog.getFarFogEndPercent() - fog.getFarFogStartPercent();
		values.farFogMin = fog.getFarFogMinThickness();
		values.farFogRange = fog.getFarFogMaxThickness() - fog.getFarFogMinThickness();
		values.farFogDensity = fog.getFarFogDensity();

		EDhApiHeightFogMixMode mix = fog.getHeightFogMixingMode();
		EDhApiHeightFogDirection direction = fog.getHeightFogDirection();
		values.heightFogStart = fog.getHeightFogStartPercent();
		values.heightFogLength = fog.getHeightFogEndPercent() - fog.getHeightFogStartPercent();
		// The far fog's thickness and density, as GlDhFogShader hands them to its height fog: the same picture as on OpenGL.
		values.heightFogMin = fog.getFarFogMinThickness();
		values.heightFogRange = fog.getFarFogMaxThickness() - fog.getFarFogMinThickness();
		values.heightFogDensity = fog.getFarFogDensity();
		values.heightFog = mix != EDhApiHeightFogMixMode.SPHERICAL && mix != EDhApiHeightFogMixMode.CYLINDRICAL;
		values.heightFogFalloff = fog.getHeightFogFalloff().value;
		values.heightFogBase = fog.getHeightFogBaseHeight();
		values.heightFromCamera = direction.basedOnCamera;
		values.heightFogUp = direction.fogAppliesUp;
		values.heightFogDown = direction.fogAppliesDown;
		values.sphericalFog = mix == EDhApiHeightFogMixMode.SPHERICAL;
		values.heightFogMix = mix.value;
		values.cameraY = (float) params.exactCameraPosition.y;
	}
}
