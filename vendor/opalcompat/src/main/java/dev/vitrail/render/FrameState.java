// OpalCompat: Vitrail's FrameState (common/src/main/java/dev/vitrail/render/FrameState.java, commit 7a3cdcb)
// ported to Minecraft 1.21.1. Same fields, same published conventions; each read of the game goes
// through the 1.21.1 API instead of 26.x's render state objects and environment attributes. Where 1.21.1
// has no counterpart (end flash, texture filtering options, chunk fade) the value is what Iris 1.21.1
// publishes there. Matrices come from LevelCapture (the arguments of LevelRenderer.renderLevel).
// LGPL-3.0, see NOTICE and NOTICE-vitrail.
package dev.vitrail.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrail.pack.id.NameIds;
import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.uniform.values.FrameSmoothed;
import dev.opalcompat.render.LevelCapture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** Everything a pack's uniforms read about the frame, read from Minecraft 1.21.1 once per frame. */
public final class FrameState implements WorldState {

	private static final float COUNTER_WRAP = 3600.0F;
	private static final int FRAME_WRAP = 720720;
	private static final Vector3f NOTHING_SELECTED = new Vector3f(-256.0F);
	private static final float MAX_HUNGER = 20.0F;
	private static final float MAX_ARMOR = 50.0F;
	private static final int FOG_OFF = 0;
	private static final int FOG_SHAPE_OFF = -1;
	private static final Matrix4fc IDENTITY = new Matrix4f();

	private final ViewMatrices view = new ViewMatrices();
	private final CameraShift shift = new CameraShift();
	private final Matrix4f reversedProjection = new Matrix4f();
	private PackDirectives directives = PackDirectives.defaults();
	private RenderStage stage = RenderStage.NONE;
	private boolean endFlashShadows;
	private boolean oldHandLight = true;
	private Object lastLevel;
	private long lastFrameNanos;
	private boolean timed;
	private int frameCounter;
	private float frameTime;
	private float frameTimeCounter;
	private float partialTick;
	private long gameTime;
	private float glintAlpha = 1.0F;
	private float viewWidth;
	private float viewHeight;
	private long worldTime;
	private long worldDay;
	private int moonPhase;
	private float sunAngleDegrees;
	private float moonAngleDegrees;
	private float rainStrength;
	private float thunderStrength;
	private int skyColorPacked;
	private float cloudHeight = 192.0F;
	private int bedrockLevel;
	private int heightLimit = 256;
	private int logicalHeightLimit = 256;
	private boolean hasCeiling;
	private boolean hasSkylight = true;
	private float ambientLight;
	private int dimensionOrdinal;
	private int seaLevel;
	private Holder<Biome> biomeHolder;
	private int biomeId;
	private int biomeCategory;
	private int biomePrecipitation;
	private float rainfall;
	private float temperature;
	private final Vector4f fogColor = new Vector4f();
	private float fogStart;
	private float fogEnd;
	private boolean heavyFog;
	private int isEyeInWater;
	private final Vector3d eyePosition = new Vector3d();
	private final Vector3f playerLookVector = new Vector3f();
	private final Vector3f playerBodyVector = new Vector3f();
	private float blindness;
	private float darknessFactor;
	private float nightVision;
	private float darknessLightFactor;
	private int cameraEntityTickCount;
	private float screenBrightness;
	private float playerMood;
	private int eyeBrightnessBlock;
	private int eyeBrightnessSky;
	private boolean sneaking;
	private boolean sprinting;
	private boolean hurt;
	private boolean invisible;
	private boolean burning;
	private boolean onGround;
	private boolean hideGui;
	private boolean rightHanded = true;
	private boolean spectator;
	private boolean firstPerson = true;
	private boolean elytraFlying;
	private boolean riding;
	private boolean feetInWater;
	private boolean swimming;
	private boolean vehicleInWater;
	private int vehicleId;
	private final Vector3d vehicleLookVector = new Vector3d();
	private final Vector3d relativeVehiclePosition = new Vector3d();
	private float playerHealth = -1.0F;
	private float playerMaxHealth = -1.0F;
	private float playerHunger = -1.0F;
	private float playerArmor = -1.0F;
	private float playerAir = -1.0F;
	private float playerMaxAir = -1.0F;
	private final Vector3f selectedBlockPos = new Vector3f(NOTHING_SELECTED);
	private int selectedBlockId;
	private final Vector4f lightningBoltPosition = new Vector4f();
	private long lightningTick = Long.MIN_VALUE;
	private int heldItemId = NameIds.NONE;
	private int heldItemId2 = NameIds.NONE;
	private int heldBlockLight;
	private int heldBlockLight2;
	private int atlasWidth;
	private int atlasHeight;

