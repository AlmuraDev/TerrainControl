package com.khorn.terraincontrol.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * TerrainControl's mod for Minecraft 26.3.
 *
 * <p>The port from 1.12.2 (legacy/forge) is under way: so far the mod only loads. The engine, the materials, the chunk generator and
 * the biome source follow, each moved over from legacy/forge as it is ported.</p>
 */
@Mod(TerrainControlMod.ID)
public final class TerrainControlMod
{
    public static final String ID = "terraincontrol";

    public TerrainControlMod(final IEventBus modBus, final ModContainer container)
    {
        NeoForgeLogger.LOGGER.info("TerrainControl {} starting", container.getModInfo().getVersion());
    }
}
