package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The 1.12 blocks ({@link DefaultMaterial} with a data value) as 26.3 block states.
 *
 * <p>TerrainControl's code still asks for 1.12 blocks in places: orange stained clay (data 1) in mesas, snow of a given height, vines
 * facing a given way. This table answers it the way Minecraft upgrades a 1.12 world: each id and data value goes through the 1.13
 * flattening table ({@link BlockStateData}), then through the data fixer up to 26.3, so renames since 1.13 (grass becoming
 * short_grass) are followed too.</p>
 *
 * <p>It is for TerrainControl's code only. The configs are converted to 26.3 block states before they are used, and are read by
 * {@link NeoForgeMaterialData#ofString(String)}, which knows nothing of 1.12 names.</p>
 */
final class LegacyMaterials
{
    /** The data version of {@link BlockStateData}'s states: 1.13's flattening. */
    private static final int FLATTENING_DATA_VERSION = 1451;

    private static volatile LegacyMaterials instance;

    /**
     * Gets the table, made the first time it is asked for (after the blocks are registered).
     *
     * @return The table.
     */
    static LegacyMaterials get()
    {
        LegacyMaterials table = instance;
        if (table == null)
        {
            synchronized (LegacyMaterials.class)
            {
                table = instance;
                if (table == null)
                {
                    table = new LegacyMaterials();
                    instance = table;
                }
            }
        }
        return table;
    }

    /** By {@code id << 4 | data}; null where 1.12 had no block of that id. */
    private final BlockState[] states = new BlockState[256 * 16];
    /** The 1.12 block each 26.3 block came from, the lowest id when several did (water for flowing and still water). */
    private final Map<Block, DefaultMaterial> materialOfBlock = new IdentityHashMap<>();
    /** The 26.3 blocks each 1.12 block id became: wool's sixteen colours, for one. */
    private final List<Set<Block>> blocksOfId = new ArrayList<>(256);

    private LegacyMaterials()
    {
        final DataFixer fixer = DataFixers.getDataFixer();
        final int currentDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        for (int id = 0; id < 256; id++)
        {
            if (id != 0 && "minecraft:air".equals(BlockStateData.upgradeBlock(id << 4)))
            {
                // 1.12 had no block with this id
                continue;
            }
            for (int data = 0; data < 16; data++)
            {
                final BlockState state = upgrade(fixer, BlockStateData.getTag(id << 4 | data), currentDataVersion);
                if (state != null)
                {
                    this.states[id << 4 | data] = state;
                }
            }
        }

        for (int id = 0; id < 256; id++)
        {
            // In data order, so a 1.12 block's first variant comes first
            final Set<Block> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
            final List<Block> ordered = new ArrayList<>();
            for (int data = 0; data < 16; data++)
            {
                final BlockState state = this.states[id << 4 | data];
                if (state != null && blocks.add(state.getBlock()))
                {
                    ordered.add(state.getBlock());
                }
            }
            this.blocksOfId.add(Collections.unmodifiableSet(new LinkedHashSet<>(ordered)));
        }
        // Lowest id first, so a block that came from several 1.12 blocks takes the first
        for (int id = 0; id < 256; id++)
        {
            final DefaultMaterial material = DefaultMaterial.getMaterial(id);
            if (material == DefaultMaterial.UNKNOWN_BLOCK)
            {
                continue;
            }
            for (final Block block : this.blocksOfId.get(id))
            {
                this.materialOfBlock.putIfAbsent(block, material);
            }
        }
    }

    private static <T> BlockState upgrade(final DataFixer fixer, final Dynamic<T> flattened, final int currentDataVersion)
    {
        final Dynamic<T> upgraded = fixer.update(References.BLOCK_STATE, flattened, FLATTENING_DATA_VERSION, currentDataVersion);
        if (!(upgraded.convert(NbtOps.INSTANCE).getValue() instanceof final CompoundTag tag))
        {
            return null;
        }
        // 26.3 names the block "id" (1.13's flattening table, before the fixer, called it "Name")
        final Identifier name = Identifier.tryParse(tag.getStringOr("id", ""));
        if (name == null || !BuiltInRegistries.BLOCK.containsKey(name))
        {
            // Without this check readBlockState would quietly give air
            return null;
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
    }

    /**
     * Gets the 26.3 block state of a 1.12 block and data value.
     *
     * @param material The 1.12 block.
     * @param data     Its data value, 0 to 15 (higher bits are dropped, as 1.12 did).
     * @return The block state.
     * @throws IllegalArgumentException When 1.12 had no such block.
     */
    BlockState state(final DefaultMaterial material, final int data)
    {
        final BlockState state = state(material.id, data);
        if (state == null)
        {
            throw new IllegalArgumentException("No 26.3 block for the 1.12 block " + material + " with data " + data);
        }
        return state;
    }

    /**
     * Gets the 26.3 block state of a 1.12 block id and data value.
     *
     * @param id   The 1.12 block id.
     * @param data Its data value, 0 to 15 (higher bits are dropped, as 1.12 did).
     * @return The block state, or null when 1.12 had no block with that id.
     */
    BlockState state(final int id, final int data)
    {
        return id >= 0 && id < 256 ? this.states[id << 4 | (data & 15)] : null;
    }

    /**
     * Gets the 26.3 blocks a 1.12 block id became, in the order of its data values.
     *
     * @param id The 1.12 block id.
     * @return The blocks; empty when 1.12 had no block with that id.
     */
    Set<Block> blocks(final int id)
    {
        return id >= 0 && id < 256 ? this.blocksOfId.get(id) : Set.of();
    }

    /**
     * Gets the 1.12 block that a 26.3 block came from.
     *
     * @param block The 26.3 block.
     * @return The 1.12 block, or {@link DefaultMaterial#UNKNOWN_BLOCK} for blocks 1.12 did not have (deepslate, a mod's blocks).
     */
    DefaultMaterial materialOf(final Block block)
    {
        return this.materialOfBlock.getOrDefault(block, DefaultMaterial.UNKNOWN_BLOCK);
    }

    /**
     * Gets whether a 26.3 block is one of those a 1.12 block became.
     *
     * @param material The 1.12 block.
     * @param block    The 26.3 block.
     * @return Whether it is.
     */
    boolean isOf(final DefaultMaterial material, final Block block)
    {
        return blocks(material.id).contains(block);
    }
}