	public FrameState() {
		FrameSmoothed.forgetAll();
	}

	public void directives(PackDirectives directives) {
		this.directives = directives;
	}

	public PackDirectives directives() {
		return this.directives;
	}

	public void endFlashShadows(boolean endFlashShadows) {
		this.endFlashShadows = endFlashShadows;
	}

	public void oldHandLight(boolean oldHandLight) {
		this.oldHandLight = oldHandLight;
	}

	public ViewMatrices view() {
		return this.view;
	}

	public void convention(Vector4fc convention) {
		this.view.convention(convention);
	}

	public void passModelView(Matrix4fc matrix, Matrix4fc bob) {
		this.view.passModelView(matrix, bob);
	}

	/** OpalCompat: takes the OpenGL form matrix the game and the shadow pass hand out on this loader. */
	public void passProjection(Matrix4fc matrix) {
		this.view.passProjection(LevelCapture.glToReversed(matrix, new Matrix4f()));
	}

	public void passColour(Vector4fc colour) {
		this.view.passColour(colour);
	}

	public void passAlphaTest(float reference) {
		this.view.passAlphaTest(reference);
	}

	public void renderStage(RenderStage stage) {
		this.stage = stage;
	}

	@SuppressWarnings("ReferenceEquality")
	public void advance() {
		advanceClock();
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (level == null) {
			CapturedProjection.clear();
			return;
		}
		if (this.lastLevel != level) {
			this.lastLevel = level;
			reset();
		}
		GameRenderer renderer = minecraft.gameRenderer;
		Camera camera = renderer.getMainCamera();
		this.partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
		this.gameTime = level.getGameTime();
		this.glintAlpha = minecraft.options.glintStrength().get().floatValue();
		RenderTarget main = minecraft.getMainRenderTarget();
		if (main != null) {
			this.viewWidth = main.width;
			this.viewHeight = main.height;
		}
		this.shift.advance(camera.getPosition());
		readWorld(level, camera);
		readFog();
		readPlayer(minecraft, level, camera);
		readBiome(minecraft, level);
		readAtlas(minecraft);
		advanceView(minecraft);
		CapturedProjection.clear();
	}

	public void reset() {
		this.view.reset();
		this.shift.reset();
		FrameSmoothed.forgetAll();
		this.timed = false;
		this.frameCounter = 0;
		this.frameTime = 0.0F;
		this.frameTimeCounter = 0.0F;
		this.lightningTick = Long.MIN_VALUE;
		this.lightningBoltPosition.zero();
		this.biomeHolder = null;
	}

	public void leaveWorld() {
		reset();
		this.lastLevel = null;
	}

	@SuppressWarnings("NarrowCalculation")
	private void advanceClock() {
		long now = System.nanoTime();
		long elapsed = this.timed ? now - this.lastFrameNanos : 0L;
		this.lastFrameNanos = now;
		this.timed = true;
		this.frameTime = (elapsed / 1000L) / 1000L / 1000.0F;
		this.frameTimeCounter += this.frameTime;
		if (this.frameTimeCounter >= COUNTER_WRAP) {
			this.frameTimeCounter = 0.0F;
		}
		this.frameCounter = (this.frameCounter + 1) % FRAME_WRAP;
	}

	private void advanceView(Minecraft minecraft) {
		int renderDistance = minecraft.options.getEffectiveRenderDistance();
		// OpalCompat: the game's projection with the bob taken out, and the bob on the model view, as
		// Iris has it too (MixinModelViewBobbing). ViewMatrices expects a reversed Z projection.
		LevelCapture.glToReversed(LevelCapture.cleanProjection(), this.reversedProjection);
		this.view.advance(LevelCapture.modelView(), LevelCapture.bob(), this.reversedProjection,
				renderDistance * 16.0F, renderDistance);
		// OpalCompat: the row of the volume Distant Horizons is drawn in, in this reversed spelling.
		float[] distant = dev.opalcompat.dh.DhSupport.planes();
		if (distant != null) {
			float near = distant[0], far = distant[1];
			this.view.advanceDistant(near, far, (int) distant[2]);
			this.view.advanceDistantVolume(near / (far - near), near * far / (far - near));
		} else {
			this.view.advanceDistant(ViewMatrices.FALLBACK_PLANE, ViewMatrices.FALLBACK_PLANE, -1);
			this.view.advanceDistantVolume(0.0F, 0.0F);
		}
		float shadowAngle = sunAngle(isDay()) / 360.0F;
		boolean drewLastFrame = ShadowAmortisation.beginFrame(this.shift.unshifted(), shadowAngle, false);
		this.view.advanceShadow(shadowAngle, this.directives.sunPathRotation(),
				this.directives.shadowIntervalSize(), this.shift.unshifted(),
				this.directives.shadowDistance(), this.directives.shadowNearPlane(),
				this.directives.shadowFarPlane(), false, 0.0F, 0.0F, drewLastFrame);
	}

