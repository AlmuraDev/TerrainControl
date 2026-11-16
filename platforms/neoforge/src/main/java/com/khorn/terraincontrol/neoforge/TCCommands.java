package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.configuration.WeightedMobSpawnGroup;
import com.khorn.terraincontrol.configuration.WorldConfig;
import com.khorn.terraincontrol.logging.LogMarker;
import com.khorn.terraincontrol.neoforge.generator.TCChunkGenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TerrainControl's /tc commands, as on 1.12, for operators (gamemasters):
 * <ul>
 *     <li>/tc: the commands;</li>
 *     <li>/tc worldinfo: the author and description of the TerrainControl world one is in;</li>
 *     <li>/tc biome: the TerrainControl biome one stands in, and the 26.3 biome its chunks are saved as;</li>
 *     <li>/tc print [world]: every biome of a TerrainControl world (the one one is in, or the level of that name), to the console.</li>
 * </ul>
 */
public final class TCCommands
{
    private TCCommands()
    {
    }

    public static void onRegisterCommands(final RegisterCommandsEvent event)
    {
        register(event.getDispatcher());
    }

    static void register(final CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("tc")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(TCCommands::help)
                .then(Commands.literal("worldinfo").executes(TCCommands::worldInfo))
                .then(Commands.literal("biome").executes(TCCommands::biome))
                .then(Commands.literal("print")
                        .executes(context -> print(context, null))
                        .then(Commands.argument("world", StringArgumentType.word())
                                .executes(context -> print(context, StringArgumentType.getString(context, "world"))))));
    }

    private static void send(final CommandSourceStack source, final Component message)
    {
        source.sendSuccess(() -> message, false);
    }

    private static MutableComponent line(final String key, final Object value)
    {
        return Component.literal("  " + key + ": ").append(Component.literal(String.valueOf(value)).withStyle(ChatFormatting.GRAY));
    }

    /** The TerrainControl world of the level one is in */
    private static Optional<NeoForgeWorld> world(final CommandSourceStack source)
    {
        return TCChunkGenerator.worldFor(source.getLevel());
    }

    private static int notEnabled(final CommandSourceStack source)
    {
        source.sendFailure(Component.literal("TerrainControl is not enabled for this world."));
        return 0;
    }

    private static int help(final CommandContext<CommandSourceStack> context)
    {
        final CommandSourceStack source = context.getSource();
        send(source, Component.literal("-- TerrainControl --").withStyle(ChatFormatting.DARK_GREEN));
        send(source, Component.literal("Commands:"));
        send(source, Component.literal("/tc worldinfo - Show author and description information for this world."));
        send(source, Component.literal("/tc biome - Show biome information for the biome at your position."));
        send(source, Component.literal("/tc print [world] - Print every biome of a TerrainControl world to the console."));
        return 1;
    }

    private static int worldInfo(final CommandContext<CommandSourceStack> context)
    {
        final CommandSourceStack source = context.getSource();
        final Optional<NeoForgeWorld> world = world(source);
        if (world.isEmpty())
        {
            return notEnabled(source);
        }
        final WorldConfig config = world.get().getConfigs().getWorldConfig();
        send(source, Component.literal("-- World info --").withStyle(ChatFormatting.DARK_GREEN));
        send(source, line("World", world.get().getName()));
        send(source, line("Author", config.author));
        send(source, line("Description", config.description));
        return 1;
    }

    private static String spawns(final List<WeightedMobSpawnGroup> groups)
    {
        return groups.isEmpty() ? "none" : groups.stream()
                .map(group -> group.getInternalName() + " (" + group.getWeight() + ", " + group.getMin() + "-" + group.getMax() + ")")
                .collect(Collectors.joining(", "));
    }

    private static int biome(final CommandContext<CommandSourceStack> context)
    {
        final CommandSourceStack source = context.getSource();
        final Optional<NeoForgeWorld> world = world(source);
        if (world.isEmpty())
        {
            return notEnabled(source);
        }
        final BlockPos pos = BlockPos.containing(source.getPosition());
        final LocalBiome biome = world.get().getCalculatedBiome(pos.getX(), pos.getZ());
        final BiomeConfig config = biome.getBiomeConfig();
        final Holder<Biome> saved = source.getLevel().getBiome(pos);
        send(source, Component.literal("Current Biome Data...").withStyle(ChatFormatting.DARK_GREEN));
        send(source, line("Name", biome.getName()));
        send(source, line("Config", biome.getName() + ".bc"));
        send(source, line("Custom", biome.isCustom()));
        send(source, line("ID", biome.getIds().getGenerationId()));
        send(source, line("Saved as", saved.unwrapKey().map(key -> key.identifier().toString()).orElse("?")));
        send(source, line("Temperature", config.biomeTemperature + " (here " + biome.getTemperatureAt(pos.getX(), pos.getY(), pos.getZ()) + ")"));
        send(source, line("Wetness", config.biomeWetness));
        send(source, Component.literal("Biome Spawn Lists...").withStyle(ChatFormatting.AQUA));
        send(source, line("Ambient", spawns(config.spawnAmbientCreatures)));
        send(source, line("Creatures", spawns(config.spawnCreatures)));
        send(source, line("Monsters", spawns(config.spawnMonsters)));
        send(source, line("Water Creatures", spawns(config.spawnWaterCreatures)));
        return 1;
    }

    private static int print(final CommandContext<CommandSourceStack> context, final String name)
    {
        final CommandSourceStack source = context.getSource();
        Optional<NeoForgeWorld> world;
        if (name == null)
        {
            world = world(source);
        } else
        {
            // A loaded level of that name (its path, as "orilla" for terraincontrol:orilla or nucleus:orilla), or a TerrainControl world
            // of that name
            world = Optional.empty();
            for (final ServerLevel level : source.getServer().getAllLevels())
            {
                final Optional<NeoForgeWorld> candidate = TCChunkGenerator.worldFor(level);
                if (candidate.isPresent() && (level.dimension().identifier().getPath().equalsIgnoreCase(name)
                        || candidate.get().getName().equalsIgnoreCase(name)))
                {
                    world = candidate;
                    break;
                }
            }
        }
        if (world.isEmpty())
        {
            source.sendFailure(Component.literal(name == null ? "TerrainControl is not enabled for this world."
                    : "No loaded TerrainControl world '" + name + "'."));
            return 0;
        }
        final NeoForgeWorld tcWorld = world.get();
        TerrainControl.log(LogMarker.INFO, "Printing the biomes of TerrainControl world '{}'. Format is Id-Name-SavedAs.", tcWorld.getName());
        Arrays.stream(tcWorld.getConfigs().getBiomeArray())
                .filter(biome -> biome != null)
                .sorted(Comparator.comparingInt(biome -> biome.getIds().getGenerationId()))
                .forEach(biome -> TerrainControl.log(LogMarker.INFO, "  {}-{}-{}", biome.getIds().getGenerationId(), biome.getName(),
                        ((NeoForgeBiome) biome).getSavedBiome().unwrapKey().map(key -> key.identifier().toString()).orElse("?")));
        send(source, Component.literal("Check console for biome printout."));
        return 1;
    }
}
