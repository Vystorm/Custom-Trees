## 1.47.2-26.2 — 2026-11-20

- New setting `settings.garden-max-trees` (default 128, as before; 16 to 1,024): how many garden trees the whole server keeps for `/customtrees prune`. `/customtrees status` shows how many are used ("gardens: trees=97/128"), and when all are taken the console says so once instead of silently not registering new trees.
- Fixed: when `garden-trees.properties` would have grown past its 8 MB limit (about 75 giant trees with 512 stored leaves each), pruning and leaf regrowth stopped for every garden tree until the next restart. Now only the new tree is not registered; all existing garden trees keep working, and the console says so once.
- Going back to an older version: older versions read at most 128 garden trees. With more, they leave `garden-trees.properties` untouched and switch pruning off. If you may need to go back, keep the setting at 128; at 128 the file stays readable for older versions.

## 1.47.1-26.2 — 2026-11-20

- Fixed: a `config.yml` with a YAML mistake no longer resets the settings on `/customtrees reload`, and the settings menu no longer saves over it. The file is left as it is, the console and the reload answer name the line and column of the mistake, and the last working settings stay active (at start: the default settings).
- Fixed: `regrowth.yml` and `fruittrees.yml` with a YAML mistake are copied to `<name>.broken-<date-time>` before the plugin writes them again, so pending regrowth and fruit trees are never lost to a typo.
- `/vadmin features` names every switch of the plugin in seven languages ("Luminous trees", "Giant trees grow by themselves", "Forest regrowth") instead of the key path ("Settings › Glowstone growth"). Switches that had a name in `/vadmin config` (the growth switches under `settings.`, world trees and others) had lost it there as well; they show a name again.
- A feature that needs another switch now names it in full: "This needs “Timber” (timber.enabled), which is switched off on this server." Before, the message only gave the key. A language file already on your server keeps its own text.
- README: the supported servers are written as tested: Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2. An outdated block from 1.40.0 that asked for Vystorm Core 0.51.0 is gone.

## 1.47.0-26.2 — 2026-11-18

- Requires Vystorm Core 0.65.0 or newer. Update Core before you update this plugin.
- Every feature can now be switched on and off in `config.yml`. The new keys are added to your existing `config.yml` with their comments and keep today's behaviour. Each switch is listed in Core's `/vadmin features` with a one-line description in seven languages and applies at once, without a restart (only `tutorial.enabled` needs one).
  - The whole plugin: `trees.enabled`. Off: saplings, mushrooms and fungi grow as in vanilla, new chunks keep their vanilla trees and no CustomTrees feature acts; `/customtrees status` and `reload` still work.
  - New switches: `crown-choice.enabled` (`/customtrees sapling`), `garden.enabled` (pruning; pruned leaves wait while it is off), `preview.enabled` (the sneak + right-click preview), `guide.enabled` (`/customtrees`, `/customtrees guide` and the web guide), `settings.biome-profiles` (off: the `biome-profiles` section is ignored), `tutorial.enabled` (needs a restart).
  - Keys that already existed keep their names and are now listed as switches too: the growth switches `settings.custom-player-grown-trees`, `settings.connected-shrubs-and-hedges`, `settings.mixed-hedges`, `settings.giant-3x3-saplings`, `settings.natural-3x3-growth`, `settings.bonemeal-3x3-growth`, `settings.colossal-5x5-saplings`, `settings.natural-5x5-growth`, `settings.bonemeal-5x5-growth`, `settings.glowstone-growth`, `settings.coal-growth`, and `settings.replace-generated-trees`, `settings.spruce-ground`, `settings.spruce-snow`, `settings.fire-protect-trunks`, `settings.fire-protect-crowns`, `shaking.golden-apples`, `timber.replant`, `timber.player-choice`, plus the `enabled` keys of world trees, shaking, herbarium, planting helper, timber, regrowth, fruit trees and breeding.
