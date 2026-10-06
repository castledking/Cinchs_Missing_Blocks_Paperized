#!/usr/bin/env python3
"""Canonical vanilla carrier-state database for CraftEngine custom blocks.

This file is deliberately version-agnostic. It is the *complete* set of carrier
candidates across Minecraft versions, not the set that happens to exist on one
target. Availability is a runtime question, answered by the companion plugin
against the server's own block registry - never by deleting content at build time.

Build-time compatibility and runtime availability are different things:

    source mod -> generator -> complete content definition + canonical carriers
                                     |
                                     v
                              Paperized jar
                                     |
                             server startup
                                     |
                              Minecraft version
                                     |
                          +----------+----------+
                          v                     v
                    register                  skip

So a carrier that does not exist on the running server is filtered out of the
*allocation* at startup, and a block that cannot be represented is reported by the
compatibility policy. Neither causes the project to lose content.

Why the carrier file exists
--------------------
CraftEngine gives every custom block state a *visual block state* - an ordinary
vanilla block state that supplies the collision box, the sound type, the light
level and the client-side model binding. Two custom block states may not share
one vanilla state unless they also share one model, and CraftEngine enforces that
at config-load time. So the number of custom blocks we can add is bounded by how
many vanilla block states exist *of a shape that matches*.

The consequence is that a family of blocks can only be ported if

    (custom blocks in the family) x (states per block) <= (vanilla states of that shape)

and because we must not steal states out from under real vanilla blocks, every
vanilla block we borrow from has to be converted into a CraftEngine block too.
That is what :func:`plan` computes.

A candidate carries a ``verified_in`` note recording the version its existence was
last confirmed against. It is documentation for humans reviewing a capacity report;
it is not used to filter anything. The plugin asks the live registry instead,
which is exact rather than guessy.

State counts below are the real per-block registry counts for Minecraft 1.21.x,
not the visually-deduplicated counts.
"""

from __future__ import annotations

import dataclasses
import itertools
import re


@dataclasses.dataclass(frozen=True, order=True)
class McVersion:
    """Comparable Minecraft version, e.g. 1.21.5 < 1.21.8 < 1.22."""

    major: int
    minor: int
    patch: int = 0

    @classmethod
    def parse(cls, text: str) -> "McVersion":
        # Materialise before padding. This was a generator consumed twice in one
        # expression: list(parts) took the values, then len(list(parts)) measured an
        # exhausted generator as empty, so the padding always appended a full [0, 0, 0].
        # "26.3" became [26, 3, 0, 0, 0] and raised "too many values to unpack (expected 3,
        # got 5)" -- so fetch_vanilla.py could not run for any version, and the cache it
        # fills had been populated some other way entirely.
        values = [int(part) for part in re.findall(r"\d+", text)[:3]]
        while len(values) < 3:
            values.append(0)
        return cls(values[0], values[1], values[2])

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}" + (f".{self.patch}" if self.patch else "")


#: The version the canonical database was last verified against.
#:
#: 26.3 is the current Minecraft release and is what the Paper dev servers run, so it
#: is the version that matters. Note that candidates which do not exist on it (the
#: ``infested_*_wall`` family) stay in the canonical database anyway: absence is a
#: runtime fact discovered by intersecting with the live registry, not a reason to
#: edit the candidate set.
VERIFIED_AGAINST = McVersion(26, 3)

# --- property vocabularies ---------------------------------------------------

FACING = ["north", "east", "south", "west"]
HALF = ["bottom", "top"]
# CraftEngine exposes five stair shapes; we only ever use the straight one (see
# README, "Stairs do not render corner shapes").
STAIRS_SHAPE = ["straight", "inner_left", "inner_right", "outer_left", "outer_right"]
SLAB_TYPE = ["bottom", "top", "double"]
WALL_SIDE = ["none", "low", "tall"]
AXIS = ["x", "y", "z"]
BOOLEAN = ["false", "true"]
#: Vanilla buttons and pressure plates only ever face north or south.
BUTTON_FACING = ["north", "south"]
FACES = ["floor", "wall", "ceiling"]


