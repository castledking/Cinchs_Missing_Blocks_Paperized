# Design notes — Cinch's Missing Blocks, Paperized

Working notes for the port. Everything here was established by reading the mod
sources and the CraftEngine 26.9.2 sources/wiki; file references are given so the
claims can be re-checked.

## 1. What the port has to reproduce

`ModBlocks.java` registers **381 blocks** (verified: 108 walls, 97 stairs,
94 slabs, 61 full cubes, 13 pillars, 5 fences, 1 pressure plate, 1 button,
1 glass pane) plus 2 standalone items, **895 recipes**, **381 loot tables** and
**1398 model JSONs**. See `tools/modsource.py`, which parses this at build time.

Roughly 95% of that is *data*, not code, and it is already in vanilla JSON form
under `common/src/main/resources/`. So the port is mostly a re-namespacing and
re-targeting exercise rather than a rewrite. The genuinely mod-only parts are
listed in §6.

## 1a. Build-time compatibility is not runtime availability

The generator never removes content because a carrier is missing on the target
version. The canonical content definition and the canonical carrier database are
both complete and version-agnostic; availability is answered at server startup.

    source mod -> generator -> complete content + canonical carriers
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

Consequences:

* A block introduced after 1.21.1 stays in the project. It simply is not enabled
  on an older server.
* `compatibility.unsupported-content` decides what happens to a block the running
  server cannot represent: `disable` skips it and reports it, `fail` refuses to
  start. Neither deletes anything.
* Carrier candidates carry no version filter in the database. The runtime asks the
  server's own block registry which candidates exist, which is exact rather than
  guessy, so supporting a new Minecraft version needs no edit here at all - the
  extra candidates simply start being found.

`VERIFIED_AGAINST` records the version the database was last checked against. It
is documentation for whoever reviews a capacity report, not an input to anything.

## 2. How CraftEngine actually renders a custom block

This is the part that is not obvious from the docs, and it dictates everything.

Every CraftEngine block state has two identities
(`configuration/block/states` in the wiki, and
`AbstractBlockManager` / `BukkitNetworkManager` in the source):

* **Visual block state** — an ordinary *vanilla* block state. It supplies the
  collision box, sound type, light emission, and it is what the client is told
  to render.
* **Internal block state** — CraftEngine's own id, what the server computes with.

`BukkitNetworkManager.remapBlockState(stateId, enableMod)` picks between two
packet remappers (`BukkitNetworkManager.java:329`):

| client | remapper | what it receives |
| --- | --- | --- |
| vanilla | `blockStateRemapper` | the **vanilla** carrier state; model comes from the `variants` entry CraftEngine writes into the vanilla blockstate file |
| CraftEngine client mod | `modBlockStateRemapper` (identity) | its own id; model comes from `assets/craftengine/blockstates/custom_<id>.json` |

**The client mod is not required.** It exists for `/setblock`, WorldEdit, Axiom
and Litematica. Correct rendering for a vanilla client works purely through the
generated resource pack.

### 2.1 The carrier-state ceiling

> "A single vanilla block state can only be bound to one unique custom model. If
> you bind the same vanilla state to two different custom models, the plugin will
> log a conflict warning."
> — wiki, `configuration/block/states`

Enforced in `AbstractBlockManager.arrangeModelForStateAndVerify`, which throws
`resource.block.state.model_conflict`. This is checked at config load **whether
or not** anyone has the client mod, so it is a hard ceiling either way.

Consequences:

1. A custom block state costs one **distinct vanilla block state**, and that
   vanilla state must have a matching **collision shape**. This is why there is a
   per-family budget rather than one global budget.
2. **Vanilla blocks that we borrow states from must themselves become CraftEngine
   blocks**, otherwise real vanilla blocks in the borrowed configurations would
   render as mod blocks.

The wiki's advertised escape hatch is `transparent: true` + `entity_renderer`
("virtually unlimited custom blocks"), but that renders through display entities —
one entity per block — and is not viable for thousands of structural blocks.

