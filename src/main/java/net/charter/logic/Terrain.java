package net.charter.logic;

import net.charter.config.CharterConfig;
import net.charter.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.charter.fx.Fx;

/**
 * What the Lesser Divinity does to the world: everything inside its sigil, from a few blocks under the point of
 * impact to the sky above it, is erased. Unbreakable blocks and the tablet of Divine Dominance survive.
 */
public final class Terrain {
	private Terrain() {
	}

	/**
	 * Removes every block inside the cylinder around {@code center}, working from the top down so that the
	 * highest things (trees, towers) go first, until the configured number of blocks has been removed.
	 *
	 * @return the number of blocks that were removed
	 */
	public static int obliterate(ServerWorld world, Vec3d center, double radius) {
		CharterConfig config = CharterConfig.get();
		int reach = (int) Math.ceil(radius);
		int centerX = MathHelper.floor(center.x);
		int centerZ = MathHelper.floor(center.z);
		int baseY = MathHelper.floor(center.y);
		int lowest = Math.max(world.getBottomY() + 1, baseY - config.terrainDepth);
		int highest = Math.min(world.getTopY() - 1, baseY + config.terrainHeight);
		double limit = radius * radius;

		int removed = 0;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		scan:
		for (int y = highest; y >= lowest; y--) {
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= reach; dz++) {
					if ((double) (dx * dx + dz * dz) > limit) {
						continue;
					}
					pos.set(centerX + dx, y, centerZ + dz);
					BlockState state = world.getBlockState(pos);
					if (state.isAir() || !destructible(world, pos, state)) {
						continue;
					}
					world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
					removed++;
					if (removed >= config.terrainMaxBlocks) {
						break scan;
					}
				}
			}
		}

		if (removed > 0) {
			Vec3d middle = new Vec3d(center.x, center.y + 1.0, center.z);
			Fx.sound(world, center, SoundEvents.ENTITY_GENERIC_EXPLODE, 10.0f, 0.55f);
			world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, middle.x, middle.y, middle.z, 3, radius * 0.4, 0.5, radius * 0.4, 0.0);
			Fx.sparks(world, middle, 160, Math.max(1.0, radius * 0.5));
		}
		return removed;
	}

	private static boolean destructible(ServerWorld world, BlockPos pos, BlockState state) {
		if (state.isOf(ModBlocks.DIVINE_DOMINANCE)) {
			return false;
		}
		// bedrock, barriers, command blocks, end portal frames: everything that cannot be mined in survival
		return state.getHardness(world, pos) >= 0.0f;
	}
}
