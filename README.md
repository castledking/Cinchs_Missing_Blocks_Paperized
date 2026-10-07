<p align="center">
  <a href="https://castled.codes"><img alt="CASTLED CODEX" src="https://castled.codes/assets/cmb-banner.png" width="754" height="354"></a>
  </a>
</p>

<p align="center">
  <a href="https://discord.com/invite/pCKdCX6nYr"><img alt="Discord" src="https://img.shields.io/badge/Discord-Community-5865F2?style=for-the-badge&logo=discord&logoColor=white"></a>
  <a href="https://github.com/castledking/Cinchs_Missing_Blocks_Paperized/issues"><img alt="GitHub Issues" src="https://img.shields.io/badge/GitHub-Issues-181717?style=for-the-badge&logo=github"></a>
  <a href="https://github.com/castledking/Cinchs_Missing_Blocks_Paperized/wiki"><img alt="Wiki" src="https://img.shields.io/badge/GitHub-Wiki-181717?style=for-the-badge&logo=github"></a>
  <a href="https://castled.codes"><img alt="CASTLED CODEX" src="https://castled.codes/assets/logo-banner.png" width="140" height="35"></a>
</p>

**Cinch's Missing Blocks — Paperized** brings the building blocks from the [*Cinch's Missing Blocks*](https://modrinth.com/mod/cinchs-missing-blocks) mod to Paper servers. It runs as a **CraftEngine** pack plus a small companion plugin, and players join with a **vanilla client**. The server sends the resource pack, and that's all they need.

***

## What you get

**996 pieces** across every family the mod has — **342 blocks** and **654 furniture** — all of them placeable. Every count below is read out of the committed `pack/` in this repository, not typed by hand.

**Blocks** — real CraftEngine blocks on a borrowed vanilla state, with vanilla collision:

| | |
|---|---|
| 174 | vertical slab doubles — two vertical slabs stacked make the full block |
| 94 | horizontal slab doubles |
| 61 | full blocks: bricks, tiles, polished, cracked and mossy variants across andesite, calcite, deepslate, diorite, dripstone, end stone, granite, mud, prismarine, quartz, sandstone, stone, tuff and more |
| 13 | pillars, rotating on all three axes |

**Furniture** — every material, spending no vanilla states:

| | |
|---|---|
| 174 | horizontal stairs — a stair on its side, the corner piece for vertical slab walls |
| 174 | vertical slabs — a slab stood on edge, for CMB **and** all vanilla materials |
| 108 | walls, with proper posts and connection behaviour |
| 97 | stairs |
| 94 | horizontal slabs |
| 5 | fences |
| 1 | tinted glass pane |
| 1 | warped nether wart crop, this port's own addition |

A doubled slab is **one piece, not two**, and it is a real block: placed in a cell it becomes a
block on the `solid` pool with its own hardness, mining tags, sounds and loot. The pack also
ships a furniture representation of the same doubled slab, so a server can pick the form that
suits it — `features.vertical-slabs.doubles` chooses `block`, `furniture` or `both`, and
`both` is the default here. That is two *representations* of one piece, which is why the
counts above do not add it twice.

All **381** of the mod's blocks are served — 342 as blocks, and walls, fences, panes, stairs
and slabs as furniture, for the reason below. Alongside them come **1,822 items** and
**1,127 recipes**, with drops, loot tables, mining tags and creative categories generated
from the mod's own data.

## Nothing vanilla changes

Many CraftEngine packs borrow vanilla block states that real blocks also use, which is why a barrel or a crafter can suddenly look like something else. **This pack never does that.** Every state a block uses is one CraftEngine has already freed from vanilla — real blocks in that state are always drawn as an identical-looking twin, so no real block in your world ever changes appearance.

Where a safe state doesn't exist, the pack doesn't borrow one. Walls and fences are the clearest example: every vanilla wall blockstate is *multipart*, and overwriting one strips the multipart rules, leaving thousands of real wall states with no model at all. So walls and fences aren't drawn on borrowed states — they're built as furniture instead. The build refuses anything it can't prove safe.

## Built on CraftEngine furniture and scaled shulker hitboxes

Most of this pack is **not** a block drawn on a vanilla state. It's **CraftEngine furniture**: a display entity for the model, and CraftEngine's built-in **scaled shulker hitbox** for collision. That hitbox is what makes walls, fences, horizontal stairs and vertical slabs actually usable rather than merely visible:

*   It's an **invisible, AI-less, client-side-only entity**. Players never see it or interact with it as an entity — it's pure client packets — while the collision is a real server-side box.
*   It's **scaled**, so a hitbox can be shaped instead of being a fixed cube. Walls and fences tile several together, which is how a 2-block-tall wall gets the right collision on every face.
*   You can **walk into them, stand on them, build against them and break them** like any other block.

Walls and fences use **connection-aware variants** — the shape changes based on which sides have neighbours, so posts appear at the ends and sides join in the middle exactly like vanilla.

Vertical slabs are one per material, for CMB *and* vanilla (`oak_vertical`, `acacia_vertical`, and so on). Place them on the **ground** or against a **wall** — the collision matches the placement either way.

### Horizontal stairs

A **horizontal stair** is a stair laid on its side: an L that fills three quarters of the cell and stands full height. It's the piece that joins **two vertical slab walls** together, closing the corner between them.

They work the same way the vertical slabs do — furniture, with the same material list (CMB and vanilla), the same ground/wall/ceiling placements and real collision. Place a vertical slab, another opposite it, and a horizontal stair between them to get a corner that walks and builds like a real one.

## Double slabs

Stacking two matching slabs merges them into a full block, and breaking that block drops **2 slabs** back. Works for both horizontal and vertical, across CMB and vanilla materials.

## Trade-offs worth knowing

Furniture is not blocks. No redstone or neighbour updates, and each piece is an entity. For building blocks that's exactly what you want, but it's why the furniture families are **opt-in** — flip `features.vertical-slabs`, `features.horizontal-stairs` and `features.furniture-fallback` in the plugin config. The plain blocks, stairs and slabs are always on.

## Requirements

*   Paper **1.21.1 – 26.3**
*   Java **21** or newer (Paper 26.x itself needs Java 25)
*   **CraftEngine** 26.9 or newer

One jar covers the whole range, and CI boots it on 1.21.1, 1.21.11 and 26.3 on every push. On
a server older than 26.x, the vertical slabs and horizontal stairs of vanilla materials it
doesn't have (cinnabar, sulfur and poplar; before 1.21.4 also pale oak and resin bricks) are
left out automatically, instead of showing with the missing texture.

**1.20.x and older, 1.8 included, are not supported, and can't be.** CMB is a CraftEngine pack,
and CraftEngine's oldest supported version is 1.20.1 (its own dev servers start there). Below
that there is nothing for CMB to run on. Every CMB furniture piece also collides through a
scaled shulker hitbox, and CraftEngine applies hitbox scale only from 1.20.5
(`ShulkerFurnitureHitbox`, behind `VersionHelper.isOrAbove1_20_5`). Below 1.20.5, every wall,
fence, stair and vertical slab would collide as a full block. CMB itself starts at 1.21.1, the
version the mod it ports is written for, and the oldest Paper API its code is checked against.