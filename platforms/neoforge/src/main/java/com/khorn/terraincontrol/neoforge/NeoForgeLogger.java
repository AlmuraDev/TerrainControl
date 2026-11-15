package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.logging.LogMarker;
import com.khorn.terraincontrol.logging.Logger;
import org.slf4j.LoggerFactory;

/**
 * TerrainControl's {@link Logger} on NeoForge, which logs through SLF4J as the 1.12 one logged through Log4j.
 */
public final class NeoForgeLogger extends Logger
{
    static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("TerrainControl");

    @Override
    public void log(final LogMarker level, final String message, final Object... params)
    {
        if (this.minimumLevel.compareTo(level) < 0)
        {
            // Only log messages that we want to see...
            return;
        }
        switch (level)
        {
            // SLF4J has no fatal level
            case FATAL, ERROR -> LOGGER.error(message, params);
            case WARN -> LOGGER.warn(message, params);
            case INFO -> LOGGER.info(message, params);
            case DEBUG -> LOGGER.debug(message, params);
            case TRACE -> LOGGER.trace(message, params);
        }
    }
}
