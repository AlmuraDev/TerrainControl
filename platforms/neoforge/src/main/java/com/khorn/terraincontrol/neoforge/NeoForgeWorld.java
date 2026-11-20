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
import com.khorn.terraincontrol.neoforge.api.TerrainControlBiomes;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import com.khorn.terraincontrol.util.NamedBinaryTag;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultBiome;
import com.khorn.terraincontrol.util.minecraftTypes.TreeType;
import com.khorn.terraincontrol.configuration.ReplacedBlocksMatrix;
import com.khorn.terraincontrol.generator.ObjectSpawner;
import com.khorn.terraincontrol.neoforge.util.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.worldgen.features.CaveFeatures;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.FluidState;
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
 * <p>TerrainControl's generators keep their working state in fields, and 26.3 generates chunks on several threads at once, so
 * each thread has a biome generator and a terrain generator of its own ({@link #getBiomeGenerator()},
 * {@link #getTerrainGenerator()}): the same seed and configs make the same terrain and biomes on any thread, as they reseed for
 * every chunk. Population (and a sapling growing into a custom tree) writes into the level and keeps the custom objects' state,
 * so it runs under {@link #lock()}, one chunk at a time, as 26.3 runs its own features step.</p>
 *
 * <p>Population runs in 26.3's features step ({@link #populate(PopulationLevel, ChunkPos)}): the blocks TerrainControl
 * reads and writes are those of that step's level, within the chunks 1.12 let it write to.</p>
 */
public final class NeoForgeWorld implements LocalWorld
{
    private final String name;
    private final HolderGetter<Biome> biomes;
    private final ServerConfigProvider settings;
    private final Map<String, LocalBiome> biomeNames = new ConcurrentHashMap<>();
    /** The config of each 26.3 biome this world saves chunks as */
    private final Map<ResourceKey<Biome>, BiomeConfig> savedConfigs = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private int nextBiomeId = DefaultBiome.values().length;

    private volatile boolean initialized;
    private long seed;
    /**
     * Each thread's generators, made on the thread's first use. A map of the world's own, not a ThreadLocal: the generators refer
     * to the world, so a ThreadLocal's would keep an unloaded world in every thread for good.
     */
    private final Map<Thread, Generators> generators = new ConcurrentHashMap<>();
    private CustomObjectStructureCache structureCache;
    private ObjectSpawner spawner;

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
            this.structureCache = new CustomObjectStructureCache(this);
            this.spawner = new ObjectSpawner(this.settings, this);
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

    /** A thread's generators: the biome generator first, as the terrain generator asks the world for it */
    private final class Generators
    {
        final BiomeGenerator biomes;
        ChunkProviderTC terrain;

        Generators()
        {
            this.biomes = TerrainControl.getBiomeModeManager().createCached(NeoForgeWorld.this.settings.getWorldConfig().biomeMode,
                    NeoForgeWorld.this);
        }

        ChunkProviderTC terrain()
        {
            if (this.terrain == null)
            {
                this.terrain = new ChunkProviderTC(NeoForgeWorld.this.settings, NeoForgeWorld.this);
            }
            return this.terrain;
        }
    }

    /** This thread's generators; null before {@link #initialize(long)} */
    private Generators generators()
    {
        if (!this.initialized)
        {
            return null;
        }
        final Thread thread = Thread.currentThread();
        Generators generators = this.generators.get(thread);
        if (generators == null)
        {
            // The generators of threads that have ended go
            this.generators.keySet().removeIf(other -> !other.isAlive());
            generators = new Generators();
            this.generators.put(thread, generators);
        }
        return generators;
    }

    /**
     * Gets the lock population runs under (and a sapling growing into a custom tree): one chunk at a time.
     *
     * @return The lock.
     */
    public ReentrantLock lock()
    {
        return this.lock;
    }

    /**
     * Gets this thread's terrain generator. Only after {@link #initialize(long)}.
     *
     * @return The terrain generator.
     */
    public ChunkProviderTC getTerrainGenerator()
    {
        final Generators generators = generators();
        return generators == null ? null : generators.terrain();
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
        final boolean custom = DefaultBiome.getBiome(biomeIds.getGenerationId()) == null;
        Holder<Biome> saved = null;
        if (custom)
        {
            // Its own 26.3 biome, from the biome exporter's datapack
            final ResourceKey<Biome> key = NeoForgeBiome.registryKey(this.name, biomeConfig.getName());
            saved = this.biomes.get(key).map(holder -> (Holder<Biome>) holder).orElse(null);
            if (saved != null)
            {
                // For other mods to show the biome's name (the clients have no translation for it)
                TerrainControlBiomes.register(key, biomeConfig.getName());
            } else
            {
                warnOnce("biome", "World \"{}\" has custom biomes without a 26.3 biome (such as {}): export the biomes to the server's"
                        + " datapack. Until then they are saved as the vanilla biome they replace to, or plains", this.name, key.identifier());
            }
        }
        if (saved == null)
        {
            saved = vanillaBiome(DefaultBiome.getBiome(biomeIds.getSavedId()) != null ? biomeIds.getSavedId() : DefaultBiome.PLAINS.Id);
        }
        final NeoForgeBiome biome = new NeoForgeBiome(biomeConfig, biomeIds, custom, saved);
        // A vanilla biome is TerrainControl's config of it, before any custom biome saved as it for want of its own
        saved.unwrapKey().ifPresent(key -> {
            if (custom)
            {
                this.savedConfigs.putIfAbsent(key, biomeConfig);
            } else
            {
                this.savedConfigs.put(key, biomeConfig);
            }
        });
        this.biomeNames.put(biome.getName(), biome);
        return biome;
    }

    /**
     * Gets the highest MineshaftRarity of this world's biomes that have mineshafts: how often a chunk may roll for one.
     *
     * @return The rarity, a percent; 0 when no biome has mineshafts.
     */
    public double getMaxMineshaftRarity()
    {
        double max = 0;
        for (final LocalBiome biome : this.settings.getBiomeArray())
        {
            if (biome != null && biome.getBiomeConfig().mineshaftType != BiomeConfig.MineshaftType.disabled)
            {
                max = Math.max(max, biome.getBiomeConfig().mineshaftsRarity);
            }
        }
        return max;
    }

    /**
     * Gets the config of a 26.3 biome this world saves chunks as.
     *
     * @param biome The biome.
     * @return Its config; null when this world saves no chunks as it.
     */
    public BiomeConfig getBiomeConfig(final Holder<Biome> biome)
    {
        return biome.unwrapKey().map(this.savedConfigs::get).orElse(null);
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
        // This thread's own (see Generators)
        final Generators generators = generators();
        return generators == null ? null : generators.biomes;
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
        return getBiomeById(getBiomeGenerator().getBiome(x, z));
    }

    // ----- Population: TerrainControl's ObjectSpawner, in 26.3's features step -----

    /**
     * Where population is writing: the level of 26.3's features step, and the chunks TerrainControl may write to. As on 1.12, a
     * chunk's population covers the 2 by 2 chunks from it (8 blocks in, so objects can reach over), which 26.3 lets it write to.
     */
    private record Population(PopulationLevel level, int chunkX, int chunkZ, boolean bounded)
    {
        boolean contains(final int x, final int z)
        {
            if (!this.bounded)
            {
                // A live level: it keeps to its loaded chunks itself
                return true;
            }
            final int dx = (x >> 4) - this.chunkX;
            final int dz = (z >> 4) - this.chunkZ;
            return dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
        }
    }

    private Population population;
    /** For Almura's wild plants (AlmuraPlants), unseeded as on 1.12 */
    private final Random plantRandom = new Random();

    /**
     * Populates a chunk: TerrainControl's resources, custom objects, trees and the rest. Only under {@link #lock()}.
     *
     * @param level    The level it populates.
     * @param chunkPos The chunk.
     */
    public void populate(final PopulationLevel level, final ChunkPos chunkPos)
    {
        // As on 1.12: the chunk's population covers the 2 by 2 chunks from it, 7 blocks in. 26.3 lets the features step reach a
        // chunk around the one it decorates; TerrainControl's own blocks stay within the 2 by 2, and 26.3's features it plants
        // (trees, dungeons) are kept within 26.3's reach by RegionPopulationLevel.
        final int chunkX = chunkPos.x();
        final int chunkZ = chunkPos.z();
        this.population = new Population(level, chunkX, chunkZ, true);
        try
        {
            this.spawner.populate(ChunkCoordinate.fromChunkCoords(chunkX, chunkZ));
        } finally
        {
            this.population = null;
        }
    }

    /**
     * Lets TerrainControl place in a live level, as when a sapling grows into one of a biome's custom trees: what it places is not
     * held to a chunk, only to the level's loaded chunks.
     *
     * @param level The level.
     * @param task  What to place; whether it was placed.
     * @return What the task returned.
     */
    public boolean placeLive(final PopulationLevel level, final java.util.function.BooleanSupplier task)
    {
        this.lock.lock();
        final Population before = this.population;
        this.population = new Population(level, 0, 0, false);
        try
        {
            return task.getAsBoolean();
        } finally
        {
            this.population = before;
            this.lock.unlock();
        }
    }

    private boolean writable(final int x, final int y, final int z)
    {
        return this.population != null && y >= this.population.level().getMinY() && y <= this.population.level().getMaxY()
                && this.population.contains(x, z);
    }

    @Override
    public void prepareDefaultStructures(final int chunkX, final int chunkZ, final boolean dry)
    {
        // 26.3 places its own structures
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
    public void startPopulation(final ChunkCoordinate chunkCoord)
    {
    }

    @Override
    public void endPopulation()
    {
    }

    /**
     * Places one of 26.3's features.
     *
     * @param key  The feature.
     * @param rand TerrainControl's random.
     * @param pos  Where.
     * @return Whether it was placed.
     */
    private boolean placeFeature(final ResourceKey<Feature> key, final Random rand, final BlockPos pos)
    {
        if (this.population == null || !this.population.contains(pos.getX(), pos.getZ()))
        {
            return false;
        }
        return this.population.level().placeFeature(key, RandomSource.create(rand.nextLong()), pos);
    }

    @Override
    public void placeDungeon(final Random rand, final int x, final int y, final int z)
    {
        placeFeature(CaveFeatures.MONSTER_ROOM, rand, new BlockPos(x, y, z));
    }

    @Override
    public void placeFossil(final Random rand, final ChunkCoordinate chunkCoord)
    {
        // Under the surface, as 1.12's were
        final int x = chunkCoord.getBlockXCenter();
        final int z = chunkCoord.getBlockZCenter();
        placeFeature(CaveFeatures.FOSSIL_COAL, rand, new BlockPos(x, Math.max(1, getHighestBlockYAt(x, z) - 15 - rand.nextInt(10)), z));
    }

    @Override
    public boolean placeTree(final TreeType type, final Random rand, final int x, final int y, final int z)
    {
        // 1.12's trees, as 26.3 has them
        final ResourceKey<Feature> tree = switch (type)
        {
            case Tree -> TreeFeatures.OAK;
            case BigTree -> TreeFeatures.FANCY_OAK;
            case Forest, Birch -> TreeFeatures.BIRCH;
            case TallBirch -> TreeFeatures.SUPER_BIRCH_BEES_0002;
            case HugeMushroom -> rand.nextBoolean() ? TreeFeatures.HUGE_BROWN_MUSHROOM : TreeFeatures.HUGE_RED_MUSHROOM;
            case HugeRedMushroom -> TreeFeatures.HUGE_RED_MUSHROOM;
            case HugeBrownMushroom -> TreeFeatures.HUGE_BROWN_MUSHROOM;
            case SwampTree -> TreeFeatures.SWAMP_OAK;
            case Taiga1 -> TreeFeatures.PINE;
            case Taiga2 -> TreeFeatures.SPRUCE;
            case HugeTaiga1 -> TreeFeatures.MEGA_PINE;
            case HugeTaiga2 -> TreeFeatures.MEGA_SPRUCE;
            case JungleTree -> TreeFeatures.MEGA_JUNGLE_TREE;
            case GroundBush -> TreeFeatures.JUNGLE_BUSH;
            case CocoaTree -> TreeFeatures.JUNGLE_TREE;
            case Acacia -> TreeFeatures.ACACIA;
            case DarkOak -> TreeFeatures.DARK_OAK;
        };
        return placeFeature(tree, rand, new BlockPos(x, y, z));
    }

    @Override
    public void replaceBlocks(final ChunkCoordinate chunkCoord)
    {
        if (this.population == null || !this.settings.getWorldConfig().BiomeConfigsHaveReplacement)
        {
            return;
        }
        // Each column of the 2 by 2 chunks, as on 1.12
        final PopulationLevel level = this.population.level();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = chunkCoord.getBlockX(); x < chunkCoord.getBlockX() + 32; x++)
        {
            for (int z = chunkCoord.getBlockZ(); z < chunkCoord.getBlockZ() + 32; z++)
            {
                final LocalBiome biome = getBiome(x, z);
                if (biome == null || !biome.getBiomeConfig().replacedBlocks.hasReplaceSettings())
                {
                    continue;
                }
                final ReplacedBlocksMatrix replacements = biome.getBiomeConfig().replacedBlocks;
                final LocalMaterialData[][] table = replacements.compiledInstructions;
                final int highestY = Math.min(replacements.getHighestReplacedY(), level.getMaxY());
                for (int y = Math.max(level.getMinY(), 0); y <= highestY; y++)
                {
                    final BlockState state = level.getBlockState(pos.set(x, y, z));
                    final int blockId = BuiltInRegistries.BLOCK.getId(state.getBlock());
                    if (blockId >= table.length || table[blockId] == null || y >= table[blockId].length)
                    {
                        continue;
                    }
                    final LocalMaterialData replaceTo = table[blockId][y];
                    if (replaceTo != null && replaceTo.getBlockId() != blockId)
                    {
                        level.setBlock(pos.immutable(), ((NeoForgeMaterialData) replaceTo).internalBlock());
                    }
                }
            }
        }
    }

    @Override
    public void placePopulationMobs(final LocalBiome biome, final Random random, final ChunkCoordinate chunkCoord)
    {
        // The spawn lists name 1.12 mobs (Mo' Creatures among them); population mobs come once those have 26.3 mobs
        warnOnce("mobs", "Population mobs are not ported to 26.3 yet");
    }

    // ----- Blocks -----

    @Override
    public LocalMaterialData getMaterial(final int x, final int y, final int z)
    {
        if (!writable(x, y, z))
        {
            return NeoForgeMaterialData.of(Blocks.AIR.defaultBlockState());
        }
        return NeoForgeMaterialData.of(this.population.level().getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public boolean isEmpty(final int x, final int y, final int z)
    {
        return !writable(x, y, z) || this.population.level().getBlockState(new BlockPos(x, y, z)).isAir();
    }

    @Override
    public void setBlock(final int x, final int y, final int z, final LocalMaterialData material)
    {
        setBlock(x, y, z, material, null);
    }

    @Override
    public void setBlock(final int x, final int y, final int z, final LocalMaterialData material, final NamedBinaryTag metaOrNull)
    {
        if (!writable(x, y, z))
        {
            return;
        }
        final BlockPos pos = new BlockPos(x, y, z);
        final PopulationLevel level = this.population.level();
        BlockState state = ((NeoForgeMaterialData) material).internalBlock();
        if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.FERN))
        {
            // Now and then one of Almura's wild plants instead, as Almura's 1.12 TerrainControl did
            state = AlmuraPlants.wild(state, getBiome(x, z).getTemperatureAt(x, y, z), this.plantRandom);
        }
        level.setBlock(pos, state);
        if (metaOrNull != null)
        {
            // A block entity, as a chest with its loot: loaded with the chunk
            final CompoundTag tag = NBTHelper.toCompoundTag(metaOrNull);
            tag.putInt("x", x);
            tag.putInt("y", y);
            tag.putInt("z", z);
            level.setBlockEntity(tag);
        }
    }

    @Override
    public void startFlowing(final int x, final int y, final int z)
    {
        if (!writable(x, y, z))
        {
            return;
        }
        final BlockPos pos = new BlockPos(x, y, z);
        final FluidState fluid = this.population.level().getBlockState(pos).getFluidState();
        if (!fluid.isEmpty())
        {
            // Springs and liquid ores flow once the chunk is loaded
            this.population.level().scheduleFluidTick(pos, fluid.getType());
        }
    }

    @Override
    public NamedBinaryTag getMetadata(final int x, final int y, final int z)
    {
        return null;
    }

    @Override
    public int getLiquidHeight(final int x, final int z)
    {
        for (int y = getHighestBlockYAt(x, z) - 1; y > 0; y--)
        {
            final LocalMaterialData material = getMaterial(x, y, z);
            if (material.isLiquid())
            {
                return y + 1;
            } else if (material.isSolid())
            {
                return -1;
            }
        }
        return -1;
    }

    @Override
    public int getSolidHeight(final int x, final int z)
    {
        for (int y = getHighestBlockYAt(x, z) - 1; y > 0; y--)
        {
            if (getMaterial(x, y, z).isSolid())
            {
                return y + 1;
            }
        }
        return -1;
    }

    @Override
    public int getHighestBlockYAt(final int x, final int z)
    {
        if (!writable(x, 0, z))
        {
            return -1;
        }
        // The y above the highest block that is not air
        return this.population.level().getSurface(x, z);
    }

    @Override
    public int getLightLevel(final int x, final int y, final int z)
    {
        if (!writable(x, y, z))
        {
            return -1;
        }
        // 26.3 lights chunks after their features, so this is the sky light only: full under the open sky, none below
        return y >= getHighestBlockYAt(x, z) ? 15 : 0;
    }

    @Override
    public boolean isLoaded(final int x, final int y, final int z)
    {
        return writable(x, y, z);
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
        // The custom objects' entities name 1.12 mobs; they come with the mobs
        warnOnce("entity", "Custom objects' entities are not ported to 26.3 yet");
    }
}