### 2.2 The per-family budget

Net capacity contributed by a vanilla block that we convert:

```
net(block) = states(block) - states_per_custom_block
```

because `states_per_custom_block` of its own states have to be spent keeping that
vanilla block looking like itself. The mod's own blocks then draw on the
remainder. `tools/vanilla_carriers.py` implements this.

## 3. Property sets, and what each one costs

Full vanilla registry state counts for 1.21.x (not the visually-deduplicated
counts). "spare" is `states - states_per_custom_block`, i.e. what a converted
vanilla block can lend once it has kept the states it needs to render itself
(those being the states where every unmodelled property is at its default).

| family | vanilla states/block | declared set | states/block | spare/block | mod blocks | spare total | demand |
| --- | --- | --- | --- | --- | --- | --- | --- |
| stairs | 80 (`facing`4 `half`2 `shape`5 `waterlogged`2) | `facing` x `half` | 8 | 72 | 97 | 4320 | 776 |
| wall | 162 (`north/east/south/west` = none/low/tall, `up`) | 4 boolean sides | 16 | 146 | 108 | 5256 | 1728 |
| slab | 6 (`type`3 `waterlogged`2) | `type` | 3 | 3 | 94 | **183** | **282** |
| fence | 32 | 4 boolean sides | 16 | 16 | 5 | 208 | 80 |
| cube | 1 | — | 1 | 0 | 61 + 13 pillars | 130 cubes | 61 + 13x3 |

Verified by running `tools/vanilla_carriers.py` against the parsed mod:

| configuration | overflow | CraftEngine internal states |
| --- | --- | --- |
| terracotta + concrete variants **on** | **33 slabs** | 3570 |
| terracotta + concrete variants **off** | **none** | 2642 |

So the port fits `block.serverside-blocks: 3000` with the variants off.

Two families needed special handling:

### 3.1 Slabs — spare 183, demand 282

60-ish vanilla slabs lend 3 states each; 94 mod slabs need 3 each. **94
full-fidelity slabs cannot coexist with the vanilla slab set** — this is arithmetic,
not tuning.

Resolution, and it lines up with the config that was wanted anyway:

* `variants.disable-terracotta-variants` / `disable-concrete-variants` remove 33
  slabs, leaving 61 <= 183. **Zero overflow.**
* Leaving them on overflows by exactly the 33 terracotta/concrete slabs, so the
  generator must fail loudly with that message rather than emit a broken pack.

Alternatives if the variants must stay on: drop to 2 states per mod slab
(`bottom` + `double`, losing top slabs) still needs 188 > 183; dropping vanilla
slabs to 2 states raises the ceiling to 240 but costs vanilla slabs either their
`double` or their `top`.

### 3.2 Pillars — spare 0

A vanilla log has exactly three states (`axis`) and needs all of them to render
itself, so it lends nothing. There is no spare column-shaped carrier in vanilla.

Workaround: carry pillars on **full-cube** carriers with a 3-value `rotation`
property (wiki: `rotation`, `type: int`; a special name that triggers precise
rotation control). 13 pillars x 3 = 39 cube carriers. Cost: collision is a full
cube rather than vanilla's 2/16-inset column. Visually identical, hitbox
slightly generous.

## 4. Features that need the companion plugin

CraftEngine ships behaviours for stairs, slabs and fences, but each one *requires*
properties we deliberately do not declare, so they cannot be reused as-is:

| behaviour | requires | we declare | usable? |
| --- | --- | --- | --- |
| `stairs_block` | `shape` (5 values) | none | no — 8 states would become 40 |
| `slab_block` | `type`, `waterlogged` | `type` | partial |
| `fence_block` | `north/east/south/west`, optional `waterlogged` | 4 booleans | **yes** |
| wall | *no wall behaviour exists at all* | 4 booleans | n/a |

So the companion plugin (`cmb`) has to provide `cmb:stairs`, `cmb:slab` and
`cmb:wall` — not just walls. It ports the vanilla logic:

