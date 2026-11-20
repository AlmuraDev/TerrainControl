package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.LocalWorld;
import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.TerrainControlEngine;
import com.khorn.terraincontrol.configuration.standard.PluginStandardValues;
import com.khorn.terraincontrol.exception.InvalidConfigException;
import com.khorn.terraincontrol.logging.LogMarker;
import com.khorn.terraincontrol.neoforge.util.NBTHelper;
import com.khorn.terraincontrol.util.NamedBinaryTag;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.biome.Biome;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TerrainControl's engine on NeoForge.
 *
 * <p>A world is loaded the first time its dimension's generator asks for it ({@link #loadWorld(String, HolderGetter)}): its configs
 * are those in worlds/&lt;name&gt; of TerrainControl's folder.</p>
 */
public final class NeoForgeEngine extends TerrainControlEngine
{
    private final File configsDir;
    private final Map<String, NeoForgeWorld> worlds = new ConcurrentHashMap<>();
    /** The worlds loaded by loadWorld that a level has taken (takeLoaded) */
    private final java.util.Set<String> taken = new java.util.HashSet<>();

    /**
     * Gets the engine, once TerrainControl has started.
     *
     * @return The engine.
     * @throws IllegalStateException Before TerrainControl has started.
     */
    public static NeoForgeEngine get()
    {
        if (!(TerrainControl.getEngine() instanceof final NeoForgeEngine engine))
        {
            throw new IllegalStateException("TerrainControl has not started yet");
        }
        return engine;
    }

    /**
     * Loads a TerrainControl world of its own, for one level: two levels with the same configs (a Sponge world made from a
     * TerrainControl dimension, beside the dimension's own) each have their own seed and generators.
     *
     * @param name   The world's name, the name of its folder in worlds.
     * @param biomes The server's biomes.
     * @return The world.
     */
    public NeoForgeWorld createWorld(final String name, final HolderGetter<Biome> biomes)
    {
        final File worldDir = new File(new File(this.configsDir, "worlds"), name);
        if (!worldDir.isDirectory())
        {
            throw new IllegalArgumentException("No TerrainControl configs for world \"" + name + "\": " + worldDir + " is no folder");
        }
        TerrainControl.log(LogMarker.INFO, "Loading configs for a level of world \"{}\"", name);
        return new NeoForgeWorld(name, worldDir, biomes);
    }

    /**
     * Gets a TerrainControl world's configs, loading them the first time they are asked for: for what does not depend on a level
     * (its biomes, its heights). A level gets a world of its own ({@link #createWorld(String, HolderGetter)}).
     *
     * @param name   The world's name, the name of its folder in worlds.
     * @param biomes The server's biomes.
     * @return The world.
     */
    public NeoForgeWorld loadWorld(final String name, final HolderGetter<Biome> biomes)
    {
        return this.worlds.computeIfAbsent(name, worldName -> {
            final File worldDir = new File(new File(this.configsDir, "worlds"), worldName);
            if (!worldDir.isDirectory())
            {
                throw new IllegalArgumentException("No TerrainControl configs for world \"" + worldName + "\": " + worldDir + " is no folder");
            }
            TerrainControl.log(LogMarker.INFO, "Loading configs for world \"{}\"", worldName);
            return new NeoForgeWorld(worldName, worldDir, biomes);
        });
    }

    /**
     * Takes a world's configs loaded by {@link #loadWorld(String, HolderGetter)} for a level of its own: a level made after its
     * configs were loaded uses them, instead of reading them again. Only once: the next level loads its own.
     *
     * @param name The world's name.
     * @return The configs loaded; null when they were not loaded (or a level has taken them already).
     */
    public NeoForgeWorld takeLoaded(final String name)
    {
        synchronized (this.taken)
        {
            final NeoForgeWorld world = this.worlds.get(name);
            // It stays loaded (getWorld finds it), now as the level's
            return world == null || !this.taken.add(name) ? null : world;
        }
    }

    /**
     * Gets the folder of a world as its biomes' ids name it ({@code terraincontrol:<world>/<biome>}, the world in lower case).
     *
     * @param slug The world as the ids name it.
     * @return The world's name, its folder in worlds; null when there is none.
     */
    public String worldFolder(final String slug)
    {
        final File[] folders = new File(this.configsDir, "worlds").listFiles(File::isDirectory);
        if (folders != null)
        {
            for (final File folder : folders)
            {
                if (NeoForgeBiome.slug(folder.getName()).equals(slug))
                {
                    return folder.getName();
                }
            }
        }
        return null;
    }

    /**
     * Creates the engine.
     *
     * @param configsDir TerrainControl's folder: its TerrainControl.ini, GlobalObjects and worlds.
     */
    public NeoForgeEngine(final File configsDir)
    {
        super(new NeoForgeLogger());
        this.configsDir = configsDir;
    }

    @Override
    public LocalWorld getWorld(final String name)
    {
        return this.worlds.get(name);
    }

    @Override
    public File getTCDataFolder()
    {
        return this.configsDir;
    }

    @Override
    public File getGlobalObjectsDirectory()
    {
        return new File(this.configsDir, PluginStandardValues.BO_DirectoryName);
    }

    @Override
    public LocalMaterialData readMaterial(final String name) throws InvalidConfigException
    {
        return NeoForgeMaterialData.ofString(name);
    }

    @Override
    public LocalMaterialData toLocalMaterialData(final DefaultMaterial defaultMaterial, final int blockData)
    {
        return NeoForgeMaterialData.ofDefaultMaterial(defaultMaterial, blockData);
    }

    @Override
    public NamedBinaryTag toNamedBinaryTag(final String mojangson) throws InvalidConfigException
    {
        try
        {
            return NBTHelper.toNamedBinaryTag("", TagParser.parseCompoundFully(mojangson));
        } catch (CommandSyntaxException e)
        {
            throw new InvalidConfigException("Error parsing NBT data \n" + mojangson + "\n" + e.getMessage());
        }
    }
}
