# CustomTrees: technical details

Companion to the [README](../README.md). This covers the rules behind growth and world generation, stored data, the web page and testing.

## Player growth

- Every growth is planned first. The tree only replaces air, grass, ferns, vines, natural leaves and its own saplings, never other wood, placed leaves or other blocks, and it never loads chunks.
- The plan is fired as a `StructureGrowEvent` with the player, so protection plugins can filter or cancel it. Cancelled or aborted growth refunds the catalyst and XP, with item metadata intact. Creative mode consumes nothing.
- CoreProtect logs the growth through that event. Trunks of protected trees (3x3, 5x5, luminous) are additionally logged as `CustomTrees` placements 20 ticks later, which is what makes SmoothTimber skip them.
- Coal prices: `settings.coal-costs.normal` (includes shrubs), `medium` (2x2), `giant` (3x3), `colossal` (5x5) and `hedge`, each 1 to 64, default 1. Charcoal is not accepted. Above one coal, the first click shows the price and a second click on the same formation within ten seconds confirms it. The hand used must hold the full price. A price change requires a new confirmation, and a change before the next-tick growth cancels and refunds it.
- Glowstone and coal cooldowns and daily limits are separate per player and per catalyst. Daily limits use UTC dates. Natural growth is never limited.
- Luminous-tree glowstone is marked in the chunk PersistentDataContainer. Mining it (also with Silk Touch or Fortune), explosions and withers remove it without drops; pistons can't move it.

## Tagged saplings

`/customtrees sapling <form> <size>` transforms the one vanilla sapling already in the main hand; it grants no item. Forms: `RANDOM`, `OPEN_GROWN`, `FOREST`, `WINDSWEPT`, `ANCIENT`. Sizes: `NORMAL`, `MEDIUM`, `GIANT`, `COLOSSAL` (1x1, 2x2, 3x3, 5x5). Every sapling in the formation must carry the same profile and the formation must match the size. Mismatches are rejected and paid catalysts or XP refunded, so tagging can't bypass growth costs. Needs Core 0.26.5 with the `TREE_GROWTH_PROFILES` capability.

## Garden trees

- Successfully grown tagged trees are registered, up to 128 trees with up to 512 leaf positions each. Existing trees are never adopted or scanned.
- `/customtrees prune` removes up to 16 nearby registered leaves without drops. It checks a six-block look distance, ownership, permission and block-break events.
- Regrowth waits for the owner to be online with `customtrees.garden`, rechecks build rights, events and Core protected regions (denied conservatively, even for regions owned by the tree owner) and never loads chunks. A due leaf only regrows if the chunk is loaded and the position is still air. Occupied positions are retired without replacing the block; temporarily denied leaves are retried after 60 seconds.
- State is stored in `plugins/CustomTrees/garden-trees.properties`, capped at 8 MiB. Loading never restores blocks. A corrupt file is left untouched and disables garden edits instead of being rewritten.

## Fire markers

`settings.fire-protect-trunks` and `fire-protect-crowns` mark only blocks committed as new custom trees, in chunk PDC with a limit of 16,384 markers per chunk. They block fire events that name a marked block directly as target or source. They don't contain fire or stop a wildfire spreading through unmarked blocks.

## World generation

- Trees in newly generated chunks are replaced by the same species generators (chance via `settings.replacement-chance`). The pass runs a few ticks after generation (`new-chunk-post-process-delay-ticks`), so Iris objects placed late are caught too.
- Only chunks generated in the current server run that are still untouched are changed: at most 60 seconds old, less than 10 seconds of player presence, no block placed or broken. Protected Core regions (claims, city plots, structures, arenas, spawn) are never changed. Existing chunks are never rewritten when loaded again.
- The search runs asynchronously on a chunk snapshot; replacing uses at most 3 ms per tick. The pass also removes pale hanging moss and mangrove propagules of the replaced vanilla tree.

## Biome profiles

Optional root-level `biome-profiles` groups select a growth profile by explicit biome key:

```yaml
biome-profiles:
  open-meadow:
    biomes: [minecraft:plains, minecraft:meadow]
    height-scale: 1.1        # 0.5 to 1.5
    crown-scale: 1.2         # 0.5 to 1.5
    crown-form: OPEN_GROWN   # RANDOM, OPEN_GROWN, FOREST, WINDSWEPT, ANCIENT
    replacement-chance: 0.8  # 0 to 1, defaults to settings.replacement-chance
```

Each biome may appear in only one group; at most 32 groups and 128 biome assignments. Profiles only affect natural tree replacement in new chunks, player growth is never randomly skipped. Empty profiles keep the original generation path; non-empty ones need Core 0.26.5.

## Stored data

No database. Luminous glowstone and fire markers live in chunk PersistentDataContainers, catalyst cooldowns and daily counters in the player's PersistentDataContainer, garden state in `garden-trees.properties`. Price quotes, pending growth and per-species counters are runtime state and clear on shutdown; cancelled pending growth is refunded while the player is online.

## Web page

With Vystorm Core 0.24.0+ and `web.enabled: true` in Core's config, CustomTrees adds the page "Tree guide" (module `trees`) to the Vystorm web panel, in the browser via `/web` and as an in-game overlay for players with the Vystorm client mod. Without it, `/customtrees guide` falls back to the in-game tutorial.

- **Guide (everyone):** top-view diagrams of every formation with the triggers this server allows, heights, crown radii, a size comparison, catalyst costs, trunk protection and world generation. A species tab shows all nine species with their four variants. All values come from the loaded config and update live after a reload.
- **Admin tab (`customtrees.admin`):** the values of `/customtrees status` (live while online), per-species totals since restart, a reload button and a link to Core's settings page.
- **Routes** below `/api/trees`, checked by Core (session, CSRF, rate limits): `GET /guide` (no permission, also published as `/data/trees/guide.json`), `GET /admin/status` and `POST /admin/reload` (`customtrees.admin`, rechecked live for online players). Live event `trees/status` for online admins.
- **Texts:** `web.*` in `lang/en.yml` and `de.yml` (page texts under `web.ui.`). The page itself ships with the Vystorm web UI bundled in Core.

The in-game guide comes from `tutorial_en.yml` and `tutorial.yml` (German) in the jar.

## Acceptance test plugin

`./gradlew integrationJar` builds the test plugin `CustomTreesAcceptance.jar` (sources in `test-harness/`). It compares tree output with an old baseline jar on an isolated server. Never install it on a real server.
