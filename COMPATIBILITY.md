# Current compatibility — 1.37.0

Paper/Purpur 1.20.6 – 26.x, Java 21 bytecode (use the Java version required by your server), Vystorm Core 0.48.0+. Offline compilation against 1.20.6 and regression tests are part of Gradle `check`. Pale oak and newer decoration are skipped when unavailable; herbarium goals follow available species. Real-server acceptance of this release is still pending; no server was started for this change.

The matrix below is historical evidence for older releases, not the requirements or certification of 1.37.0.

## Compatibility checks — 2026-10-22

CustomTrees 1.29.x requires **Vystorm Core 0.39.0 or newer** (1.29.1: 0.39.1 recommended for underground roots; 1.28.x: 0.38.0); the 2026-10-22 matrix below predates the world-tree and fungus features and was not rerun for them.

Minimum for the current six-plugin collection: **Paper/Purpur 1.21.8, Java 25**. Use Core 0.28.1 wherever Core is installed. The historic `-26.2` filename suffix does not express the new minimum.

Validated builds: Paper 1.21.8 #60, 1.21.11 #132, 26.1.2 #74, 26.2 #134 and 26.3 #211; Purpur 1.21.8 #2497 and 26.3 #2654. On each: 17 XP checks, 321 block/dialog checks, 162 tree generation cases / 500 checks including actual world storage/reload/rebuild and listener registration. Seven restarts confirmed all six plugins/dependencies and the enabled pause-menu data pack. Current collection unit results: 1,265 passed, eight database tests skipped, zero failures; the unchanged BetterPortals result is reused.

Paper 1.21.7 is excluded because Player.closeDialog() is absent. 1.21.6 lacks the required native dialog API. The new API declaration is supported by functional tests, not by startup alone. Compile against Paper 1.21.11 / Adventure 4 for this range; newer Adventure builder call descriptors are not backward compatible.

Tested dependencies: VaultUnlocked 2.20.1 and ProtocolLib 5.5.0-SNAPSHOT-f0e959a. Automatic ProtocolLib update checks were disabled in test fixtures after an updater-only shutdown race. ProtocolLib warns that its author has not tested 26.3. Real-client portal rendering/teleportation, proxy/payment crash recovery, database integration, optional web and client/resource-pack combinations were not certified. Resource-pack formats must match the actual server/client version. No Folia/plain-Spigot or paid-plugin compatibility claim. Do not hot-reload.

Full local evidence is in `~/Documents/Codex/2026-10-22/free-plugin-compat/`; the sanitized report and reproducible probe sources are versioned in the vystorm-site repository under `tools/marketplace/reports/2026-10-22-backport-*` and `tools/marketplace/compatibility/`. No live deployment was performed.
