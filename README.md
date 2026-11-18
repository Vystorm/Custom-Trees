# CustomTrees


## Sapling breeding (1.41.0-26.2)

Create two same-species growth-profile saplings with `/customtrees sapling <form> <size>`, hold exactly one in each hand, then use `/customtrees breed`. Both parents are consumed and one offspring replaces the main-hand parent. The offspring independently inherits a crown form and growth class from its parents; its generation is shown in the lore.

`breeding.mutation-chance` defaults to 0.10 and is bounded to 0–0.25. A mutation changes at most one trait: a crown form or one adjacent growth class, always within the existing NORMAL/MEDIUM/GIANT/COLOSSAL classes. `breeding.cooldown-seconds` defaults to 30 (0–3600). Player cooldowns and offspring traits survive restarts. `customtrees.breed` defaults to true; breeding requires Survival or Adventure mode.

Plant the offspring in the formation indicated by its profile. The existing chunk-persisted planting record and Core tree generator apply the inherited size and crown; existing costs, protection checks, CoreProtect handling and cancelled-growth refunds still apply. No second tree generator is introduced. Plugins may cancel `SaplingBreedEvent` before either parent is consumed; item views are defensive copies, and the player lifecycle, permission and both hands are rechecked after the event. All messages and help are translated into all seven bundled languages.

CustomTrees replaces vanilla tree growth with bigger, more varied trees. Plant one sapling and you get a tree close to vanilla. Plant a 3x3 or 5x5 square and you get a giant up to 68 blocks tall. Lines of saplings turn into shrubs and hedges, and new chunks get the same trees during world generation. It runs on the server only, so players need no mod.

## Features

