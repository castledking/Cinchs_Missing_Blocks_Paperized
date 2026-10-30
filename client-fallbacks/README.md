# Assets for older clients

These are the nine vanilla texture/model pairs referenced by the generated CMB pack
that are present in the official 26.3 client but absent from 1.21.1. They cover
cinnabar, cinnabar bricks, polished cinnabar, sulfur, sulfur bricks, polished sulfur,
poplar planks, pale oak planks, and resin bricks.

`manifest.json` records the source client SHA-1 and each bundled file's SHA-256.
Models and textures live under `cinchsmissingblocks:block/vanilla/` so the fallback
does not replace vanilla assets. The generator rewrites references to these assets;
normal builds use the already-generated files in `pack/`.

Run `./gradlew regeneratePack` in the workspace to regenerate the bundle.
`LegacyClientAssetsTest` checks every bundled model's parents and textures against
the actual 1.21.1 asset inventory plus the generated resource pack. This covers
older ViaBackwards clients as well as native clients.
