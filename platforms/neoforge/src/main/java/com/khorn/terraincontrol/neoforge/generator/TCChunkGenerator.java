package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.neoforge.NeoForgeWorld;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Util;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TerrainControl world's chunks for 26.3. A dimension uses it as its generator:
 * {@code "generator": {"type": "terraincontrol:terraincontrol", "world": "Keystone"}}, where "world" names the folder in
 * config/TerrainControl/worlds.
 *
 * <p>26.3's terrain step is TerrainControl's chunk generation (terrain, surface, caves and canyons, all in one as on 1.12). Its
 * features step is TerrainControl's population (resources, custom objects, trees), in place of 26.3's own features (they would
 * plant the trees and ores of the vanilla biomes the chunks are saved as).</p>
 */
public final class TCChunkGenerator extends ChunkGenerator
{
    public static final MapCodec<TCChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("world").forGetter(generator -> generator.source.worldName()),
            RegistryOps.<Biome, TCChunkGenerator>retrieveGetter(Registries.BIOME)
    ).apply(i, i.stable(TCChunkGenerator::new)));

    /** Chunks generated only to answer height questions (structures, spawning), kept for a while */
    private static final int COLUMN_CACHE_SIZE = 256;

    private final TCBiomeSource source;
    /**
     * The TerrainControl world of each level using this generator, by the level's RandomState. Several levels can share one generator
     * (a Sponge world made from this dimension, beside the dimension's own), each with its own seed: each gets its own TerrainControl
     * world, as TerrainControl's generators hold the seed.
     */
    private final Map<RandomState, NeoForgeWorld> levels = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<NeoForgeWorld, Map<Long, BlockState[]>> columnChunks = Collections.synchronizedMap(new WeakHashMap<>());

    private TCChunkGenerator(final String worldName, final HolderGetter<Biome> biomes)
    {
        this(new TCBiomeSource(worldName, biomes));
    }

    private TCChunkGenerator(final TCBiomeSource source)
    {
        super(source);
        this.source = source;
    }

    /** The world's configs, for what does not depend on the level (sea level, heights) */
    private NeoForgeWorld world()
    {
        return this.source.world();
    }

    /**
     * Gets the TerrainControl world of a level.
     *
     * @param randomState The level's RandomState.
     * @return Its TerrainControl world.
     */
    /**
     * Gets the TerrainControl world of a level, when TerrainControl generates it.
     *
     * @param level The level.
     * @return Its TerrainControl world; empty for a level TerrainControl does not generate (or has not yet).
     */
    public static Optional<NeoForgeWorld> worldFor(final ServerLevel level)
    {
        if (level.getChunkSource().getGenerator() instanceof final TCChunkGenerator generator)
        {
            return Optional.ofNullable(generator.levels.get(level.getChunkSource().randomState()));
        }
        return Optional.empty();
    }

    NeoForgeWorld world(final RandomState randomState)
    {
        final NeoForgeWorld world = this.levels.get(randomState);
        if (world == null)
        {
            throw new IllegalStateException("No TerrainControl world for a level of \"" + this.source.worldName() + "\" yet");
        }
        return world;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec()
    {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(final HolderLookup<StructureSet> structureSets, final RandomState randomState,
            final long legacyLevelSeed)
    {
        // Made when a level is: its own TerrainControl world, with the level's seed
        final NeoForgeWorld world = this.source.createLevelWorld();
        world.initialize(legacyLevelSeed);
        this.levels.put(randomState, world);
        this.source.bind(randomState, world);
        // The structure sets with a structure this world has (26.3's own states would drop those none of whose biomes are in 26.3's
        // biome tags, as TerrainControl's own biomes are not)
        final List<Holder<StructureSet>> sets = structureSets.listElements()
                .filter(set -> set.value().structures().stream()
                        .anyMatch(entry -> entry.structure().unwrapKey().map(key -> TCStructures.inWorld(world, key)).orElse(false)))
                .map(set -> TCStructures.spaced(world, (Holder<StructureSet>) set))
                .toList();
        return new ChunkGeneratorStructureState(randomState, this.source, legacyLevelSeed, getOrigin(randomState), legacyLevelSeed, sets);
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(final RandomState randomState, final Blender blender,
            final StructureManager structureManager, final ChunkAccess protoChunk)
    {
        // The level's own biomes (26.3 would hand the biome source a climate sampler, which does not say which level)
        final BiomeResolver resolver = this.source.resolverFor(randomState);
        return CompletableFuture.supplyAsync(() -> {
            protoChunk.fillBiomesFromNoise(resolver);
            return protoChunk;
        }, Util.backgroundExecutor().forName("createBiomes"));
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(final ChunkAccess chunk, final Blender blender, final RandomState randomState,
            final StructureManager structureManager, final BiomeManager biomeManager, @Nullable final WorldGenRegion carverBiomeRegion,
            final Set<Holder<Biome>> possibleBiomes)
    {
        // On 26.3's background threads, as 26.3's own terrain: each thread has its own terrain generator (NeoForgeWorld), and the
        // same chunk comes out on any of them. (Done here, it would run on 26.3's one worldgen thread, a chunk at a time.)
        final NeoForgeWorld world = world(randomState);
        return CompletableFuture.supplyAsync(() -> {
            final List<LevelChunkSection> sections = new ArrayList<>();
            for (final LevelChunkSection section : chunk.getSections())
            {
                section.acquire();
                sections.add(section);
            }
            try
            {
                world.getTerrainGenerator().generate(new ChunkAccessBuffer(chunk));
            } finally
            {
                for (final LevelChunkSection section : sections)
                {
                    section.release();
                }
            }
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
            return chunk;
        }, Util.backgroundExecutor().forName("buildTerrain"));
    }

    @Override
    public void createStructures(final RegistryAccess registryAccess, final ChunkGeneratorStructureState state,
            final StructureManager structureManager, final ChunkAccess centerChunk, final StructureTemplateManager structureTemplateManager,
            final ResourceKey<Level> level)
    {
        // As 26.3 does, but asking TerrainControl's biome configs where a structure may start
        final NeoForgeWorld world = world(state.randomState());
        final ChunkPos sourceChunkPos = centerChunk.getPos();
        this.source.forLevel(state.randomState(), () -> {
            final Climate.Sampler climateSampler = state.randomState().createClimateSampler(SamplerContext.builder().enableCaches().build());
            // The ground of the chunk, for the newer structures (TCStructures.Ground): measured once, when one first asks
            final TCStructures.Ground[] ground = new TCStructures.Ground[1];
            final java.util.function.Supplier<TCStructures.Ground> groundHere = () -> {
                if (ground[0] == null)
                {
                    ground[0] = ground(world, sourceChunkPos, centerChunk, state.randomState());
                }
                return ground[0];
            };
            for (final Holder<StructureSet> set : state.possibleStructureSets())
            {
                final StructurePlacement placement = set.value().placement();
                final List<StructureSet.StructureSelectionEntry> structures = set.value().structures();
                boolean started = false;
                for (final StructureSet.StructureSelectionEntry structure : structures)
                {
                    final StructureStart existing = structureManager.getStartForStructure(structure.structure().value(), centerChunk);
                    if (existing != null && existing.isValid())
                    {
                        started = true;
                        break;
                    }
                }
                if (started || !placement.isStructureChunk(state, sourceChunkPos.x(), sourceChunkPos.z()))
                {
                    continue;
                }
                final List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(structures);
                options.removeIf(option -> !option.structure().unwrapKey().map(key -> TCStructures.inWorld(world, key)).orElse(false));
                final WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
                random.setLargeFeatureSeed(state.getLevelSeed(), sourceChunkPos.x(), sourceChunkPos.z());
                int total = options.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
                while (!options.isEmpty())
                {
                    int choice = options.size() == 1 ? 0 : random.nextInt(total);
                    int index = 0;
                    for (final StructureSet.StructureSelectionEntry option : options)
                    {
                        choice -= option.weight();
                        if (choice < 0)
                        {
                            break;
                        }
                        index++;
                    }
                    index = Math.min(index, options.size() - 1);
                    final StructureSet.StructureSelectionEntry selected = options.get(index);
                    final Structure structure = selected.structure().value();
                    final StructureStart previous = structureManager.getStartForStructure(structure, centerChunk);
                    // A village as big as the world's VillageSize: laid out by a bigger copy, then saved as the village it is
                    final Structure sized = TCStructures.sized(world, selected.structure(), registryAccess);
                    final StructureStart laidOut = sized.generate(selected.structure(), level, registryAccess, this, this.source,
                            climateSampler, state.randomState(), structureTemplateManager, state.getLevelSeed(), sourceChunkPos,
                            previous != null ? previous.getReferences() : 0, centerChunk,
                            biome -> TCStructures.inBiome(world, selected.structure(), biome, groundHere) && (!TCStructures.isMineshaft(selected.structure())
                                    || TCStructures.mineshaftRoll(world, state.getLevelSeed(), sourceChunkPos, world.getBiomeConfig(biome))));
                    final StructureStart start = sized == structure || !laidOut.isValid() ? laidOut
                            : new StructureStart(structure, laidOut.getChunkPos(), laidOut.getReferences(), new PiecesContainer(laidOut.getPieces()));
                    if (start.isValid())
                    {
                        structureManager.setStartForStructure(structure, start, centerChunk);
                        break;
                    }
                    options.remove(index);
                    total -= selected.weight();
                }
            }
        });
    }

    /**
     * Gets the ground in the middle of a chunk, from TerrainControl's terrain there (made for the asking, as for heights).
     *
     * @param world          The level's TerrainControl world.
     * @param chunk          The chunk.
     * @param heightAccessor The level's heights.
     * @param randomState    The level's RandomState.
     * @return The ground.
     */
    private TCStructures.Ground ground(final NeoForgeWorld world, final ChunkPos chunk, final LevelHeightAccessor heightAccessor,
            final RandomState randomState)
    {
        final int x = chunk.getMiddleBlockX();
        final int z = chunk.getMiddleBlockZ();
        final int surface = getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState);
        final int floor = getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);
        final BlockState top = getBaseColumn(x, z, heightAccessor, randomState).getBlock(floor - 1);
        final boolean sandy = top.is(Blocks.SAND) || top.is(Blocks.RED_SAND) || top.is(Blocks.GRAVEL);
        return new TCStructures.Ground(surface, floor, world.getConfigs().getWorldConfig().waterLevelMax, sandy);
    }

    /**
     * Places the pieces of the structures that reach into a chunk, as 26.3's features step does, without 26.3's features.
     */
    private void placeStructures(final WorldGenLevel level, final ChunkAccess chunk, final StructureManager structureManager)
    {
        if (!structureManager.shouldGenerateStructures())
        {
            return;
        }
        final ChunkPos centerPos = chunk.getPos();
        final SectionPos sectionPos = SectionPos.of(centerPos, level.getMinSectionY());
        final BlockPos origin = sectionPos.origin();
        final Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        final Map<Integer, List<Structure>> byStep = structures.stream().collect(Collectors.groupingBy(structure -> structure.step().ordinal()));
        final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        final long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        final LevelHeightAccessor heights = chunk.getHeightAccessorForGeneration();
        final BoundingBox writable = new BoundingBox(centerPos.getMinBlockX(), heights.getMinY() + 1, centerPos.getMinBlockZ(),
                centerPos.getMinBlockX() + 15, heights.getMaxY(), centerPos.getMinBlockZ() + 15);
        for (int step = 0; step < GenerationStep.Decoration.values().length; step++)
        {
            int index = 0;
            for (final Structure structure : byStep.getOrDefault(step, List.of()))
            {
                random.setFeatureSeed(decorationSeed, index, step);
                structureManager.startsForStructure(sectionPos.x(), sectionPos.z(), structure)
                        .forEach(start -> start.placeInChunk(level, structureManager, this, random, writable, centerPos));
                index++;
            }
        }
    }

    @Override
    public void applyBiomeDecoration(final WorldGenLevel level, final ChunkAccess chunk, final StructureManager structureManager)
    {
        // 1.12's order: the structures, then TerrainControl's population (instead of the features of the biomes the chunks are saved as)
        placeStructures(level, chunk, structureManager);
        final NeoForgeWorld world = world(level.getLevel().getChunkSource().randomState());
        world.lock().lock();
        try
        {
            world.populate(new RegionPopulationLevel(level, this), chunk.getPos());
        } finally
        {
            world.lock().unlock();
        }
        // Then what other mods add to the biomes (Almura's fruit trees, grass and ores), as they generated after TerrainControl's
        // population on 1.12
        placeModFeatures(level, chunk);
    }

    /**
     * Places the features other mods add to the chunk's biomes (by NeoForge biome modifiers), as 26.3's features step places a
     * biome's features: 26.3's own (the minecraft namespace) are left out, as TerrainControl's population places its worlds' trees,
     * ores and plants itself.
     */
    private void placeModFeatures(final WorldGenLevel level, final ChunkAccess chunk)
    {
        final ChunkPos centerPos = chunk.getPos();
        final SectionPos sectionPos = SectionPos.of(centerPos, level.getMinSectionY());
        final BlockPos origin = sectionPos.origin();
        // The biomes around, as 26.3 gathers them: their features check their biome where they place
        final Set<Holder<Biome>> biomes = new LinkedHashSet<>();
        ChunkPos.rangeClosed(centerPos, 1).forEach(pos -> {
            for (final LevelChunkSection section : level.getChunk(pos.x(), pos.z()).getSections())
            {
                section.getBiomes().getAll(biomes::add);
            }
        });
        final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        final long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        final FeaturePlacer placer = new FeaturePlacer(level, this);
        for (int step = 0; step < GenerationStep.Decoration.values().length; step++)
        {
            final Map<String, PlacedFeature> features = new TreeMap<>();
            for (final Holder<Biome> biome : biomes)
            {
                final List<HolderSet<PlacedFeature>> steps = getBiomeGenerationSettings(biome).features();
                if (step < steps.size())
                {
                    for (final Holder<PlacedFeature> feature : steps.get(step))
                    {
                        feature.unwrapKey().filter(key -> !key.identifier().getNamespace().equals("minecraft"))
                                .ifPresent(key -> features.put(key.identifier().toString(), feature.value()));
                    }
                }
            }
            int index = 0;
            for (final Map.Entry<String, PlacedFeature> feature : features.entrySet())
            {
                random.setFeatureSeed(decorationSeed, index++, step);
                level.setCurrentlyGenerating(feature::getKey);
                placer.placeWithBiomeCheck(feature.getValue(), random, origin);
            }
        }
        level.setCurrentlyGenerating(null);
    }

    @Override
    public void spawnOriginalMobs(final WorldGenRegion worldGenRegion)
    {
        // TerrainControl's population mobs come with population
    }

    /**
     * Gets the blocks of the chunk around a column, generating it on its own when it is not at hand.
     *
     * @param x The column's x.
     * @param z The column's z.
     * @param heightAccessor The level's heights.
     * @return The chunk's blocks, by (y - min y) * 256 + z * 16 + x.
     */
    private BlockState[] columnChunk(final int x, final int z, final LevelHeightAccessor heightAccessor, final RandomState randomState)
    {
        final NeoForgeWorld world = world(randomState);
        final Map<Long, BlockState[]> columnChunks = this.columnChunks.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        final long key = (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
        BlockState[] blocks = columnChunks.get(key);
        if (blocks != null)
        {
            return blocks;
        }
        final ArrayChunkBuffer buffer = new ArrayChunkBuffer(chunkX, chunkZ, heightAccessor.getMinY(), heightAccessor.getHeight());
        // This thread's own terrain generator (NeoForgeWorld)
        world.getTerrainGenerator().generate(buffer);
        blocks = buffer.blocks();
        if (columnChunks.size() >= COLUMN_CACHE_SIZE)
        {
            columnChunks.clear();
        }
        columnChunks.put(key, blocks);
        return blocks;
    }

    @Override
    public int getBaseHeight(final int x, final int z, final Heightmap.Types type, final LevelHeightAccessor heightAccessor,
            final RandomState randomState)
    {
        final BlockState[] blocks = columnChunk(x, z, heightAccessor, randomState);
        for (int y = heightAccessor.getMaxY(); y >= heightAccessor.getMinY(); y--)
        {
            if (type.isOpaque().test(blocks[(y - heightAccessor.getMinY()) * 256 + (z & 15) * 16 + (x & 15)]))
            {
                return y + 1;
            }
        }
        return heightAccessor.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(final int x, final int z, final LevelHeightAccessor heightAccessor, final RandomState randomState)
    {
        final BlockState[] blocks = columnChunk(x, z, heightAccessor, randomState);
        final BlockState[] column = new BlockState[heightAccessor.getHeight()];
        for (int i = 0; i < column.length; i++)
        {
            final BlockState state = blocks[i * 256 + (z & 15) * 16 + (x & 15)];
            column[i] = state == null ? Blocks.AIR.defaultBlockState() : state;
        }
        return new NoiseColumn(heightAccessor.getMinY(), column);
    }

    @Override
    public int getGenDepth()
    {
        return world().getHeightCap();
    }

    @Override
    public int getSeaLevel()
    {
        return world().getConfigs().getWorldConfig().waterLevelMax;
    }

    @Override
    public int getMinY()
    {
        return 0;
    }

    @Override
    public void addDebugScreenInfo(final List<String> result, final RandomState randomState, final BlockPos feetPos,
            final SamplerContext samplerContext)
    {
        final NeoForgeWorld world = this.levels.get(randomState);
        if (world != null)
        {
            final LocalBiome biome = world.getCalculatedBiome(feetPos.getX(), feetPos.getZ());
            result.add("TerrainControl " + world.getName() + ": " + biome.getName());
        }
    }
}
