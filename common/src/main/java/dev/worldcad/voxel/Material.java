package dev.worldcad.voxel;

/**
 * One entry of the export palette. Becomes a {@code <base>} in the 3MF {@code <basematerials>} group.
 *
 * @param name block id, e.g. {@code minecraft:oak_planks}
 * @param rgb  0xRRGGBB display color
 */
public record Material(String name, int rgb) {
}
