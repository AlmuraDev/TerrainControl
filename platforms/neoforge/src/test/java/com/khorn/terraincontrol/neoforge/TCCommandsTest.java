package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

/**
 * The /tc commands register as on 1.12: /tc, /tc worldinfo, /tc biome, /tc print [world].
 */
final class TCCommandsTest
{
    @Test
    void tcCommandsRegister()
    {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        TCCommands.register(dispatcher);
        final CommandNode<CommandSourceStack> tc = dispatcher.getRoot().getChild("tc");
        assertNotNull(tc, "/tc");
        assertNotNull(tc.getCommand(), "/tc runs");
        for (final String sub : new String[] {"worldinfo", "biome", "print"})
        {
            assertNotNull(tc.getChild(sub), "/tc " + sub);
            assertNotNull(tc.getChild(sub).getCommand(), "/tc " + sub + " runs");
        }
        assertTrue(tc.getChild("print").getChild("world") != null, "/tc print <world>");
    }
}