@dataclasses.dataclass(frozen=True)
class ShapeFamily:
    """One borrowable pool of vanilla blocks that share a collision shape."""

    name: str
    blocks: tuple[str, ...]
    #: Full registry state space of one vanilla block in this family.
    all_properties: tuple[tuple[str, tuple[str, ...]], ...]
    #: The reduced property set each CraftEngine block of this family declares.
    used_properties: tuple[tuple[str, tuple[str, ...]], ...]
    #: Properties whose states are held back for the vanilla block itself. Defaults
    #: to used_properties, but a family can widen it: the plate family models all
    #: three properties yet can only spare the single default state per block.
    reserve_properties: tuple[tuple[str, tuple[str, ...]], ...] | None = None
    #: Whether overwriting this family's vanilla blockstate is safe.
    #:
    #: CraftEngine removes ``multipart`` from any blockstate it writes into and merges
    #: its own entries into ``variants``. On a variants-only blockstate that is
    #: harmless, because the original variants survive the merge. On a multipart one
    #: it destroys every state vanilla used to render that we did not allocate, and
    #: those states draw magenta in game.
    #:
    #: So a carrier is eligible only if modifying its blockstate cannot destroy
    #: unrelated vanilla rendering. Fences, panes and walls are all multipart and are
    #: therefore rejected; the blocks that needed them are deferred by the allocator
    #: like any other block it cannot represent. tools/carrier_eligibility.py checks
    #: this flag against vanilla's real blockstates, so a family wrongly marked safe
    #: is a build failure rather than silent damage.
    merge_safe: bool = True

    # There is no family-level "unmodded client safe" flag any more. Its rule - never
    # lend a waterlogged state, because CraftEngine shows that carrier verbatim to an
    # unmodded client, which draws the block submerged - is applied per state in
    # CarrierPool.build. That lets a family serve the blocks it has dry, freed
    # carriers for (three copper-carried stairs) and defer the rest, instead of
    # all or nothing.
    #: Carrier states must match these properties exactly, in addition to agreeing
    #: with the declared projection. Needed where an unprojected property is part of
    #: the physical geometry: a stair carrier with shape=inner_right is a perfectly
    #: valid vanilla state, but its collision shape is a corner, so a straight stair
    #: carried by it silently gets the wrong hitbox.
    required_carrier_properties: dict[str, tuple[str, ...]] = dataclasses.field(
        default_factory=dict)
    #: Properties to prefer when ordering candidates. Carrier states are handed out
    #: in this order so that, for example, stairs take a `shape=straight` state
    #: rather than an inner-corner one, and a dry state rather than a waterlogged
    #: one. Preference only reorders; it never excludes.
    prefer: dict[str, str] = dataclasses.field(default_factory=dict)
    #: declared value -> vanilla value, for properties whose vocabularies differ.
    #: Walls model a side as a boolean but vanilla spells it none/low/tall, so a
    #: connected side is carried by a `low` side.
    value_aliases: dict[str, dict[str, str]] = dataclasses.field(default_factory=dict)

    def __post_init__(self) -> None:
        # Several lists were written by hand and picked up duplicates; a duplicate
        # would silently inflate capacity.
        object.__setattr__(self, "blocks", tuple(dict.fromkeys(self.blocks)))

    @property
    def states_per_vanilla_block(self) -> int:
        return _product(self.all_properties)

    @property
    def reserve_props(self) -> tuple[tuple[str, tuple[str, ...]], ...]:
        """The effective reserve set, falling back to what we model."""
        return self.reserve_properties or self.used_properties

    @property
    def states_per_custom_block(self) -> int:
        return _product(self.reserve_props)

    @property
    def capacity(self) -> int:
        return len(self.blocks) * self.states_per_vanilla_block

    def vanilla_states(self) -> list[tuple[str, str]]:
        """Every (block, property-string) pair in the family, in a stable order."""
        out: list[tuple[str, str]] = []
        for block in self.blocks:
            for combo in _combos(self.all_properties):
                out.append((block, combo))
        return out


def _product(props) -> int:
    total = 1
    for _, values in props:
        total *= len(values)
    return total


def _combos(props) -> list[str]:
    """All property combinations as vanilla-style ``a=x,b=y`` strings."""
    names = [n for n, _ in props]
    value_lists = [v for _, v in props]
    out = []
    for combo in itertools.product(*value_lists):
        out.append(",".join(f"{n}={v}" for n, v in zip(names, combo)))
    return out


# --- the families ------------------------------------------------------------

