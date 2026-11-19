package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.configuration.WeightedMobSpawnGroup;
import com.khorn.terraincontrol.logging.LogMarker;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Other mods' mobs in TerrainControl's biomes, as their configs list them (Mo' Creatures': {@code mocreatures.deer}).
 *
 * <p>The exported biomes (the terraincontrol-worlds datapack) have 26.3's own mobs only: a biome naming a mob no mod registered
 * would not load. So the mods' mobs are added here, as the server starts, to those of the mods it has: a NeoForge biome modifier
 * ({@code terraincontrol:mod_mobs}, data/terraincontrol/neoforge/biome_modifier/mod_mobs.json) that for each of TerrainControl's
 * biomes reads its config's spawn lists. A mob the server does not have is left out, with one line in the log.</p>
 *
 * <p>1.12's names were {@code <mod>.<mob>}; 26.3's are {@code <mod>:<mob>}. Mo' Creatures' scorpion became one of each kind; the
 * wild ones here are 1.12's: a frost scorpion where it snows, otherwise a dirt scorpion.</p>
 */
final class ModMobs implements BiomeModifier
{
    static final MapCodec<ModMobs> CODEC = MapCodec.unit(ModMobs::new);

    /** The mobs left out, each logged once */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    @Override
    public void modify(final RegistryAccess registryAccess, final Holder<Biome> biome, final Phase phase,
            final ModifiableBiomeInfo.BiomeInfo.Builder builder)
    {
        if (phase != Phase.ADD)
        {
            return;
        }
        final Optional<ResourceKey<Biome>> key = biome.unwrapKey();
        if (key.isEmpty() || !key.get().identifier().getNamespace().equals(TerrainControlMod.ID))
        {
            return;
        }
        final BiomeConfig config = config(registryAccess, biome, key.get());
        if (config == null)
        {
            return;
        }
        add(builder, MobCategory.MONSTER, config.spawnMonsters, config);
        add(builder, MobCategory.CREATURE, config.spawnCreatures, config);
        add(builder, MobCategory.WATER_CREATURE, config.spawnWaterCreatures, config);
        add(builder, MobCategory.AMBIENT, config.spawnAmbientCreatures, config);
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec()
    {
        return CODEC;
    }

    /** The config of one of TerrainControl's biomes, {@code terraincontrol:<world>/<biome>}: from its world's configs */
    private static BiomeConfig config(final RegistryAccess registryAccess, final Holder<Biome> biome, final ResourceKey<Biome> key)
    {
        final String path = key.identifier().getPath();
        final int slash = path.indexOf('/');
        if (slash < 0)
        {
            return null;
        }
        final NeoForgeEngine engine = NeoForgeEngine.get();
        final String worldName = engine.worldFolder(path.substring(0, slash));
        if (worldName == null)
        {
            return null;
        }
        try
        {
            return engine.loadWorld(worldName, registryAccess.lookupOrThrow(Registries.BIOME)).getBiomeConfig(biome);
        } catch (final RuntimeException e)
        {
            TerrainControl.log(LogMarker.WARN, "No mod mobs for {}: its world's configs did not load ({})", key.identifier(), e.toString());
            return null;
        }
    }

    private static void add(final ModifiableBiomeInfo.BiomeInfo.Builder builder, final MobCategory category,
            final List<WeightedMobSpawnGroup> groups, final BiomeConfig config)
    {
        for (final WeightedMobSpawnGroup group : groups)
        {
            final String name = group.getInternalName().toLowerCase(Locale.ROOT);
            final Identifier id = modMob(name, config);
            if (id == null || group.getWeight() <= 0)
            {
                // 26.3's own mobs, which the exported biomes have already
                continue;
            }
            final Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (type.isEmpty())
            {
                if (MISSING.add(id.toString()))
                {
                    TerrainControl.log(LogMarker.INFO, "Biome configs spawn {}, which this server does not have: left out", id);
                }
                continue;
            }
            final int min = Math.max(1, group.getMin());
            builder.getMobSpawnSettings().addSpawn(type.get(), category, group.getWeight(), UniformInt.of(min, Math.max(min, group.getMax())));
        }
    }

    /**
     * Gets the 26.3 id of a mod's mob as a biome config names it.
     *
     * @param name   The name in the config, lower case: {@code mocreatures.deer} (1.12) or {@code mocreatures:deer}.
     * @param config The biome's config.
     * @return The id; null for 26.3's own mobs (no mod named).
     */
    static Identifier modMob(final String name, final BiomeConfig config)
    {
        final int dot = name.indexOf(':') >= 0 ? name.indexOf(':') : name.indexOf('.');
        if (dot <= 0 || dot == name.length() - 1)
        {
            return null;
        }
        final String mod = name.substring(0, dot);
        String mob = name.substring(dot + 1);
        if (mod.equals("minecraft"))
        {
            return null;
        }
        if (mod.equals("mocreatures") && mob.equals("scorpion"))
        {
            // 1.12's scorpion was a frost scorpion in the snow, a dirt scorpion elsewhere (a cave or fire one underground and in
            // the Nether, which TerrainControl's worlds do not spawn by biome)
            mob = config.biomeTemperature < 0.15F ? "frostscorpion" : "dirtscorpion";
        }
        return Identifier.tryParse(mod + ":" + mob);
    }
}