	private float sunAngle(boolean sun) {
		float angle = (sun ? this.sunAngleDegrees : this.moonAngleDegrees) + 90.0F;
		if (angle < 0.0F) {
			angle += 360.0F;
		} else if (angle > 360.0F) {
			angle -= 360.0F;
		}
		return angle;
	}

	private boolean isDay() {
		return sunAngle(true) < 180.0F;
	}

	private void readWorld(ClientLevel level, Camera camera) {
		float pt = this.partialTick;
		// OpalCompat: 1.21.1 has one celestial angle; the moon sits opposite the sun, as in Iris 1.21.1.
		this.sunAngleDegrees = level.getTimeOfDay(pt) * 360.0F;
		this.moonAngleDegrees = this.sunAngleDegrees + 180.0F;
		this.moonPhase = level.getMoonPhase();
		Vec3 sky = level.getSkyColor(camera.getPosition(), pt);
		this.skyColorPacked = 0xFF000000 | (Mth.clamp((int) (sky.x * 255.0), 0, 255) << 16)
				| (Mth.clamp((int) (sky.y * 255.0), 0, 255) << 8) | Mth.clamp((int) (sky.z * 255.0), 0, 255);
		this.cloudHeight = level.effects().getCloudHeight();
		this.rainStrength = Mth.clamp(level.getRainLevel(pt), 0.0F, 1.0F);
		this.thunderStrength = Mth.clamp(level.getThunderLevel(pt), 0.0F, 1.0F);
		this.dimensionOrdinal = dimensionOrdinal(level);
		long clock = level.getDayTime();
		this.worldDay = clock / 24000L;
		this.worldTime = clock % 24000L;
		DimensionType dimension = level.dimensionType();
		this.bedrockLevel = dimension.minY();
		this.heightLimit = dimension.height();
		this.logicalHeightLimit = dimension.logicalHeight();
		this.hasCeiling = dimension.hasCeiling();
		this.hasSkylight = dimension.hasSkyLight();
		this.ambientLight = dimension.ambientLight();
		this.seaLevel = level.getSeaLevel();
	}

	@SuppressWarnings("ReferenceEquality")
	private static int dimensionOrdinal(ClientLevel level) {
		if (level.dimension() == Level.OVERWORLD) {
			return 0;
		} else if (level.dimension() == Level.NETHER) {
			return -1;
		} else if (level.dimension() == Level.END) {
			return 1;
		}
		return 2;
	}

	/** OpalCompat: 1.21.1 keeps the frame's fog in RenderSystem, set by FogRenderer during the level. */
	private void readFog() {
		float[] colour = RenderSystem.getShaderFogColor();
		this.fogColor.set(colour[0], colour[1], colour[2], colour[3]);
		this.fogStart = RenderSystem.getShaderFogStart();
		this.fogEnd = RenderSystem.getShaderFogEnd();
	}

	private void readPlayer(Minecraft minecraft, ClientLevel level, Camera camera) {
		float pt = this.partialTick;
		LocalPlayer player = minecraft.player;
		Entity cameraEntity = minecraft.getCameraEntity();
		this.isEyeInWater = eyeInWater(camera.getFluidInCamera(), player != null && player.isSpectator());
		this.heavyFog = minecraft.gui.getBossOverlay().shouldCreateWorldFog();
		this.hideGui = minecraft.options.hideGui;
		this.rightHanded = minecraft.options.mainHand().get() == HumanoidArm.RIGHT;
		this.screenBrightness = minecraft.options.gamma().get().floatValue();
		this.firstPerson = minecraft.options.getCameraType().isFirstPerson();
		this.spectator = minecraft.gameMode != null && minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR;
		if (cameraEntity != null) {
			this.cameraEntityTickCount = cameraEntity.tickCount;
			Vec3 eye = cameraEntity.getEyePosition(pt);
			this.eyePosition.set(eye.x, eye.y, eye.z);
			Vec3 forward = cameraEntity.getForward();
			this.playerBodyVector.set((float) forward.x, (float) forward.y, (float) forward.z);
			BlockPos eyeBlock = BlockPos.containing(eye.x, cameraEntity.getEyeY(), eye.z);
			this.eyeBrightnessBlock = level.getBrightness(LightLayer.BLOCK, eyeBlock) * 16;
			this.eyeBrightnessSky = level.getBrightness(LightLayer.SKY, eyeBlock) * 16;
		}
		if (cameraEntity instanceof LivingEntity living) {
			Vec3 look = living.getViewVector(pt);
			this.playerLookVector.set((float) look.x, (float) look.y, (float) look.z);
			this.blindness = blindness(living);
			MobEffectInstance darkness = living.getEffect(MobEffects.DARKNESS);
			this.darknessFactor = darkness == null ? 0.0F : darkness.getBlendFactor(living, pt);
		} else {
			this.blindness = 0.0F;
			this.darknessFactor = 0.0F;
		}
		this.nightVision = nightVision(player, cameraEntity, pt);
		this.darknessLightFactor = darknessLightFactor(minecraft, player, pt);
		this.playerMood = player == null ? 0.0F : Mth.clamp(player.getCurrentMood(), 0.0F, 1.0F);
		readFlags(player);
		readStats(minecraft, player);
		readSelection(minecraft, level, camera);
		readLightning(level, pt);
		readHeld(player);
	}

