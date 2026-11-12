package dev.opalcompat.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.opalcompat.compat.CreateSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/** Ponder's {@code SpriteShifter.get} caches its entries in a static {@code HashMap}, filled from mod constructors: {@link CreateSprites}. */
@Pseudo
@Mixin(targets = "net.createmod.catnip.render.SpriteShifter", remap = false)
public abstract class CreateSpriteShifterMixin {

	@WrapOperation(method = "get", at = @At(value = "INVOKE", target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z"))
	private static boolean opalcompat$has(Map<Object, Object> cache, Object key, Operation<Boolean> original) {
		synchronized (CreateSprites.LOCK) {
			return original.call(cache, key);
		}
	}

	@WrapOperation(method = "get", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
	private static Object opalcompat$get(Map<Object, Object> cache, Object key, Operation<Object> original) {
		synchronized (CreateSprites.LOCK) {
			return original.call(cache, key);
		}
	}

	@WrapOperation(method = "get", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
	private static Object opalcompat$put(Map<Object, Object> cache, Object key, Object value, Operation<Object> original) {
		synchronized (CreateSprites.LOCK) {
			return original.call(cache, key, value);
		}
	}
}
