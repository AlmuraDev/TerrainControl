package com.khorn.terraincontrol.configuration.settingType;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.exception.InvalidConfigException;
import com.khorn.terraincontrol.util.MaterialSet;
import com.khorn.terraincontrol.util.MaterialSetEntry;
import com.khorn.terraincontrol.util.helpers.StringHelper;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;

/**
 * Reads and writes a set of materials, used for matching.
 *
 * <p>Materials are separated using a comma and, optionally, whitespace. Each
 * material is stripped from its whitespace and read using
 * {@link MaterialSet#parseAndAdd(String)}.
 *
 */
class MaterialSetSetting extends Setting<MaterialSet>
{
    private final DefaultMaterial[] defaultMaterials;
    private final String[] defaultValues;

    MaterialSetSetting(String name, DefaultMaterial... defaultMaterials)
    {
        super(name);
        this.defaultMaterials = defaultMaterials;
        this.defaultValues = new String[0];
    }

    public MaterialSetSetting(String name, String... defaultValues)
    {
        super(name);
        this.defaultMaterials = new DefaultMaterial[0];
        this.defaultValues = defaultValues;
    }

    @Override
    public MaterialSet getDefaultValue()
    {
        try
        {
            MaterialSet blocks = new MaterialSet();
            // 1.12 blocks, as TerrainControl's code names them: a 1.12 block matched any of its data values, so each block it became
            // (DIRT: dirt, coarse dirt and podzol)
            for (DefaultMaterial material : defaultMaterials)
            {
                for (int data = 0; data < 16; data++)
                {
                    LocalMaterialData block = TerrainControl.toLocalMaterialData(material, data).withDefaultBlockData();
                    blocks.add(new MaterialSetEntry(block, false));
                }
            }
            // Words like "All"
            for (String blockName : defaultValues)
            {
                blocks.parseAndAdd(blockName);
            }
            return blocks;
        } catch (InvalidConfigException e)
        {
            throw new AssertionError(e);
        }
    }

    @Override
    public MaterialSet read(String string) throws InvalidConfigException
    {
        MaterialSet blocks = new MaterialSet();

        for (String blockName : StringHelper.readCommaSeperatedString(string))
        {
            blocks.parseAndAdd(blockName);
        }

        return blocks;
    }

}
