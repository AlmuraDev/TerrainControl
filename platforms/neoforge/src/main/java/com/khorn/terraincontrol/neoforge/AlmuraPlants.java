package com.khorn.terraincontrol.neoforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Random;

/**
 * Almura's wild plants in TerrainControl's worlds, as Almura's 1.12 TerrainControl placed them: when population places tall grass or a
 * fern, in a temperate biome (0.5 to 0.95 where it stands) one in 75 becomes a random Almura crop, grown, and another one in 75 a
 * random Almura flower; in a snowy one (0.05 or less) one in 25 becomes an ice rose.
 *
 * <p>1.12's lists, as they were (some crops twice, so twice as likely), with Almura 4.0's names (its flowers moved from
 * horizontal/flower to flower). The blocks are looked up by name, so TerrainControl runs without Almura: a plant whose block is not
 * there stays tall grass.</p>
 */
final class AlmuraPlants
{
    /** A plant: its block, and for a crop how far grown (1.12's data value: its age) */
    private record Plant(String id, int age)
    {
        BlockState state()
        {
            final Identifier identifier = Identifier.tryParse(this.id);
            if (identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier))
            {
                return null;
            }
            final Block block = BuiltInRegistries.BLOCK.getValue(identifier);
            BlockState state = block.defaultBlockState();
            if (this.age > 0)
            {
                for (final Property<?> property : state.getProperties())
                {
                    if (property instanceof final IntegerProperty integer && property.getName().equals("age"))
                    {
                        final int max = integer.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                        state = state.setValue(integer, Math.min(this.age, max));
                    }
                }
            }
            return state;
        }
    }

    private static final List<Plant> CROPS = List.of(
            new Plant("almura:crop/agave", 5),
            new Plant("almura:crop/alfalfa", 3),
            new Plant("almura:crop/bambooshoot", 4),
            new Plant("almura:crop/barley", 3),
            new Plant("almura:crop/basil", 4),
            new Plant("almura:crop/bellpepper", 4),
            new Plant("almura:crop/blackberry", 4),
            new Plant("almura:crop/bellpepper", 4),
            new Plant("almura:crop/blackroot", 5),
            new Plant("almura:crop/blueberry", 4),
            new Plant("almura:crop/broccoli", 4),
            new Plant("almura:crop/butterbean", 4),
            new Plant("almura:crop/cabbage", 4),
            new Plant("almura:crop/celery", 4),
            new Plant("almura:crop/chilipepper", 4),
            new Plant("almura:crop/chive", 5),
            new Plant("almura:crop/cilantro", 5),
            new Plant("almura:crop/clove", 5),
            new Plant("almura:crop/coffee_bean", 4),
            new Plant("almura:crop/concord_grape", 3),
            new Plant("almura:crop/corn", 4),
            new Plant("almura:crop/cotton", 4),
            new Plant("almura:crop/cranberry", 4),
            new Plant("almura:crop/cucumber", 4),
            new Plant("almura:crop/cumin", 5),
            new Plant("almura:crop/daikon", 4),
            new Plant("almura:crop/dill", 5),
            new Plant("almura:crop/eggplant", 4),
            new Plant("almura:crop/fargreen", 4),
            new Plant("almura:crop/garlic", 4),
            new Plant("almura:crop/honeydew", 4),
            new Plant("almura:crop/hop", 5),
            new Plant("almura:crop/laurel", 5),
            new Plant("almura:crop/leek", 4),
            new Plant("almura:crop/lettuce", 4),
            new Plant("almura:crop/lotusroot", 4),
            new Plant("almura:crop/luffa", 4),
            new Plant("almura:crop/mint", 5),
            new Plant("almura:crop/oat", 3),
            new Plant("almura:crop/olive", 4),
            new Plant("almura:crop/onion", 4),
            new Plant("almura:crop/oregano", 5),
            new Plant("almura:crop/parsley", 5),
            new Plant("almura:crop/pea", 5),
            new Plant("almura:crop/peanut", 3),
            new Plant("almura:crop/peppercorn", 5),
            new Plant("almura:crop/pineapple", 3),
            new Plant("almura:crop/radish", 4),
            new Plant("almura:crop/raspberry", 4),
            new Plant("almura:crop/rice", 3),
            new Plant("almura:crop/rosemary", 5),
            new Plant("almura:crop/rye", 4),
            new Plant("almura:crop/sorghum", 5),
            new Plant("almura:crop/soybean", 4),
            new Plant("almura:crop/spinach", 4),
            new Plant("almura:crop/strawberry", 4),
            new Plant("almura:crop/sugarbeet", 5),
            new Plant("almura:crop/sweetpepper", 4),
            new Plant("almura:crop/sweetpotato", 4),
            new Plant("almura:crop/tarragon", 5),
            new Plant("almura:crop/thyme", 5),
            new Plant("almura:crop/tobacco", 4),
            new Plant("almura:crop/tomato", 4),
            new Plant("almura:crop/turnip", 4),
            new Plant("almura:crop/white_grape", 3),
            new Plant("almura:crop/cantaloupe", 5),
            new Plant("almura:crop/cinnamon", 5),
            new Plant("almura:crop/ginger_root", 5),
            new Plant("almura:crop/popcorn", 4),
            new Plant("almura:crop/sesame", 5),
            new Plant("almura:crop/sweetcorn", 4));

    private static final List<Plant> FLOWERS = List.of(
            new Plant("almura:flower/amaryllis", 0),
            new Plant("almura:flower/bladeshrub", 0),
            new Plant("almura:flower/bleedingheart", 0),
            new Plant("almura:flower/bluebells", 0),
            new Plant("almura:flower/bonsai", 0),
            new Plant("almura:flower/bud", 0),
            new Plant("almura:flower/carnation_blue", 0),
            new Plant("almura:flower/carnation_pink", 0),
            new Plant("almura:flower/carnation_purple", 0),
            new Plant("almura:flower/carnation_red", 0),
            new Plant("almura:flower/carnation_yellow", 0),
            new Plant("almura:flower/chrysanthemum", 0),
            new Plant("almura:flower/cosmos", 0),
            new Plant("almura:flower/cyprus", 0),
            new Plant("almura:flower/daffodil", 0),
            new Plant("almura:flower/fairystool", 0),
            new Plant("almura:flower/firehazel", 0),
            new Plant("almura:flower/foxglove", 0),
            new Plant("almura:flower/frostshroom", 0),
            new Plant("almura:flower/frozengrass", 0),
            new Plant("almura:flower/fuzzybrush", 0),
            new Plant("almura:flower/geranium", 0),
            new Plant("almura:flower/goldenlactarius", 0),
            new Plant("almura:flower/goldenrod", 0),
            new Plant("almura:flower/grandrussala", 0),
            new Plant("almura:flower/grayboletus", 0),
            new Plant("almura:flower/heartleaf", 0),
            new Plant("almura:flower/iris", 0),
            new Plant("almura:flower/kaiseragaric", 0),
            new Plant("almura:flower/kingstrumpet", 0),
            new Plant("almura:flower/leafyswamproot", 0),
            new Plant("almura:flower/lily", 0),
            new Plant("almura:flower/mandragora", 0),
            new Plant("almura:flower/meadowparasol", 0),
            new Plant("almura:flower/moneytree", 0),
            new Plant("almura:flower/mountainrose", 0),
            new Plant("almura:flower/mushboom", 0),
            new Plant("almura:flower/nightshade", 0),
            new Plant("almura:flower/orchid", 0),
            new Plant("almura:flower/palm", 0),
            new Plant("almura:flower/pampas_grass", 0),
            new Plant("almura:flower/poppy", 0),
            new Plant("almura:flower/rafflesia", 0),
            new Plant("almura:flower/redbud", 0),
            new Plant("almura:flower/rosebush", 0),
            new Plant("almura:flower/roundedtopiary", 0),
            new Plant("almura:flower/shitake", 0),
            new Plant("almura:flower/shore_grass", 0),
            new Plant("almura:flower/smallfern", 0),
            new Plant("almura:flower/snowdrops", 0),
            new Plant("almura:flower/snowfern", 0),
            new Plant("almura:flower/stoneshroom", 0),
            new Plant("almura:flower/sunflower", 0),
            new Plant("almura:flower/sunnyside", 0),
            new Plant("almura:flower/tallfern", 0),
            new Plant("almura:flower/tallplainsgrass", 0),
            new Plant("almura:flower/thistle", 0),
            new Plant("almura:flower/timidpincussion", 0),
            new Plant("almura:flower/tropicalgrass", 0),
            new Plant("almura:flower/tulip", 0),
            new Plant("almura:flower/violets", 0),
            new Plant("almura:flower/whitecaps", 0),
            new Plant("almura:flower/wildflower_blue", 0),
            new Plant("almura:flower/wildflower_lightblue", 0),
            new Plant("almura:flower/wildflower_pink", 0),
            new Plant("almura:flower/wildflower_purple", 0),
            new Plant("almura:flower/wildflower_red", 0),
            new Plant("almura:flower/wildflower_white", 0),
            new Plant("almura:flower/wildflower_yellow", 0),
            new Plant("almura:flower/witherymorel", 0),
            new Plant("almura:flower/woodears", 0),
            new Plant("almura:flower/zenset", 0),
            new Plant("almura:flower/flower_bonsai", 0),
            new Plant("almura:flower/flower_daisy", 0),
            new Plant("almura:flower/flower_hydrangea", 0),
            new Plant("almura:flower/flower_lotus", 0),
            new Plant("almura:flower/flower_peony", 0));

    private static final List<Plant> ICE_FLOWERS = List.of(
            new Plant("almura:flower/icerose", 0));

    private AlmuraPlants()
    {
    }

    /**
     * Gets what population places for a block: the block, or for tall grass or a fern now and then one of Almura's plants.
     *
     * @param state       What population would place.
     * @param temperature The temperature where it goes.
     * @param random      The random.
     * @return What to place.
     */
    static BlockState wild(final BlockState state, final float temperature, final Random random)
    {
        if (!state.is(Blocks.SHORT_GRASS) && !state.is(Blocks.FERN))
        {
            return state;
        }
        Plant plant = null;
        if (temperature <= 0.05F)
        {
            // Snow
            if (random.nextInt(25) == 0)
            {
                plant = ICE_FLOWERS.get(random.nextInt(ICE_FLOWERS.size()));
            }
        } else if (temperature >= 0.5F && temperature <= 0.95F)
        {
            final int chance = random.nextInt(75);
            if (chance == 0)
            {
                plant = CROPS.get(random.nextInt(CROPS.size()));
            } else if (chance == 1)
            {
                plant = FLOWERS.get(random.nextInt(FLOWERS.size()));
            }
        }
        final BlockState wild = plant == null ? null : plant.state();
        return wild == null ? state : wild;
    }
}
