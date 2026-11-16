package com.khorn.terraincontrol.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.configuration.WeightedMobSpawnGroup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a TerrainControl world's custom biomes as 26.3 biomes, into a datapack: data/terraincontrol/worldgen/biome/&lt;world&gt;/&lt;biome&gt;.json,
 * at the keys {@link NeoForgeBiome#registryKey(String, String)} gives. 26.3's biomes are fixed when the server starts, so they are
 * written from the configs beforehand; the server sends them to the clients, which then see each biome's name, colours and rain.
 *
 * <p>From each biome config: temperature, wetness (downfall; no rain or snow at 0), sky colour, and the grass, foliage and water
 * colours where set (#FFFFFF means unset: 26.3's own colours for the climate; a multiplier is applied to 26.3's usual colour). Its
 * spawn lists' vanilla mobs; mobs 26.3 does not have (Mo' Creatures) are left out. No features or carvers: TerrainControl places
 * everything itself.</p>
 */
final class BiomeExporter
{
    /** 26.3's usual colours, which TerrainControl's multipliers apply to */
    private static final int WATER = 0x3F76E4, GRASS = 0x91BD59, FOLIAGE = 0x77AB2F;

    /** 1.12's names for mobs 26.3 calls otherwise */
    private static final Map<String, String> RENAMED_MOBS = Map.of("snowman", "snow_golem", "zombie_pigman", "zombified_piglin");

    int biomes;
    final Map<String, Integer> skippedMobs = new HashMap<>();

    /**
     * Writes a world's custom biomes.
     *
     * @param world    The world, its configs loaded.
     * @param datapack The datapack's folder.
     * @throws IOException When writing fails.
     */
    void export(final NeoForgeWorld world, final Path datapack) throws IOException
    {
        final Map<ResourceKey<Biome>, String> written = new HashMap<>();
        for (final LocalBiome biome : world.getConfigs().getBiomeArray())
        {
            if (biome == null || !biome.isCustom())
            {
                continue;
            }
            final ResourceKey<Biome> key = NeoForgeBiome.registryKey(world.getName(), biome.getName());
            final String before = written.put(key, biome.getName());
            if (before != null && !before.equals(biome.getName()))
            {
                throw new IllegalStateException("Biomes \"" + before + "\" and \"" + biome.getName() + "\" of " + world.getName()
                        + " would both be " + key.identifier());
            }
            final Path file = datapack.resolve("data").resolve(key.identifier().getNamespace()).resolve("worldgen/biome")
                    .resolve(key.identifier().getPath() + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json(biome.getBiomeConfig())) + "\n",
                    StandardCharsets.UTF_8);
            this.biomes++;
        }
    }

    private static String color(final int rgb)
    {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    private static int multiply(final int base, final int by)
    {
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8)
        {
            result |= ((base >> shift & 0xFF) * (by >> shift & 0xFF) / 255) << shift;
        }
        return result;
    }

    /** A grass or foliage colour, or null for 26.3's own */
    private static String plantColor(final int color, final boolean multiplier, final int usual)
    {
        if ((color & 0xFFFFFF) == 0xFFFFFF)
        {
            return null;
        }
        return color(multiplier ? multiply(usual, color) : color);
    }

    JsonObject json(final BiomeConfig config)
    {
        final JsonObject biome = new JsonObject();
        biome.addProperty("temperature", config.biomeTemperature);
        biome.addProperty("downfall", Math.max(0, Math.min(1, config.biomeWetness)));
        biome.addProperty("has_precipitation", config.biomeWetness > 0);

        final JsonObject effects = new JsonObject();
        effects.addProperty("water_color", color((config.waterColor & 0xFFFFFF) == 0xFFFFFF ? WATER : multiply(WATER, config.waterColor)));
        final String grass = plantColor(config.grassColor, config.grassColorIsMultiplier, GRASS);
        if (grass != null)
        {
            effects.addProperty("grass_color", grass);
        }
        final String foliage = plantColor(config.foliageColor, config.foliageColorIsMultiplier, FOLIAGE);
        if (foliage != null)
        {
            effects.addProperty("foliage_color", foliage);
        }
        biome.add("effects", effects);

        final JsonObject attributes = new JsonObject();
        attributes.addProperty("minecraft:visual/sky_color", color(config.skyColor));
        final JsonObject byCategory = new JsonObject();
        spawns(byCategory, "monster", config.spawnMonsters);
        spawns(byCategory, "creature", config.spawnCreatures);
        spawns(byCategory, "water_creature", config.spawnWaterCreatures);
        spawns(byCategory, "ambient", config.spawnAmbientCreatures);
        final JsonObject argument = new JsonObject();
        argument.add("spawn_costs", new JsonObject());
        argument.add("spawns_by_category", byCategory);
        final JsonObject naturalSpawns = new JsonObject();
        // As 26.3's own biomes give theirs: laid over the dimension's (which has none)
        naturalSpawns.addProperty("modifier", "overlay");
        naturalSpawns.add("argument", argument);
        attributes.add("minecraft:gameplay/natural_mob_spawns", naturalSpawns);
        biome.add("attributes", attributes);

        biome.add("carvers", new JsonArray());
        biome.add("features", new JsonArray());
        return biome;
    }

    private void spawns(final JsonObject byCategory, final String category, final List<WeightedMobSpawnGroup> groups)
    {
        final JsonArray spawns = new JsonArray();
        for (final WeightedMobSpawnGroup group : groups)
        {
            final String name = RENAMED_MOBS.getOrDefault(group.getInternalName().toLowerCase(Locale.ROOT),
                    group.getInternalName().toLowerCase(Locale.ROOT));
            final Identifier id = Identifier.tryParse(name.indexOf(':') < 0 ? "minecraft:" + name : name);
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id) || group.getWeight() <= 0)
            {
                this.skippedMobs.merge(name, 1, Integer::sum);
                continue;
            }
            final JsonObject spawn = new JsonObject();
            spawn.addProperty("type", id.toString());
            spawn.addProperty("weight", group.getWeight());
            final int min = Math.max(1, group.getMin());
            final int max = Math.max(min, group.getMax());
            if (min == max)
            {
                spawn.addProperty("count", min);
            } else
            {
                final JsonObject count = new JsonObject();
                count.addProperty("type", "minecraft:uniform");
                count.addProperty("min_inclusive", min);
                count.addProperty("max_inclusive", max);
                spawn.add("count", count);
            }
            spawns.add(spawn);
        }
        if (!spawns.isEmpty())
        {
            byCategory.add(category, spawns);
        }
    }
}
