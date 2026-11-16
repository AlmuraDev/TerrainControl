package com.khorn.terraincontrol.neoforge.api;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TerrainControl's biomes for other mods, on the server.
 *
 * <p>TerrainControl's custom biomes are 26.3 biomes of their own (terraincontrol:&lt;world&gt;/&lt;biome&gt;), which the server sends to
 * the clients with their colours and climate, but without a name a player can read: a biome's name is a translation the client looks
 * up, and the clients have none for TerrainControl's. This gives a biome's own name from its config ("Lakeshore",
 * "GNP Montane Forest 1318m"), for a mod to send to its clients itself (as Almura's HUD does).</p>
 *
 * <p>Server side only, and filled as TerrainControl's worlds load their configs: a biome of a world that has not loaded yet has no
 * name here. Biomes that are not TerrainControl's own (vanilla biomes, which TerrainControl's configs of vanilla biomes are saved as)
 * have none either.</p>
 */
public final class TerrainControlBiomes
{
    private static final Map<ResourceKey<Biome>, String> NAMES = new ConcurrentHashMap<>();

    /**
     * Gets the name of one of TerrainControl's biomes.
     *
     * @param biome The biome's key, such as terraincontrol:orilla/lakeshore.
     * @return Its name in TerrainControl's configs; empty when it is no TerrainControl biome of a loaded world.
     */
    public static Optional<String> displayName(final ResourceKey<Biome> biome)
    {
        return Optional.ofNullable(NAMES.get(biome));
    }

    /**
     * Gets the name of one of TerrainControl's biomes.
     *
     * @param biome The biome, such as the one at a player's position (level.getBiome(pos)).
     * @return Its name in TerrainControl's configs; empty when it is no TerrainControl biome of a loaded world.
     */
    public static Optional<String> displayName(final Holder<Biome> biome)
    {
        return biome.unwrapKey().flatMap(TerrainControlBiomes::displayName);
    }

    /**
     * Records a biome's name, as a world loads its biome configs. For TerrainControl itself.
     *
     * @param biome The biome's key.
     * @param name  Its name in the configs.
     */
    public static void register(final ResourceKey<Biome> biome, final String name)
    {
        NAMES.put(biome, name);
    }

    private TerrainControlBiomes()
    {
    }
}
