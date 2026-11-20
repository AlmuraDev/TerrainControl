package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.neoforge.TerrainControlMod;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.common.world.ModifiableStructureInfo;
import net.neoforged.neoforge.common.world.StructureModifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * TerrainControl's biomes in the biomes of the structures TerrainControl places, so 26.3's /locate finds them.
 *
 * <p>TerrainControl decides itself where its structures start ({@link TCStructures}), but 26.3's /locate (and its other
 * structure searches, such as explorer maps) first leaves out every structure none of whose biomes (26.3's biome tags) a world
 * has, and TerrainControl's own biomes are in none of them: in a TerrainControl world it found villages only near 26.3's own
 * biomes, and ancient cities, trial chambers and the rest never. With TerrainControl's biomes added (a NeoForge structure
 * modifier, {@code terraincontrol:biomes_for_structures}, data/terraincontrol/neoforge/structure_modifier), 26.3 looks at them,
 * and for a chunk not generated yet makes its structure starts, which are TerrainControl's say: it finds what is there, and
 * nothing that is not. The structures' own biomes stay as they were for what follows them (TCStructures reads the originals).</p>
 */
public final class TCStructureBiomes implements StructureModifier
{
    public static final MapCodec<TCStructureBiomes> CODEC = MapCodec.unit(TCStructureBiomes::new);

    /** TerrainControl's biomes, by the server's registries */
    private static final Map<RegistryAccess, List<Holder<Biome>>> BIOMES = new WeakHashMap<>();

    @Override
    public void modify(final RegistryAccess registries, final Holder<Structure> structure, final Phase phase,
            final ModifiableStructureInfo.StructureInfo.Builder builder)
    {
        if (phase != Phase.ADD || structure.unwrapKey().map(key -> !TCStructures.places(key)).orElse(true))
        {
            return;
        }
        final List<Holder<Biome>> terrainControl;
        synchronized (BIOMES)
        {
            terrainControl = BIOMES.computeIfAbsent(registries, r -> {
                final List<Holder<Biome>> biomes = new ArrayList<>();
                r.lookupOrThrow(Registries.BIOME).listElements()
                        .filter(biome -> biome.key().identifier().getNamespace().equals(TerrainControlMod.ID))
                        .forEach(biomes::add);
                return biomes;
            });
        }
        if (terrainControl.isEmpty())
        {
            return;
        }
        final List<Holder<Biome>> biomes = new ArrayList<>(builder.getStructureSettings().getBiomes().stream().toList());
        biomes.addAll(terrainControl);
        builder.getStructureSettings().setBiomes(HolderSet.direct(biomes));
    }

    @Override
    public MapCodec<? extends StructureModifier> codec()
    {
        return CODEC;
    }
}
