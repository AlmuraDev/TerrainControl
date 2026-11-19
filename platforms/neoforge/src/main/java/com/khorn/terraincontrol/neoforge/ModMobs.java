package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.configuration.WeightedMobSpawnGroup;
import com.khorn.terraincontrol.configuration.io.FileSettingsReader;
import com.khorn.terraincontrol.configuration.io.SettingsMap;
import com.khorn.terraincontrol.configuration.standard.BiomeStandardValues;
import com.khorn.terraincontrol.configuration.standard.PluginStandardValues;
import com.khorn.terraincontrol.configuration.standard.WorldStandardValues;
import com.khorn.terraincontrol.logging.LogMarker;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Other mods' mobs in TerrainControl's biomes, as their configs list them (Mo' Creatures': {@code mocreatures.deer}), and no
 * monsters at all in a world whose WorldConfig says MonstersEnabled: false.
 *
 * <p>The exported biomes (the terraincontrol-worlds datapack) have 26.3's own mobs only: a biome naming a mob no mod registered
 * would not load. So the mods' mobs are added here, as the server starts, to those of the mods it has: a NeoForge biome modifier
 * ({@code terraincontrol:mod_mobs}, data/terraincontrol/neoforge/biome_modifier/mod_mobs.json) that for each of TerrainControl's
 * biomes reads its config's spawn lists. A mob the server does not have is left out, with one line in the log.</p>
 *
 * <p>Biome modifiers run for every biome as the server starts, also those of worlds that never load; so only the spawn lists are
 * read, from the biome files (with what they extend) and MonstersEnabled from the WorldConfig: no world is loaded for them, no
 * custom objects, and no file is written.</p>
 *
 * <p>1.12's names were {@code <mod>.<mob>}; 26.3's are {@code <mod>:<mob>}. Mo' Creatures' scorpion became one of each kind; the
 * wild ones here are 1.12's: a frost scorpion where it snows, otherwise a dirt scorpion.</p>
 */
final class ModMobs implements BiomeModifier
{
    static final MapCodec<ModMobs> CODEC = MapCodec.unit(ModMobs::new);

    /** The mobs left out, each logged once */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    /** A world's settings for this, read once a server start: whether it has monsters, and its biome files by their ids */
    record WorldSpawns(boolean monsters, Map<String, File> biomeFiles, Map<String, File> biomeFilesByName)
    {
    }

    /** A biome's spawn settings */
    record BiomeSpawns(float temperature, List<WeightedMobSpawnGroup> monsters, List<WeightedMobSpawnGroup> creatures,
            List<WeightedMobSpawnGroup> waterCreatures, List<WeightedMobSpawnGroup> ambient)
    {
    }

    private static final Map<String, Optional<WorldSpawns>> WORLDS = new ConcurrentHashMap<>();