FAMILIES: dict[str, ShapeFamily] = {
    "stairs": ShapeFamily(
        name="stairs",

        # Lendable states must be freed by a block_state_mapping, dry and straight
        # (see CarrierPool.build). On 26.3 that leaves the 24 unwaxed copper stair
        # states CraftEngine maps onto their waxed twins and default_assets does not
        # claim: three stairs. Every other stair state is either reachable by real
        # vanilla stairs or wet.
        blocks=(
            "oak_stairs",
            "spruce_stairs",
            "birch_stairs",
            "jungle_stairs",
            "acacia_stairs",
            "dark_oak_stairs",
            "mangrove_stairs",
            "cherry_stairs",
            "bamboo_stairs",
            "bamboo_mosaic_stairs",
            "crimson_stairs",
            "warped_stairs",
            "cobblestone_stairs",
            "mossy_cobblestone_stairs",
            "stone_stairs",
            "andesite_stairs",
            "polished_andesite_stairs",
            "granite_stairs",
            "polished_granite_stairs",
            "diorite_stairs",
            "polished_diorite_stairs",
            "brick_stairs",
            "stone_brick_stairs",
            "mossy_stone_brick_stairs",
            "sandstone_stairs",
            "smooth_sandstone_stairs",
            "red_sandstone_stairs",
            "smooth_red_sandstone_stairs",
            "prismarine_stairs",
            "prismarine_brick_stairs",
            "dark_prismarine_stairs",
            "nether_brick_stairs",
            "red_nether_brick_stairs",
            "quartz_stairs",
            "smooth_quartz_stairs",
            "blackstone_stairs",
            "polished_blackstone_stairs",
            "polished_blackstone_brick_stairs",
            "cobbled_deepslate_stairs",
            "polished_deepslate_stairs",
            "deepslate_brick_stairs",
            "deepslate_tile_stairs",
            "mud_brick_stairs",
            "end_stone_brick_stairs",
            "purpur_stairs",
            "tuff_stairs",
            "polished_tuff_stairs",
            "tuff_brick_stairs",
            "cut_copper_stairs",
            "exposed_cut_copper_stairs",
            "weathered_cut_copper_stairs",
            "oxidized_cut_copper_stairs",
            "waxed_cut_copper_stairs",
            "waxed_exposed_cut_copper_stairs",
            "waxed_weathered_cut_copper_stairs",
            "waxed_oxidized_cut_copper_stairs",
),
        all_properties=(
            ("facing", tuple(FACING)), ("half", tuple(HALF)),
            ("shape", tuple(STAIRS_SHAPE)), ("waterlogged", tuple(BOOLEAN)),
        ),
        # A stair declares facing x half x shape (40 states), like a vanilla stair
        # minus waterlogged. Each lent state is a freed carrier whose own shape is
        # the declared shape, so corners collide as corners. On 26.3 every state of
        # the four unwaxed copper stairs is freed, and their 40 dry states each are
        # exactly one full stair per copper block.
        #
        # This used to be facing x half only, with shape=straight required of the
        # carrier: a full-shape stair cost 40 states when dozens of stairs competed
        # for the pool, so corners rendered as straight stairs. With a handful of
        # freed carriers per stair the trade-off no longer exists.
        used_properties=(
            ("facing", tuple(FACING)), ("half", tuple(HALF)),
            ("shape", tuple(STAIRS_SHAPE)),
        ),
        reserve_properties=(
            ("facing", tuple(FACING)), ("half", tuple(HALF)),
            ("shape", tuple(STAIRS_SHAPE)),
        ),
        prefer={"waterlogged": "false"},
    ),
    "wall": ShapeFamily(
        name="wall",
        merge_safe=False,
        # Walls are multipart. 24 candidates on 26.3, and borrowing them would
        # strip rendering from 7776 vanilla wall states - the worst ratio in the
        # database, so walls are rejected despite being our largest family.
        blocks=(
            "cobblestone_wall",
            "mossy_cobblestone_wall",
            "andesite_wall",
            "sandstone_wall",
            "red_sandstone_wall",
            "blackstone_wall",
            "polished_blackstone_wall",
            "polished_blackstone_brick_wall",
            "cobbled_deepslate_wall",
            "polished_deepslate_wall",
            "deepslate_brick_wall",
            "infested_cobblestone_wall", "infested_mossy_cobblestone_wall",
            "infested_stone_wall", "infested_mossy_stone_wall",
            "infested_stone_brick_wall", "infested_mossy_stone_brick_wall",
            "infested_cracked_stone_brick_wall", "infested_chiseled_stone_brick_wall",
            "deepslate_wall", "mossy_deepslate_brick_wall",
            "smooth_sandstone_wall", "smooth_red_sandstone_wall",
            "deepslate_tile_wall",
            "mossy_stone_brick_wall",
            "brick_wall",
            "granite_wall",
            "diorite_wall",
            "mud_brick_wall",
            "polished_tuff_wall",
            "tuff_wall",
            "tuff_brick_wall",
            "end_stone_brick_wall",
            "prismarine_wall",
            "red_nether_brick_wall",
            "nether_brick_wall",
),
        all_properties=(
            ("east", tuple(WALL_SIDE)), ("north", tuple(WALL_SIDE)),
            ("south", tuple(WALL_SIDE)), ("up", tuple(BOOLEAN)),
            ("west", tuple(WALL_SIDE)),
        ),
        used_properties=(
            ("east", tuple(BOOLEAN)), ("north", tuple(BOOLEAN)),
            ("south", tuple(BOOLEAN)), ("west", tuple(BOOLEAN)),
        ),
        value_aliases={side: {"true": "low", "false": "none"}
                       for side in ("north", "east", "south", "west")},
    ),
    "slab": ShapeFamily(
        name="slab",

        # Same as stairs: all 180 lendable slab states are waterlogged. There is no
        # dry state to lend, because every one is spoken for by the converted
        # carrier's own rendering.
        blocks=(
            "oak_slab",
            "spruce_slab",
            "birch_slab",
            "jungle_slab",
            "acacia_slab",
            "dark_oak_slab",
            "mangrove_slab",
            "cherry_slab",
            "bamboo_slab",
            "bamboo_mosaic_slab",
            "crimson_slab",
            "warped_slab",
            "petrified_oak_slab",
            "cobblestone_slab",
            "mossy_cobblestone_slab",
            "stone_slab",
            "smooth_stone_slab",
            "andesite_slab",
            "polished_andesite_slab",
            "granite_slab",
            "polished_granite_slab",
            "diorite_slab",
            "polished_diorite_slab",
            "brick_slab",
            "stone_brick_slab",
            "mossy_stone_brick_slab",
            "sandstone_slab",
            "cut_sandstone_slab",
            "smooth_sandstone_slab",
            "red_sandstone_slab",
            "cut_red_sandstone_slab",
            "smooth_red_sandstone_slab",
            "prismarine_slab",
            "prismarine_brick_slab",
            "dark_prismarine_slab",
            "nether_brick_slab",
            "red_nether_brick_slab",
            "quartz_slab",
            "smooth_quartz_slab",
            "blackstone_slab",
            "polished_blackstone_slab",
            "polished_blackstone_brick_slab",
            "cobbled_deepslate_slab",
            "polished_deepslate_slab",
            "deepslate_brick_slab",
            "deepslate_tile_slab",
            "mud_brick_slab",
            "end_stone_brick_slab",
            "purpur_slab",
            "tuff_slab",
            "polished_tuff_slab",
            "tuff_brick_slab",
            "cut_copper_slab",
            "exposed_cut_copper_slab",
            "weathered_cut_copper_slab",
            "oxidized_cut_copper_slab",
            "waxed_cut_copper_slab",
            "waxed_exposed_cut_copper_slab",
            "waxed_weathered_cut_copper_slab",
            "waxed_oxidized_cut_copper_slab",
),
        all_properties=(
            ("type", tuple(SLAB_TYPE)), ("waterlogged", tuple(BOOLEAN)),
        ),
        used_properties=(("type", tuple(SLAB_TYPE)),),
    ),
    # Vertical slabs: a standing half-block plane. No ordinary vanilla block has that
    # collision, which is the whole reason this family is separate from "slab" and not
    # a variant of it.
    #
    # /cmbvshape swept every block in the live 26.3 registry (1,560 of them) for a
    # collision that is full height, full width on one horizontal axis and thin on the
    # other. 52 qualify as single boxes and every one of them is a door (0.1875), a
    # ladder (0.1875) or a shelf (0.3125) - all far too thin. Exactly one is 0.5:
    #
    #   bell[attachment=floor,facing=north] -> [0,0,0.25 .. 1,1,0.75]
    #
    # A bell is therefore the only exact vertical slab carrier in the game, and that is
    # the reason this family exists at all. It needs no display entity, no Interaction
    # and no barrier cube: it is an ordinary block with an ordinary collision shape, so
    # collision follows from the carrier exactly as it does for every other family.
    #
    # Capacity is 4 and cannot be otherwise: the bell has four floor states (one per
    # facing) and all four must be free. That is checked against the enabled packs'
    # block_state_mappings like any other carrier; see DESIGN.md 6.1 for why the pack
    # has to free them itself.
    "vertical_slab": ShapeFamily(
        name="vertical_slab",
        blocks=("bell",),
        all_properties=(
            ("attachment", ("floor", "wall", "ceiling")),
            ("facing", ("north", "south", "east", "west")),
            ("powered", tuple(BOOLEAN)),
        ),
        # A vertical slab's whole state space is which way it faces. There is no
        # "double", because two vertical slabs side by side are two blocks, and no
        # half/top/bottom, because that describes a lying slab.
        used_properties=(("facing", ("north", "south", "east", "west")),),
        # The bell's own rendering and physics must not be disturbed: only the four
        # floor states are candidates and each has to be freed by a mapping. The
        # attachment property is pinned to floor because every other attachment has a
        # different collision shape entirely (a ceiling bell is thin on Y).
        required_carrier_properties={
            "bell": {"attachment": ("floor",)},
        },
    ),
    # Pressure plates and buttons share one pool. A vanilla pressure plate has only
    # two states and needs both, so it lends nothing; vanilla buttons have twelve,
    # which leaves seven each. Both are small flat plates, so a button state is a
    # close enough collision carrier for a pressure plate.
    "plate": ShapeFamily(
        name="plate",
        blocks=(
            "stone_button",
            "polished_blackstone_button",
            "spruce_button",
            "birch_button",
            "jungle_button",
            "acacia_button",
            "dark_oak_button",
            "oak_button",
            "mangrove_button",
            "cherry_button",
            "bamboo_button",
            "crimson_button",
            "warped_button",
),
        all_properties=(
            ("face", ("floor", "wall", "ceiling")),
            ("facing", tuple(BUTTON_FACING)),
            ("powered", tuple(BOOLEAN)),
        ),
        used_properties=(
            ("face", ("floor", "wall", "ceiling")),
            ("facing", tuple(BUTTON_FACING)),
            ("powered", tuple(BOOLEAN)),
        ),
        reserve_properties=(
            ("face", ("floor", "wall", "ceiling")),
            ("facing", tuple(BUTTON_FACING)),
            ("powered", tuple(BOOLEAN)),
        ),
    ),
    # Glass panes were borrowing the wall pool, which gave them wall collision - a
    # 1.5-block post with arms instead of a thin panel. A vanilla pane has the same
    # four connection booleans plus waterlogged, so like walls and stairs it can lend
    # its waterlogged states: the block keeps its sixteen dry states to render as
    # itself and lends the other sixteen.
    "pane": ShapeFamily(
        name="pane",
        merge_safe=False,
        # Panes are multipart; borrowing one would strip vanilla pane rendering.
        blocks=(
            "glass_pane", "white_stained_glass_pane", "orange_stained_glass_pane",
            "magenta_stained_glass_pane", "light_blue_stained_glass_pane",
            "yellow_stained_glass_pane", "lime_stained_glass_pane",
            "pink_stained_glass_pane", "gray_stained_glass_pane",
            "light_gray_stained_glass_pane", "cyan_stained_glass_pane",
            "purple_stained_glass_pane", "blue_stained_glass_pane",
            "brown_stained_glass_pane", "green_stained_glass_pane",
            "red_stained_glass_pane", "black_stained_glass_pane",
        ),
        all_properties=(
            ("east", tuple(BOOLEAN)), ("north", tuple(BOOLEAN)),
            ("south", tuple(BOOLEAN)), ("waterlogged", tuple(BOOLEAN)),
            ("west", tuple(BOOLEAN)),
        ),
        used_properties=(
            ("east", tuple(BOOLEAN)), ("north", tuple(BOOLEAN)),
            ("south", tuple(BOOLEAN)), ("west", tuple(BOOLEAN)),
        ),
        reserve_properties=(
            ("east", tuple(BOOLEAN)), ("north", tuple(BOOLEAN)),
            ("south", tuple(BOOLEAN)), ("west", tuple(BOOLEAN)),
        ),
    ),
    "fence": ShapeFamily(
        name="fence",
        merge_safe=False,
        # Fences are multipart; borrowing one would strip vanilla fence rendering.
        blocks=(
            "oak_fence",
            "spruce_fence",
            "birch_fence",
            "jungle_fence",
            "acacia_fence",
            "dark_oak_fence",
            "mangrove_fence",
            "cherry_fence",
            "bamboo_fence",
            "crimson_fence",
            "warped_fence",
            "nether_brick_fence",
),
        all_properties=(
            ("east", tuple(BOOLEAN)), ("north", tuple(BOOLEAN)),
            ("south", tuple(BOOLEAN)), ("waterlogged", tuple(BOOLEAN)),
            ("west", tuple(BOOLEAN)),
        ),
        used_properties=(
            ("east", tuple(BOOLEAN)), ("north", tuple(BOOLEAN)),
            ("south", tuple(BOOLEAN)), ("west", tuple(BOOLEAN)),
        ),
    ),
}