- A switched-off feature answers one shared message, "This feature is switched off on this server.", its subcommands are hidden from help and tab completion and its menu entries disappear. A feature that needs another switch (regrowth needs timber, everything needs `trees.enabled`) names it. Regrowth now also waits while `regrowth.enabled` is off instead of planting saplings that were already queued.
- Tab completion and help follow a switch at once, without registering the command again.
- Placeholders: the plugin registers its values with Vystorm Core under the namespace `trees`: herbarium forms found, total, rank name and id, forms to the next rank, top list places, keepers, the breeding cooldown and the grown counters. Examples: `%vystorm_trees_herbarium_found%` (forms the player found) and `{trees:herbarium_top:1:name}` (the collector on place 1). `/vadmin placeholders trees` lists them. A value of a switched-off feature is empty.
- The off messages of the herbarium, the planting helper and breeding are now the shared message.
- README: seven languages instead of three, and the Spigot and CraftBukkit 26.2 and 26.3 start test of 1.40.0 named in the requirements instead of "plain Spigot is not supported".

## 1.46.0-26.2 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The fire protection for trees (burning, ignition and fire spread) now denies before other plugins act.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.45.1-26.2 — 2026-11-16

- Lighter on the server: the garden-tree regrowth check (once per second) now skips every tree that has no pruned leaves waiting, instead of looking up its owner, permissions and root block each time. Behaviour is unchanged.

## 1.45.0-26.2 — 2026-11-15

- Rebuilt the Herbarium collection browser and planting helper as Core Panels, with plant selection, collection progress, and square/hedge tabs.
- Both original chest menus remain as fallbacks when Panels are unavailable. There are no item-transfer chest menus in CustomTrees.
- Panel actions recheck current permissions and feature settings; all panel labels are available in all seven bundled languages.

## 1.44.1-26.2 — 2026-11-14

- Fixed: On Spigot, the rollback notice of the settings editor (shown when an invalid value is reset) could not be sent. It now works on Spigot too.

## 1.44.0-26.2 — 2026-11-12

- Fruit trees: right-click a sapling with an apple, sweet berries, glow berries or cocoa beans to make it a fruit sapling. The tree that grows from it bears that fruit – ripe fruit hangs visibly under the leaves and is picked by right-clicking the leaves with an empty hand. One more fruit ripens every 15 minutes, up to 6 (`fruit-trees.*`, also in the first-run setup). Permission `customtrees.fruit`.

## 1.43.0-26.2 — 2026-11-11

- Forest regrowth: a wild tree felled with timber that you did not replant grows back by itself. After 20 minutes (configurable) saplings of the same wood appear where the trunk stood – one or 2x2 for small trees, a 3x3 or 5x5 square for giants and colossi, so they grow back to their old size. Only free, plantable spots are used; pending plantings survive restarts. Settings under `regrowth`, also in the first-run setup.

## 1.42.0-26.2 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.41.1-26.2 — 2026-11-08

- Fixed timber reporting "tree too big" for wide trees such as acacias and grown giant trees. The first, cheap scan uses a 12-block radius; a branch beyond it now triggers a second scan up to `detection.max-radius` (default 64) instead of rejecting the tree. Only trees beyond the configured limits are refused.
- A refused timber fell never blocks the normal block break: the chopped log always breaks as usual, only the automatic felling is skipped.
- Added scanner tests for every vanilla wood type and for wide acacia branches.

## 1.41.0-26.2 — 2026-11-07

- Added sapling breeding. Create two same-species growth-profile saplings with `/customtrees sapling <form> <size>`, hold exactly one in each hand, then use `/customtrees breed`. Both parents are consumed and one offspring replaces the main-hand parent. The offspring independently inherits a crown form and growth class from its parents; its generation is shown in the lore.
- Added all seven languages, persistent progress, permission checks and focused regression tests. Existing Spigot/CraftBukkit and Paper paths remain supported.
- See `FEATURE_VALIDATION.md` for the tested build and native server flows.

## 1.40.0-26.2 - Spigot / CraftBukkit compatibility

- Native block-replacement checks and Bukkit-compatible durability/menu feedback through Core.
- Requires Core 0.51.0 or newer.
- Existing configuration, player data and language files remain compatible.

## 1.39.0-26.2 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Include translated tutorials in all four languages.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.38.0-26.2 (2026-11-05)

- Add `/customtrees plant status`: show the selected square or hedge, required total plant count, remaining selection time and repeat mode without renewing or consuming it. Includes localized messages and command completion.

