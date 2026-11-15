package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.LocalWorld;
import com.khorn.terraincontrol.TerrainControlEngine;
import com.khorn.terraincontrol.configuration.standard.PluginStandardValues;
import com.khorn.terraincontrol.exception.InvalidConfigException;
import com.khorn.terraincontrol.neoforge.util.NBTHelper;
import com.khorn.terraincontrol.util.NamedBinaryTag;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.TagParser;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TerrainControl's engine on NeoForge.
 *
 * <p>Worlds are added once the chunk generator is ported; until then {@link #getWorld(String)} knows none.</p>
 */
public final class NeoForgeEngine extends TerrainControlEngine
{
    private final File configsDir;
    private final Map<String, LocalWorld> worlds = new ConcurrentHashMap<>();

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
