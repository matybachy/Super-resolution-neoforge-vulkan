package dev.opalcompat.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.opalcompat.compat.CreateSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Ponder's {@code StitchedSprite} adds itself to a static map of lists in its constructor, which mods call from their own
 * constructors on several threads at once: {@link CreateSprites}. The stitch reads a copy taken under the same lock.
 */
@Pseudo
@Mixin(targets = "net.createmod.catnip.render.StitchedSprite", remap = false)
public abstract class CreateStitchedSpriteMixin {

	@WrapOperation(method = "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;)V",
			at = @At(value = "INVOKE", target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
	private Object opalcompat$listOf(Map<Object, Object> all, Object atlas, Function<Object, Object> create, Operation<Object> original) {
		synchronized (CreateSprites.LOCK) {
			return original.call(all, atlas, create);
		}
	}

	@WrapOperation(method = "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;)V",
			at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
	private boolean opalcompat$add(List<Object> list, Object sprite, Operation<Boolean> original) {
		synchronized (CreateSprites.LOCK) {
			return original.call(list, sprite);
		}
	}

	@WrapOperation(method = "onTextureStitchPost", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
	private static Object opalcompat$copy(Map<Object, Object> all, Object atlas, Operation<Object> original) {
		synchronized (CreateSprites.LOCK) {
			Object list = original.call(all, atlas);
			return list instanceof List<?> sprites ? new ArrayList<>(sprites) : list;
		}
	}
}
