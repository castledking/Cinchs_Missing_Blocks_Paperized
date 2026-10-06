package net.cinchtail.cinchsmissingblocks.cmb.block;

import java.util.Optional;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.SingleBlockHalf;
import net.momirealms.craftengine.core.block.property.type.StairsShape;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;

/**
 * {@code cmb:stairs} - stairs whose state space is {@code facing x half x shape}.
 *
 * <p>Shape follows vanilla's rule: a stair turns into an outer corner when the stair in
 * front of it faces across it, and into an inner corner when the stair behind it does,
 * provided both share a half. Only {@code cmb:stairs} neighbours count. The server-side
 * block is a CraftEngine block, not a vanilla stair, so vanilla stairs do not join with
 * these either.
 *
 * <p>Why not CraftEngine's {@code stairs_block}: it extends its waterlogged behaviour and
 * accepts a missing {@code waterlogged} property, but its liquid handling still
 * dereferences it, so a water bucket on a stair would fail on the server. Declaring
 * {@code waterlogged} would double the states and need wet carriers, which unmodded
 * clients draw submerged. So this behaviour has no liquid handling at all.
 *
 * <p>Collision comes from the carrier: a freed, dry vanilla stair state whose facing,
 * half and shape always equal the ones declared here, so corners collide as corners.
 *
 * <p>Until shape was added, this behaviour modelled {@code facing x half} only and
 * corners rendered straight. That was a carrier-budget trade-off which no longer
 * applies: each served stair now has a whole freed copper stair block of 40 dry states.
 */
public final class StairsBehavior extends BukkitBlockBehavior {

    public static final BlockBehaviorFactory<StairsBehavior> FACTORY = (block, section) ->
            new StairsBehavior(block, section);

    private final Property<Direction> facing;
    private final Property<SingleBlockHalf> half;
    private final Property<StairsShape> shape;

    private StairsBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.facing = BlockBehaviorFactory.getProperty(
                section.path(), block, "facing", Direction.class);
        this.half = BlockBehaviorFactory.getProperty(
                section.path(), block, "half", SingleBlockHalf.class);
        this.shape = BlockBehaviorFactory.getProperty(
                section.path(), block, "shape", StairsShape.class);
    }

    /**
     * Vanilla's placement rule: {@code facing} follows the player, {@code half} is
     * bottom unless aiming at a top face or the upper half of a side, and {@code shape}
     * comes from the neighbours.
     */
    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context,
                                                       ImmutableBlockState state) {
        Direction clickedFace = context.getClickedFace();
        BlockPos clickedPos = context.getClickedPos();
        double hitY = context.getClickedLocation().y;
        boolean upperHalf = hitY - clickedPos.y() > 0.5;

        SingleBlockHalf placedHalf = clickedFace != Direction.DOWN
                && (clickedFace == Direction.UP || !upperHalf)
                ? SingleBlockHalf.BOTTOM
                : SingleBlockHalf.TOP;

        // Start from the default so nothing carries over from the held item's state.
        ImmutableBlockState placed = state.owner().value().defaultState()
                .with(this.facing, context.getHorizontalDirection())
                .with(this.half, placedHalf);
        return placed.with(this.shape,
                shapeAt(placed, context.getLevel().minecraftWorld(), clickedPos));
    }

    /** A horizontal neighbour changed: recompute the corner, as vanilla does. */
    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        Object blockState = args[0];
        Optional<ImmutableBlockState> custom = BlockStateUtils.getOptionalCustomBlockState(blockState);
        if (custom.isEmpty()) {
            return blockState;
        }
        Direction direction = DirectionUtils.fromNMSDirection(
                VersionHelper.isOrAbove1_21_2 ? args[4] : args[1]);
        if (!direction.axis().isHorizontal()) {
            return blockState;
        }
        BlockPos pos = LocationUtils.fromBlockPos(args[updateShape$blockPos]);
        ImmutableBlockState state = custom.get();
        return state.with(this.shape, shapeAt(state, args[updateShape$level], pos))
                .customBlockState().minecraftState();
    }

    private StairsShape shapeAt(ImmutableBlockState state, Object level, BlockPos pos) {
        Direction facing = state.get(this.facing);

        ImmutableBlockState front = stairAt(level, pos.relative(facing));
        if (front != null && sameHalf(state, front)) {
            Direction frontFacing = front.get(behaviourOf(front).facing);
            if (frontFacing.axis() != facing.axis()
                    && canTakeShape(state, level, pos, frontFacing.opposite())) {
                return frontFacing == facing.counterClockWise()
                        ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
            }
        }

        ImmutableBlockState back = stairAt(level, pos.relative(facing.opposite()));
        if (back != null && sameHalf(state, back)) {
            Direction backFacing = back.get(behaviourOf(back).facing);
            if (backFacing.axis() != facing.axis()
                    && canTakeShape(state, level, pos, backFacing)) {
                return backFacing == facing.counterClockWise()
                        ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
            }
        }
        return StairsShape.STRAIGHT;
    }

    /** A corner is only taken if the stair on that side is not already continuing ours. */
    private boolean canTakeShape(ImmutableBlockState state, Object level, BlockPos pos,
                                 Direction side) {
        ImmutableBlockState other = stairAt(level, pos.relative(side));
        if (other == null) {
            return true;
        }
        StairsBehavior behaviour = behaviourOf(other);
        return other.get(behaviour.facing) != state.get(this.facing)
                || other.get(behaviour.half) != state.get(this.half);
    }

    private boolean sameHalf(ImmutableBlockState state, ImmutableBlockState other) {
        return state.get(this.half) == other.get(behaviourOf(other).half);
    }

    /** The cmb:stairs state at {@code pos}, or null if there is none. */
    private static ImmutableBlockState stairAt(Object level, BlockPos pos) {
        Object raw = BlockGetterProxy.INSTANCE.getBlockState(level, LocationUtils.toBlockPos(pos));
        Optional<ImmutableBlockState> custom = BlockStateUtils.getOptionalCustomBlockState(raw);
        if (custom.isEmpty() || custom.get().behavior().getFirst(StairsBehavior.class) == null) {
            return null;
        }
        return custom.get();
    }

    private static StairsBehavior behaviourOf(ImmutableBlockState state) {
        return state.behavior().getFirst(StairsBehavior.class);
    }
}
