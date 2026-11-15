package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.BiomeIds;
import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.LocalWorld;
import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.configuration.BiomeLoadInstruction;
import com.khorn.terraincontrol.configuration.ConfigProvider;
import com.khorn.terraincontrol.configuration.ServerConfigProvider;
import com.khorn.terraincontrol.customobjects.CustomObjectStructureCache;
import com.khorn.terraincontrol.exception.BiomeNotFoundException;
import com.khorn.terraincontrol.generator.ChunkProviderTC;
import com.khorn.terraincontrol.generator.SpawnableObject;
import com.khorn.terraincontrol.generator.biome.BiomeGenerator;
import com.khorn.terraincontrol.logging.LogMarker;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import com.khorn.terraincontrol.util.NamedBinaryTag;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultBiome;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;
import com.khorn.terraincontrol.util.minecraftTypes.TreeType;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A TerrainControl world on 26.3: its configs (config/TerrainControl/worlds/&lt;name&gt;), its biomes, and the generators that
 * {@link com.khorn.terraincontrol.neoforge.generator.TCChunkGenerator} and
 * {@link com.khorn.terraincontrol.neoforge.generator.TCBiomeSource} run.
 *
 * <p>The configs are loaded when the world is made; the biome generator and the terrain generator need the world's seed, and are
 * made by {@link #initialize(long)} once the level is created.</p>
 *
 * <p>TerrainControl's generators keep their working state in fields, and 26.3 generates chunks on several threads at once, so all
 * of TerrainControl's generating runs under {@link #lock()}, one chunk at a time.</p>
 *
 * <p>Population (resources, custom objects, trees, mobs: the features step) is not ported yet; its methods do nothing for now.</p>
 */
public final class NeoForgeWorld implements LocalWorld
{
    private final String name;
    private final HolderGetter<Biome> biomes;
    private final ServerConfigProvider settings;
    private final Map<String, LocalBiome> biomeNames = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private int nextBiomeId = DefaultBiome.values().length;

    private volatile boolean initialized;
    private long seed;
    private BiomeGenerator biomeGenerator;
    private ChunkProviderTC terrain;
    private CustomObjectStructureCache structureCache;

    /**
     * Loads a world's configs.
     *
     * @param name     The world's name, the name of its folder in config/TerrainControl/worlds.
     * @param worldDir That folder.
     * @param biomes   The server's biomes.
     */
    NeoForgeWorld(final String name, final File worldDir, final HolderGetter<Biome> biomes)
    {
        this.name = name;
        this.biomes = biomes;
        this.settings = new ServerConfigProvider(worldDir, this);
    }

    /**
     * Makes the generators that need the seed. Only the first call counts.
     *
     * @param seed The level's seed.
     */
    public void initialize(final long seed)
    {
        if (this.initialized)
        {
            return;
        }
        this.lock.lock();
        try
        {
            if (this.initialized)
            {
                return;
            }
            this.seed = seed;
            this.biomeGenerator = TerrainControl.getBiomeModeManager().createCached(this.settings.getWorldConfig().biomeMode, this);
            this.terrain = new ChunkProviderTC(this.settings, this);
            this.structureCache = new CustomObjectStructureCache(this);
            this.initialized = true;
            TerrainControl.log(LogMarker.INFO, "World \"{}\" ready, seed {}", this.name, seed);
        } finally
        {
            this.lock.unlock();
        }
    }

    /**
     * Gets whether {@link #initialize(long)} has run.
     *
     * @return Whether it has.
     */
    public boolean isInitialized()
    {
        return this.initialized;
    }

    /**
     * Gets the lock all of TerrainControl's generating for this world runs under.
     *
     * @return The lock.
     */
    public ReentrantLock lock()
    {
        return this.lock;
    }

    /**
     * Gets the terrain generator. Only after {@link #initialize(long)}, and only under {@link #lock()}.
     *
     * @return The terrain generator.
     */
    public ChunkProviderTC getTerrainGenerator()
    {
        return this.terrain;
    }

    /**
     * Gets the 26.3 biome a 1.12 vanilla biome became.
     *
     * @param legacyId The 1.12 biome id.
     * @return The biome; plains when 26.3 has none for it.
     */
    private Holder<Biome> vanillaBiome(final int legacyId)
    {
        final ResourceKey<Biome> key = VanillaBiomes.byLegacyId(legacyId);
        return this.biomes.get(key == null ? Biomes.PLAINS : key).map(holder -> (Holder<Biome>) holder)
                .orElseGet(() -> this.biomes.getOrThrow(Biomes.PLAINS));
    }

    /**
     * Gets every 26.3 biome this world saves its chunks with.
     *
     * @return The biomes.
     */
    public List<Holder<Biome>> getSavedBiomes()
    {
        final List<Holder<Biome>> saved = new ArrayList<>();
        for (final LocalBiome biome : this.settings.getBiomeArray())
        {
            if (biome != null && !saved.contains(((NeoForgeBiome) biome).getSavedBiome()))
            {
                saved.add(((NeoForgeBiome) biome).getSavedBiome());
            }
        }
        return saved;
    }

    private void warnOnce(final String key, final String message, final Object... params)
    {
        if (this.warned.add(key))
        {
            TerrainControl.log(LogMarker.WARN, message, params);
        }
    }

    // ----- Biome init -----

    @Override
    public LocalBiome createBiomeFor(final BiomeConfig biomeConfig, final BiomeIds biomeIds)
    {
        final Holder<Biome> saved;
        if (DefaultBiome.getBiome(biomeIds.getSavedId()) != null)
        {
            saved = vanillaBiome(biomeIds.getSavedId());
        } else
        {
            // A custom biome saved as itself needs a 26.3 biome of its own: these come with the biome registry entries, until then
            // its chunks are saved as plains
            warnOnce("biome:" + biomeConfig.getName(), "Biome \"{}\" of world \"{}\" has no 26.3 biome yet; its chunks are saved as plains",
                    biomeConfig.getName(), this.name);
            saved = vanillaBiome(DefaultBiome.PLAINS.Id);
        }
        final NeoForgeBiome biome = new NeoForgeBiome(biomeConfig, biomeIds, DefaultBiome.getBiome(biomeIds.getGenerationId()) == null,
                saved);
        this.biomeNames.put(biome.getName(), biome);
        return biome;
    }

    @Override
    public int getMaxBiomesCount()
    {
        return TerrainControl.MAX_BIOME_ID;
    }

    @Override
    public int getFreeBiomeId()
    {
        return this.nextBiomeId++;
    }

    @Override
    public LocalBiome getBiomeById(final int id) throws BiomeNotFoundException
    {
        final LocalBiome biome = this.settings.getBiomeByIdOrNull(id);
        if (biome == null)
        {
            throw new BiomeNotFoundException(id, Arrays.asList(this.settings.getBiomeArray()));
        }
        return biome;
    }

    @Override
    public LocalBiome getBiomeByIdOrNull(final int id)
    {
        return this.settings.getBiomeByIdOrNull(id);
    }

    @Override
    public LocalBiome getBiomeByName(final String name) throws BiomeNotFoundException
    {
        final LocalBiome biome = this.biomeNames.get(name);
        if (biome == null)
        {
            TerrainControl.log(LogMarker.INFO, "No biome found with name '{}'. Skipping...", name);
        }
        return biome;
    }

    @Override
    public Collection<? extends BiomeLoadInstruction> getDefaultBiomes()
    {
        final List<BiomeLoadInstruction> standardBiomes = new ArrayList<>();
        for (final DefaultBiome defaultBiome : DefaultBiome.values())
        {
            standardBiomes.add(defaultBiome.getLoadInstructions(new NeoForgeMojangSettings(defaultBiome, vanillaBiome(defaultBiome.Id)),
                    128));
        }
        return standardBiomes;
    }

    @Override
    public int getVanillaBiomesUpdates()
    {
        return 0;
    }

    // ----- Biome manager -----

    @Override
    public BiomeGenerator getBiomeGenerator()
    {
        return this.biomeGenerator;
    }

    @Override
    public LocalBiome getBiome(final int x, final int z) throws BiomeNotFoundException
    {
        return getCalculatedBiome(x, z);
    }

    @Override
    public LocalBiome getSavedBiome(final int x, final int z) throws BiomeNotFoundException
    {
        // The chunks are saved with the biomes TerrainControl calculates
        return getCalculatedBiome(x, z);
    }

    @Override
    public LocalBiome getCalculatedBiome(final int x, final int z)
    {
        return getBiomeById(this.biomeGenerator.getBiome(x, z));
    }

    // ----- Default generators: 26.3 places its own structures; the rest comes with population -----

    @Override
    public void prepareDefaultStructures(final int chunkX, final int chunkZ, final boolean dry)
    {
    }

    @Override
    public void placeDungeon(final Random rand, final int x, final int y, final int z)
    {
        warnOnce("dungeon", "Dungeons are not ported to 26.3 yet");
    }

    @Override
    public void placeFossil(final Random rand, final ChunkCoordinate chunkCoord)
    {
        warnOnce("fossil", "Fossils are not ported to 26.3 yet");
    }

    @Override
    public boolean placeTree(final TreeType type, final Random rand, final int x, final int y, final int z)
    {
        warnOnce("tree", "Vanilla trees are not ported to 26.3 yet");
        return false;
    }

    @Override
    public boolean placeDefaultStructures(final Random rand, final ChunkCoordinate chunkCoord)
    {
        return false;
    }

    @Override
    public SpawnableObject getMojangStructurePart(final String name)
    {
        return null;
    }

    @Override
    public void replaceBlocks(final ChunkCoordinate chunkCoord)
    {
    }

    @Override
    public void placePopulationMobs(final LocalBiome biome, final Random random, final ChunkCoordinate chunkCoord)
    {
    }

    // ----- Population -----

    @Override
    public void startPopulation(final ChunkCoordinate chunkCoord)
    {
    }

    @Override
    public void endPopulation()
    {
    }

    // ----- Blocks: come with population -----

    @Override
    public LocalMaterialData getMaterial(final int x, final int y, final int z)
    {
        return NeoForgeMaterialData.ofDefaultMaterial(DefaultMaterial.AIR, 0);
    }

    @Override
    public boolean isEmpty(final int x, final int y, final int z)
    {
        return true;
    }

    @Override
    public void setBlock(final int x, final int y, final int z, final LocalMaterialData material)
    {
    }

    @Override
    public void setBlock(final int x, final int y, final int z, final LocalMaterialData material, final NamedBinaryTag metaOrNull)
    {
    }

    @Override
    public NamedBinaryTag getMetadata(final int x, final int y, final int z)
    {
        return null;
    }

    @Override
    public int getLiquidHeight(final int x, final int z)
    {
        return -1;
    }

    @Override
    public int getSolidHeight(final int x, final int z)
    {
        return -1;
    }

    @Override
    public int getHighestBlockYAt(final int x, final int z)
    {
        return -1;
    }

    @Override
    public int getLightLevel(final int x, final int y, final int z)
    {
        return -1;
    }

    @Override
    public boolean isLoaded(final int x, final int y, final int z)
    {
        return false;
    }

    // ----- Other information -----

    @Override
    public ConfigProvider getConfigs()
    {
        return this.settings;
    }

    @Override
    public CustomObjectStructureCache getStructureCache()
    {
        return this.structureCache;
    }

    @Override
    public String getName()
    {
        return this.name;
    }

    @Override
    public long getSeed()
    {
        return this.seed;
    }

    @Override
    public int getHeightCap()
    {
        return this.settings.getWorldConfig().worldHeightCap;
    }

    @Override
    public int getHeightScale()
    {
        return this.settings.getWorldConfig().worldHeightScale;
    }

    @Override
    public void spawnEntity(final String id, final float x, final float y, final float z, final int amount, final NamedBinaryTag metaOrNull)
    {
    }
}