#: A pillar needs three visual orientations. Vanilla logs have exactly three
#: states (``axis``) and need all of them to render, so they cannot host a mod
#: pillar. Instead we carry pillars on full cubes using a 3-value ``rotation``
#: property, which the wiki documents as a special property name. The cost is that
#: collision is a full cube rather than vanilla's 2/16-inset column.
PILLAR_ROTATIONS = 3

#: Full-cube carriers: CraftEngine's own ``solid`` auto-state group.
#:
#: A lent state has to be *freed*: a CraftEngine ``block_state_mapping`` must draw
#: every real vanilla block in that state as something else, or the real block shows
#: our model. The previous pool (barrel, crafter, chiseled bookshelf, trial spawner,
#: creaking heart, respawn anchor) lent states players reach in normal play - a real
#: barrel facing east was drawn as diorite bricks. Collision-safe is not visually
#: safe.
#:
#: Note blocks and the three mushroom blocks are full cubes whose states CraftEngine's
#: internal pack maps away almost entirely (every note block is drawn as one state,
#: every mushroom block face combination as its all-faces state), which is exactly why
#: CraftEngine's ``auto_state: solid`` draws from them. Which states are actually free
#: is not assumed here: it comes from the mappings on disk (block_mappings.py), and
#: the property vocabularies come from Mojang's block report (block_identities.py),
#: never a hand-typed table.
#:
#: No conversion: the mapping already protects every real block, so nothing has to
#: be turned into a CraftEngine block to keep rendering as itself.
SOLID_CARRIERS: tuple[str, ...] = (
    "note_block",
    "brown_mushroom_block",
    "red_mushroom_block",
    "mushroom_stem",
)

