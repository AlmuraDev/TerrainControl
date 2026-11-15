package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.TerrainControl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Starts TerrainControl once for all tests (its engine is one per game). Its folder is the converted configs, ServerConfigs-26.3,
 * when they are there, so the tests can load Almura's worlds; otherwise an empty folder.
 */
final class TestEngine
{
    static synchronized void start()
    {
        if (TerrainControl.getEngine() != null)
        {
            return;
        }
        try
        {
            final Path folder = configs() != null ? configs() : Files.createTempDirectory("terraincontrol");
            TerrainControl.setEngine(new NeoForgeEngine(folder.toFile()));
        } catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Gets the converted configs.
     *
     * @return Their folder, or null when they are not there.
     */
    static Path configs()
    {
        final String folder = System.getProperty("tc.test.configs", "");
        return !folder.isEmpty() && Files.isDirectory(Path.of(folder, "worlds")) ? Path.of(folder) : null;
    }

    private TestEngine()
    {
    }
}
