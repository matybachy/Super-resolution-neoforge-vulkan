package dev.opalcompat.mixin;

import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = WorldRenderer.class, remap = false)
public interface WorldRendererAccessor {

	@Accessor("sectionGraph")
	SectionGraph opalcompat$sectionGraph();

	@Invoker("sortTranslucentSections")
	void opalcompat$sortTranslucentSections(double camX, double camY, double camZ);
}
