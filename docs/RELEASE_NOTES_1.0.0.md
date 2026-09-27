## WorldCAD 1.0.0

Turn anything you build in Minecraft into a 3D print. Stand on two corners, type one command, and you get a clean, print-ready **STL** for Bambu Studio, PrusaSlicer, Cura and other slicers.

### Download the jar for your Minecraft version
| Minecraft | File |
|---|---|
| 26.3 | `worldcad-1.0.0+mc26.3.jar` |
| 1.21.11 | `worldcad-1.0.0+mc1.21.11.jar` |
| 1.21 – 1.21.1 | `worldcad-1.0.0+mc1.21.1.jar` |
| 1.20.1 | `worldcad-1.0.0+mc1.20.1.jar` |

It also needs [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api). Put both jars in your `.minecraft/mods` folder.

### How to use
1. Stand on a bottom corner of your build and type `/cad pos1`.
2. Stand on (or fly to) the opposite top corner and type `/cad pos2`. A colored particle outline shows the selection.
3. Type `/cad export mybuild`. The file lands in `.minecraft/cad_exports/`. Click the path in chat to copy it.

Type `/cad` in-game for all options: `--scale=5` (mm per block), `--format=3mf` (colored), `--smooth`.

### Highlights
- **Commands only.** No items or blocks, so your worlds stay vanilla and you can remove the mod any time.
- **No cheats needed** in single-player.
- **Crack-free meshes** that need no repair in the slicer, with merged faces for small files.
- **License:** CC BY-NC-SA 4.0. Free to share and modify with credit to Knbezz; no commercial use; changed versions must use the same license.
- **Server-friendly.** Install it on the server only; players join with normal Minecraft. Only operators can use `/cad`.
