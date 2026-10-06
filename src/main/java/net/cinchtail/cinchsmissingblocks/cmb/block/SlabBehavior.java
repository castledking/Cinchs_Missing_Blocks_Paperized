package net.cinchtail.cinchsmissingblocks.cmb.block;

import java.util.Optional;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.SlabType;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.behavior.BlockItem;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.MutableBoolean;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;

/**
 * {@code cmb:slab} - slabs whose entire state space is {@code type} (bottom/top/double).
 *
 * <p>Why not CraftEngine's {@code slab_block}: it accepts a missing {@code waterlogged}
 * property at construction, but its liquid handling still dereferences it.
 * {@code canPlaceLiquid} reports true for water on any non-double slab, after which
 * {@code placeLiquid} and {@code pickupBlock} call {@code state.get(null)}. A water
 * bucket on a slab would then fail on the server. Declaring {@code waterlogged} instead
 * would double the states per slab and require wet carrier states, which an unmodded
 * client draws as submerged.
 *
 * <p>So this behaviour models exactly what the slim state space supports: vanilla's
 * placement rule (top or bottom from the clicked face and height) and merging a second
 * slab into a double. It deliberately has no liquid handling, so a slab is not a
 * liquid container: water behaves against it as it does against any solid block.
 * Collision comes from the carrier, a real dry vanilla slab state whose {@code type}
 * always matches the one declared here.
 */
public final class SlabBehavior extends BukkitBlockBehavior {

    public static final BlockBehaviorFactory<SlabBehavior> FACTORY = (block, section) ->
            new SlabBehavior(block, section);

    private final Property<SlabType> type;

    private SlabBehavior(BlockDefinition block, ConfigSection section) {
        super(block);
        this.type = BlockBehaviorFactory.getProperty(section.path(), block, "type", SlabType.class);
    }

    /**
     * Whether placing the held item here merges into this slab instead of going next to it.
     *
     * <p>Vanilla's rule: only the same slab, only onto a single slab, and only from the
     * side that would complete it - the top face or upper half of a side for a bottom
     * slab, the bottom face or lower half for a top slab.
     */
    @Override
    public boolean canBeReplaced(BlockPlaceContext context, ImmutableBlockState state) {
        SlabType current = state.get(this.type);
        Item item = context.getItem();
        if (current == SlabType.DOUBLE || ItemUtils.isEmpty(item)) {
            return false;
        }
        Optional<ItemDefinition> held = item.getDefinition();
        if (held.isEmpty()) {
            return false;
        }
        MutableBoolean sameSlab = new MutableBoolean(false);
        held.get().behavior().let(BlockItem.class, b -> {
            if (b.block().equals(super.blockDefinition.id())) {
                sameSlab.set(true);
            }
        });
        if (!sameSlab.booleanValue()) {
            return false;
        }
        if (!context.replacingClickedBlock()) {
            return true;
        }
        boolean upper = context.getClickedLocation().y - context.getClickedPos().y() > 0.5;
        Direction face = context.getClickedFace();
        return current == SlabType.BOTTOM
                ? face == Direction.UP || (upper && face.axis().isHorizontal())
                : face == Direction.DOWN || (!upper && face.axis().isHorizontal());
    }

    /**
     * Double when merging into the same slab, otherwise top or bottom by vanilla's rule.
     *
     * <p>Built from the block's default state so nothing carries over from the held
     * item, and only {@code type} is ever set.
     */
    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context,
                                                       ImmutableBlockState state) {
        BlockPos pos = context.getClickedPos();
        ImmutableBlockState existing = context.getLevel().getBlock(pos).customBlockState();
        if (existing != null && existing.owner().value() == super.blockDefinition) {
            return existing.with(this.type, SlabType.DOUBLE);
        }
        Direction face = context.getClickedFace();
        boolean upper = context.getClickedLocation().y - pos.y() > 0.5;
        SlabType placed = face == Direction.DOWN || (face != Direction.UP && upper)
                ? SlabType.TOP : SlabType.BOTTOM;
        return state.owner().value().defaultState().with(this.type, placed);
    }
}
