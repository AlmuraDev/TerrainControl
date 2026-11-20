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

import com.khorn.terraincontrol.generator.resource.TreeGen;

import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of 26.3's structures a TerrainControl world has, as 1.12's TerrainControl decided it.
 *
 * <p>A world has 1.12's structures (villages, mineshafts, strongholds, temples, swamp huts, igloos, ocean monuments, mansions,
 * nether fortresses) and 26.3's newer overworld ones (pillager outposts, shipwrecks, ocean ruins, buried treasure, ruined portals,
 * trail ruins, abandoned camps, ancient cities, trial chambers, snowy villages), each as its WorldConfig enables them. 26.3's Nether
 * and End ones (bastions, nether fossils, end cities) do not come into TerrainControl's worlds. Where one may start is the biome config's say (VillageType, MineshaftType, RareBuildingType), not 26.3's
 * biome tags, which TerrainControl's own biomes are not in (strongholds where StrongholdsEnabled, mineshafts by MineshaftRarity);
 * ocean monuments, mansions and fortresses follow 26.3's biome tags, as 1.12 followed its vanilla biomes for them.</p>
 *
 * <p>Where a newer one may start is its biome config's say too (PillagerOutposts, ShipwreckType, RuinedPortalType...), and
 * "auto", the default, lets the ground where it would start decide, as 26.3 decides by its own biomes: {@link Ground}. Ancient
 * cities and trial chambers are as high above the world's bottom as in 26.3's overworld ({@link #sized}).</p>
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
        final String name = name(structure);
        if (name.startsWith(CAMP))
        {
            return config.abandonedCampsEnabled;
        }
        return switch (name)
        {
            case "village_plains", "village_desert", "village_taiga", "village_savanna", "village_snowy" -> config.villagesEnabled;
            case "pillager_outpost" -> config.pillagerOutpostsEnabled;
            case "shipwreck", "shipwreck_beached" -> config.shipwrecksEnabled;
            case "ocean_ruin_cold", "ocean_ruin_warm" -> config.oceanRuinsEnabled;
            case "buried_treasure" -> config.buriedTreasureEnabled;
            case "ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp", "ruined_portal_mountain",
                    "ruined_portal_ocean" -> config.ruinedPortalsEnabled;
            case "trail_ruins" -> config.trailRuinsEnabled;
            case "ancient_city" -> config.ancientCitiesEnabled;
            case "trial_chambers" -> config.trialChambersEnabled;
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
        return inBiome(world, structure, biome, () -> null);
    }

    /**
     * Gets whether a structure may start in a biome of a world, on the ground where it would start.
     *
     * @param world     The world.
     * @param structure The structure.
     * @param biome     The 26.3 biome where it would start.
     * @param ground    The ground there, asked for only by the newer structures left to it ("auto"); null when unknown (they do
     *                  not start then).
     * @return Whether it may.
     */
    public static boolean inBiome(final NeoForgeWorld world, final Holder<Structure> structure, final Holder<Biome> biome,
            final Supplier<Ground> ground)
    {
        final String name = structure.unwrapKey().map(TCStructures::name).orElse("");
        final BiomeConfig config = world.getBiomeConfig(biome);
        if (config == null)
        {
            return false;
        }
        if (name.startsWith(CAMP))
        {
            final BiomeConfig.AbandonedCampType camp = camp(config, ground);
            return camp != null && name.equals(CAMP + snake(camp.name()));
        }
        if (name.startsWith("ruined_portal"))
        {
            final BiomeConfig.RuinedPortalType portal = portal(config, ground);
            return portal != null && name.equals(portal == BiomeConfig.RuinedPortalType.standard ? "ruined_portal" : "ruined_portal_" + portal.name());
        }
        return switch (name)
        {
            case "village_snowy" -> config.villageType == BiomeConfig.VillageType.snowy;
            case "pillager_outpost" -> rule(config.pillagerOutposts, ground, g -> g.dry() && !g.beach());
            case "shipwreck" -> config.shipwreckType == BiomeConfig.ShipwreckType.ocean
                    || config.shipwreckType == BiomeConfig.ShipwreckType.auto && is(ground, Ground::sea);
            case "shipwreck_beached" -> config.shipwreckType == BiomeConfig.ShipwreckType.beached
                    || config.shipwreckType == BiomeConfig.ShipwreckType.auto && is(ground, Ground::beach);
            case "ocean_ruin_cold" -> config.oceanRuinType == BiomeConfig.OceanRuinType.cold
                    || config.oceanRuinType == BiomeConfig.OceanRuinType.auto && !warm(config) && is(ground, Ground::sea);
            case "ocean_ruin_warm" -> config.oceanRuinType == BiomeConfig.OceanRuinType.warm
                    || config.oceanRuinType == BiomeConfig.OceanRuinType.auto && warm(config) && is(ground, Ground::sea);
            case "buried_treasure" -> rule(config.buriedTreasure, ground, Ground::beach);
            case "trail_ruins" -> rule(config.trailRuins, ground, g -> g.dry() && forested(config));
            // Under high ground (26.3's are under its mountains), so the city stays in the rock
            case "ancient_city" -> rule(config.ancientCities, ground, g -> g.dry() && g.surface() >= g.seaLevel() + 30);
            // Not under the sea, which would flood them
            case "trial_chambers" -> rule(config.trialChambers, ground, g -> g.floor() >= g.seaLevel() - 8);
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
        final boolean village = size > 0 && name.startsWith("village_");
        final boolean underground = name.equals("ancient_city") || name.equals("trial_chambers");
        if (!village && !underground || !(structure.value() instanceof JigsawStructure))
        {
            return structure.value();
        }
        final Map<String, Structure> sized;
        synchronized (SIZED)
        {
            sized = SIZED.computeIfAbsent(registries, r -> new ConcurrentHashMap<>());
        }
        return sized.computeIfAbsent(world.getName() + "/" + name,
                key -> village ? resize(structure.value(), size, registries) : lower(structure.value(), registries));
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

    /** How far below y 0 26.3's overworld goes, which its underground structures' heights count from */
    private static final int OVERWORLD_MIN_Y = -64;

    /**
     * Gets a copy of an underground structure (an ancient city, trial chambers) as high above the world's bottom as in 26.3's
     * overworld: 26.3 places them at fixed heights below y 0 (an ancient city at -27, 37 above the bottom), and TerrainControl's
     * worlds start at y 0.
     *
     * @param structure  The structure.
     * @param registries The server's registries.
     * @return The copy; the structure itself when it could not be copied.
     */
    static Structure lower(final Structure structure, final HolderLookup.Provider registries)
    {
        final RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        final JsonElement encoded = Structure.DIRECT_CODEC.encodeStart(ops, structure).result().orElse(null);
        if (!(encoded instanceof final JsonObject json) || !json.has("start_height"))
        {
            return structure;
        }
        json.add("start_height", aboveBottom(json.get("start_height")));
        return Structure.DIRECT_CODEC.parse(ops, json).result().orElse(structure);
    }

    /** A height provider with each absolute height as that height above 26.3's overworld bottom */
    private static JsonElement aboveBottom(final JsonElement element)
    {
        if (element instanceof final JsonObject object)
        {
            if (object.size() == 1 && object.has("absolute"))
            {
                final JsonObject anchor = new JsonObject();
                anchor.addProperty("above_bottom", object.get("absolute").getAsInt() - OVERWORLD_MIN_Y);
                return anchor;
            }
            final JsonObject copy = new JsonObject();
            object.entrySet().forEach(entry -> copy.add(entry.getKey(), aboveBottom(entry.getValue())));
            return copy;
        }
        return element;
    }

    private static int depth(final JsonObject json)
    {
        return json.has("size") ? json.get("size").getAsInt() : 6;
    }

    private static final String CAMP = "abandoned_camp_";
    private static final Pattern FORESTED = Pattern.compile(
            "forest|wood|wald|taiga|jungle|grove|pine|birch|redwood|cedar|spruce|oak|tree|timber|rain|urwald|bamboo|cherry",
            Pattern.CASE_INSENSITIVE);

    /**
     * The ground where a structure would start (the middle of its chunk), for the newer structures to go where 26.3's biomes would
     * have them: sunk ones in the sea, beached ones on beaches, the rest on dry land.
     *
     * @param surface  The first air above the column (water counts as ground).
     * @param floor    The first air or water above its solid ground.
     * @param seaLevel The world's sea level.
     * @param sandy    Whether its top solid block is sand or gravel.
     */
    public record Ground(int surface, int floor, int seaLevel, boolean sandy)
    {
        /** How deep the water over it is */
        int waterDepth()
        {
            return Math.max(0, this.surface - this.floor);
        }

        /** The open sea: deep water well under the sea level (not a pond) */
        boolean sea()
        {
            return waterDepth() >= 10 && this.floor <= this.seaLevel - 10;
        }

        /** Dry land */
        boolean dry()
        {
            return waterDepth() == 0;
        }

        /** A beach: sand or gravel at the sea level, dry or just awash */
        boolean beach()
        {
            return this.sandy && waterDepth() <= 2 && this.floor >= this.seaLevel - 2 && this.floor <= this.seaLevel + 4;
        }

        /** High land */
        boolean mountain()
        {
            return dry() && this.surface >= this.seaLevel + 50;
        }
    }

    private static boolean is(final Supplier<Ground> ground, final Predicate<Ground> test)
    {
        final Ground g = ground.get();
        return g != null && test.test(g);
    }

    private static boolean rule(final BiomeConfig.StructureRule rule, final Supplier<Ground> ground, final Predicate<Ground> auto)
    {
        return rule == BiomeConfig.StructureRule.enabled || rule == BiomeConfig.StructureRule.auto && is(ground, auto);
    }

    private static boolean named(final BiomeConfig config, final String pattern)
    {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(config.getName()).find();
    }

    /** Warm water: a hot biome, or one called tropical (the oceans of the configs are all as warm as 26.3s, 0.5) */
    static boolean warm(final BiomeConfig config)
    {
        return config.biomeTemperature >= 1.0F || named(config, "tropic|warm|caribbean|seychelles");
    }

    /** A forest: trees of its own (Tree(...)), or a forest's name (Almura's forests grow their trees as custom objects) */
    static boolean forested(final BiomeConfig config)
    {
        for (final Object resource : config.resourceSequence)
        {
            if (resource instanceof final TreeGen tree && tree.getFrequency() >= 2)
            {
                return true;
            }
        }
        return FORESTED.matcher(config.getName()).find();
    }

    private static boolean jungle(final BiomeConfig config)
    {
        return named(config, "jungle|rainforest|rain forest|urwald|bamboo") || config.biomeTemperature >= 0.95F && config.biomeWetness >= 0.85F;
    }

    private static boolean swamp(final BiomeConfig config)
    {
        return named(config, "swamp|marsh|bog|fen|mangrove");
    }

    private static boolean desert(final BiomeConfig config, final Ground ground)
    {
        return named(config, "desert|dune|sand") || ground.sandy() && config.biomeWetness <= 0.2F;
    }

    /** The ruined portal a biome has on its ground; null for none */
    static BiomeConfig.RuinedPortalType portal(final BiomeConfig config, final Supplier<Ground> ground)
    {
        if (config.ruinedPortalType != BiomeConfig.RuinedPortalType.auto)
        {
            return config.ruinedPortalType == BiomeConfig.RuinedPortalType.disabled ? null : config.ruinedPortalType;
        }
        final Ground g = ground.get();
        if (g == null)
        {
            return null;
        }
        if (g.sea())
        {
            return BiomeConfig.RuinedPortalType.ocean;
        }
        if (g.mountain() || named(config, "mesa|badland"))
        {
            return BiomeConfig.RuinedPortalType.mountain;
        }
        if (jungle(config))
        {
            return BiomeConfig.RuinedPortalType.jungle;
        }
        if (swamp(config))
        {
            return BiomeConfig.RuinedPortalType.swamp;
        }
        if (desert(config, g))
        {
            return BiomeConfig.RuinedPortalType.desert;
        }
        return BiomeConfig.RuinedPortalType.standard;
    }

    /** The abandoned camp a biome has on its ground, by its name and climate; null for none */
    static BiomeConfig.AbandonedCampType camp(final BiomeConfig config, final Supplier<Ground> ground)
    {
        if (config.abandonedCampType != BiomeConfig.AbandonedCampType.auto)
        {
            return config.abandonedCampType == BiomeConfig.AbandonedCampType.disabled ? null : config.abandonedCampType;
        }
        final Ground g = ground.get();
        if (g == null || !g.dry() || !forested(config))
        {
            return null;
        }
        if (config.biomeTemperature < 0.15F)
        {
            return BiomeConfig.AbandonedCampType.snowyTaiga;
        }
        if (named(config, "birch"))
        {
            return BiomeConfig.AbandonedCampType.birchForest;
        }
        if (named(config, "cherry"))
        {
            return BiomeConfig.AbandonedCampType.cherryGrove;
        }
        if (named(config, "flower"))
        {
            return BiomeConfig.AbandonedCampType.flowerForest;
        }
        if (named(config, "pale|roofed|dark"))
        {
            return BiomeConfig.AbandonedCampType.paleGarden;
        }
        if (named(config, "redwood|mega|pine"))
        {
            return BiomeConfig.AbandonedCampType.oldGrowthPineTaiga;
        }
        if (named(config, "spruce"))
        {
            return BiomeConfig.AbandonedCampType.oldGrowthSpruceTaiga;
        }
        if (named(config, "bamboo"))
        {
            return BiomeConfig.AbandonedCampType.bambooJungle;
        }
        if (jungle(config))
        {
            return BiomeConfig.AbandonedCampType.sparseJungle;
        }
        if (swamp(config))
        {
            return BiomeConfig.AbandonedCampType.swamp;
        }
        if (named(config, "savanna") || config.biomeTemperature >= 1.2F && config.biomeWetness <= 0.3F)
        {
            return BiomeConfig.AbandonedCampType.savanna;
        }
        if (named(config, "mesa|badland"))
        {
            return BiomeConfig.AbandonedCampType.woodedBadlands;
        }
        if (named(config, "taiga|cedar") || config.biomeTemperature < 0.4F)
        {
            return BiomeConfig.AbandonedCampType.taiga;
        }
        if (g.mountain() || named(config, "extreme|windswept|hills"))
        {
            return BiomeConfig.AbandonedCampType.windsweptForest;
        }
        return BiomeConfig.AbandonedCampType.forest;
    }

    /** "bambooJungle" as 26.3 names it: "bamboo_jungle" */
    public static String snake(final String name)
    {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private TCStructures()
    {
    }
}