- **Bigger formations.** One sapling grows a custom tree, a 2x2 square a medium tree, a 3x3 square a giant (36 to 48 blocks, crown radius 11) and a 5x5 square a colossus with a round trunk (52 to 68 blocks, crown radius 17).
- **Nine species, four styles each.** Oak, birch, spruce, jungle, acacia, dark oak, mangrove, cherry and pale oak. Every species comes free-standing, forest, wind-bent or as an old tree. Single saplings stay close to the vanilla look; jungle trees get vines and cocoa, cherries hanging blossoms, acacias the odd side fork.
- **Shrubs and hedges.** Two or three connected saplings of the same kind become a shrub. Four or more become one continuous hedge, straight, diagonal, around corners or branching, up to 128 saplings.
- **Luminous trees.** Right-click a sapling formation with glowstone dust and it grows at once, with glowstone hidden in the dark inner foliage. Default cost: one dust and 100 XP points. The glowstone is decoration, so it drops nothing and pistons can't move it.
- **Coal trees.** Right-click with coal for an instant tree that tree-felling plugins may cut down. Prices per formation size are set by the admin; above one coal you click twice to confirm.
- **Pick the crown yourself.** `/customtrees sapling <form> <size>` tags the sapling in your hand with a crown form (open-grown, forest, windswept, ancient or random) and a formation size.
- **Garden trees.** Trees grown from tagged saplings remember their crown. The owner can trim up to 16 leaves at a time with `/customtrees prune`, and they grow back after a while (default 5 minutes).
- **Preview before growing.** Sneak and right-click a sapling with empty hands to see the formation and the space it needs as particles only you can see. Nothing grows and nothing is spent.
- **World generation.** Trees in freshly generated chunks are swapped for the same custom shapes, on vanilla and Iris worlds. Chunks players have already touched, and protected Vystorm Core regions, are left alone.
- **Plays nice with protection.** Growth only replaces air, grass, ferns, vines, natural leaves and its own saplings, never loads chunks and fires a normal `StructureGrowEvent`, so protection plugins can block it. Blocked growth refunds the catalyst and XP.
- **CoreProtect and SmoothTimber.** CoreProtect logs every growth, so rollbacks work. Trunks of 3x3, 5x5 and luminous trees are protected from SmoothTimber; 1x1, 2x2, coal and hedge trees can be felled normally.
- **Shake a tree.** Right-click a trunk with an empty hand: sticks, saplings and the species' own fruit fall from the crown (apples, cocoa beans, cherry petals, sweet berries, propagules, pale moss), rarely a golden apple. Each tree rests 5 minutes afterwards.
- **Herbarium.** Every tree and fungus form you grow yourself is collected: 83 forms, seven ranks from Sprout to Keeper of Yggdrasil, a menu with your progress and the top foresters, and a look at other players' herbaria from Core's player menu.
- **Planting helper.** Pick 2x2, 3x3, 5x5 or 10x10 in a menu, place one sapling and the whole square is planted from your inventory. Blocked spots are shown, claims are respected.
- **Timber.** Chop one log with an axe and the whole tree falls: as a real tree fall that topples away from you and breaks into items, or straight down. Choose between the first log and a share of the trunk (default 80 %). Player builds are left alone, claims are respected and CoreProtect logs every block. It pauses itself while SmoothTimber is installed. See [Timber](#timber-1330).
- **Optional fire markers.** If enabled, trunks and crowns of newly grown trees ignore fire events aimed directly at them. This is not wildfire containment.
- **Guide in game and in the browser.** `/vmenu tutorials` has an English and German guide. With Vystorm Core's web platform, `/customtrees guide` opens an illustrated tree guide with formation diagrams and all nine species.

### Herbarium planting tasks (1.35.0)

`/customtrees next` recommends an uncollected form, starting with the fewest plants required (ties follow species and form order). `/customtrees next cherry` limits it to one species; tab completion offers all 13 plant IDs. Click a species in any herbarium to receive a task for **your own** collection, even when viewing another player. The localized recipe explains the square or hedge layout and growth trigger. Completed selections get a completion message. Server growth settings, costs, permissions and soil requirements still apply. No new config keys or permissions are needed.

Leaves decay naturally after the tree is cut.

### Hedge planting templates (1.36.0)

Choose a row of 4, 8 or 16 saplings in `/customtrees plant`, or use `/customtrees plant hedge12` for any length from 4 to 32 within the server limit. Place an untagged sapling: the row extends in your facing direction on the same Y level, using matching plain saplings from your inventory. Existing matching saplings count toward the row. Mushrooms and fungi do not trigger this mode.

`planting-helper.max-hedge-length` defaults to 16; values below 4 disable rows, and the maximum is 32. Existing configs use the default automatically. The Core config editor exposes the setting. The existing `customtrees.plant` permission, timeout and keep-active setting also apply. Blocked ground, unloaded chunks or a denied placement prevent the additional row; the originally placed sapling remains. No chunks are loaded. Use bone meal afterwards to grow the hedge. Keep rows away from other saplings if you want a separate hedge: normal connected-formation detection still applies.

## Compatibility (1.37.0)

Runs on Paper 1.20.6 – 26.x. On 1.20.6, pale oak and newer vegetation do not exist: the herbarium offers 76 attainable forms instead of 83, and pale-oak shaking loot is omitted. Saved collection entries remain intact. Existing chest menus remain available; Core supplies its compatible settings and tutorial UI. Newer servers retain pale oak, newer vegetation and hanging-moss tip data.

Build with JDK 25; all source sets emit Java 21 bytecode. `check` includes `compat1206` (main sources compiled against local 1.20.6 libraries) and `test1206` (compatibility tests running the shipped classes against that API). Override the library directory with `-Ppaper1206Libraries=/path/to/libraries`. The modern block adapter is compiled separately and bundled in the same jar. These checks do not start a server.

## Installation

This release requires **Vystorm Core 0.65.0 or newer** (placeholders), including its compatibility helpers. The optional client mod has separate version requirements.


- Paper or Purpur **1.20.6 – 26.x**, **Java 21 or newer**, subject to the Java requirement of your server version. Spigot and CraftBukkit 26.2 and 26.3 passed a start test with native gameplay checks of the Bukkit replacement paths (1.40.0, see below); other Spigot versions were not tested. See [compatibility checks](COMPATIBILITY.md).
- [Vystorm Core](https://github.com/Vystorm/VystormCore) 0.65.0 or newer with `giant-world-trees`.
- Optional: Iris (late-placed Iris trees get replaced too), CoreProtect (logging, rollback, trunk protection), SmoothTimber (respects protected trunks)

Drop the jar into `plugins/` and start the server. Without the optional plugins everything still works except the part that needs them.

Changes: [CHANGELOG.md](CHANGELOG.md).

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/customtrees guide [species\|admin]` | none | Opens the tree guide (web page, or the in-game tutorial without it). `web` works as an alias. |
| `/customtrees sapling <form> <size>` | `customtrees.grow` | Tags the vanilla sapling in your main hand with a crown form and formation size |
| `/customtrees prune` | `customtrees.garden` | Trims up to 16 leaves on a garden tree you own |
| `/customtrees next [plant]` | `customtrees.herbarium` | Suggests a missing form with a planting recipe; optional plant ID (tab completion) |
| `/customtrees plant [2x2\|3x3\|5x5\|10x10\|hedge<N>\|off]` | `customtrees.plant` | Planting helper: without a size it opens the menu; the next plant you place fills the selected square or hedge row |
| `/customtrees plant status` | `customtrees.plant` | Shows your selected pattern, plant count, remaining selection time and whether it stays active after use; does not renew or consume the selection |
| `/customtrees herbarium [player\|top]` | `customtrees.herbarium` | Opens your herbarium (or another player's), `top` lists the best foresters |
| `/customtrees status` | `customtrees.admin` | Active worlds, switches, growth counters since restart, world trees and tree shaking (trees shaken, items and golden apples dropped, trees resting) |
| `/customtrees reload` | `customtrees.admin` | Reloads config and language files; a broken config keeps the old settings |

| Permission | Default | For |
|---|---|---|
| `customtrees.grow` | everyone | Growing custom trees from saplings, `/customtrees sapling` |
| `customtrees.garden` | everyone | Pruning your garden trees |
| `customtrees.shake` | everyone | Shaking trees with an empty hand |
| `customtrees.plant` | everyone | Planting helper |
| `customtrees.herbarium` | everyone | Collecting forms in the herbarium, viewing herbaria |
| `customtrees.timber` | everyone | Felling whole trees with the built-in timber |
| `customtrees.admin` | OP | `status`, `reload`, server settings, admin tab of the web guide |
| `customtrees.*` | OP | All of the above |

Tab completion only suggests what you are allowed to use.

## Configuration

Admins can edit the whole `config.yml` in game with `/vadmin config CustomTrees` (Core 0.27, permission `vystorm_core.config.customtrees`). Saving applies it like `/customtrees reload`. Most settings, except `enabled-worlds`, are also in the Vystorm menu under Settings → Server → CustomTrees (`/vsettings server`).

The settings most servers touch:

- `settings.enabled-worlds`: empty means every normal overworld.
- `settings.replace-generated-trees`, `replacement-chance`: world generation. Only affects new chunks.
- `settings.giant-*`, `settings.colossal-*`: heights, crown radius, and whether natural growth and bone meal can trigger 3x3 and 5x5 trees.
- `settings.glowstone-*`, `settings.coal-growth`, `settings.coal-costs.*`: catalyst growth and prices (1 to 64 coal per size).
- `settings.*-cooldown-seconds`, `*-daily-limit`: optional per-player limits for glowstone and coal growth. `0` turns them off.
- `settings.fire-protect-trunks`, `fire-protect-crowns`: fire markers, off by default.
- `settings.garden-regrowth-seconds`: leaf regrowth for garden trees, 10 to 86,400 seconds.
- `shaking.*`: tree shaking on/off, rest time, drops per shake, minimum crown size, and `golden-apples` (default `true`; `false` keeps golden apples out of the loot). Labelled in `/vadmin config CustomTrees` since 1.31.0. `loot-table` (default empty): Vystorm Core loot table or category id for shaking (Core 0.44+); empty = `category:gathering_find` with the species list as own table `customtrees:shaking/<species>` (same odds), the player's luck counts.
- `herbarium.*`: herbarium on/off, `announce-ranks` and optional console `rank-commands` per rank. Since 1.32.0.
- `planting-helper.*`: planting helper on/off, largest square (`max-size`), `keep-active` and `timeout-seconds`. Since 1.32.0.
- `timber.*`: the built-in timber: mode, trigger, sneaking, axe, worlds, player choice, modes per tree size, tool wear, drops, detection limits, displays and performance caps. See [Timber](#timber-1330). Since 1.33.0.
- `biome-profiles`: optional groups that make trees in certain biomes taller, wider or a fixed crown form. See [docs/TECHNICAL.md](docs/TECHNICAL.md#biome-profiles).

`language: auto` follows each player's Minecraft language. Seven languages ship in `plugins/CustomTrees/lang/`: English, German, French, Italian, Brazilian Portuguese, Russian and Simplified Chinese (`zh_cn`). To add a language, copy `en.yml` to e.g. `nl.yml`, translate it and run `/customtrees reload`; missing keys fall back to English.

CustomTrees needs no database. Its data lives in the world (chunk and player data) plus a small `garden-trees.properties` and `herbarium.yml`, so back up world and player data together.

## Switches

Every feature has an on/off switch in `config.yml`. They are listed in Core's `/vadmin features` with a one-line
description and apply at once, except `tutorial.enabled` (restart). A switched-off feature answers "This feature is
switched off on this server.", its subcommands disappear from help and tab completion, its menu entries go and its
placeholders are empty. Grown trees stay; herbarium entries, tagged saplings, fruit trees, pruned gardens and pending
regrowth stay saved.

- The whole plugin: `trees.enabled` (off: saplings, mushrooms and fungi grow as in vanilla and no feature acts;
  `/customtrees status` and `reload` still work).
- Growth (older keys, kept): `settings.custom-player-grown-trees`, `settings.connected-shrubs-and-hedges`,
  `settings.mixed-hedges`, `settings.giant-3x3-saplings`, `settings.natural-3x3-growth`, `settings.bonemeal-3x3-growth`,
  `settings.colossal-5x5-saplings`, `settings.natural-5x5-growth`, `settings.bonemeal-5x5-growth`,
  `settings.glowstone-growth`, `settings.coal-growth`, `world-trees.enabled`.
- World: `settings.replace-generated-trees`, `settings.spruce-ground`, `settings.spruce-snow`,
  `settings.fire-protect-trunks`, `settings.fire-protect-crowns`, `settings.biome-profiles` (new: use the
  `biome-profiles` section).
- Around trees: `shaking.enabled`, `shaking.golden-apples`, `fruit-trees.enabled`, `timber.enabled`, `timber.replant`,
  `timber.player-choice`, `regrowth.enabled` (needs timber), `herbarium.enabled`, `planting-helper.enabled`,
  `breeding.enabled`.
- New: `crown-choice.enabled` (`/customtrees sapling`), `garden.enabled` (`/customtrees prune` and regrowing leaves),
  `preview.enabled` (sneak + right-click preview), `guide.enabled` (`/customtrees guide` and the web guide),
  `tutorial.enabled` (restart).

Protection and logging (grow events, refunds, CoreProtect, SmoothTimber) and the admin commands have no switch: they
keep claims and logs correct and are needed to turn things back on.

## Placeholders

Namespace `trees`, listed by `/vadmin placeholders trees`: `%vystorm_trees_herbarium_found%`, `herbarium_total`,
`herbarium_rank`, `herbarium_rank_id`, `herbarium_next_needed`, `herbarium_top_<n>_name` / `herbarium_top_<n>_found`,
`herbarium_keepers`, `breed_cooldown`, and the counters since the start `trees_grown`, `giant_grown`, `colossal_grown`,
`shrubs_grown`, `hedges_grown`. Short form in Vystorm texts: `{trees:herbarium_found}`.

## For developers

Build with JDK 25 and the Gradle wrapper. Vystorm Core is a compile-only dependency:

```sh
./gradlew test jar -PvystormCoreJar=/path/to/Vystorm_Core.jar
```

Or put the Core jar at `libs/Vystorm_Core.jar` and leave out the parameter. The plugin jar ends up in `build/libs/CustomTrees-<version>.jar`.

World generation rules, web routes, stored data, biome profiles and the acceptance test plugin are described in [docs/TECHNICAL.md](docs/TECHNICAL.md).

## Spruce forest floor (1.26.0)

Large planted spruces (2x2, 3x3 and 5x5) and newly generated spruces can add
irregular podzol/coarse-dirt patches and a single snow layer nearby. Small planted
spruces, shrubs and hedges do not change the ground. Patches follow nearby slopes
within a bounded radius (4, 6 or 8 blocks depending on formation size).

`settings.spruce-ground` and `settings.spruce-snow` independently enable soil and
snow (both default true). `settings.spruce-snow-cold-only` defaults true; turn it
off for decorative snow in warm biomes. The settings also appear in the localized
Core settings/config editors. Existing configs use these defaults after updating.

Only grass blocks, dirt, podzol and coarse dirt can become forest floor. Existing
snow and plants remain; new snow requires air above suitable soil. Sapling changes
are offered in the same cancellable StructureGrowEvent as the tree. Generation
uses the existing fresh-chunk and protected-region checks, without forced loads.
Snow is ordinary Minecraft snow and may melt naturally. No biome data is changed.

## 10x10 world trees (1.27.0, giant redesign 1.29.0)

Requires Vystorm Core **0.37.0** (`world-tree-plans`). Plant a complete square of **100 identical, untagged plants** at one Y level. All nine saplings are supported, plus red mushrooms, brown mushrooms, crimson fungi and warped fungi. Right-click any member with **one bone meal** (`customtrees.grow`). Creative consumes nothing; no XP is charged. Coal, glowstone and profile-sapling items do not apply to this new size. Sneak-right-click with an empty main hand to inspect configured dimensions.

| Family | Height including crown | Crown radius |
| --- | ---: | ---: |
| Overworld trees | 160 | 44 (max 48) |
| Red/brown mushrooms | 80 | 24 |
| Crimson/warped fungi | 48 | 18 |

Heights and radii are configurable under `world-trees`, also labelled in `/vadmin config CustomTrees`. Normal and Nether worlds are supported, subject to the existing `settings.enabled-worlds` name list; this does not enable ordinary tree replacement in the Nether. All forms in a Nether world use `nether-height`. Crimson and warped formations need their matching nylium below all 100 plants; trees/mushrooms need the supported soil or mycelium. Since 1.29.1 obstacles never reject growth, missing chunks are loaded temporarily and too tall trees are shrunk; see *Relaxed growth rules* below. A complete 10x10 formation waits for explicit bone meal and takes priority over smaller formations.

Geometry is planned off-thread, then free space is checked and blocks are placed on the server thread in configurable batches (default 512 per tick, shared by one world-tree job). A real `StructureGrowEvent` includes the initiating player before costs or placement; cancellation leaves the plants and bone meal unchanged. Later edits by players and unloaded chunks are skipped rather than overwritten/loaded. Leaves are persistent. Existing fire-protection settings apply, and CoreProtect trunk logging is delayed beyond SmoothTimber's grow-event entries. Growth is not a persistent transaction: stopping/restarting the plugin during placement can leave a partial tree; already placed blocks are retained. There is no automatic world-generation replacement with world trees.
Validation for 1.27.0: 114 automated CustomTrees tests pass, including 10x10 detection, loaded-chunk boundaries, mixed plants, settings validation, staged-write limits, protection cancellation before consumption and concurrent-edit preservation. Core 0.37.0: 932 tests, 920 passed and 12 database-dependent cases skipped. Geometry is checked across all 13 plants and multiple seeds. No new real-client visual/load acceptance or live deployment was performed for this release.

## All fungus sizes (1.28.0)

Requires Core 0.38.0 (`fungus-growth-plans`). Red and brown mushrooms, crimson fungi and warped fungi now support **1x1, 2x2, 3x3, 5x5 and 10x10**. The largest complete same-species square containing the clicked plant wins. Ordinary saplings still use their existing generators at the four smaller sizes.

| Formation | Mushroom height / radius | Nether fungus height / radius |
| --- | --- | --- |
| 1x1 | 8 / 3 | 8 / 3 |
| 2x2 | 16 / 5 | 14 / 5 |
| 3x3 | 28 / 9 | 24 / 8 |
| 5x5 | 44 / 15 | 36 / 12 |
| 10x10 | 80 / 24 | 48 / 18 |

Smaller sizes are configurable under `world-trees.fungi.<size>`; 10x10 retains the previous fields. `world-trees.enabled` controls this fungus growth path as well as world trees. Growth uses an explicit bone-meal click, including the smaller fungi; it does not trigger automatically or consume XP. Preview, collision/claim checks, soil requirements, staged placement and restart limitations are the same as for 10x10. Coal, glowstone and tagged sapling profiles remain unsupported on this path.

Validation for 1.28.0: all 115 CustomTrees tests pass. Core 0.38.0: 934 cases, 922 passed and 12 database-dependent cases skipped. Coverage includes all four fungus types at every size, largest-square priority and deterministic bounded geometry. Real-client in-game acceptance has not been rerun.

## Vanilla fallback for small fungi (1.28.1)

Only complete 10x10 formations are reserved against vanilla growth. Smaller mushroom and fungus squares (1x1 to 5x5) behave like vanilla again whenever CustomTrees does not grow them itself: dispensers, off-hand bone meal, players without `customtrees.grow`, and bone meal on soil the custom path does not accept (for example a mushroom on stone in a dark cave or on netherrack) produce the normal vanilla huge mushroom or fungus. Coal and glowstone dust on a small fungus are no longer intercepted. Main-hand bone meal from a player with `customtrees.grow` on supported soil still uses the custom staged growth above. Only one growth job runs per server at a time; a second player gets a short "please wait" message.

Validation for 1.28.1: all 118 CustomTrees tests pass against Core 0.38.0. Real-client in-game acceptance has not been rerun.

### Yggdrasil-scale world trees (1.29.0)

Requires Core 0.39.0 (`giant-world-trees`). The nine sapling species now grow as giant, species-specific trees modelled on real ancient trees: a Major-Oak style oak, a sequoia/Douglas-fir spruce, a silver-birch cluster, a kapok with plank buttresses (jungle), an umbrella-thorn acacia, a banyan-like dark oak with aerial prop roots, a mangrove lifted on arching prop roots, a weeping Jindai-zakura cherry and a moss-hung pale oak. Trunks are flared, braided and hollow; roots arch outward up to ~30 blocks; crowns are grown by space colonisation with tapered limbs, leaf clusters, gaps and curtains. Defaults are height 160 and crown radius 44 (`tree-radius` accepts 12..48). Existing configs keep their values: set `world-trees.tree-height: 160` and `tree-radius: 44` to use the new defaults.

At the defaults a tree has about 50,000-135,000 blocks and covers roughly 110x110 blocks (footprint ±56 around the formation centre; ±60 at 192/48). Before planning, every chunk of that footprint must already be loaded and the crown must fit below the build limit, otherwise the player gets a message and nothing is consumed. Placement then grows **around** natural terrain, water and neighbouring trees (those blocks are left untouched) and replaces only air, plants, snow layers and leaves; any other block in the way (buildings, planks, glass, cobblestone, lava, ...) stops the growth before anything is placed. If more than 30% of the plan would have to be left out, growth is refused. Vines get their faces from the plan, pale hanging moss its tip state, leaves stay persistent, and CoreProtect logging now includes wood blocks and mangrove roots. Placement stays staged (default 512 blocks per tick), so a default tree needs roughly 10-30 seconds of validation plus placement at 20 TPS.

Validation for 1.29.0: all 121 CustomTrees tests pass against Core 0.39.0 (937 Core cases, 925 passed, 12 database-dependent skipped). Real-client in-game acceptance has not been run.

### Relaxed growth rules and digging roots (1.29.1)

Growth no longer stops because of anything in the way. Every planned block is either **placed** or **left out**:

- Replaced: air, all wild plants (grass, ferns, flowers, saplings, mushrooms, berry bushes, dripleaf, azalea, lily pads), vines, moss and moss carpets, leaves of any tree, snow layers, snow blocks and powder snow, and soft natural ground: dirt, grass block, coarse/rooted dirt, podzol, mycelium, dirt path, sand, red sand, gravel, clay, mud and muddy mangrove roots.
- Roots and the trunk base (planned wood at or one block above the planting level) additionally replace water (source and flowing), bubble columns and underwater plants (seagrass, kelp, sea pickles).
- Left out, never replaced: everything else, in particular player-built and valuable blocks (containers and other tile entities, beds, signs, rails, redstone, glass, planks, bricks, cobblestone, crops, farmland), bedrock, barriers, portals, ice, other trees' logs/wood, lava, and water or waterlogged blocks in the crown.
- `world-trees.replace-stone: false` (new): when `true`, natural stone (stone, deepslate, andesite, diorite, granite, tuff, calcite, sandstone, red sandstone, plain and badlands-coloured terracotta) is replaced as well. Ores are never replaced.
- `world-trees.max-skipped-percent: 100` (new, 0..100): refuse a growth when more than this share of the plan would be left out. The default 100 never refuses (1.29.0 had a fixed 30%).

**Chunks.** Missing chunks of the footprint (about 64 chunks at the defaults, at most 400) are loaded asynchronously (`World#getChunkAtAsync`) after the bone-meal click. They are held by a temporary plugin chunk ticket only while the job validates and places, and released when it ends (complete, partial, cancelled or failed). If loading fails or times out (60 s), the player gets a message and nothing is consumed.

**Height.** If the configured height does not fit below the build limit (in the Nether below the roof when planted under it), the tree is shrunk to fit and the crown radius scales proportionally, never below Core's minimums (height 32, radius 12 for 10x10; 6/2 for smaller fungi). The player is told the reduced height. Only when even the minimum height does not fit is growth refused. With Core 0.39.1 the height is also reduced, where possible, so the underground roots stay above the world's minimum height; roots that still reach below it are left out.

**Digging roots.** With Vystorm Core **0.39.1** (`underground-tree-roots`) the roots of the nine sapling trees dig into the ground: every root sinks and forks 6-14 blocks below the planting level, and the trunk base gets sinker roots and a taproot. CustomTrees 1.29.1 still starts on Core 0.39.0 (without underground roots); 0.39.1 is recommended.

Unchanged: `StructureGrowEvent` with the player before any cost (claims can cancel), staged placement, `customtrees.grow`, bone meal consumed only after approval and refunded when nothing was placed, CoreProtect logging, concurrent player edits preserved.

Validation for 1.29.1: all 130 CustomTrees tests pass against Core 0.39.1 (938 Core cases, 926 passed, 12 database-dependent skipped). Real-client in-game acceptance has not been run.

## Herbarium (1.32.0)

Collect every tree your server can grow. Each form a player grows **themselves** is recorded in their herbarium:

- the nine species (oak, birch, spruce, jungle, acacia, dark oak, mangrove, cherry, pale oak) as single tree or shrub, 2x2 tree, 3x3 giant, 5x5 colossus, hedge, luminous tree and 10x10 world tree;
- red and brown mushrooms, crimson and warped fungi at 1x1, 2x2, 3x3, 5x5 and 10x10.

That is 83 forms. A new form shows a chat line with a clickable **[Herbarium]** button. The number of forms decides the rank: Sprout (0), Gardener (5), Forester (15), Arborist (30), Tree warden (50), Forest elder (70) and Keeper of Yggdrasil (all 83). A new rank plays a sound and, with `announce-ranks: true`, is announced to everyone online.

Open it with `/customtrees herbarium`, from `/vmenu` (Character) or, on Vystorm Core 0.43.0+, by sneak-right-clicking another player ("Herbarium of <player>" in Core's player menu). The menu shows the rank, progress to the next rank, every plant with its found and missing forms (complete plants glow) and the top foresters; `/customtrees herbarium top` prints the top 10, `/customtrees herbarium <player>` opens someone else's herbarium.

```yaml
herbarium:
  enabled: true
  announce-ranks: true
  rank-commands: {}   # e.g. forester: ["give {player} bone_meal 16"], run from the console
```

Only growth started by a player counts (bone meal, glowstone, coal, world trees); natural sapling ticks don't. Data is stored in `plugins/CustomTrees/herbarium.yml`, written atomically at most once a minute and on shutdown. An unreadable file is moved aside as `herbarium.yml.broken-<time>` instead of being overwritten.

## Planting helper (1.32.0)

Planting a 10x10 world tree means placing 100 saplings by hand. The planting helper does it in one click:

1. Open it from `/vmenu` (Building) or with `/customtrees plant` and pick **2x2, 3x3, 5x5 or 10x10** (`/customtrees plant 5x5` works too).
2. Place one sapling, mangrove propagule, mushroom or Nether fungus. It becomes the corner nearest to you; the square grows forward and to your right and is filled from your inventory.

Every spot must be air or a replaceable plant with ground the plant can stand on. If one spot is blocked, nothing else is planted and the blocked spots are marked with red particles. Each plant fires a normal block-place event, so claim and protection plugins can refuse it (the whole square is then undone) and CoreProtect logs it as your placement. Survival players need enough plain plants of that kind; creative needs none. Tagged profile saplings (`/customtrees sapling`) are never copied. The helper switches off after one square, after 5 minutes unused, or with `/customtrees plant off`.

```yaml
planting-helper:
  enabled: true
  max-size: 10          # largest square offered: 2, 3, 5 or 10
  keep-active: false    # stay on after a square
  timeout-seconds: 300  # an unused helper switches off
```

Validation for 1.32.0: all 153 CustomTrees tests pass against Vystorm Core 0.43.0. Real-client in-game acceptance has not been run.

## Timber (1.33.0)

Chop one log with an axe and the whole tree comes down. It works for natural trees and for every CustomTrees tree: custom shapes, shrubs and hedges, 3x3 giants, 5x5 colossi, 10x10 world trees, huge mushrooms and Nether fungi.

**Two ways down** (`timber.mode`):

- `fall` (default): a real tree fall. The tree tips over as one piece around its stump, away from you, and accelerates as it falls. It is shown with block display entities, so no real falling blocks are created and nothing is placed. Terrain, buildings and other trunks in its path shorten the arc. Where it lands it breaks apart into items, with particles and a crash sound. A tree is shown with at most `fall.max-displays` displays (default 400); larger trees such as world trees are shown as merged cubes.
- `drop`: straight down. The tree breaks layer by layer from the bottom up, and the items land under the spot each part came from.

**When it falls** (`timber.trigger`):

- `first-log` (default): breaking any log of the tree fells the whole tree, down to its base.
- `percent`: the tree falls once `trunk-percent` (default 80 %) of the trunk cross-section at the chopped height is cut. For example, a 2x2 trunk needs 4 cuts and a 3x3 trunk needs 8. The action bar shows "Trunk 3/8". The stump below the cut stays standing. A single trunk falls at the first cut.

By default the timber needs an axe and works while you are not sneaking, so you can sneak to chop single logs (`sneaking: standing | sneaking | any`, `require-axe`). `worlds` limits it to certain worlds. With `player-choice: true`, every player can switch the timber off for themselves or pick "tree fall" or "straight down" in `/vsettings` (Settings → Timber). `tree-types.small|giant|colossal|world` sets a fixed mode per tree size or `disabled`. A disabled type always wins, then a fixed type mode, then the player's choice, then `mode`.

**Never player builds.** A tree is only felled when all of these hold:

- its logs touch only natural blocks (no planks, glass, torches, chests, glazed terracotta, stripped logs, ...);
- its trunk stands upright on natural ground;
- it has at least `min-leaves` natural leaves (leaves a player placed are persistent and do not count; CustomTrees' own world-tree leaves do);
- with CoreProtect, no trunk log was placed by a player (`detection.coreprotect-check`).

`detection.only-generated-trees: true` limits the timber to trees CustomTrees grew or generated, or, with CoreProtect, trees that no player placed. Leaves that are closer to a neighbouring tree stay with that tree, and roots in the ground stay too.

**Drops and tool.** Logs drop as logs. Leaves drop like vanilla with your tool: saplings, sticks and apples, Fortune helps, and Silk Touch keeps the leaves (`drops.leaves: none` turns leaf drops off). Drops are merged into full stacks. `drops.into-inventory` puts them straight into your inventory. The axe loses `tool.durability-per-log` (default 1) per felled log, and Unbreaking applies. With `tool.spare-tool` (default on), a tree is not felled when the axe would break, so only the chopped log breaks.

**Replanting.** After a small tree (single or 2x2 trunk) falls, a fitting sapling, propagule or fungus is planted where the trunk stood, four for a 2x2 trunk, if the ground fits (`replant`, default on). The saplings come from the tree's own drops first, then from your inventory; with too few nothing is planted. Each sapling fires a block-place event, so claim plugins can refuse it and CoreProtect logs it.

**Protection and logging.** Before a block is removed, the timber fires a block-break event for it (`TimberBreakEvent`, a `BlockBreakEvent`). Claim plugins (CityClaim, Estates, WorldGuard, ...) can refuse it, and CoreProtect logs it as your removal. If one log is refused, the whole tree stays standing. Vystorm Core regions of the kinds in `protected-region-kinds` (spawn, safe, arena, market, dungeon, structure) are never touched, and a region flag `timber` overrides that. On very large crowns (over 4096 leaves) one event is fired per 4x4x4 cell and the remaining leaves are logged to CoreProtect through its API.

**Other timber plugins.** If SmoothTimber (or UltimateTimber, TreeAssist, TreeCapitator, ...) is installed, the built-in timber pauses itself by default and the console says so (`other-timber-plugins: pause`). Set `run-both` to use both anyway, which is not recommended because both react to the same chop.

**Performance.** Chunk snapshots are taken on the server thread and the tree is detected off-thread, together with the CoreProtect lookup. Protection checks and block changes run in budgeted batches (`performance.checks-per-tick` 1000, `blocks-per-tick` 1500). Each tree is limited by `detection.max-blocks`, `max-radius` and `max-height`, and the search never loads chunks. At most `falls-per-world` (4) trees fall at once per world and `falls-per-player` (1) per player. Display entities are tagged and never saved with the chunk. They are removed when the fall ends, when the chunk unloads, when the plugin stops, and when entities load after a crash.

Permission `customtrees.timber` (default: everyone). `/customtrees status` shows a timber line, and every setting has English and German labels in `/vadmin config CustomTrees`.

```yaml
timber:
  enabled: true
  mode: fall               # fall | drop
  trigger: first-log       # first-log | percent
  trunk-percent: 80
  sneaking: standing       # standing | sneaking | any
  require-axe: true
  worlds: []
  player-choice: true
  tree-types: {small: server-mode, giant: server-mode, colossal: server-mode, world: server-mode}
  tool: {durability-per-log: 1.0, spare-tool: true}
  drops: {leaves: vanilla, into-inventory: false}
  replant: true
  detection: {max-blocks: 150000, max-radius: 64, max-height: 200, min-leaves: 4, natural-ground: true, coreprotect-check: true, only-generated-trees: false}
  fall: {max-displays: 400, fall-ticks: 40}
  performance: {blocks-per-tick: 1500, checks-per-tick: 1000, falls-per-world: 4, falls-per-player: 1}
  other-timber-plugins: pause   # pause | run-both
  percent-memory-seconds: 600
  protected-region-kinds: [spawn, safe, arena, arena-zone, market, dungeon, structure]
```

Validation for 1.33.0: all 201 CustomTrees tests pass against Vystorm Core 0.44.0, 41 of them new for the timber. These cover tree detection (natural trees compared with player builds, leaves, roots, fungi), the percent trigger, fall direction and rotation, obstacles, proxy displays, durability and drop merging, region rules and display cleanup. On a local Purpur 26.2 server a protocol bot felled a natural oak in fall mode, a natural oak in drop mode and a 2x2 dark oak with the percent trigger (4 of 4 cuts). Drops were merged and landed where expected, no display entity was left behind, and a log pillar next to planks was not felled. Not tested: CoreProtect, claim plugins, SmoothTimber detection and real-client visuals.


### Server compatibility (1.40.0-26.2)

Native block-replacement checks and Bukkit-compatible durability/menu feedback through Core. Compatibility adapters target Spigot/CraftBukkit 26.2 and 26.3 while preserving the existing Paper paths. Bukkit means a Bukkit API server implementation such as CraftBukkit, not an additional server distribution. Install Vystorm Core 0.51.0 or newer.

Validation for this release: full automated build and tests pass, including the existing Paper 1.20.6 compatibility compilation. Both plugins enabled successfully on official Spigot 26.2 and 26.3 with Core 0.51.0; native gameplay checks exercised the Bukkit replacement paths.

## License

Copyright (c) 2026 Esoren

Versions after 1.45.1-26.2 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.45.1-26.2 and earlier were released under the MIT License ([LICENSE-MIT-until-1.45.1-26.2.txt](LICENSE-MIT-until-1.45.1-26.2.txt)) and stay available under those terms.