## 1.37.0-26.2 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x; emits Java 21 bytecode and declares API 1.20.6.
- Uses Vystorm Core 0.48.0 compatibility helpers (new minimum) for optional materials and inventory views. Biome keys use the stable Keyed interface.
- Isolates hanging-moss block data in a separately compiled, feature-detected adapter. On 1.20.6, pale oak and newer vegetation are unavailable; pale-oak loot and collection goals are omitted (76 attainable herbarium forms), while stored entries are preserved. Existing menus and other tree features remain available.
- Adds offline `compat1206` and old-API runtime regression tests to `check`.

## 1.36.0-26.2 (2026-11-03)

- Add hedge planting templates: choose a 4, 8 or 16-sapling row in the planting menu, or use `/customtrees plant hedge<N>` for lengths 4-32. Rows extend forward from the first sapling, consume plain saplings from inventory and reuse the placement-event rollback path. Mushrooms and fungi are excluded; no chunks are loaded.
- Add `planting-helper.max-hedge-length` (default 16; below 4 disables rows) with localized Core config-editor labels. Existing timeout, keep-active and permission settings apply. English, German and Simplified Chinese messages included.

## 1.35.0-26.2 (2026-11-02)

- New: `/customtrees next [plant]` suggests an uncollected herbarium form, preferring the fewest plants, and explains the formation and growth trigger. Filter by a plant ID using tab completion, or click a species in any herbarium for a task for your own collection. Uses `customtrees.herbarium`; English, German and Simplified Chinese messages and command help included. Suggestions are read-only and remain subject to server growth settings.

## 1.34.0-26.2 (unreleased)

- New: **replanting after the timber**. When a small tree (single or 2x2 trunk) comes down, a fitting sapling, mangrove propagule or Nether fungus is planted where its trunk stood, four for a 2x2 trunk, as long as the ground fits. The saplings come from the felled tree's own drops first, then from the player's inventory (creative: free); with too few, nothing is planted. Each sapling fires a `TimberReplantEvent` (a `BlockPlaceEvent`), so claim plugins can refuse it (the whole replanting is then taken back) and CoreProtect logs it. Setting `timber.replant` (default on, label in `/vadmin config CustomTrees`); `/customtrees status` counts replanted trees.

- Fixed: the one-time pass over newly generated chunks cut apart the world trees and 5x5 giant trees of Vystorm Strata worlds (it replaced the part of a tree inside one chunk). Worlds whose generator comes from a plugin in `settings.skip-generator-plugins` (default: `VystormStrata`) are now skipped by that pass; their generator places its own trees.

## 1.33.0-26.2 (2026-10-31)

Needs Vystorm Core 0.45.0.

- New: **built-in timber**. Chop a log with an axe and the whole tree comes down. This works for natural trees and every CustomTrees tree, from shrubs to 10x10 world trees, huge mushrooms and Nether fungi.
  - Two modes (`timber.mode`): `fall` is a real tree fall. Block displays topple away from the player around the stump, the arc stops at terrain, and on impact the tree breaks into items with particles and sound. `drop` breaks the tree from the bottom up, with the items landing underneath.
  - Two triggers (`timber.trigger`): `first-log`, or `percent`, where the tree falls once `trunk-percent` (default 80) of the trunk cross-section is cut. The action bar shows "Trunk 3/8" and the stump stays.
  - Options: sneaking rule, axe requirement, allowed worlds, personal on/off and mode in `/vsettings` (`player-choice`), and a mode or `disabled` per tree size (`tree-types.small|giant|colossal|world`).