* placement (`getPlacementState`) — facing from player, `half` from clicked face
  and hit position, `type` from clicked face for slabs;
* `updateShape` — recompute connections on neighbour change;
* wall side resolution and post height;
* slab merge-to-double and the double-slab loot branch.

Nexo was checked as an alternative and is **not** a way out: its GitHub repo is
gone (only the Modrinth page remains) and its decompiled
`team.unnamed.creative.serialize.minecraft.blockstate.BlockStateSerializer` does
support full `multipart`, but it has no wall/connection mechanic either — only
`mechanics/furniture/connectable`.

## 5. New content: warped nether wart

Generated by `tools/recolor_wart.py` (default ramp `warped`, which is sampled
from the existing `warped_wart_block.png`). Output is in
`warped_netherwart_art/<variant>/`. Recolouring is a **palette-rank** mapping, not
a hue rotation: one shared source ramp is built across the item and both crop
stages, sorted by luminance, and each rank is mapped onto a target ramp. That
keeps the hand-painted shading intact and keeps the stages consistent with each
other and with the item. `--variant {warped,azure,blue,void}` are available.

Content:

* `warped_nether_wart` item — retinted nether wart.
* `warped_nether_wart` crop — `age` 0..3, retinted stages.
* `warped_wart_block` — 9x `warped_nether_wart`, using the existing texture.
* `blue_nether_brick` recipe switches from `nether_sprouts` to
  `warped_nether_wart`. The blue brick block family is otherwise unchanged.

### 5.1 Acquisition — the reason this is behind a config flag

A crop that nothing places is unobtainable, so it needs worldgen. Two candidate
mechanisms:

**(a) Soul-sand wart patch.** The vanilla nether fortress wart room is two 10 block (2x5) patches of soul sand, either 2x5 pattern detected can have a chance thats configured to swap the vanilla netherwart stage with the corresponding 
warped stage

**(b) Chunk-load conversion.** Never convert already player loaded chunks, only on first load should the roll happen

**(c) Biome transition between crimson/warped forests and soul sand valleys** A biome *tag* containing
`warped_forest` + `crimson_forest` would place the patch uniformly across those
biomes, which is not what we want. Putting it in **`soul_sand_valley` only**
gives the seam behaviour for free: `soul_sand_valley` is already the small biome
wedged between the two forests and the open nether, so the patches read as part
of the forest edge and are naturally rare. If a true N-block band is wanted on
top of that, the companion plugin can add a distance-to-biome-border check
during generation rather than baking it into a biome override.

The Crimson counterpart should be gated on the same flag so the two stay symmetric.

### 5.2 Config

```yaml
variants:
  disable-terracotta-variants: true
  disable-concrete-variants: false
content:
  warped-nether-wart: true        # crop, item, warped_wart_block, worldgen
  # when false, blue_nether_brick falls back to nether_sprouts
features:
  vertical-slabs: false           # experimental, see 6.1
```

## 6. Mod-only features

### 6.1 Vertical slabs — keep, default off

The feature is a mixin on the vanilla `SlabBlock` that adds `cinchs_vertical` +
`cinchs_vertical_side` properties and rotates the baked quads client-side
(`VerticalSlabBakedModel`, which rewrites packed vertex floats and remaps
`Direction`s). A plugin cannot ship a client-side `BakedModel`, so the rotated
geometry cannot be drawn for a vanilla client. Server-side placement, outline
shape and merge-to-double logic *are* portable.

Ship it behind `features.vertical-slabs: false` with the server-side half
implemented and the client limitation documented. Never on by default.

#### The collision question, measured rather than assumed

The whole feature turns on whether any vanilla block can carry a standing slab, so
that was checked against the live registry instead of being reasoned about.
`VerticalShapeProbe` (`/cmbvshape`) places every block's data on one block far from
the world and reports the collision, sweeping for a shape that is full height, full
width on one horizontal axis and thin on the other.

