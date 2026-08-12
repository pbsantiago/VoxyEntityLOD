# VoxyEntityLOD

Renders entities and Create contraptions beyond the default render distance, integrated with Voxy's LOD terrain system.

## Overview

VoxyEntityLOD was originally designed to render **regular entities** (mobs, villagers, items, etc.) outside the vanilla render distance by creating server-side tracked copies. Support for **Create contraptions** (stationary and moving structures) was added later and has become one of the mod's most important features — allowing contraptions to remain visible far beyond where Create itself stops rendering them.

The mod works alongside Voxy's hierarchical LOD terrain to provide a seamless visual experience from near to far.

## Requirements

- Minecraft **1.20.1** + Fabric Loader
- **Fabric API** >= 0.91.1
- **Voxy** >= 0.2.0 (required — the mod does nothing without it)
- **Create** (optional, Fabric 6.0.8.1 — required for contraption rendering)
- **EntityCulling** (optional — if installed, entities behind solid geometry are automatically hidden)

## How it works

The server tracks entities within a configurable range (up to 2048 blocks, or 4× the server view distance). When an entity approaches the edge of vanilla tracking, a **prefetch** copies its data to the client ahead of time. When vanilla drops the entity, the copy takes over instantly — no pop-in, no delay.

### LOD tiers

Distance thresholds scale with Voxy's `sectionRenderDistance` slider (default 512 display value ≈ 8192 blocks):

| Range (% of Voxy max) | Entities | Create Contraptions |
|---|---|---|
| 0–4% | Full vanilla model | Full SBB (SuperByteBuffer) mesh |
| 4–100% | Coloured wool block | Coloured wool block |

> Wool blocks are tinted to match the entity/contraption's predominant colour via a curated colour table (entities) or the most common block in the contraption's palette.

### Performance

- **Underground culling**: entities below the surface heightmap are skipped entirely
- **EntityCulling integration**: if the mod is present, its per-frame occlusion raycasting is respected — hidden entities are not rendered
- **Server range is dynamic**: `rangeBlocks` refreshes once per second from the server's current `view-distance`, supporting dynamic render distance changes

## Shader compatibility

Since **1.1.0**, LOD geometry is drawn **inside the entity phase of `renderLevel`** (at `ClientLevel.entitiesForRendering()`, between the solid/cutout chunk layers and the translucent pass) instead of at the `renderLevel` TAIL. At the TAIL the draw landed outside Iris's gbuffer passes — after the pipeline finalizes — so shader packs that replaced the vanilla render types showed nothing, invisible wool, or wrong colours. Inside the entity phase, the vanilla render types used by the mod (entity models, wool blocks, Create meshes) are mapped by the shader pack to the correct gbuffers with proper depth, fog and lighting.

Shader packs that remap render types for their own effects may still look off — **testing is welcome**; report packs that work (or don't) so compatibility can be improved.

## Credits

**Turiom**
