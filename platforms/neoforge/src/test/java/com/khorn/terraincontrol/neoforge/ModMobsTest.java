package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.khorn.terraincontrol.configuration.BiomeConfig;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Other mods' mobs as the biome configs name them, in 26.3's ids.
 */
final class ModMobsTest
{
    /** A biome config of a temperature only (all modMob reads) */
    private static BiomeConfig config(final float temperature) throws ReflectiveOperationException
    {
        final Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        final BiomeConfig config = (BiomeConfig) ((Unsafe) field.get(null)).allocateInstance(BiomeConfig.class);
        config.biomeTemperature = temperature;
        return config;
    }

    @Test
    void namesAs26_3Has() throws ReflectiveOperationException
    {
        final BiomeConfig temperate = config(0.8F);
        assertEquals("mocreatures:deer", ModMobs.modMob("mocreatures.deer", temperate).toString());
        assertEquals("mocreatures:silverskeleton", ModMobs.modMob("mocreatures:silverskeleton", temperate).toString());
        // 26.3's own mobs: in the exported biomes already
        assertNull(ModMobs.modMob("zombie", temperate));
        assertNull(ModMobs.modMob("minecraft:zombie", temperate));
        assertNull(ModMobs.modMob("minecraft.zombie", temperate));
        // 1.12's one scorpion
        assertEquals("mocreatures:dirtscorpion", ModMobs.modMob("mocreatures.scorpion", temperate).toString());
        assertEquals("mocreatures:frostscorpion", ModMobs.modMob("mocreatures.scorpion", config(0.0F)).toString());
    }
}