On 26.3, of 1,560 probed block types, 52 have a single collision box that is thin
and full height:

* all doors and ladders, 0.1875 thick - a fifth of a slab
* all shelves, 0.3125 thick
* **bell**, `attachment=floor`, at
  `[0.000 0.000 0.250 .. 1.000 1.000 0.750]` - full height, full on X, and
  exactly 0.5 on Z, centred.

So exactly one block in the game is a vertical slab, and it is a bell. That is a
real carrier, and it was the first design: a `vertical_slab` ShapeFamily over
`bell`, four `attachment=floor` states, each freed by a `block_state_mapping` the
pack declares itself.

**It is not what to ship.** Capacity is four, permanently, because the bell has
four floor states and the pack has to take every real floor bell's rendering to
get them. And it only fixes collision - the geometry still has to come from a
CraftEngine model, so the model route below is needed regardless. Carrying collision
in a scarce bell to get four blocks is not worth it when CraftEngine already ships a
collider that is not made of a vanilla block at all.

#### What to ship instead: CraftEngine furniture

`entity_renderer` plus a furniture hitbox is the same technique as AliCushions and
RP Engine - a display entity carries the geometry - but CraftEngine implements it
natively and attaches a real collider to it, so the "no collision" objection to the
display route does not apply.

CraftEngine's own `default_assets` pack uses exactly this shape, in `sofa.yml`:

```yaml
appearances:
  facing=north,shape=straight:
    state: barrier
    entity_renderer:
      item: default:sofa
      rotation: 270
```

and declares collision separately under `hitboxes`, where each hitbox takes
`type: shulker` with `direction`, `peek` and `scale`. For a wall-facing hitbox the
geometry CraftEngine builds is width `scale` and height `peek`-derived - a thin,
full-height plate aimed at one of four sides, with `AttachFace` set so the client
matches it.

That is the vertical slab exactly, and it is better than the bell in every way that
mattered:

* **Collision is the mod's own shape.** The mod's `CINCHS_NORTH` is
  `createCuboidShape(0,0,0, 16,16,8)`; a north-facing shulker hitbox is
  `scale: 0.5` on Z and full on Y. No proxy shape, no approximation.
* **No carrier is consumed.** A display entity is not a block state, so this does
  not draw on the state budget, does not need a `block_state_mapping`, and cannot
  break a real vanilla block's rendering. The 5 enabled slabs and 4 enabled stairs
  stay available to the lying families.
* **Geometry is unrestricted**, so the standing model is an ordinary CraftEngine
  model and nothing client-side is needed.

What it costs, honestly:

* It is **furniture, not a block.** No redstone, no neighbour updates, no
  block-to-block recipes, and breaking it goes through the furniture loot path. A
  vertical slab that behaves like a slab in every other respect is not this.
* It needs an **item** (`furniture_item`), because furniture is placed from an item.
* Every placed vertical slab is an entity. That is fine for a decorative slab and
  wrong for thousands in a build.

So: keep it behind `features.vertical-slabs`, default off, and treat it as the
feature the mod's vertical slabs are for - decoration that stands up against a wall.
It is the first thing in this port that the mod genuinely cannot do and that is
nonetheless correct for a vanilla client, and it is only that because CraftEngine
carries a collider that is not a vanilla block.

Also unportable as designed: `enableTuffBrickPillar` replaces the *Java class* of
vanilla `chiseled_tuff_bricks` via a MixinExtras `@WrapOperation` on `Blocks`
static init, adding an `axis` property to an already-registered block. On a
plugin this has to become a separate `tuff_brick_pillar` content block, and the
mod's client/server config-mismatch handshake becomes a kick.

### 6.2 Everything else

The builtin resource/data packs (`BuiltinResourcePacks`, `BuiltinDataPacks`,
`ModBuiltinPacks`) have no Paper equivalent. On a plugin they become a
CraftEngine pack plus an ordinary datapack, which is the point of the port.
