package net.charter.block;

import org.jetbrains.annotations.Nullable;

import net.charter.registry.ModBlockEntities;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * Divine Dominance, as a block: a two-block pillar wrapped in golden Charter chains, crowned with the red tablet.
 * Once placed it raises a golden square wall that slowly closes in; only its owner and souls bound to Charter can
 * cross it, and whoever is still inside when it closes is judged.
 *
 * <p>Like a door, it is two blocks that live and die together. The lower half carries the block entity (the
 * clock, the owner, the wall) and the loot; the upper half only has to be there.
 */
public class DivineDominanceBlock extends BlockWithEntity {
	public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

	private static final VoxelShape SHAPE = Block.createCuboidShape(2.0, 0.0, 2.0, 14.0, 16.0, 14.0);

	public DivineDominanceBlock(Settings settings) {
		super(settings);
		this.setDefaultState(this.stateManager.getDefaultState().with(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(HALF);
	}

	/** The pillar needs room for both halves. */
	@Nullable
	@Override
	public BlockState getPlacementState(ItemPlacementContext context) {
		BlockPos pos = context.getBlockPos();
		World world = context.getWorld();
		if (pos.getY() < world.getTopY() - 1 && world.getBlockState(pos.up()).canReplace(context)) {
			return this.getDefaultState().with(HALF, DoubleBlockHalf.LOWER);
		}
		return null;
	}

	/** If one half is gone the other one goes with it. */
	@Override
	public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		DoubleBlockHalf half = state.get(HALF);
		if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
			if (!neighborState.isOf(this) || neighborState.get(HALF) == half) {
				return Blocks.AIR.getDefaultState();
			}
		}
		return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public BlockRenderType getRenderType(BlockState state) {
		// BlockWithEntity makes blocks invisible by default
		return BlockRenderType.MODEL;
	}

	/** The position of the half that holds the block entity. */
	private static BlockPos lowerHalf(BlockState state, BlockPos pos) {
		return state.get(HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return state.get(HALF) == DoubleBlockHalf.LOWER ? new DivineDominanceBlockEntity(pos, state) : null;
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		if (world.isClient || state.get(HALF) != DoubleBlockHalf.LOWER) {
			return null;
		}
		return checkType(type, ModBlockEntities.DIVINE_DOMINANCE, DivineDominanceBlockEntity::tick);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.onPlaced(world, pos, state, placer, stack);
		world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
		if (world instanceof ServerWorld) {
			BlockEntity entity = world.getBlockEntity(pos);
			if (entity instanceof DivineDominanceBlockEntity) {
				PlayerEntity owner = placer instanceof PlayerEntity ? (PlayerEntity) placer : null;
				((DivineDominanceBlockEntity) entity).arm((ServerWorld) world, owner);
			}
		}
	}

	/** In creative mode the two halves go together without a drop, whichever one is struck. */
	@Override
	public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (!world.isClient && player.isCreative()) {
			BlockPos lower = lowerHalf(state, pos);
			BlockState lowerState = world.getBlockState(lower);
			if (lowerState.isOf(this) && lowerState.get(HALF) == DoubleBlockHalf.LOWER) {
				world.setBlockState(lower, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
			}
		}
		super.onBreak(world, pos, state, player);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		BlockEntity entity = world.getBlockEntity(lowerHalf(state, pos));
		if (entity instanceof DivineDominanceBlockEntity) {
			((DivineDominanceBlockEntity) entity).describe(player);
		}
		return ActionResult.CONSUME;
	}
}