#: The previous cube carriers, kept so tests can assert they are never lent again.
#: Each has states that real vanilla blocks reach in survival and nothing frees.
FORMER_CUBE_CARRIERS: tuple[str, ...] = (
    "barrel", "crafter", "chiseled_bookshelf", "trial_spawner", "creaking_heart",
    "respawn_anchor",
)



@dataclasses.dataclass
class CubeCarrierPool:
    """Lends mapping-freed full-cube states from the solid pool, in a fixed order."""

    #: block -> lendable canonical "prop=val,..." strings, in hand-out order
    lendable: dict[str, list[str]]
    #: kept for the allocator's snapshot/restore; nothing is reserved or converted
    reserved: dict[str, str | None]
    used: dict[str, int] = dataclasses.field(default_factory=dict)
    touched: list[str] = dataclasses.field(default_factory=list)
    #: freed states skipped because another CraftEngine pack already binds them
    externally_claimed: list[str] = dataclasses.field(default_factory=list)

    @classmethod
    def build(cls, registry: set[str] | None = None,
              claimed: set[str] | None = None,
              freed: set[str] | None = None,
              canonical=None) -> "CubeCarrierPool":
        """Build the pool from the freed states of the solid carriers.

        ``freed`` is the canonical set from ``BlockMappings.freed``; without it the
        pool is empty, because nothing can be proven safe to lend. ``canonical``
        canonicalises claims (``Identities.canonical``) so a claim written as a bare
        or partial state still removes the exact state it names.
        """
        taken: set[str] = set()
        for state in claimed or ():
            if canonical is not None:
                state = canonical(state)[0] or state
            taken.add(state)

        lendable: dict[str, list[str]] = {}
        externally_claimed: list[str] = []
        for block in SOLID_CARRIERS:
            if registry is not None and block not in registry:
                continue
            prefix = f"minecraft:{block}["
            states = sorted(s for s in (freed or ()) if s.startswith(prefix))
            lendable[block] = []
            for state in states:
                if state in taken:
                    externally_claimed.append(state)
                    continue
                lendable[block].append(state[len(prefix):-1])
        pool = cls(lendable, {block: None for block in lendable})
        pool.externally_claimed = externally_claimed
        return pool

    def capacity(self) -> int:
        return sum(len(v) for v in self.lendable.values())

    def take(self) -> str | None:
        """Consume the next lendable state, note blocks first."""
        for block in SOLID_CARRIERS:
            if self.lendable.get(block):
                self.used[block] = self.used.get(block, 0) + 1
                if block not in self.touched:
                    self.touched.append(block)
                return f"minecraft:{block}[{self.lendable[block].pop(0)}]"
        return None

    def convertible(self) -> list[str]:
        """Nothing is converted: the mappings protect every real block."""
        return []