	private static int eyeInWater(FogType submersion, boolean spectator) {
		if (submersion == FogType.WATER) {
			return 1;
		} else if (!spectator && submersion == FogType.LAVA) {
			return 2;
		} else if (submersion == FogType.POWDER_SNOW) {
			return 3;
		}
		return 0;
	}

	private static float blindness(LivingEntity living) {
		MobEffectInstance blindness = living.getEffect(MobEffects.BLINDNESS);
		if (blindness == null) {
			return 0.0F;
		}
		return blindness.isInfiniteDuration() ? 1.0F : Mth.clamp(blindness.getDuration() / 20.0F, 0.0F, 1.0F);
	}

	private static float nightVision(LocalPlayer player, Entity cameraEntity, float pt) {
		if (cameraEntity instanceof LivingEntity living && living.hasEffect(MobEffects.NIGHT_VISION)) {
			return Mth.clamp(GameRenderer.getNightVisionScale(living, pt), 0.0F, 1.0F);
		}
		if (player != null && player.getWaterVision() > 0.0F && player.hasEffect(MobEffects.CONDUIT_POWER)) {
			return Mth.clamp(player.getWaterVision(), 0.0F, 1.0F);
		}
		return 0.0F;
	}

	private static float darknessLightFactor(Minecraft minecraft, LocalPlayer player, float pt) {
		if (player == null) {
			return 0.0F;
		}
		MobEffectInstance darkness = player.getEffect(MobEffects.DARKNESS);
		if (darkness == null) {
			return 0.0F;
		}
		float scale = minecraft.options.darknessEffectScale().get().floatValue();
		float gamma = darkness.getBlendFactor(player, pt) * scale;
		float value = Math.max(0.0F, Mth.cos((player.tickCount - pt) * (float) Math.PI * 0.025F) * 0.45F * gamma);
		return value * scale;
	}

	private void readFlags(LocalPlayer player) {
		if (player == null) {
			noVehicle();
			noFlags();
			return;
		}
		this.sneaking = player.isCrouching();
		this.sprinting = player.isSprinting();
		this.hurt = player.hurtTime > 0;
		this.invisible = player.isInvisible();
		this.burning = player.isOnFire();
		this.onGround = player.onGround();
		this.elytraFlying = player.isFallFlying();
		this.riding = player.isPassenger();
		// OpalCompat: 1.21.1 has no isInShallowWater; in water with the eyes above it.
		this.feetInWater = player.isInWater() && !player.isUnderWater();
		this.swimming = player.isSwimming();
		Entity vehicle = player.getVehicle();
		if (vehicle == null) {
			noVehicle();
		} else {
			this.vehicleInWater = vehicle.isInWater();
			this.vehicleId = PackNameIds.entity(vehicle.getType());
			Vec3 forward = vehicle.getForward();
			this.vehicleLookVector.set(forward.x, forward.y, forward.z);
			Vec3 position = vehicle.getPosition(this.partialTick);
			this.relativeVehiclePosition.set(this.shift.unshifted()).sub(position.x, position.y, position.z);
		}
	}

	private void noVehicle() {
		this.vehicleInWater = false;
		this.vehicleId = 0;
		this.vehicleLookVector.zero();
		this.relativeVehiclePosition.zero();
	}

