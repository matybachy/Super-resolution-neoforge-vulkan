package dev.opalcompat.render;

import dev.vitrail.render.BlockStateIds;
import dev.vitrail.render.PackNameIds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;

/**
 * The three identifiers in the entity vertex input, in the translator's lane order. Which number an entity
 * or item answers follows Iris 1.21.1 ({@code mixin/entity_render_context}). LGPL-3.0, see NOTICE.
 */
public record DrawIds(int entity, int blockEntity, int item, Kind kind) {
	public enum Kind { NONE, ENTITY, BLOCK_ENTITY }
	public static final DrawIds NONE = new DrawIds(0, -1, -1, Kind.NONE);

	static DrawIds entity(Entity entity) {
		int id;
		if (entity instanceof ZombieVillager zombie && zombie.isConverting() && PackNameIds.namesConvertingVillager()) {
			id = PackNameIds.convertingVillager();
		} else if (entity instanceof Player && Minecraft.getInstance().getCameraEntity() == entity
				&& PackNameIds.namesCurrentPlayer()) {
			id = PackNameIds.currentPlayer();
		} else {
			id = PackNameIds.entity(entity.getType());
		}
		return new DrawIds(id, -1, -1, Kind.ENTITY);
	}

	static DrawIds blockEntity(BlockState state) {
		return new DrawIds(0, BlockStateIds.id(state), -1, Kind.BLOCK_ENTITY);
	}

	static DrawIds flame() {
		return new DrawIds(PackNameIds.flame(), -1, -1, Kind.ENTITY);
	}

	DrawIds withItem(ItemStack stack) {
		if (stack.isEmpty()) return this;
		// Iris identifies block items by block-state ID and marks the block-entity lane as one.
		if (stack.getItem() instanceof BlockItem block && !(stack.getItem() instanceof SolidBucketItem)) {
			int id = BlockStateIds.id(block.getBlock().defaultBlockState());
			return new DrawIds(this.entity, 1, id < 0 ? 0 : id, this.kind);
		}
		return new DrawIds(this.entity, this.blockEntity,
				PackNameIds.item(BuiltInRegistries.ITEM.getKey(stack.getItem())), this.kind);
	}

	/** The lanes are 32 bits here, so the -1 of an unmapped name reaches {@code int(EntityIds[n])} as -1, as in Iris. */
	void put(ByteBuffer buffer, int offset) {
		buffer.putInt(offset, this.entity);
		buffer.putInt(offset + 4, this.blockEntity);
		buffer.putInt(offset + 8, this.item);
		buffer.putInt(offset + 12, 0);
	}
}
