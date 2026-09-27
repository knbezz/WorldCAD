# WorldCAD

By **Knbezz**.

A Fabric mod that exports a region of a Minecraft world as a print-ready **STL**, or a colored **3MF**. It only adds commands: no items, blocks or client code. Worlds stay vanilla, and a server running it accepts unmodded players.

**Download:** get the jar for your Minecraft version from the [Releases](../../releases) page. It also needs [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api). How to use it: [docs/MOD_PAGE.md](docs/MOD_PAGE.md).

Licensed under [CC BY-NC-SA 4.0](LICENSE): credit Knbezz, no commercial use, and share changed versions under the same license.

## Supported versions
| Jar | Minecraft | Java |
|---|---|---|
| `worldcad-<ver>+mc1.20.1.jar` | 1.20.1 | 17+ |
| `worldcad-<ver>+mc1.21.1.jar` | 1.21 – 1.21.1 | 21+ |
| `worldcad-<ver>+mc1.21.11.jar` | 1.21.11 | 21+ |
| `worldcad-<ver>+mc26.3.jar` | 26.3 | 25+ |

## Building
```
./gradlew buildAll                    # all versions → build/release/
./gradlew :fabric-26.3:runClient      # test one version in a dev client
./gradlew :common:test                # mesh / export unit tests
```
Loom 1.18 runs on **Java 25**. `gradle/gradle-daemon-jvm.properties` asks for it, and Gradle finds any installed JDK 25. Here it lives in `~/.gradle/jdks`, registered through `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`.

## Layout
```
common/                  pure Java, no Minecraft classes, compiled for Java 17, unit-tested
  voxel/  VoxelGrid, Material
  mesh/   GreedyMesher (merged faces + T-junction repair), SurfaceNetsMesher (--smooth), Mesh
  shape/  ShapeGeometry, ShapeType (for the future Studio edition)
  export/ ExportOptions, ModelTransform, StlWriter, ThreeMFWriter
fabric/shared/           Minecraft glue, written against Mojang's official names; compiles unchanged for every version
  WorldCad               entrypoint: commands, particle outline tick, disconnect cleanup
  command/CadCommand     /cad …
  selection/             SelectionBox, SelectionManager, SelectionOutline (dust particles, per player)
  export/                VoxelCapture (server thread), ExportService (background meshing + chat result)
fabric/<version>/        build.gradle + gradle.properties + Compat.java (the only per-version code)
fabric/fabric.gradle     shared Loom setup (remap plugin ≤ 1.21.11, plain Loom for 26.x)
```
### What `Compat.java` covers per version
| | 1.20.1 / 1.21.1 | 1.21.11 / 26.3 |
|---|---|---|
| player's level | `serverLevel()` | `level()` |
| permission check | `hasPermission(2)` | `permissions().hasPermission(COMMANDS_GAMEMASTER)` |
| owner check | `isSingleplayerOwner(GameProfile)` | `isSingleplayerOwner(NameAndId)` |
| chat link | `new ClickEvent(COPY_TO_CLIPBOARD, …)` | `new ClickEvent.CopyToClipboard(…)` |
| dust particle | `DustParticleOptions(Vector3f, scale)` | `DustParticleOptions(int rgb, scale)` |
| send particle | `sendParticles(player, p, force, …)` | `sendParticles(player, p, force, alwaysShow, …)` |

Chat links copy the file path instead of opening the file. Minecraft rejects OPEN_FILE click events sent by a server (the whole message is dropped with "Can't deliver chat message").

### Adding a Minecraft version
1. Create `fabric/<ver>/` by copying the closest version's folder, and set `minecraft_version`, `fabric_api_version` (from Modrinth) and the Java versions.
2. Add the version to `settings.gradle`.
3. Run `./gradlew :fabric-<ver>:build` and fix whatever breaks in `Compat.java`. If something shared breaks, move that call into `Compat`.

## Mesh guarantees
- STL faces are merged, then T-junctions are split, so the export is closed with no cracks. `--no-merge` emits one quad per face.
- Known limitation: blocks that touch only along an edge produce a non-manifold edge. Slicers handle it.
