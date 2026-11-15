package com.khorn.terraincontrol;

import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;

/**
 * Represents one of Minecraft's block states. Immutable.
 *
 * <p>On 1.12 a material was a block id with a data value (0 to 15); since Minecraft 1.13 it is a block state, a block with its
 * properties, so the data value is gone. {@link DefaultMaterial} still names the 1.12 blocks, for the code that asks for them.</p>
 * 
 * @see TerrainControlEngine#readMaterial(String)
 * @see TerrainControlEngine#toLocalMaterialData(DefaultMaterial, int)
 */
public interface LocalMaterialData
{

    /**
     * Gets the name of this material, as a block state: the block's id, with its properties in brackets when they differ from the
     * block's default state, like "minecraft:oak_log[axis=x]".
     * 
     * @return The name of this material.
     */
    String getName();

    /**
     * Same as {@link #getName()}.
     * 
     * @return The name of this material.
     */
    @Override
    String toString();

    /**
     * Gets the internal block id: the block's number in the block registry, the same for all of its states. It is only good while
     * the server runs, never for saving.
     *
     * @return The internal block id, 0 or more.
     */
    int getBlockId();

    /**
     * Gets whether this material is a liquid, like water or lava.
     * 
     * @return True if this material is a liquid, false otherwise.
     */
    boolean isLiquid();

    /**
     * Gets whether this material is solid. If there is a
     * {@link #toDefaultMaterial() DefaultMaterial available}, this property is
     * defined by {@link DefaultMaterial#isSolid()}. Otherwise, it's up to the
     * mod that provided this block to say whether it's solid or not.
     * 
     * @return True if this material is solid, false otherwise.
     */
    boolean isSolid();

    /**
     * Gets whether this material is air. This is functionally equivalent to
     * {@code isMaterial(DefaultMaterial.AIR)}, but may yield better
     * performance.
     * @return True if this material is air, false otherwise.
     */
    boolean isAir();

    /**
     * Gets the default material belonging to this material. The block data will
     * be lost. If the material is not one of the vanilla Minecraft materials,
     * {@link DefaultMaterial#UNKNOWN_BLOCK} is returned.
     * 
     * @return The default material.
     */
    DefaultMaterial toDefaultMaterial();

    /**
     * Gets whether snow can fall on this block.
     * 
     * @return True if snow can fall on this block, false otherwise.
     */
    boolean canSnowFallOn();

    /**
     * Gets whether the block is of the given material. Block data is ignored,
     * as {@link DefaultMaterial} doesn't include block data.
     * 
     * @param material
     *            The material to check.
     * @return True if this block is of the given material, false otherwise.
     */
    boolean isMaterial(DefaultMaterial material);

    /**
     * Gets the default state of this material's block. This instance is not modified.
     *
     * @return The block's default state.
     */
    LocalMaterialData withDefaultBlockData();

    /**
     * Gets whether this material equals another material. The block data is
     * taken into account.
     * 
     * @param other
     *            The other material.
     * @return True if the materials are equal, false otherwise.
     */
    @Override
    boolean equals(Object other);

    /**
     * Gets the hashCode of the material, unique for its block state.
     * 
     * @return The unique hashCode.
     */
    @Override
    int hashCode();

    /**
     * Gets the hashCode of the material, based on only its block. No
     * hashCode returned by this method may be the same as any hashCode returned
     * by {@link #hashCode()}.
     * 
     * @return The unique hashCode.
     */
    int hashCodeWithoutBlockData();

    /**
     * Gets a new material that is rotated 90 degrees. North -> west -> south ->
     * east. If this material cannot be rotated, the material itself is
     * returned.
     * 
     * @return The rotated material.
     */
    LocalMaterialData rotate();

    /**
     * Gets whether this material falls down when no other block supports this
     * block, like gravel and sand do.
     * @return True if this material can fall, false otherwise.
     */
    boolean canFall();

}
