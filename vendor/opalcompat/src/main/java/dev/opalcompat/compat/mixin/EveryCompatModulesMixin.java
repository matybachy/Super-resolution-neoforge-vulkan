package dev.opalcompat.compat.mixin;

import com.google.common.collect.Multimap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Every Compat walks its modules in its mod constructor while other mods add theirs from their own constructors, on other threads.
 * Adding takes the lock of the class and walking does not, so now and then the walk throws a ConcurrentModificationException. The
 * game then loads no mod configs and the first client tick crashes ("Cannot get config value before config is loaded"), in whichever
 * mod reads one first. A copy taken under the same lock can't be changed underneath; this handler is merged into that class and a
 * static synchronized method locks its class. A module added during the walk is treated like one added just after it.
 */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.every_compat.EveryCompat", remap = false)
public abstract class EveryCompatModulesMixin {

	@Redirect(method = "forAllModules", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Multimap;values()Ljava/util/Collection;"))
	private static synchronized Collection<?> opalcompat$copy(Multimap<?, ?> modules) {
		return new ArrayList<>(modules.values());
	}
}
