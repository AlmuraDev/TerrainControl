package com.khorn.terraincontrol.neoforge;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.ChunkHeightAndBiomeFix;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;

import java.util.HashMap;
import java.util.Map;

/**
 * The 1.12 vanilla biomes (TerrainControl's DefaultBiome, by id) as 26.3 biomes.
 *
 * <p>As {@link LegacyMaterials} does for blocks, this follows Minecraft's own upgrade of a 1.12 world: the ids go through the table
 * the 1.18 chunk fix uses (ChunkHeightAndBiomeFix's BIOMES_BY_ID, opened by the access transformer), then through the data fixer up
 * to 26.3, which follows 1.18's renames and merges (Extreme Hills became windswept_hills, Ice Mountains became snowy_plains).</p>
 */
final class VanillaBiomes
{
    /** The data version of ChunkHeightAndBiomeFix's names */
    private static final int BIOME_TABLE_DATA_VERSION = 2832;

    private static volatile Map<Integer, ResourceKey<Biome>> keys;

    /**
     * Gets the 26.3 biome a 1.12 biome id became.
     *
     * @param id The 1.12 biome id.
     * @return The biome, or null when 1.12 had no biome with that id.
     */
    static ResourceKey<Biome> byLegacyId(final int id)
    {
        Map<Integer, ResourceKey<Biome>> table = keys;
        if (table == null)
        {
            synchronized (VanillaBiomes.class)
            {
                table = keys;
                if (table == null)
                {
                    table = build();
                    keys = table;
                }
            }
        }
        return table.get(id);
    }

    private static Map<Integer, ResourceKey<Biome>> build()
    {
        final int currentDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        final Map<Integer, ResourceKey<Biome>> table = new HashMap<>();
        ChunkHeightAndBiomeFix.BIOMES_BY_ID.int2ObjectEntrySet().forEach(entry -> {
            final Dynamic<Tag> name = new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(entry.getValue()));
            final String upgraded = DataFixers.getDataFixer()
                    .update(References.BIOME, name, BIOME_TABLE_DATA_VERSION, currentDataVersion)
                    .asString(entry.getValue());
            table.put(entry.getIntKey(), ResourceKey.create(Registries.BIOME, Identifier.parse(upgraded)));
        });
        return Map.copyOf(table);
    }

    private VanillaBiomes()
    {
    }
}
