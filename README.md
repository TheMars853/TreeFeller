# TreeFell

Fabric mod for Minecraft **26.2**: break a single log block and the entire tree
(wood only — leaves stay and decay normally) is felled instantly.

## Versions used (verified June 20, 2026)

| Component       | Version                |
|------------------|--------------------------|
| Minecraft        | 26.2                     |
| Fabric Loader    | 0.19.3                   |
| Fabric API       | 0.152.2+26.2             |
| Fabric Loom      | 1.17                     |
| Gradle           | 9.5.1                    |
| Mappings         | None — Minecraft 26.2 is already unobfuscated (native Mojang names in the jar) |
| Java             | 25                       |

## Project setup ( For Developers )

1. You need **Java 25 (JDK)** and **Gradle 9.5.1** (or use the wrapper, once generated).
2. Generate the Gradle wrapper the first time (if not already present):
```bash
   gradle wrapper --gradle-version 9.5.1
```
3. Open the folder in IntelliJ IDEA (2025.3+ recommended) as a Gradle project,
   or from the terminal:
```bash
   ./gradlew build
```
4. The compiled jar will be in `build/libs/treefell-1.0.0.jar`.

## Playing the mod

1. Install Fabric Loader 0.19.3 for Minecraft 26.2 (fabricmc.net/use/installer).
2. Download the mod from the official Modrinth page:
   👉 **https://modrinth.com/mod/treefell**
3. Also download **Fabric API** (required dependency) for 26.2, also available on
   Modrinth.
4. Drop both jars into the `mods/` folder of your Fabric 26.2 profile and launch
   the game.

For development with direct hot-testing via Gradle:
```bash
./gradlew runClient
```

## How it works

- Hooks into Fabric API's `PlayerBlockBreakEvents.AFTER` event (no Mixin required,
  which makes it more resilient to future Minecraft updates).
- When the broken block belongs to the vanilla `minecraft:logs` tag (covers regular
  and stripped logs of every wood type, including modded wood that uses the same
  tag), a BFS (breadth-first search) starts across 26 directions (3x3x3 around each
  log) to find all connected log blocks.
- Every block found is destroyed with `destroyBlock(pos, true)`, which drops items
  exactly like a normal break (so Fortune/Silk Touch still work as expected).
- Safety cap: **512 blocks** per tree, to prevent lag on huge structures or
  potential abuse/griefing on builds made entirely of wood.
- Everything runs **server-side and syngleplayer too** (`world.isClientSide()` check).
- Leaves are never touched: they remain and decay according to normal vanilla
  rules (no log within 4-6 blocks).
