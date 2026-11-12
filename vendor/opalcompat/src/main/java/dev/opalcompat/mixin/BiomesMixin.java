package dev.opalcompat.mixin;

import dev.vitrail.render.BiomeClassifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Numbers the vanilla biomes in declaration order, for the BIOME_* defines. After Vitrail's BiomesMixin. */
@Mixin(Biomes.class)
public abstract class BiomesMixin {

	@Inject(method = "register", at = @At("TAIL"))
	private static void opalcompat$record(String name, CallbackInfoReturnable<ResourceKey<Biome>> cir) {
		BiomeClassifier.record(cir.getReturnValue());
	}
}
