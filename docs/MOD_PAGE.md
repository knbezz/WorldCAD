# WorldCAD: 3D print your Minecraft builds

*by Knbezz*

Select anything in your world (a house, a tree, a whole castle) and export it as a **clean, print-ready STL** that opens in Bambu Studio, PrusaSlicer, Cura and other slicers.

**Commands only, no new items or blocks.** Your worlds stay completely vanilla, and you can remove the mod at any time. On servers, players can join without installing anything.

**Works on Minecraft 1.20.1, 1.21.1, 1.21.11 and 26.3** (Fabric).

## Features
- **Two commands to select:** stand on a corner block and type `/cad pos1`, then stand on (or fly to) the opposite corner and type `/cad pos2`. The selection is outlined with colored particles.
- **Print-ready STL:** a closed, crack-free mesh with no repair needed. Faces are merged, so files stay small.
- **Your choice of size:** 10 mm per block by default, adjustable with `--scale`.
- **Sits flat on the print bed**, centered and Z-up.
- **Optional:** colored 3MF (`--format=3mf`) or a smoothed look (`--smooth`).
- **Doesn't freeze the game.** The mesh is built in the background.
- **No cheats needed** in single-player.

## How to use
1. Stand on the block at one bottom corner of your build and type `/cad pos1`.
2. Go to the opposite corner, diagonally across **and at the top**. Stand on the top block, or fly there in Creative. Then type `/cad pos2`.
3. Type `/cad export mycastle`.
4. Click the green file path in chat to copy it, or open the `cad_exports` folder in your Minecraft folder.

Type `/cad` in-game for all commands.

| Command | What it does |
|---|---|
| `/cad pos1`, `/cad pos2` | Sets a corner at the block you're standing on |
| `/cad pos1 <x y z>` | Sets a corner at exact coordinates |
| `/cad info` | Shows the selection size |
| `/cad export <name>` | Saves `<name>.stl` |
| `/cad export <name> --scale=5` | 5 mm per block (half size) |
| `/cad export <name> --format=3mf` | Colored 3MF instead of STL |
| `/cad export <name> --smooth` | Rounded, organic look |
| `/cad clear` | Removes the selection |

## Good to know
- The block you stand on is included, so for `/cad pos1` stand on your build's first layer if you don't want the ground.
- Air, water, lava, grass, flowers and torches are left out, so you get the solid shape. Add `--fluids` or `--all-blocks` to include them.
- Stairs, slabs and fences export as full blocks.
- The biggest area you can export is about 4 million blocks, for example 256 × 64 × 256.
- **Multiplayer:** install WorldCAD on the server. Only operators can use `/cad`, and files are saved on the server.

## License
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/): you may share and modify WorldCAD, but not sell it or use it commercially. You must credit Knbezz, and changed versions must use the same license.

## Requirements
- Minecraft **1.20.1, 1.21 – 1.21.1, 1.21.11 or 26.3**. Download the jar that matches your version.
- [Fabric Loader](https://fabricmc.net/use/)
- [Fabric API](https://modrinth.com/mod/fabric-api)
