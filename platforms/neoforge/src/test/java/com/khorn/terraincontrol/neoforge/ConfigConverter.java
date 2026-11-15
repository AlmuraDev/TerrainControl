package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.generator.resource.PlantType;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts TerrainControl's 1.12 configs to 26.3: every block they name becomes a 26.3 block state.
 *
 * <p>Only the places TerrainControl reads blocks from are touched (the block settings, and the block arguments of the resources and
 * of the custom objects' functions, as their code reads them); everything else, comments and spacing included, stays as it was.
 * The rules follow what the blocks meant on 1.12:</p>
 * <ul>
 *     <li>A block to place ({@code SurfaceBlock}, {@code Ore(...)}'s ore, {@code Block(...)}) becomes its 26.3 block state:
 *     {@code minecraft:stained_hardened_clay:1} becomes {@code minecraft:orange_terracotta}, {@code LOG:4} becomes
 *     {@code minecraft:oak_log[axis=x]}.</li>
 *     <li>A block to check for ({@code SourceBlocks}, {@code BlockCheck(...)}) without a data value matched all of a 1.12 block's
 *     variants, which 26.3 splits into blocks of their own: {@code minecraft:stone} becomes stone, granite, polished granite, diorite,
 *     polished diorite, andesite and polished andesite. With a data value it becomes the one 26.3 block.</li>
 *     <li>Numbers with no 1.12 block (item ids, like 342) were air on 1.12: they are dropped from checks, and placed as air.</li>
 *     <li>What it cannot read is left as it was and listed in the report, so TerrainControl names it when it loads the config.</li>
 * </ul>
 *
 * <p>It reads 1.12 names only: run it once, on the 1.12 configs. Not yet converted: the {@code .nbt} files, mob names and heights.</p>
 */
final class ConfigConverter
{
    /** A 1.12 block, by id, with a data value (-1 for none); {@link #JUNK} for a number 1.12 had no block for. */
    record Legacy(int id, int data)
    {
        static final Legacy JUNK = new Legacy(-1, -1);
    }

    /** How a function's arguments name blocks: single blocks at some positions, a list of blocks from a position on. */
    private record FunctionBlocks(Set<Integer> singles, int listFrom, boolean plantFirst, boolean randomBlocks)
    {
    }

    private static final Map<String, FunctionBlocks> FUNCTIONS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    static
    {
        // Resources (generator/resource), as their load methods read their arguments
        FUNCTIONS.put("Ore", new FunctionBlocks(Set.of(0), 6, false, false));
        FUNCTIONS.put("UnderWaterOre", new FunctionBlocks(Set.of(0), 4, false, false));
        FUNCTIONS.put("Liquid", new FunctionBlocks(Set.of(0), 5, false, false));
        FUNCTIONS.put("SmallLake", new FunctionBlocks(Set.of(0), -1, false, false));
        FUNCTIONS.put("Boulder", new FunctionBlocks(Set.of(0), 5, false, false));
        FUNCTIONS.put("Cactus", new FunctionBlocks(Set.of(0), 5, false, false));
        FUNCTIONS.put("Reed", new FunctionBlocks(Set.of(0), 5, false, false));
        FUNCTIONS.put("AboveWaterRes", new FunctionBlocks(Set.of(0), -1, false, false));
        FUNCTIONS.put("IceSpike", new FunctionBlocks(Set.of(0), 6, false, false));
        FUNCTIONS.put("SurfacePatch", new FunctionBlocks(Set.of(0, 1), 4, false, false));
        FUNCTIONS.put("Vein", new FunctionBlocks(Set.of(0), 9, false, false));
        FUNCTIONS.put("Well", new FunctionBlocks(Set.of(0, 1, 2), 7, false, false));
        // A plant type's name (Dandelion) or a block
        FUNCTIONS.put("Grass", new FunctionBlocks(Set.of(), 4, true, false));
        FUNCTIONS.put("Plant", new FunctionBlocks(Set.of(), 5, true, false));
        // Custom objects' functions (customobjects/bo3), with their short names
        for (final String name : List.of("Block", "B"))
        {
            FUNCTIONS.put(name, new FunctionBlocks(Set.of(3), -1, false, false));
        }
        for (final String name : List.of("RandomBlock", "RB"))
        {
            FUNCTIONS.put(name, new FunctionBlocks(Set.of(), -1, false, true));
        }
        for (final String name : List.of("BlockCheck", "BC", "BlockCheckNot", "BCN"))
        {
            FUNCTIONS.put(name, new FunctionBlocks(Set.of(), 3, false, false));
        }
    }

    /** Settings that name a block to place */
    private static final Set<String> SINGLE_SETTINGS = caseless("SurfaceBlock", "GroundBlock", "StoneBlock", "WaterBlock", "IceBlock",
            "CooledLavaBlock", "BedrockobBlock");
    /** Settings that name blocks to check for */
    private static final Set<String> LIST_SETTINGS = caseless("SourceBlocks", "ExtrudeThroughBlocks", "spawnOnBlockType",
            "collisionBlockType");
    /** A material set's words for groups of blocks (MaterialSet) */
    private static final Set<String> SET_WORDS = caseless("All", "Solid", "NonSolid");

    private static final Pattern FUNCTION = Pattern.compile("^(\\s*)([A-Za-z]+)\\((.*)\\)(\\s*)$");
    private static final Pattern SETTING = Pattern.compile("^(\\s*)([A-Za-z]+)(\\s*[:=]\\s*)(.*?)(\\s*)$");
    /** A BO2's block: "x,z,y:block", optionally followed by "#direction@odds" for a branch */
    private static final Pattern BO2_BLOCK = Pattern.compile("^(\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*:\\s*)([^#\\s]+)(.*)$");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+");

    private static Set<String> caseless(final String... names)
    {
        final Set<String> set = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(List.of(names));
        return set;
    }

    /** What a run did, for its report */
    static final class Report
    {
        int files;
        int changedFiles;
        int converted;
        int expanded;
        int junk;
        /** Blocks it could not convert (or, checking, that are no 26.3 block state); the first thousand are in {@link #unresolved} */
        int failed;
        final List<String> unresolved = new ArrayList<>();

        void fail(final String what)
        {
            if (this.failed++ < 1000)
            {
                this.unresolved.add(what);
            }
        }

        String summary()
        {
            return "Files: " + this.files + " (" + this.changedFiles + " changed)\n"
                    + "Blocks converted: " + this.converted + "\n"
                    + "1.12 blocks checked for in all their 26.3 variants: " + this.expanded + "\n"
                    + "Numbers with no 1.12 block (air on 1.12): " + this.junk + "\n"
                    + "Not converted (left as they were): " + this.failed + "\n";
        }
    }

    private final Object2IntMap<String> idsByOldName;
    private final LegacyMaterials legacy = LegacyMaterials.get();
    final Report report = new Report();
    private String where = "";
    /** Whether this run checks converted configs instead of converting: each block must read as a 26.3 block state */
    private boolean checking;

    @SuppressWarnings("unchecked")
    ConfigConverter()
    {
        try
        {
            // 1.12's block names ("minecraft:stained_hardened_clay"), by the flattening table's id << 4 | data
            final Field field = BlockStateData.class.getDeclaredField("ID_BY_OLD_NAME");
            field.setAccessible(true);
            this.idsByOldName = (Object2IntMap<String>) field.get(null);
        } catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Cannot read 1.12's block names from Minecraft's flattening table", e);
        }
    }

    // ----- 1.12 blocks -----

    private int idOfName(final String name)
    {
        if (NUMBER.matcher(name).matches())
        {
            final int id = Integer.parseInt(name);
            return this.legacy.blocks(id).isEmpty() ? -2 : id;
        }
        final String full = name.indexOf(':') < 0 ? "minecraft:" + name : name;
        final int packed = this.idsByOldName.getOrDefault(full, -1);
        if (packed >= 0)
        {
            return packed >> 4;
        }
        // TerrainControl's own 1.12 names (STATIONARY_WATER, SMOOTH_BRICK)
        final DefaultMaterial material = DefaultMaterial.getMaterial(name);
        return material == DefaultMaterial.UNKNOWN_BLOCK ? -1 : material.id;
    }

    /**
     * Reads a 1.12 block the way 1.12's TerrainControl did: a block name, or a name or number followed by ":" or "." and a data value.
     *
     * @param token The block, as in the 1.12 configs.
     * @return The block, {@link Legacy#JUNK} for a number 1.12 had no block for, or null when it is no 1.12 block.
     */
    Legacy read(final String token)
    {
        final String name = token.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty())
        {
            return null;
        }
        int id = idOfName(name);
        if (id >= 0)
        {
            return new Legacy(id, -1);
        }
        if (id == -2)
        {
            return Legacy.JUNK;
        }
        int split = name.lastIndexOf(':');
        if (split < 0)
        {
            split = name.lastIndexOf('.');
        }
        if (split > 0 && NUMBER.matcher(name.substring(split + 1)).matches())
        {
            id = idOfName(name.substring(0, split));
            if (id >= 0)
            {
                return new Legacy(id, Integer.parseInt(name.substring(split + 1)) & 15);
            }
            if (id == -2)
            {
                return Legacy.JUNK;
            }
        }
        return null;
    }

    /** 1.12's skull block */
    private static final int SKULL = 144;

    private BlockState state(final int id, final int data)
    {
        return id == SKULL ? skull(data) : this.legacy.state(id, data);
    }

    /**
     * Gets a 1.12 skull's 26.3 block state. 1.12 kept a skull's kind in its block entity, so Minecraft's flattening table has no state
     * for it; the converted configs get skeleton skulls, 1.12's kind when a skull has no block entity, and the kind (SkullType 0) of
     * Almura's one skull NBT file (nbt/Misc/Skull.nbt). The data value gave where it hangs: 1 on the floor, 2 to 5 on a wall.
     *
     * @param data The data value.
     * @return The block state.
     */
    private static BlockState skull(final int data)
    {
        final String name = switch (data & 7)
        {
            case 2 -> "minecraft:skeleton_wall_skull[facing=north]";
            case 3 -> "minecraft:skeleton_wall_skull[facing=south]";
            case 4 -> "minecraft:skeleton_wall_skull[facing=west]";
            case 5 -> "minecraft:skeleton_wall_skull[facing=east]";
            default -> "minecraft:skeleton_skull";
        };
        try
        {
            return NeoForgeMaterialData.ofString(name).internalBlock();
        } catch (com.khorn.terraincontrol.exception.InvalidConfigException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static String name(final BlockState state)
    {
        return NeoForgeMaterialData.of(state).getName();
    }

    private static String name(final Block block)
    {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /**
     * Converts a block to place.
     *
     * @param token The 1.12 block.
     * @return The 26.3 block state; the token itself when it is no 1.12 block.
     */
    String single(final String token)
    {
        if (this.checking)
        {
            check(token);
            return token.trim();
        }
        final Legacy block = read(token);
        final BlockState state = block == null || block == Legacy.JUNK ? null : state(block.id(), Math.max(block.data(), 0));
        if (block == null || block != Legacy.JUNK && state == null)
        {
            this.report.fail(this.where + ": " + token.trim());
            return token.trim();
        }
        this.report.converted++;
        if (block == Legacy.JUNK)
        {
            this.report.junk++;
            return "minecraft:air";
        }
        return name(state);
    }

    /**
     * Converts a block to check for.
     *
     * @param token The 1.12 block.
     * @return The 26.3 blocks it matched (none for a number 1.12 had no block for); the token itself when it is no 1.12 block.
     */
    List<String> list(final String token)
    {
        if (SET_WORDS.contains(token.trim()))
        {
            return List.of(token.trim());
        }
        if (this.checking)
        {
            check(token);
            return List.of(token.trim());
        }
        final Legacy block = read(token);
        if (block == null || block != Legacy.JUNK && block.id() != SKULL && this.legacy.blocks(block.id()).isEmpty())
        {
            this.report.fail(this.where + ": " + token.trim());
            return List.of(token.trim());
        }
        this.report.converted++;
        if (block == Legacy.JUNK)
        {
            this.report.junk++;
            return List.of();
        }
        if (block.data() >= 0)
        {
            return List.of(name(state(block.id(), block.data()).getBlock()));
        }
        final Set<Block> blocks = block.id() == SKULL
                ? Set.of(skull(1).getBlock(), skull(2).getBlock())
                : this.legacy.blocks(block.id());
        if (blocks.size() > 1)
        {
            this.report.expanded++;
        }
        return blocks.stream().map(ConfigConverter::name).toList();
    }

    private void check(final String token)
    {
        this.report.converted++;
        try
        {
            NeoForgeMaterialData.ofString(token);
        } catch (com.khorn.terraincontrol.exception.InvalidConfigException e)
        {
            this.report.fail(this.where + ": " + e.getMessage());
        }
    }

    /**
     * Converts a list of blocks to check for.
     *
     * @param tokens The 1.12 blocks.
     * @return The 26.3 blocks, each once; air when nothing is left.
     */
    List<String> list(final List<String> tokens)
    {
        final Set<String> blocks = new LinkedHashSet<>();
        for (final String token : tokens)
        {
            blocks.addAll(list(token));
        }
        return blocks.isEmpty() ? List.of("minecraft:air") : List.copyOf(blocks);
    }

    // ----- Lines -----

    /**
     * Splits arguments at the commas outside of brackets, as StringHelper.readCommaSeperatedString does.
     *
     * @param arguments The arguments.
     * @return Each argument, trimmed.
     */
    static List<String> split(final String arguments)
    {
        final List<String> parts = new ArrayList<>();
        if (arguments.trim().isEmpty())
        {
            return parts;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < arguments.length(); i++)
        {
            final char c = arguments.charAt(i);
            if (c == '(' || c == '[')
            {
                depth++;
            } else if (c == ')' || c == ']')
            {
                depth--;
            } else if (c == ',' && depth == 0)
            {
                parts.add(arguments.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(arguments.substring(start).trim());
        return parts;
    }

    private static String separator(final String original)
    {
        return original.contains(", ") ? ", " : ",";
    }

    private String function(final String name, final String arguments)
    {
        final FunctionBlocks blocks = FUNCTIONS.get(name);
        if (blocks == null)
        {
            return null;
        }
        final List<String> args = split(arguments);
        final List<String> out = new ArrayList<>(args.size());
        if (blocks.randomBlocks())
        {
            // RandomBlock(x,y,z,block[,nbt file],chance,block[,nbt file],chance...)
            out.addAll(args.subList(0, Math.min(3, args.size())));
            int i = 3;
            while (i < args.size())
            {
                out.add(single(args.get(i++)));
                if (i < args.size() && !NUMBER.matcher(args.get(i)).matches())
                {
                    out.add(args.get(i++));
                }
                if (i < args.size())
                {
                    out.add(args.get(i++));
                }
            }
        } else
        {
            for (int i = 0; i < args.size(); i++)
            {
                final String arg = args.get(i);
                if (blocks.listFrom() >= 0 && i >= blocks.listFrom())
                {
                    out.addAll(list(args.subList(i, args.size())));
                    break;
                }
                if (i == 0 && blocks.plantFirst())
                {
                    out.add(isPlantType(arg) ? arg : single(arg));
                } else
                {
                    out.add(blocks.singles().contains(i) ? single(arg) : arg);
                }
            }
        }
        return name + "(" + String.join(separator(arguments), out) + ")";
    }

    private static boolean isPlantType(final String name)
    {
        return PlantType.values().stream().anyMatch(plant -> plant.getName().equalsIgnoreCase(name));
    }

    private String replacedBlocks(final String value)
    {
        if (value.isEmpty() || value.equalsIgnoreCase("None"))
        {
            return value;
        }
        final List<String> out = new ArrayList<>();
        for (final String instruction : split(value))
        {
            final int start = instruction.indexOf('(');
            final int end = instruction.lastIndexOf(')');
            if (start < 0 || end < start)
            {
                out.add(instruction);
                continue;
            }
            final List<String> parts = split(instruction.substring(start + 1, end));
            if (parts.size() < 2)
            {
                out.add(instruction);
                continue;
            }
            // The block replaced: on 1.12 any of its data values, so each 26.3 block it became
            final List<String> from = list(parts.get(0));
            final String to = single(parts.get(1));
            final String rest = parts.size() > 2 ? "," + String.join(",", parts.subList(2, parts.size())) : "";
            for (final String block : from)
            {
                out.add("(" + block + "," + to + rest + ")");
            }
        }
        return String.join(",", out);
    }

    private String surfaceAndGroundControl(final String value)
    {
        // Mesa, MesaForest, MesaBryce, or surface block, ground block, noise, ...
        final List<String> parts = split(value);
        if (parts.size() < 3)
        {
            return value;
        }
        final List<String> out = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++)
        {
            out.add(i % 3 == 2 ? parts.get(i) : single(parts.get(i)));
        }
        return String.join(separator(value), out);
    }

    private String setting(final String key, final String value)
    {
        if (SINGLE_SETTINGS.contains(key))
        {
            return value.isEmpty() ? value : single(value);
        }
        if (LIST_SETTINGS.contains(key))
        {
            return value.isEmpty() ? value : String.join(separator(value), list(split(value)));
        }
        if (key.equalsIgnoreCase("ReplacedBlocks"))
        {
            return replacedBlocks(value);
        }
        if (key.equalsIgnoreCase("SurfaceAndGroundControl"))
        {
            return surfaceAndGroundControl(value);
        }
        return null;
    }

    /**
     * Converts one line of a config.
     *
     * @param line The line, without its line break.
     * @param bo2  Whether it is from a BO2.
     * @return The converted line.
     */
    String line(final String line, final boolean bo2)
    {
        if (line.trim().startsWith("#"))
        {
            return line;
        }
        if (bo2)
        {
            final Matcher block = BO2_BLOCK.matcher(line);
            if (block.matches())
            {
                return block.group(1) + single(block.group(2)) + block.group(3);
            }
        } else
        {
            final Matcher function = FUNCTION.matcher(line);
            if (function.matches())
            {
                final String converted = function(function.group(2), function.group(3));
                return converted == null ? line : function.group(1) + converted + function.group(4);
            }
        }
        final Matcher setting = SETTING.matcher(line);
        if (setting.matches())
        {
            final String converted = setting(setting.group(2), setting.group(4));
            if (converted != null)
            {
                return setting.group(1) + setting.group(2) + setting.group(3) + converted + setting.group(5);
            }
        }
        return line;
    }

    // ----- Files -----

    /**
     * Checks a folder of converted configs: every block TerrainControl reads from them must be a 26.3 block state. Nothing is written.
     *
     * @param folder The converted configs.
     * @throws IOException When reading fails.
     */
    void check(final Path folder) throws IOException
    {
        this.checking = true;
        try (Stream<Path> files = Files.walk(folder))
        {
            for (final Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
            {
                if (isConfig(file.getFileName().toString()))
                {
                    this.report.files++;
                    file(folder.relativize(file).toString(), Files.readString(file, StandardCharsets.ISO_8859_1),
                            file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bo2"));
                }
            }
        }
    }

    private static boolean isConfig(final String fileName)
    {
        final String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bc") || lower.endsWith(".ini") || lower.endsWith(".bo3") || lower.endsWith(".bo2");
    }

    /**
     * Converts a folder of 1.12 configs (TerrainControl's folder, or part of it) into a new folder. Files that are no configs are
     * copied as they are.
     *
     * @param from The 1.12 configs.
     * @param to   Where the 26.3 configs go; it must not exist yet.
     * @throws IOException When reading or writing fails.
     */
    void convert(final Path from, final Path to) throws IOException
    {
        if (Files.exists(to))
        {
            throw new IOException(to + " already exists; the converter only writes to a new folder");
        }
        try (Stream<Path> files = Files.walk(from))
        {
            for (final Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
            {
                final Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                if (!isConfig(file.getFileName().toString()))
                {
                    Files.copy(file, target);
                    continue;
                }
                this.report.files++;
                // Latin-1 reads and writes any bytes as they are; the blocks are plain ASCII
                final String text = Files.readString(file, StandardCharsets.ISO_8859_1);
                final String converted = file(from.relativize(file).toString(), text,
                        file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bo2"));
                if (!converted.equals(text))
                {
                    this.report.changedFiles++;
                }
                Files.writeString(target, converted, StandardCharsets.ISO_8859_1);
            }
        } catch (UncheckedIOException e)
        {
            throw e.getCause();
        }
        Files.writeString(to.resolve("conversion-report.txt"),
                this.report.summary() + "\nNot converted:\n" + String.join("\n", this.report.unresolved) + "\n");
    }

    /**
     * Converts the text of one config, keeping its line breaks.
     *
     * @param name Its name, for the report.
     * @param text Its text.
     * @param bo2  Whether it is a BO2.
     * @return The converted text.
     */
    String file(final String name, final String text, final boolean bo2)
    {
        final StringBuilder out = new StringBuilder(text.length() + 64);
        int lineNumber = 0;
        int start = 0;
        while (start < text.length())
        {
            int end = text.indexOf('\n', start);
            end = end < 0 ? text.length() : end + 1;
            String line = text.substring(start, end);
            String lineBreak = "";
            if (line.endsWith("\r\n"))
            {
                lineBreak = "\r\n";
            } else if (line.endsWith("\n"))
            {
                lineBreak = "\n";
            }
            line = line.substring(0, line.length() - lineBreak.length());
            this.where = name + ":" + ++lineNumber;
            out.append(line(line, bo2)).append(lineBreak);
            start = end;
        }
        return out.toString();
    }
}