- Player builds are never felled. Logs next to non-natural blocks, lying trunks, trunks not standing on natural ground and log structures without natural leaves are refused. With CoreProtect, logs a player placed are refused too. `detection.only-generated-trees` limits the timber to trees CustomTrees grew or generated.
- Tool wear per log (`tool.durability-per-log`, Unbreaking applies) and `tool.spare-tool`, which skips the fell when the axe would break. Leaves drop like vanilla with the tool used (Fortune, Silk Touch). Drops are merged into full stacks, and `drops.into-inventory` is optional.
- Every block fires a `TimberBreakEvent` (a `BlockBreakEvent`) first, so claim plugins can refuse it and CoreProtect logs it. If one log is refused, the tree stays standing. Vystorm Core regions of the kinds in `protected-region-kinds` are never touched (region flag `timber` overrides this).
- While SmoothTimber or another timber plugin is installed, the built-in timber pauses itself with a console warning (`other-timber-plugins: pause`, or `run-both`).
- Performance: chunk snapshots are captured over a few ticks and the tree is scanned off-thread. Checks and block changes are budgeted per tick, each tree has block, radius and height limits, and fells are capped per world and per player. Display entities are capped per tree (larger trees use merged cubes), are non-persistent and tagged, and are removed on finish, chunk unload, plugin disable and entity load.
- New permission `customtrees.timber` (default: everyone). `/customtrees status` has a timber line, and all `timber.*` settings have English and German labels and choices in `/vadmin config CustomTrees`. The tutorial has a timber page.
- Simplified Chinese (zh_cn) translation.
- `/customtrees` now runs through Vystorm Core's command system: subcommands, help, tab completion and error messages in the player's language (`/customtrees setzling`, `/customtrees pflanzen aus`, `/customtrees 植物图鉴 排行榜` work next to the English names for everyone), a clickable `/customtrees help` (`hilfe`, `帮助`), a "did you mean" for typos and an entry in `/vhelp`. New localized root commands `/baumgarten` (German) and `/自定义树木` (Chinese) from the language files (`commands.customtrees.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). `/customtrees` and `/customtrees web` stay, all English subcommands and arguments work as before. The command is no longer declared in plugin.yml; without Core 0.45.0 the log says so and the command is missing.

## 1.32.0-26.2 (2026-10-30)

- New: **Herbarium**, a tree collection for every player. Each tree and fungus form a player grows themselves is recorded: the nine species as single tree/shrub, 2x2, 3x3 giant, 5x5 colossus, hedge, luminous tree and 10x10 world tree, plus red/brown mushrooms and crimson/warped fungi at 1x1, 2x2, 3x3, 5x5 and 10x10 (83 forms). Seven ranks from Sprout to Keeper of Yggdrasil; a new form gives a chat line with a clickable `[Herbarium]` button, a new rank a sound and (optionally) a server-wide announcement. `/customtrees herbarium [player|top]`, entry in `/vmenu` (Character) and, with Vystorm Core 0.43.0, "Herbarium of <player>" in Core's player menu (sneak-right-click a player). The chest menu shows rank, progress, every plant with found/missing forms and the top foresters. Permission `customtrees.herbarium` (default: everyone). Settings `herbarium.enabled`, `announce-ranks` and optional console `rank-commands` per rank (empty by default). Data in `plugins/CustomTrees/herbarium.yml` (atomic writes once a minute and on shutdown; an unreadable file is moved aside, never overwritten). Natural growth without a player is not counted.
- New: **Planting helper**. Pick a square (2x2, 3x3, 5x5 or 10x10) in `/vmenu` (Building) or with `/customtrees plant <size>`, then place one sapling, propagule, mushroom or fungus: the whole square is planted from the inventory, starting at the placed plant and growing forward and to the right. All spots must be free with fitting ground, otherwise nothing else is planted and the blocked spots are shown with red particles. Every plant fires a normal block-place event, so claim plugins and CoreProtect see it; one refusal undoes the whole square. Creative needs no items; tagged profile saplings are not copied. Switches off after one square or 5 minutes; `/customtrees plant off`. Permission `customtrees.plant` (default: everyone). Settings `planting-helper.enabled`, `max-size`, `keep-active`, `timeout-seconds`.
- `/customtrees status` adds a herbarium line (collectors, players with every form, rank commands) and a planting-helper line (squares and plants planted, active helpers). All new settings have English and German labels in `/vadmin config CustomTrees`; the in-game tutorial has a page for each feature.
- Loot now uses Vystorm Core weighted loot tables (Core 0.44+, capability `loot-rolls`): tree shaking rolls `category:gathering_find`, and every species registers its shaking list as own table `customtrees:shaking/<species>` (old weights converted to rarity ranks, same odds; "nothing" stays an empty draw). New key `shaking.loot-table` (default `''` = the category; any Core table or category id overrides it). The player's luck (Luck attribute and Core loot luck) counts; `shaking.golden-apples: false` now excludes golden apples from every shaking roll, including entries an admin adds to the category. With an older Core the plugin keeps its own pick.

### Fixed

- An unreadable `herbarium.yml` whose move to `herbarium.yml.broken-<date-time>` failed would have been overwritten by the next save. A broken file now switches the herbarium to read-only until the next restart (never saved), with a clear console error explaining how to restore it.

## 1.31.0-26.2 (2026-10-29)

- New `shaking.golden-apples` (default true): set it to false and shaken oak and dark oak crowns never drop golden apples, for servers that keep them out of their economy. All `shaking` settings (on/off, rest time, max drops, minimum leaves, golden apples) now have English and German labels in `/vadmin config CustomTrees`.
- `/customtrees status` adds a line for tree shaking: switches plus how many trees were shaken, how many items and golden apples fell since the start, and how many trees are resting right now.

## 1.30.1-26.2 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

## 1.30.0-26.2 (2026-10-28)

- New: shake trees. Right-click a trunk with an empty hand (not sneaking) and the tree shakes: leaves rustle and a few items fall out of the crown – sticks, a sapling and the species' own fruit (apples from oak and dark oak, cocoa beans from jungle, cherry petals, sweet berries from spruce, mangrove propagules, pale hanging moss), very rarely a golden apple from oaks. Bigger crowns roll more often (one roll per 40 leaves, up to 6). Each tree then rests 5 minutes. Works for natural, custom and world trees. Permission `customtrees.shake` (default: everyone); protection plugins that cancel the click also block shaking. Settings under `shaking`.
- `/customtrees status` adds a line for world trees: enabled, height, radius, how many grew completely or partly since the start, and whether one is growing right now.

## 1.29.1-26.2 (2026-10-27)

- World-tree growth never stops because of obstacles: every planned block is placed or left out. Plants, leaves, snow, moss and soft natural ground (dirt, grass block, coarse/rooted dirt, podzol, mycelium, dirt path, sand, red sand, gravel, clay, mud, muddy mangrove roots) are replaced; roots and the trunk base also grow through water and underwater plants. Buildings, containers, tile entities, beds, signs, rails, redstone, glass, planks, bricks, crops, farmland, ice, bedrock, barriers, portals, other trees' wood, lava and crown-level water are left out.
- New `world-trees.replace-stone` (default false) also replaces natural stone (never ores); new `world-trees.max-skipped-percent` (default 100 = never refuse) replaces the fixed 30% limit. Both are in `/vadmin config CustomTrees` with de/en labels.
- Missing footprint chunks are loaded asynchronously and held by a temporary plugin chunk ticket only while the job runs (cap 400 chunks, 60 s timeout, message and nothing consumed on failure); the `world-tree.unloaded` refusal is gone.
- Too tall trees shrink to fit the build limit (Nether: roof) with proportional radius and Core minimums, and the player is told the reduced height; refused only if even height 32 does not fit. With Core 0.39.1 the underground roots are also kept above the world bottom where possible.
- Supports Core 0.39.1 roots that dig 6-14 blocks into the ground (`underground-tree-roots`); still starts on Core 0.39.0.
- New messages `world-tree.shrunk`, `too-high`, `too-large`, `load-failed`; tutorial and config texts updated.

## 1.29.0-26.2 (2026-10-27)

- World trees from the nine saplings are now Yggdrasil-scale giants from Vystorm Core 0.39.0: braided hollow trunks, arching buttress/prop roots, space-colonisation crowns with leaf curtains, vines, moss and species shapes modelled on real ancient trees. Mushrooms and Nether fungi are unchanged.
- New defaults: `world-trees.tree-height` 160 (was 128) and `tree-radius` 44 (was 28); `tree-radius` now accepts 12..48. Existing configs keep their values.
- Growth now checks that the whole footprint (about 110x110 blocks at the defaults) is loaded and fits below the build limit before planning (`world-tree.unloaded` message), grows around natural terrain, water and other trees instead of failing, still stops on buildings, and refuses plans with more than 30% left out.
- Vine faces and pale hanging moss tips are derived from the plan; block data is shared per material during validation; CoreProtect logging includes `*_WOOD` and mangrove roots.
- Require Vystorm Core 0.39.0 (`giant-world-trees`).

## 1.28.1-26.2 (2026-10-27)

- Fix: smaller mushroom and fungus squares (1x1 to 5x5) no longer block vanilla growth. Dispensers, off-hand bone meal, players without `customtrees.grow` and bone meal on soil the custom path does not accept (stone, netherrack) grow vanilla huge mushrooms and fungi again; only complete 10x10 formations stay reserved. Coal and glowstone dust on small fungi are no longer intercepted.
- Docs: plugin.yml states the Vystorm Core 0.38.0 requirement; README no longer refers to an unshipped preview page.

## 1.28.0-26.2

- Add red/brown mushrooms and crimson/warped fungi at 1x1, 2x2, 3x3 and 5x5 alongside 10x10. Largest complete squares take priority; smaller ordinary tree generation remains unchanged.
- Add per-size height/radius configuration and localized editor labels; use the same protected, bone-meal-driven staged growth path.
- Require Vystorm Core 0.38.0 (`fungus-growth-plans`).

## 1.27.0-26.2

- Add explicit 10x10 world-tree formations for nine sapling species, red/brown mushrooms and crimson/warped fungi, using Core 0.37.0 bounded plans.
- Add configurable height, crown radius and staged placement budget, localized preview/tutorial/config labels, natural-growth reservation, collision checks and player-aware protection events before bone meal consumption.
- Extend fire markers and delayed CoreProtect trunk logging to huge mushrooms and Nether fungi.

## 1.26.1-26.2 (2026-10-26)

- Reject malformed prune commands before they can fall through to sapling conversion. Reject non-finite generated-tree replacement chances during configuration validation.

# Changelog

## 1.26.0 (2026-10-25)

- Add irregular podzol/coarse-dirt surface patches around 2x2, 3x3 and 5x5 spruces, plus newly generated spruces.
- Add optional single-layer snow on empty ground around these trees. Snow defaults to cold locations; disable `settings.spruce-snow-cold-only` for decorative snow in warm biomes.
- Toggle soil and snow independently with `settings.spruce-ground` and `settings.spruce-snow`. Existing configs use the new defaults.
- Include sapling ground changes in the cancellable growth event. World-generation decoration follows the existing fresh-chunk/region restrictions. Existing snow, buildings, farmland and planted vegetation are preserved.

## 1.25.4 (2026-10-24)

- Accept the documented `open-grown` crown spelling in `/customtrees sapling` alongside `open_grown`. Empty-argument tab requests now return permission-filtered commands.

## 1.25.3 (2026-10-22)

- Build against the older Paper/Adventure API and allow servers from 1.21.8; Java 25 remains required. Use Core 0.28.1 or newer for the compatibility fixes.

## 1.25.2 (2026-10-18)
- Konfiguration im Core-Editor: ab Vystorm Core 0.27.0 ist die ganze `config.yml` unter `/vadmin config CustomTrees`
  bearbeitbar (Recht `vystorm_core.config.customtrees`); die 37 Werte des Einstellungsabschnitts tragen dort dieselben
  Beschriftungen und Grenzen. Speichern lädt wie `/customtrees reload` (geprüft; bei einem Fehler bleiben die alten
  Einstellungen aktiv und der Admin bekommt die Meldung). Ältere Cores überspringen das still.

## 1.25.1 (2026-10-18)
- Rechte: deutscher Beschreibungstext für alle Knoten und neuer Sammelknoten `customtrees.*`.
- Tab-Vorschläge: `web`, `sapling` (mit Kronenform und Größe) und `prune` werden jetzt vorgeschlagen, jeweils nur mit
  dem passenden Recht; Groß-/Kleinschreibung beim Präfix egal. Test `CommandTabTest`.

## 1.25.0 (2026-10-13)
- Tagged single saplings select a crown form and required formation size through `/customtrees sapling <form> <size>`;
  inconsistent patterns are rejected and catalyst/XP costs are refunded. This optional command requires Core 0.26.5's
  `TREE_GROWTH_PROFILES` capability; the minimum Core requirement for other features remains 0.22.0.
- Coal prices (normal, medium, giant, colossal and hedge), exact trunk/crown fire protection, and garden regrowth delay
  are editable in the Core server settings menu.
- Newly committed custom-tree trunks/crowns can receive bounded chunk-PDC fire markers. Registered tagged gardens allow
  owners to prune up to 16 leaves and regrow them after a configurable delay, with loaded-chunk, owner, permission,
  block-event and Core-region checks; existing trees are not scanned or adopted.
- Garden metadata is stored in a bounded, strict properties file and is never used to restore blocks during startup.

## 1.24.2 (2026-10-12)
- Configuration validation errors use the administrator's selected language for command, settings-menu and web reloads.
- Sneak-right-click previews the sapling pattern, estimated clearance, soil support and registered protected-region overlap without growing a tree.
- Admin status reports per-species growth totals since restart. Glowstone and coal catalyst limits can independently use a per-player cooldown and UTC-day cap; both default to disabled.

## 1.24.1 (2026-09-30)
- Tree shapes are much closer to vanilla Minecraft (with Vystorm Core 0.24.3 or newer, which builds the shapes):
  single saplings grow vanilla-like oaks (blob and fancy), birches, spruces and pines, dark and pale oaks and mangroves
  on arching roots; 2×2, 3×3 and 5×5 trees use the same leaf rules scaled up (fancy-oak clusters, mega-spruce rings,
  dark-oak roofs, pale hanging moss, hanging mangrove propagules).
- Jungle, acacia and cherry keep their earlier branching shapes, slightly extended: fuller crowns, jungle trunk and
  leaf vines with cocoa, hanging cherry blossoms, an occasional small acacia side fork with its own flat pad.
- The new-chunk pass also removes pale hanging moss and hanging mangrove propagules of the replaced vanilla tree.

## 1.24.0 (2026-09-30)
- Optional web page "Tree guide" (module `trees`) in the Vystorm web panel (browser via `/web`, in-game overlay with the
  Vystorm client mod), needs Vystorm Core 0.24.0+ with `web.enabled: true`; without it nothing changes:
  - illustrated guide with this server's configuration: sapling formations (single, 2×2, shrubs, hedge, 3×3 giant,
    5×5 colossus) as top-view diagrams with their triggers, size comparison, catalyst costs (glowstone dust + XP, coal),
    all species with silhouettes and the four architecture variants;
  - admin panel (`customtrees.admin`): the values of `/customtrees status` (live), reload button, link to the settings page.
- `/customtrees guide [species|admin]` (also `/customtrees web`) opens the guide in the overlay, else sends a login link;
  without the web platform it opens the in-game tutorial. The tutorial hint and `/customtrees status` get a guide button.
- `/customtrees` is now usable by every player (guide); `status` and `reload` still need `customtrees.admin`. Tab completion.
- Reloading (command or web) also refreshes Core's web settings page.

## 1.23.0 (2026-09-30)
- English and German language packs (`lang/en.yml`, `lang/de.yml`) via Vystorm Core's localization:
  every player sees the plugin in their Minecraft language (German clients keep the previous German texts);
  new `language` option in `config.yml` (`auto` or a fixed code); `/customtrees reload` also reloads the language files.
- English in-game guide (`tutorial_en.yml`); German players keep the existing guide.
- Server settings in the Vystorm menu are translated per viewer.
- Requires Vystorm Core 0.22.0 or newer (clear console message on older Core).
- MIT license (in the jar and the repository); English README.

## 1.22.5
- Rejects `generated-tree-scan-depth` outside 24–160 instead of clamping silently; warns about unknown worlds in `enabled-worlds`.

## 1.22.4
- Player-grown trees only replace free blocks and fire a `StructureGrowEvent` so protection plugins can filter them;
  cancelled or aborted growth refunds catalyst and XP; no synchronous chunk loads.

## 1.22.3
- Luminous-tree glowstone is decoration without drops (no glowstone duplication).

## 1.22.2
- New-chunk pass only changes fresh, untouched chunks outside protected regions; asynchronous search with a tick budget.