	private void noFlags() {
		this.sneaking = false;
		this.sprinting = false;
		this.hurt = false;
		this.invisible = false;
		this.burning = false;
		this.onGround = false;
		this.elytraFlying = false;
		this.riding = false;
		this.feetInWater = false;
		this.swimming = false;
	}

	private void readStats(Minecraft minecraft, LocalPlayer player) {
		boolean survival = player != null && minecraft.gameMode != null
				&& minecraft.gameMode.getPlayerMode().isSurvival();
		if (!survival) {
			this.playerHealth = -1.0F;
			this.playerMaxHealth = -1.0F;
			this.playerHunger = -1.0F;
			this.playerArmor = -1.0F;
			this.playerAir = -1.0F;
			this.playerMaxAir = -1.0F;
			return;
		}
		this.playerHealth = player.getHealth() / player.getMaxHealth();
		this.playerMaxHealth = player.getMaxHealth();
		this.playerHunger = player.getFoodData().getFoodLevel() / MAX_HUNGER;
		this.playerArmor = player.getArmorValue() / MAX_ARMOR;
		this.playerAir = (float) player.getAirSupply() / (float) player.getMaxAirSupply();
		this.playerMaxAir = player.getMaxAirSupply();
	}

	private void readSelection(Minecraft minecraft, ClientLevel level, Camera camera) {
		HitResult hit = minecraft.hitResult;
		if (hit == null || hit.getType() != HitResult.Type.BLOCK
				|| !(minecraft.getCameraEntity() instanceof Player player) || minecraft.options.hideGui) {
			nothingSelected();
			return;
		}
		BlockPos block = ((BlockHitResult) hit).getBlockPos();
		if (!player.getAbilities().mayBuild && !outlined(minecraft, level, player, block)) {
			nothingSelected();
			return;
		}
		Vec3 position = camera.getPosition();
		this.selectedBlockPos.set((float) (block.getX() + 0.5 - position.x),
				(float) (block.getY() + 0.5 - position.y), (float) (block.getZ() + 0.5 - position.z));
		BlockState state = level.getBlockState(block);
		this.selectedBlockId = state.isAir() || !level.getWorldBorder().isWithinBounds(block)
				? 0 : BlockStateIds.id(state);
	}

	private void nothingSelected() {
		this.selectedBlockPos.set(NOTHING_SELECTED);
		this.selectedBlockId = 0;
	}

	private static boolean outlined(Minecraft minecraft, ClientLevel level, Player player, BlockPos block) {
		if (minecraft.gameMode != null && minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
			return level.getBlockState(block).getMenuProvider(level, block) != null;
		}
		ItemStack held = player.getMainHandItem();
		// OpalCompat: 1.21.1 offers only the place-on test as a public method.
		return !held.isEmpty() && held.canPlaceOnBlockInAdventureMode(new BlockInWorld(level, block, false));
	}

