package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.configuration.WorldConfig;
import com.khorn.terraincontrol.neoforge.NeoForgeWorld;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of 26.3's structures a TerrainControl world has, as 1.12's TerrainControl decided it.
 *
 * <p>A world has 1.12's structures only (villages, mineshafts, strongholds, temples, swamp huts, igloos, ocean monuments, mansions,
 * nether fortresses), each as its WorldConfig enables them; 26.3's newer ones (trial chambers, ruined portals...) do not come into
 * TerrainControl's worlds. Where one may start is the biome config's say (VillageType, MineshaftType, RareBuildingType), not 26.3's
 * biome tags, which TerrainControl's own biomes are not in (strongholds where StrongholdsEnabled, mineshafts by MineshaftRarity);
 * ocean monuments, mansions and fortresses follow 26.3's biome tags, as 1.12 followed its vanilla biomes for them.</p>
 *
 * <p>How far apart they are is the WorldConfig's (VillageDistance, Minimum/MaximumDistanceBetweenRareBuildings and ...Mansions,
 * OceanMonumentGridSize and RandomOffset, StrongholdDistance, Count and Spread), spaced as 1.12 spaced them: {@link #spaced}.</p>
 */
public final class TCStructures
{
    /** The villages as big as each world has them, by the server's registries (one set of them a server), then world and village */
    private static final Map<HolderLookup.Provider, Map<String, Structure>> SIZED = new WeakHashMap<>();

    private static String name(final ResourceKey<Structure> structure)
    {
        return structure.identifier().getNamespace().equals("minecraft") ? structure.identifier().getPath() : "";
    }

    /**
     * Gets whether a world has a structure at all.
     *
     * @param world     The world.
     * @param structure The structure.
     * @return Whether its WorldConfig enables it.
     */
    public static boolean inWorld(final NeoForgeWorld world, final ResourceKey<Structure> structure)
    {
        final WorldConfig config = world.getConfigs().getWorldConfig();
        return switch (name(structure))
        {
            case "village_plains", "village_desert", "village_taiga", "village_savanna" -> config.villagesEnabled;
            case "desert_pyramid", "jungle_pyramid", "swamp_hut", "igloo" -> config.rareBuildingsEnabled;
            case "mineshaft", "mineshaft_mesa" -> config.mineshaftsEnabled;
            case "stronghold" -> config.strongholdsEnabled;
            case "monument" -> config.oceanMonumentsEnabled;
            case "mansion" -> config.mansionsEnabled;
            case "fortress" -> config.netherFortressesEnabled;
            default -> false;
        };
    }

    /**
     * Gets whether a structure may start in a biome of a world.
     *
     * @param world     The world.
     * @param structure The structure.
     * @param biome     The 26.3 biome where it would start.
     * @return Whether it may.
     */
    public static boolean inBiome(final NeoForgeWorld world, final Holder<Structure> structure, final Holder<Biome> biome)
    {
        final String name = structure.unwrapKey().map(TCStructures::name).orElse("");
        final BiomeConfig config = world.getBiomeConfig(biome);
        if (config == null)
        {
            return false;
        }
        return switch (name)
        {
            case "village_plains" -> config.villageType == BiomeConfig.VillageType.wood;
            case "village_desert" -> config.villageType == BiomeConfig.VillageType.sandstone;
            case "village_taiga" -> config.villageType == BiomeConfig.VillageType.taiga;
            case "village_savanna" -> config.villageType == BiomeConfig.VillageType.savanna;
            case "desert_pyramid" -> config.rareBuildingType == BiomeConfig.RareBuildingType.desertPyramid;
            case "jungle_pyramid" -> config.rareBuildingType == BiomeConfig.RareBuildingType.jungleTemple;
            case "swamp_hut" -> config.rareBuildingType == BiomeConfig.RareBuildingType.swampHut;
            case "igloo" -> config.rareBuildingType == BiomeConfig.RareBuildingType.igloo;
            case "mineshaft" -> config.mineshaftType == BiomeConfig.MineshaftType.normal;
            case "mineshaft_mesa" -> config.mineshaftType == BiomeConfig.MineshaftType.mesa;
            case "stronghold" -> config.strongholdsEnabled;
            case "monument", "mansion", "fortress" -> structure.value().biomes().contains(biome);
            default -> false;
        };
    }

    /**
     * Gets a structure set as a world spaces it: 26.3's placement, with the world's spacing from its WorldConfig. 1.12 spread its
     * structures as 26.3 does (a grid, a least separation, a random offset), so the settings carry over as they are.
     *
     * @param world The world.
     * @param set   26.3's structure set.
     * @return The set as the world spaces it; the set itself when TerrainControl has no spacing for it.
     */
    public static Holder<StructureSet> spaced(final NeoForgeWorld world, final Holder<StructureSet> set)
    {
        final WorldConfig config = world.getConfigs().getWorldConfig();
        final String name = set.value().structures().stream().findFirst()
                .flatMap(entry -> entry.structure().unwrapKey()).map(TCStructures::name).orElse("");
        final StructurePlacement placement = set.value().placement();
        final StructurePlacement spaced;
        if (placement instanceof final RandomSpreadStructurePlacement spread)
        {
            spaced = switch (name)
            {
                // 1.12: a grid of VillageDistance chunks, each village at least 8 in
                case "village_plains", "village_desert", "village_taiga", "village_savanna", "village_snowy" ->
                        spread(spread, config.villageDistance, 8, RandomSpreadType.LINEAR, spread.frequency());
                // 1.12 took one off the minimum distance, as its own scattered features did
                case "desert_pyramid", "jungle_pyramid", "swamp_hut", "igloo" -> spread(spread, config.maximumDistanceBetweenRareBuildings,
                        config.minimumDistanceBetweenRareBuildings - 1, RandomSpreadType.LINEAR, spread.frequency());
                case "mansion" -> spread(spread, config.maximumDistanceBetweenMansions, config.minimumDistanceBetweenMansions,
                        RandomSpreadType.TRIANGULAR, spread.frequency());
                // 1.12: (nextInt(offset + 1) + nextInt(offset + 1)) / 2 into a grid of GridSize, as 26.3's triangular spread with
                // spacing - separation = offset + 1
                case "monument" -> spread(spread, config.oceanMonumentGridSize,
                        config.oceanMonumentGridSize - config.oceanMonumentRandomOffset - 1, RandomSpreadType.TRIANGULAR, spread.frequency());
                // Every chunk may roll for one: as often as the world's most mineshaft-rich biome has them (MineshaftRarity is a
                // percent a chunk); each biome then has its own share (see mineshaftRoll)
                case "mineshaft", "mineshaft_mesa" -> spread(spread, spread.spacing(), spread.separation(), spread.spreadType(),
                        (float) (world.getMaxMineshaftRarity() / 100.0));
                default -> placement;
            };
        } else if (placement instanceof final ConcentricRingsStructurePlacement rings && name.equals("stronghold"))
        {
            spaced = new ConcentricRingsStructurePlacement(rings.locateOffset(), rings.frequencyReductionMethod(), rings.frequency(),
                    rings.salt(), rings.exclusionZone(), Math.max(1, (int) Math.round(config.strongholdDistance)),
                    Math.max(1, config.strongholdSpread), Math.max(1, config.strongholdCount), rings.preferredBiomes());
        } else
        {
            spaced = placement;
        }
        return spaced == placement ? set : Holder.direct(new StructureSet(set.value().structures(), spaced));
    }

    private static RandomSpreadStructurePlacement spread(final RandomSpreadStructurePlacement vanilla, final int spacing,
            final int separation, final RandomSpreadType type, final float frequency)
    {
        final int grid = Math.max(1, spacing);
        return new RandomSpreadStructurePlacement(vanilla.locateOffset(), vanilla.frequencyReductionMethod(), frequency, vanilla.salt(),
                vanilla.exclusionZone(), grid, Math.max(0, Math.min(grid - 1, separation)), type);
    }

    /**
     * Gets whether a chunk's mineshaft may start in a biome: the chunk rolls once (the same each time), and a biome takes it when
     * the roll is under its MineshaftRarity, as 1.12 did, out of the world's highest (which the placement already rolled for).
     *
     * @param world   The world.
     * @param seed    The level's seed.
     * @param chunk   The chunk.
     * @param biome   The biome's config.
     * @return Whether it may.
     */
    public static boolean mineshaftRoll(final NeoForgeWorld world, final long seed, final ChunkPos chunk, final BiomeConfig biome)
    {
        final WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed ^ 0x6D696E6573686166L, chunk.x(), chunk.z());
        return random.nextDouble() * world.getMaxMineshaftRarity() < biome.mineshaftsRarity;
    }

    /**
     * Gets whether a structure is one of the mineshafts.
     *
     * @param structure The structure.
     * @return Whether it is.
     */
    public static boolean isMineshaft(final Holder<Structure> structure)
    {
        final String name = structure.unwrapKey().map(TCStructures::name).orElse("");
        return name.equals("mineshaft") || name.equals("mineshaft_mesa");
    }

    /**
     * Gets a structure as big as a world has it: for a village, 26.3's village laid out as far as the world's VillageSize takes it.
     *
     * <p>1.12's VillageSize put more of each house into a village (each kind's count up by the size, up to twice the size), so the
     * village reached further. 26.3 lays a village out along its streets, as many pieces deep as its size (6); each VillageSize
     * adds two more pieces of depth, as far out as 12 more blocks (up to 26.3's limit of 128 with the ground's edge), and so more
     * streets and houses. The copy is only for laying the village out: it is saved as 26.3's own village (see createStructures).</p>
     *
     * @param world     The world.
     * @param structure The structure.
     * @param registries The server's registries, to copy the village with.
     * @return The structure as big as the world has it; the structure itself when the world has it as 26.3 does.
     */
    public static Structure sized(final NeoForgeWorld world, final Holder<Structure> structure, final HolderLookup.Provider registries)
    {
        final int size = world.getConfigs().getWorldConfig().villageSize;
        final String name = structure.unwrapKey().map(TCStructures::name).orElse("");
        if (size <= 0 || !name.startsWith("village_") || !(structure.value() instanceof JigsawStructure))
        {
            return structure.value();
        }
        final Map<String, Structure> sized;
        synchronized (SIZED)
        {
            sized = SIZED.computeIfAbsent(registries, r -> new ConcurrentHashMap<>());
        }
        return sized.computeIfAbsent(world.getName() + "/" + name, key -> resize(structure.value(), size, registries));
    }

    /**
     * Gets a copy of a village, deeper and reaching further by a VillageSize.
     *
     * @param village    The village.
     * @param size       The VillageSize, 1 or more.
     * @param registries The server's registries.
     * @return The copy; the village itself when it could not be copied.
     */
    static Structure resize(final Structure village, final int size, final HolderLookup.Provider registries)
    {
        final RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        final JsonElement encoded = Structure.DIRECT_CODEC.encodeStart(ops, village).result().orElse(null);
        if (!(encoded instanceof final JsonObject json))
        {
            return village;
        }
        final int edge = village.terrainAdaptation() == TerrainAdjustment.NONE ? 0 : 12;
        json.addProperty("size", Math.min(JigsawStructure.MAX_DEPTH, depth(json) + 2 * size));
        final JsonElement distance = json.get("max_distance_from_center");
        final int horizontal = distance == null ? 80
                : distance.isJsonObject() ? distance.getAsJsonObject().get("horizontal").getAsInt() : distance.getAsInt();
        final int reach = Math.min(JigsawStructure.MAX_TOTAL_STRUCTURE_RANGE - edge, horizontal + 12 * size);
        if (distance != null && distance.isJsonObject())
        {
            distance.getAsJsonObject().addProperty("horizontal", reach);
        } else
        {
            json.addProperty("max_distance_from_center", reach);
        }
        return Structure.DIRECT_CODEC.parse(ops, json).result().orElse(village);
    }

    private static int depth(final JsonObject json)
    {
        return json.has("size") ? json.get("size").getAsInt() : 6;
    }

    private TCStructures()
    {
    }
}
