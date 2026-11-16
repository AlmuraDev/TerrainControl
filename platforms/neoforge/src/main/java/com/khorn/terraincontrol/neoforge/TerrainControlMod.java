package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.neoforge.generator.SaplingListener;
import com.khorn.terraincontrol.neoforge.generator.TCBiomeSource;
import com.khorn.terraincontrol.neoforge.generator.TCChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * TerrainControl's mod for Minecraft 26.3.
 *
 * <p>It registers TerrainControl's chunk generator and biome source ({@code terraincontrol:terraincontrol}), which a dimension
 * names to be a TerrainControl world, and starts TerrainControl (config/TerrainControl) once the blocks are registered.</p>
 */
@Mod(TerrainControlMod.ID)
public final class TerrainControlMod
{
    public static final String ID = "terraincontrol";

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, ID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, ID);

    static
    {
        CHUNK_GENERATORS.register(ID, () -> TCChunkGenerator.CODEC);
        BIOME_SOURCES.register(ID, () -> TCBiomeSource.CODEC);
    }

    public TerrainControlMod(final IEventBus modBus, final ModContainer container)
    {
        NeoForgeLogger.LOGGER.info("TerrainControl {} starting", container.getModInfo().getVersion());
        CHUNK_GENERATORS.register(modBus);
        BIOME_SOURCES.register(modBus);
        modBus.addListener(TerrainControlMod::onCommonSetup);
        // Saplings grow into their biome's own trees
        NeoForge.EVENT_BUS.addListener(SaplingListener::onGrow);
        // /tc worldinfo, /tc biome, /tc print
        NeoForge.EVENT_BUS.addListener(TCCommands::onRegisterCommands);
    }

    private static void onCommonSetup(final FMLCommonSetupEvent event)
    {
        // The configs name blocks, so TerrainControl starts once they are all registered
        event.enqueueWork(() -> {
            if (TerrainControl.getEngine() == null)
            {
                // config/TerrainControl, or -Dterraincontrol.configs=<folder> (the tests use the converted configs)
                final String folder = System.getProperty("terraincontrol.configs", "");
                TerrainControl.setEngine(new NeoForgeEngine(folder.isEmpty()
                        ? FMLPaths.CONFIGDIR.get().resolve("TerrainControl").toFile()
                        : new java.io.File(folder)));
            }
        });
    }
}
