package dev.opalcompat.mixin;

import dev.opalcompat.compat.Mods;
import dev.opalcompat.dh.DhSupport;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * All of these belong to the Vulkan side, so they apply only with VulkanMod Reforged in the game. The mixins into another mod
 * apply where that mod is there in the version they were written against.
 */
public final class OpalCompatMixinPlugin implements IMixinConfigPlugin {

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!Mods.VULKAN) return false;
		return !mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1).startsWith("Dh") || DhSupport.ACTIVE;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
