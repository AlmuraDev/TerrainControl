package com.khorn.terraincontrol.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class StringHelperTest
{

    @Test
    public void testReadCommaSeperatedString()
    {
        // Test empty string
        assertArrayEquals(StringHelper.readCommaSeperatedString(""), new String[0]);

        // Test simple strings
        assertArrayEquals(StringHelper.readCommaSeperatedString("a"), new String[] {"a"});
        assertArrayEquals(StringHelper.readCommaSeperatedString("a,b,cd"), new String[] {"a", "b", "cd"});

        // Test if commas inside braces are ignored
        assertArrayEquals(StringHelper.readCommaSeperatedString("a,b(c,d),e"), new String[] {"a", "b(c,d)", "e"});

        // Test handling of nested braces
        assertArrayEquals(StringHelper.readCommaSeperatedString("a,b(c,d(e,f),g),h"), new String[] {"a", "b(c,d(e,f),g)", "h"});

        // Test if whitespace is stripped
        assertArrayEquals(StringHelper.readCommaSeperatedString("   "), new String[0]);
        assertArrayEquals(StringHelper.readCommaSeperatedString("a, b(c,d), e"), new String[] {"a", "b(c,d)", "e"});
        assertArrayEquals(StringHelper.readCommaSeperatedString("  a, b(c,d), e  "), new String[] {"a", "b(c,d)", "e"});
    }

    @Test
    public void testSpecifiesBlockData()
    {
        assertFalse(StringHelper.specifiesBlockData("oak_log"));
        assertFalse(StringHelper.specifiesBlockData("minecraft:oak_log"));
        assertTrue(StringHelper.specifiesBlockData("minecraft:oak_log[]"));
        assertTrue(StringHelper.specifiesBlockData("minecraft:oak_log[axis=x]"));
        assertTrue(StringHelper.specifiesBlockData("minecraft:oak_stairs[facing=east,half=top]"));
    }

    @Test
    public void testCommasInBlockStates()
    {
        assertArrayEquals(new String[] {"minecraft:oak_stairs[facing=east,half=top]", "minecraft:stone"},
                StringHelper.readCommaSeperatedString("minecraft:oak_stairs[facing=east,half=top], minecraft:stone"));
        assertArrayEquals(new String[] {"0", "1", "2", "minecraft:oak_stairs[facing=east,half=top]", "chest.nbt"},
                StringHelper.readCommaSeperatedString("0,1,2,minecraft:oak_stairs[facing=east,half=top],chest.nbt"));
    }

}