	private void readLightning(ClientLevel level, float pt) {
		long tick = level.getGameTime();
		if (tick == this.lightningTick) {
			return;
		}
		this.lightningTick = tick;
		this.lightningBoltPosition.zero();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof LightningBolt) {
				Vec3 position = entity.getPosition(pt);
				Vector3dc camera = this.shift.unshifted();
				this.lightningBoltPosition.set((float) (position.x - camera.x()), (float) (position.y - camera.y()),
						(float) (position.z - camera.z()), 1.0F);
				return;
			}
		}
	}

	private void readHeld(LocalPlayer player) {
		if (player == null) {
			this.heldItemId = NameIds.NONE;
			this.heldItemId2 = NameIds.NONE;
			this.heldBlockLight = 0;
			this.heldBlockLight2 = 0;
			return;
		}
		ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
		ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
		this.heldItemId = itemId(mainHand);
		this.heldItemId2 = itemId(offHand);
		int main = emission(mainHand);
		int off = emission(offHand);
		this.heldBlockLight = this.oldHandLight ? Math.max(main, off) : main;
		this.heldBlockLight2 = off;
	}

	private static int itemId(ItemStack stack) {
		if (stack == null) {
			return NameIds.NONE;
		}
		// OpalCompat: 1.21.1 has no item_model component; the item's registry name is the id.
		return PackNameIds.item(BuiltInRegistries.ITEM.getKey(stack.getItem()));
	}

	private static int emission(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		Block block = Block.byItem(stack.getItem());
		return block == null ? 0 : block.defaultBlockState().getLightEmission();
	}

	private void readBiome(Minecraft minecraft, ClientLevel level) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		BlockPos position = player.blockPosition();
		Holder<Biome> holder = level.getBiome(position);
		if (holder != this.biomeHolder) {
			this.biomeHolder = holder;
			this.biomeId = BiomeClassifier.identify(holder);
			this.biomeCategory = BiomeClassifier.categoryOf(holder);
		}
		this.biomePrecipitation = switch (holder.value().getPrecipitationAt(position)) {
			case NONE -> 0;
			case RAIN -> 1;
			case SNOW -> 2;
		};
		// OpalCompat: NeoForge exposes the climate settings, so no accessor is needed for the downfall.
		this.rainfall = holder.value().modifiableBiomeInfo().get().climateSettings().downfall();
		this.temperature = holder.value().getBaseTemperature();
	}

	private void readAtlas(Minecraft minecraft) {
		AbstractTexture atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		if (atlas instanceof dev.opalcompat.mixin.TextureAtlasAccessor sized) {
			this.atlasWidth = sized.opalcompat$width();
			this.atlasHeight = sized.opalcompat$height();
		}
	}

	private static final class CameraShift {
		private static final double WALK_RANGE = 30000.0;
		private static final double TP_RANGE = 1000.0;
		private final Vector3d shift = new Vector3d();
		private final Vector3d current = new Vector3d();
		private final Vector3d previous = new Vector3d();
		private final Vector3d currentUnshifted = new Vector3d();
		private final Vector3d previousUnshifted = new Vector3d();
		private boolean seeded;

		void advance(Vec3 position) {
			this.previous.set(this.current);
			this.previousUnshifted.set(this.currentUnshifted);
			this.currentUnshifted.set(position.x, position.y, position.z);
			this.current.set(this.currentUnshifted).add(this.shift);
			if (!this.seeded) {
				this.previous.set(this.current);
				this.previousUnshifted.set(this.currentUnshifted);
				this.seeded = true;
			}
			double dX = shiftOf(this.current.x, this.previous.x);
			double dZ = shiftOf(this.current.z, this.previous.z);
			if (dX == 0.0 && dZ == 0.0) {
				return;
			}
			this.shift.x += dX;
			this.current.x += dX;
			this.previous.x += dX;
			this.shift.z += dZ;
			this.current.z += dZ;
			this.previous.z += dZ;
		}

		void reset() {
			this.shift.zero();
			this.current.zero();
			this.previous.zero();
			this.currentUnshifted.zero();
			this.previousUnshifted.zero();
			this.seeded = false;
		}

		private static double shiftOf(double value, double previous) {
			if (Math.abs(value) > WALK_RANGE || Math.abs(value - previous) > TP_RANGE) {
				return -(value - (value % WALK_RANGE));
			}
			return 0.0;
		}

		Vector3dc shifted() { return this.current; }
		Vector3dc previousShifted() { return this.previous; }
		Vector3dc unshifted() { return this.currentUnshifted; }
		Vector3dc previousUnshifted() { return this.previousUnshifted; }
	}

	@Override public int frameCounter() { return this.frameCounter; }
	@Override public float frameTime() { return this.frameTime; }
	@Override public float frameTimeCounter() { return this.frameTimeCounter; }
	@Override public float partialTick() { return this.partialTick; }
	@Override public long gameTime() { return this.gameTime; }
	@Override public float glintAlpha() { return this.glintAlpha; }
	@Override public float viewWidth() { return this.viewWidth; }
	@Override public float viewHeight() { return this.viewHeight; }
	@Override public Vector3dc cameraPosition() { return this.shift.shifted(); }
	@Override public Vector3dc previousCameraPosition() { return this.shift.previousShifted(); }
	@Override public Vector3dc cameraPositionUnshifted() { return this.shift.unshifted(); }
	@Override public Vector3dc previousCameraPositionUnshifted() { return this.shift.previousUnshifted(); }
	@Override public long worldTime() { return this.worldTime; }
	@Override public long worldDay() { return this.worldDay; }
	@Override public int moonPhase() { return this.moonPhase; }
	@Override public float sunAngleDegrees() { return this.sunAngleDegrees; }
	@Override public float moonAngleDegrees() { return this.moonAngleDegrees; }
	@Override public float sunPathRotation() { return this.directives.sunPathRotation(); }
	@Override public float rainStrength() { return this.rainStrength; }
	@Override public float thunderStrength() { return this.thunderStrength; }
	@Override public int skyColorPacked() { return this.skyColorPacked; }
	@Override public float cloudHeight() { return this.cloudHeight; }
	@Override public int bedrockLevel() { return this.bedrockLevel; }
	@Override public int heightLimit() { return this.heightLimit; }
	@Override public int logicalHeightLimit() { return this.logicalHeightLimit; }
	@Override public boolean hasCeiling() { return this.hasCeiling; }
	@Override public boolean hasSkylight() { return this.hasSkylight; }
	@Override public float ambientLight() { return this.ambientLight; }
	@Override public int dimensionOrdinal() { return this.dimensionOrdinal; }
	@Override public int seaLevel() { return this.seaLevel; }
	@Override public int biomeId() { return this.biomeId; }
	@Override public int biomeCategory() { return this.biomeCategory; }
	@Override public int biomePrecipitation() { return this.biomePrecipitation; }
	@Override public float rainfall() { return this.rainfall; }
	@Override public float temperature() { return this.temperature; }
	@Override public boolean hasEndFlash() { return false; }
	@Override public boolean endFlashShadows() { return this.endFlashShadows; }
	@Override public float endFlashXAngleDegrees() { return 0.0F; }
	@Override public float endFlashYAngleDegrees() { return 0.0F; }
	@Override public float endFlashIntensity() { return 0.0F; }
	@Override public float previousEndFlashIntensity() { return 0.0F; }
	@Override public float fogR() { return this.fogColor.x; }
	@Override public float fogG() { return this.fogColor.y; }
	@Override public float fogB() { return this.fogColor.z; }
	@Override public float fogA() { return this.fogColor.w; }
	@Override public float fogStart() { return this.fogStart; }
	@Override public float fogEnd() { return this.fogEnd; }
	@Override public float fogDensity() { return 0.0F; }
	@Override public int fogMode() { return FOG_OFF; }
	@Override public int fogShape() { return FOG_SHAPE_OFF; }
	@Override public boolean heavyFog() { return this.heavyFog; }
	@Override public int isEyeInWater() { return this.isEyeInWater; }
	@Override public Vector3dc eyePosition() { return this.eyePosition; }
	@Override public Vector3fc playerLookVector() { return this.playerLookVector; }
	@Override public Vector3fc playerBodyVector() { return this.playerBodyVector; }
	@Override public float blindness() { return this.blindness; }
	@Override public float darknessFactor() { return this.darknessFactor; }
	@Override public float nightVision() { return this.nightVision; }
	@Override public float darknessLightFactor() { return this.darknessLightFactor; }
	@Override public int cameraEntityTickCount() { return this.cameraEntityTickCount; }
	@Override public float screenBrightness() { return this.screenBrightness; }
	@Override public float playerMood() { return this.playerMood; }
	@Override public float constantMood() { return 0.0F; }
	@Override public int eyeBrightnessBlock() { return this.eyeBrightnessBlock; }
	@Override public int eyeBrightnessSky() { return this.eyeBrightnessSky; }
	@Override public boolean sneaking() { return this.sneaking; }
	@Override public boolean sprinting() { return this.sprinting; }
	@Override public boolean hurt() { return this.hurt; }
	@Override public boolean invisible() { return this.invisible; }
	@Override public boolean burning() { return this.burning; }
	@Override public boolean onGround() { return this.onGround; }
	@Override public boolean hideGui() { return this.hideGui; }
	@Override public boolean rightHanded() { return this.rightHanded; }
	@Override public boolean spectator() { return this.spectator; }
	@Override public boolean firstPerson() { return this.firstPerson; }
	@Override public boolean elytraFlying() { return this.elytraFlying; }
	@Override public boolean riding() { return this.riding; }
	@Override public boolean feetInWater() { return this.feetInWater; }
	@Override public boolean swimming() { return this.swimming; }
	@Override public boolean vehicleInWater() { return this.vehicleInWater; }
	@Override public int vehicleId() { return this.vehicleId; }
	@Override public Vector3dc vehicleLookVector() { return this.vehicleLookVector; }
	@Override public Vector3dc relativeVehiclePosition() { return this.relativeVehiclePosition; }
	@Override public float playerHealth() { return this.playerHealth; }
	@Override public float playerMaxHealth() { return this.playerMaxHealth; }
	@Override public float playerHunger() { return this.playerHunger; }
	@Override public float playerMaxHunger() { return MAX_HUNGER; }
	@Override public float playerArmor() { return this.playerArmor; }
	@Override public float playerMaxArmor() { return MAX_ARMOR; }
	@Override public float playerAir() { return this.playerAir; }
	@Override public float playerMaxAir() { return this.playerMaxAir; }
	@Override public Vector3fc selectedBlockPos() { return this.selectedBlockPos; }
	@Override public int selectedBlockId() { return this.selectedBlockId; }
	@Override public Vector4fc lightningBoltPosition() { return this.lightningBoltPosition; }
	@Override public int heldItemId() { return this.heldItemId; }
	@Override public int heldItemId2() { return this.heldItemId2; }
	@Override public int heldBlockLight() { return this.heldBlockLight; }
	@Override public int heldBlockLight2() { return this.heldBlockLight2; }
	@Override public int atlasWidth() { return this.atlasWidth; }
	@Override public int atlasHeight() { return this.atlasHeight; }
	@Override @SuppressWarnings("EnumOrdinal") public int renderStage() { return this.stage.ordinal(); }
	@Override public float anisotropy() { return 0.0F; }
	@Override public int colorSpace() { return 0; }
	@Override public int textureFilteringMode() { return 0; }
	@Override public float chunkFadeTimeInv() { return 0.0F; }
	@Override public float wetnessHalfLife() { return this.directives.wetnessHalflife(); }
	@Override public float drynessHalfLife() { return this.directives.drynessHalflife(); }
	@Override public float eyeBrightnessHalfLife() { return this.directives.eyeBrightnessHalflife(); }
	@Override public float centerDepthHalfLife() { return this.directives.centerDepthHalflife(); }
	@Override public float ambientOcclusionLevel() { return this.directives.ambientOcclusionLevel(); }
	@Override public float noiseTextureResolution() { return this.directives.noiseTextureResolution(); }
	@Override public float shadowDistance() { return this.directives.shadowDistance(); }
	@Override public float shadowNearPlane() { return this.directives.shadowNearPlane(); }
	@Override public float shadowFarPlane() { return this.directives.shadowFarPlane(); }
	@Override public float shadowIntervalSize() { return this.directives.shadowIntervalSize(); }
	@Override public Matrix4fc gbufferModelView() { return this.view.gbufferModelView(); }
	@Override public Matrix4fc gbufferModelViewInverse() { return this.view.gbufferModelViewInverse(); }
	@Override public Matrix4fc passModelView() { return this.view.passModelView(); }
	@Override public Matrix4fc cameraBob() { return this.view.cameraBob(); }
	@Override public Matrix4fc passModelViewInverse() { return this.view.passModelViewInverse(); }
	@Override public Matrix4fc passProjection() { return this.view.passProjection(); }
	@Override public Matrix4fc passProjectionInverse() { return this.view.passProjectionInverse(); }
	@Override public Vector4fc passColour() { return this.view.passColour(); }
	@Override public float passAlphaTest() { return this.view.passAlphaTest(); }
	@Override public Matrix4fc gbufferProjection() { return this.view.gbufferProjection(); }
	@Override public Matrix4fc gbufferProjectionInverse() { return this.view.gbufferProjectionInverse(); }
	@Override public Matrix4fc gbufferPreviousModelView() { return this.view.gbufferPreviousModelView(); }
	@Override public Matrix4fc gbufferPreviousProjection() { return this.view.gbufferPreviousProjection(); }
	@Override public Matrix4fc shadowModelView() { return this.view.shadowModelView(); }
	@Override public Matrix4fc shadowModelViewInverse() { return this.view.shadowModelViewInverse(); }
	@Override public Matrix4fc shadowProjection() { return this.view.shadowProjection(); }
	@Override public Matrix4fc shadowProjectionInverse() { return this.view.shadowProjectionInverse(); }
	@Override public Matrix4fc drawnShadowModelView() { return this.view.drawnShadowModelView(); }
	@Override public Matrix4fc drawnShadowModelViewInverse() { return this.view.drawnShadowModelViewInverse(); }
	@Override public Matrix4fc drawnShadowProjection() { return this.view.drawnShadowProjection(); }
	@Override public Matrix4fc drawnShadowProjectionInverse() { return this.view.drawnShadowProjectionInverse(); }
	@Override public Matrix4fc drawnDistantProjection() { return this.view.drawnDistantProjection(); }
	@Override public boolean distantDepthPair(Vector2f dest) { return this.view.distantDepthPair(dest); }
	@Override public Matrix4fc dhProjection() { return this.view.dhProjection(); }
	@Override public Matrix4fc dhProjectionInverse() { return this.view.dhProjectionInverse(); }
	@Override public Matrix4fc dhPreviousProjection() { return this.view.dhPreviousProjection(); }
	@Override public float dhNearPlane() { return this.view.dhNearPlane(); }
	@Override public float dhFarPlane() { return this.view.dhFarPlane(); }
	@Override public int dhRenderDistance() { return this.view.dhRenderDistance(); }
	@Override public float near() { return this.view.near(); }
	@Override public float far() { return this.view.far(); }
	@Override public Vector4fc depthConvention() { return this.view.depthConvention(); }
}