@dataclasses.dataclass
class Allocation:
    """Which vanilla states back which custom blocks."""

    #: family -> list of (custom_block_id, [(property_string, vanilla_block, vanilla_props)])
    mod_carriers: dict[str, list[tuple[str, list[tuple[str, str]]]]]
    #: family -> list of vanilla block ids converted into CraftEngine blocks
    converted_vanilla: dict[str, list[str]]
    #: family -> (capacity, states_used, states_available)
    report: dict[str, tuple[int, int, int]]
    overflow: list[str]


@dataclasses.dataclass
class CarrierPool:
    """Hands out vanilla states that match a requested property combination.

    A carrier is only usable if its own properties agree with the CraftEngine
    properties we declared, otherwise collision and sound come from the wrong
    configuration: an east-facing stair carried by a north-facing vanilla stair
    would have a north-facing hitbox. So the pool is keyed by *projection* - the
    declared properties read off a candidate state - and every candidate under a
    key is interchangeable.

    States that a converted vanilla block needs in order to render as itself are
    held back. Those are the states where every property we do not model sits at
    its default, which for a wall is every side combination with ``up=false``.
    """

    family: ShapeFamily
    #: projection string -> list of available vanilla states, in stable order
    available: dict[str, list[tuple[str, str]]]
    #: how many of each projection we handed out
    used: dict[str, int]
    #: vanilla blocks we drew from, and therefore must convert
    touched: set[str]
    #: the candidates this pool was actually allowed to consider, after any registry
    #: filter. The canonical family list is deliberately left untouched; this is what
    #: capacity should be reported against.
    candidates: list[str]
    #: the natural states each touched block keeps for itself
    reserved_for: dict[str, list[tuple[str, str]]]
    #: states skipped because another CraftEngine pack already binds them
    externally_claimed: list[str] = dataclasses.field(default_factory=list)
    #: valid states turned away because they are not collision-compatible
    rejected: list[str] = dataclasses.field(default_factory=list)
    #: set when the family is ineligible to lend at all, with the reason
    ineligible: str = ""

    @classmethod
    def build(cls, family: ShapeFamily,
              registry: set[str] | None = None,
              claimed: set[str] | None = None,
              freed: set[str] | None = None,
              canonical=None) -> "CarrierPool":
        """Build the pool, optionally restricted to carriers that really exist.

        ``registry`` is the set of carrier block ids the running server actually
        has. The generator leaves it None so it plans against the whole canonical
        database; the plugin passes the live registry contents. Either way no
        content is removed - only the pool narrows.

        ``claimed`` is a set of full ``minecraft:block[props]`` strings another
        CraftEngine pack already binds to its own model. CraftEngine treats a vanilla
        state as a single-bind visual resource, so a state another pack holds is not
        available even though it exists. The candidate stays in the canonical
        database; it is simply not handed out.

        ``freed`` is the canonical set of states a block_state_mapping frees from
        vanilla use (block_mappings.py). Eligibility is per state: lendable only if
        freed, dry (an unmodded client draws a waterlogged carrier submerged),
        carrying the family's required properties, and unclaimed. Nothing is reserved
        and nothing is converted: the mapping already keeps real blocks looking like
        themselves. Without ``freed`` nothing can be proven safe, so the pool is empty.
        """
        available: dict[str, list[tuple[str, str]]] = {}
        reserved_for: dict[str, list[tuple[str, str]]] = {}
        candidates: list[str] = []
        externally_claimed: list[str] = []
        rejected: list[str] = []
        if not family.merge_safe:
            # Ineligible: contribute no capacity at all, so the allocator defers the
            # blocks that needed this family through its normal path.
            pool = cls(family=family, available={}, used={}, touched=set(),
                       candidates=[], reserved_for={},
                       ineligible="vanilla blockstate is multipart; borrowing it would "
                                  "strip rendering from states we do not own")
            pool.externally_claimed = []
            pool.rejected = []
            return pool

        taken: set[str] = set()
        for state in claimed or ():
            taken.add((canonical(state)[0] if canonical else None) or state)

        for block in family.blocks:
            reserved_for[block] = []
            if registry is not None and block not in registry:
                # Explicitly excluded: this version does not have the block. The
                # canonical family list keeps it, but it contributes nothing here.
                continue
            candidates.append(block)
            for combo in _combos(family.all_properties):
                state = dict(part.split("=", 1) for part in combo.split(","))
                full = f"minecraft:{block}[{combo}]"
                key = (canonical(full)[0] if canonical else None) or full
                if freed is None or key not in freed:
                    # Real vanilla blocks can show this state: lending it would draw
                    # our model on them.
                    continue
                if state.get("waterlogged") == "true":
                    rejected.append(full)
                    continue
                if key in taken:
                    externally_claimed.append(full)
                    continue
                projection = ",".join(
                    f"{k}={state[k]}" for k, _ in family.reserve_props)
                available.setdefault(projection, []).append((block, combo))

        # Reject states that are valid but not collision-compatible with what the
        # Paperized block declares. Counted so the diagnostic can say what was
        # turned away and why.
        required = family.required_carrier_properties
        if required:
            for key, bucket in list(available.items()):
                keep = []
                for entry in bucket:
                    state = dict(part.split("=", 1) for part in entry[1].split(","))
                    if all(state.get(k) in v for k, v in required.items()):
                        keep.append(entry)
                    else:
                        rejected.append(f"minecraft:{entry[0]}[{entry[1]}]")
                if keep:
                    available[key] = keep
                else:
                    del available[key]

        prefer = family.prefer
        if prefer:
            for key, bucket in available.items():
                bucket.sort(key=lambda entry: tuple(
                    0 if dict(part.split("=", 1) for part in entry[1].split(",")).get(k) == v
                    else 1 for k, v in sorted(prefer.items())))

        # Keyword arguments, because the field order is easy to get wrong and a
        # silent swap here would hand back the wrong mapping.
        pool = cls(family=family, available=available, used={}, touched=set(),
                   candidates=candidates, reserved_for=reserved_for)
        pool.externally_claimed = externally_claimed
        pool.rejected = rejected
        return pool

    def project(self, declared: dict[str, str]) -> str:
        """Canonical projection key for a declared property combination."""
        parts = []
        for name, _ in self.family.reserve_props:
            value = declared[name]
            parts.append(f"{name}={self.family.value_aliases.get(name, {}).get(value, value)}")
        return ",".join(parts)

    def vanilla_state(self, declared: dict[str, str]) -> str | None:
        """Consume and return one unused vanilla state matching a combination.

        The cursor has to advance here, not only in assign(), because the generator
        asks for carriers one block state at a time. Leaving it read-only would
        hand the same vanilla state to every block.
        """
        key = self.project(declared)
        take = self.used.get(key, 0)
        bucket = self.available.get(key, [])
        if take >= len(bucket):
            return None
        block, props = bucket[take]
        self.used[key] = take + 1
        self.touched.add(block)
        return f"minecraft:{block}[{props}]"

    def vanilla_state_nearest(self, declared: dict[str, str]) -> str | None:
        """Closest carrier for a combination whose exact projection is exhausted.

        Walls are the case that needs this. Vanilla has exactly one fully
        disconnected wall state per wall block, so the "nothing connected"
        projection can never satisfy 108 mod walls - there are only as many
        distinct wall shapes in the game as there are wall blocks. Rather than
        give up on walls, fall back to the projection with the *most* available
        carriers among those with at least as many connected sides as we asked
        for, so the carrier's collision is never smaller than the wall really is.
        A wall may therefore be a little too solid, but never pass-through.
        """
        exact = self.vanilla_state(declared)
        if exact is not None:
            return exact

        wanted = sum(1 for value in declared.values() if value in ("true", "low", "tall"))
        best_key, best_len = None, -1
        for key, bucket in self.available.items():
            if len(bucket) <= self.used.get(key, 0):
                continue
            sides = dict(part.split("=", 1) for part in key.split(","))
            have = sum(1 for v in sides.values() if v in ("low", "tall"))
            if have < wanted:
                continue
            if len(bucket) > best_len:
                best_key, best_len = key, len(bucket)
        if best_key is None:
            return None
        return self.vanilla_state(
            {k: v for k, v in (part.split("=", 1) for part in best_key.split(","))})

    def capacity_for(self, projection: str) -> int:
        return len(self.available.get(projection, ()))

    def assign(self, projection: str, count: int) -> list[tuple[str, str]]:
        """Take `count` carriers matching `projection`, or raise if short."""
        bucket = self.available.get(projection, [])
        take = self.used.get(projection, 0)
        if take + count > len(bucket):
            raise CarrierExhausted(
                f"{self.family.name}: need {count} more carrier(s) for "
                f"[{projection}], only {max(0, len(bucket) - take)} of "
                f"{len(bucket)} left")
        chunk = bucket[take:take + count]
        self.used[projection] = take + count
        for block, _ in chunk:
            self.touched.add(block)
        return chunk

    @property
    def converted(self) -> list[str]:
        """Nothing is converted: every lent state is mapping-freed, so the mapping
        already keeps real blocks in that state looking like themselves."""
        return []

    @property
    def candidates_used(self) -> int:
        return len(self.converted)

    # Accounting shares one denominator. `available` is the post-filter pool:
    # states that exist, are unique, are unclaimed by another pack, and are
    # collision-safe. Reserved states (the ones a converted carrier keeps to render
    # as itself) are counted separately rather than added on top, so used + free
    # always equals available and free can never go negative.
    @property
    def capacity_states(self) -> int:
        """Lendable capacity: the size of the collision-safe bucket space.

        Bucket lengths are static; they are capacity, not headroom.
        """
        return sum(len(v) for v in self.available.values())

    @property
    def assigned_states(self) -> int:
        """States actually consumed by allocations."""
        return sum(self.used.values())

    @property
    def available_states(self) -> int:
        """Capacity minus what has been handed out, i.e. the real headroom."""
        return self.capacity_states - self.assigned_states

    @property
    def reserved_states(self) -> int:
        """States each converted carrier keeps to render as itself.

        Metadata about what a carrier owns, deliberately kept out of the assigned
        and available denominators: mixing it in made a pool with three
        assignments look identical to one with six.
        """
        return sum(len(self.reserved_for[b]) for b in self.converted)

    @property
    def states_used(self) -> int:
        return self.assigned_states

    @property
    def states_free(self) -> int:
        return self.available_states


class CarrierExhausted(RuntimeError):
    """Raised when a family cannot supply the carriers a block needs."""