    @Override
    public void modify(final RegistryAccess registryAccess, final Holder<Biome> biome, final Phase phase,
            final ModifiableBiomeInfo.BiomeInfo.Builder builder)
    {
        if (phase != Phase.ADD && phase != Phase.REMOVE)
        {
            return;
        }
        final Optional<ResourceKey<Biome>> key = biome.unwrapKey();
        if (key.isEmpty() || !key.get().identifier().getNamespace().equals(TerrainControlMod.ID))
        {
            return;
        }
        final String path = key.get().identifier().getPath();
        final int slash = path.indexOf('/');
        if (slash < 0)
        {
            return;
        }
        final WorldSpawns world = world(path.substring(0, slash)).orElse(null);
        if (world == null)
        {
            return;
        }
        if (phase == Phase.REMOVE)
        {
            if (!world.monsters())
            {
                // MonstersEnabled: false: none at all, 26.3's own (the exported biome's) and those other mods add
                builder.getMobSpawnSettings().removeSpawns(spawn -> spawn.value().type().getCategory() == MobCategory.MONSTER);
            }
            return;
        }
        final BiomeSpawns spawns = spawns(world, path.substring(slash + 1));
        if (spawns == null)
        {
            return;
        }
        if (world.monsters())
        {
            add(builder, MobCategory.MONSTER, spawns.monsters(), spawns.temperature());
        }
        add(builder, MobCategory.CREATURE, spawns.creatures(), spawns.temperature());
        add(builder, MobCategory.WATER_CREATURE, spawns.waterCreatures(), spawns.temperature());
        add(builder, MobCategory.AMBIENT, spawns.ambient(), spawns.temperature());
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec()
    {
        return CODEC;
    }

    /** Forgets what was read, for the next server start (the configs may have changed) */
    static void clear()
    {
        WORLDS.clear();
    }

    /** A world's settings by its id in the biomes' ids (lower case) */
    private static Optional<WorldSpawns> world(final String slug)
    {
        return WORLDS.computeIfAbsent(slug, s -> {
            final NeoForgeEngine engine = NeoForgeEngine.get();
            final String name = engine.worldFolder(s);
            if (name == null)
            {
                return Optional.empty();
            }
            return Optional.of(read(engine.getTCDataFolder(), name));
        });
    }

    /**
     * Reads a world's settings for its mobs: MonstersEnabled, and where its biome files are (its own WorldBiomes before
     * GlobalBiomes, as TerrainControl loads them).
     *
     * @param tcFolder TerrainControl's folder.
     * @param name     The world's name, its folder in worlds.
     * @return The settings.
     */
    static WorldSpawns read(final File tcFolder, final String name)
    {
        final File worldFolder = new File(new File(tcFolder, "worlds"), name);
        final SettingsMap worldConfig = FileSettingsReader.read(name, new File(worldFolder, WorldStandardValues.WORLD_CONFIG_FILE_NAME));
        final boolean monsters = worldConfig.getSetting(WorldStandardValues.MONSTERS_ENABLED);
        final Map<String, File> bySlug = new HashMap<>();
        final Map<String, File> byName = new HashMap<>();
        for (final File folder : new File[] {new File(worldFolder, WorldStandardValues.WORLD_BIOMES_DIRECTORY_NAME),
                new File(tcFolder, PluginStandardValues.BiomeConfigDirectoryName)})
        {
            index(folder, bySlug, byName);
        }
        return new WorldSpawns(monsters, bySlug, byName);
    }

    private static void index(final File folder, final Map<String, File> bySlug, final Map<String, File> byName)
    {
        final File[] files = folder.listFiles();
        if (files == null)
        {
            return;
        }
        for (final File file : files)
        {
            if (file.isDirectory())
            {
                index(file, bySlug, byName);
                continue;
            }
            for (final String extension : BiomeStandardValues.BiomeConfigExtensions)
            {
                if (file.getName().endsWith(extension))
                {
                    final String biome = file.getName().substring(0, file.getName().lastIndexOf(extension));
                    // The first found wins: the world's own before GlobalBiomes
                    bySlug.putIfAbsent(NeoForgeBiome.slug(biome), file);
                    byName.putIfAbsent(biome, file);
                    break;
                }
            }
        }
    }

    /**
     * Reads a biome's spawn settings from its file and those it extends (BiomeExtends), as TerrainControl reads them.
     *
     * @param world The world's settings.
     * @param slug  The biome as its id names it.
     * @return The settings; null when the world has no file for it.
     */
    static BiomeSpawns spawns(final WorldSpawns world, final String slug)
    {
        final File file = world.biomeFiles().get(slug);
        if (file == null)
        {
            return null;
        }
        final SettingsMap settings = FileSettingsReader.read(file.getName(), file);
        SettingsMap child = settings;
        // BiomeExtends, as far as it goes (a loop or a missing biome ends it, as TerrainControl ignores BiomeExtends then)
        final Set<File> seen = new java.util.HashSet<>(List.of(file));
        for (int depth = 0; depth < 32; depth++)
        {
            final String extended = child.getSetting(BiomeStandardValues.BIOME_EXTENDS);
            final File parentFile = extended == null || extended.isEmpty() ? null : world.biomeFilesByName().get(extended);
            if (parentFile == null || !seen.add(parentFile))
            {
                break;
            }
            final SettingsMap parent = FileSettingsReader.read(parentFile.getName(), parentFile);
            child.setFallback(parent);
            child = parent;
        }
        return new BiomeSpawns(settings.getSetting(BiomeStandardValues.BIOME_TEMPERATURE),
                settings.getSetting(BiomeStandardValues.SPAWN_MONSTERS), settings.getSetting(BiomeStandardValues.SPAWN_CREATURES),
                settings.getSetting(BiomeStandardValues.SPAWN_WATER_CREATURES),
                settings.getSetting(BiomeStandardValues.SPAWN_AMBIENT_CREATURES));
    }

    private static void add(final ModifiableBiomeInfo.BiomeInfo.Builder builder, final MobCategory category,
            final List<WeightedMobSpawnGroup> groups, final float temperature)
    {
        if (groups == null)
        {
            return;
        }
        for (final WeightedMobSpawnGroup group : groups)
        {
            final String name = group.getInternalName().toLowerCase(Locale.ROOT);
            final Identifier id = modMob(name, temperature);
            if (id == null || group.getWeight() <= 0)
            {
                // 26.3's own mobs, which the exported biomes have already
                continue;
            }
            final Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (type.isEmpty())
            {
                if (MISSING.add(id.toString()))
                {
                    TerrainControl.log(LogMarker.INFO, "Biome configs spawn {}, which this server does not have: left out", id);
                }
                continue;
            }
            final int min = Math.max(1, group.getMin());
            builder.getMobSpawnSettings().addSpawn(type.get(), category, group.getWeight(), UniformInt.of(min, Math.max(min, group.getMax())));
        }
    }

    /**
     * Gets the 26.3 id of a mod's mob as a biome config names it.
     *
     * @param name        The name in the config, lower case: {@code mocreatures.deer} (1.12) or {@code mocreatures:deer}.
     * @param temperature The biome's temperature.
     * @return The id; null for 26.3's own mobs (no mod named).
     */
    static Identifier modMob(final String name, final float temperature)
    {
        final int dot = name.indexOf(':') >= 0 ? name.indexOf(':') : name.indexOf('.');
        if (dot <= 0 || dot == name.length() - 1)
        {
            return null;
        }
        final String mod = name.substring(0, dot);
        String mob = name.substring(dot + 1);
        if (mod.equals("minecraft"))
        {
            return null;
        }
        if (mod.equals("mocreatures") && mob.equals("scorpion"))
        {
            // 1.12's scorpion was a frost scorpion in the snow, a dirt scorpion elsewhere (a cave or fire one underground and in
            // the Nether, which TerrainControl's worlds do not spawn by biome)
            mob = temperature < 0.15F ? "frostscorpion" : "dirtscorpion";
        }
        return Identifier.tryParse(mod + ":" + mob);
    }
}
