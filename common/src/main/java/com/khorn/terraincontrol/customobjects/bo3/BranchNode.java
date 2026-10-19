package com.khorn.terraincontrol.customobjects.bo3;


import com.khorn.terraincontrol.customobjects.CustomObject;
import com.khorn.terraincontrol.util.Rotation;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple class to hold the spawn chance and rotation of a BO3 in the Branch or
 * WeightedBranch function in the BO3 file.
 */
public class BranchNode implements Comparable<BranchNode>
{

    /**
     * The rotation of a branch
     */
    private Rotation rotation;
    /**
     * The chance of a branch
     */
    private double chance;
    /**
     * The branch with associated rotation and chance values
     */
    private CustomObject customObject;
    /**
     * Tie-breaker for nodes with equal chances, see {@link #compareTo(BranchNode)}
     */
    private final long creationOrder = NEXT_CREATION_ORDER.getAndIncrement();
    private static final AtomicLong NEXT_CREATION_ORDER = new AtomicLong();

    /**
     * Creates an instance of BranchNode with given rotation, chance, and branch
     * fields
     * <p/>
     * 
     * @param rotation The rotation of the branch
     * @param chance The spawn chance of the branch
     * @param branch The branch
     */
    public BranchNode(Rotation rotation, double chance, CustomObject branch)
    {
        this.rotation = rotation;
        this.chance = chance;
        this.customObject = branch;
    }

    /**
     * @return the spawn chance of the branch
     */
    public double getChance()
    {
        return chance;
    }

    /**
     * @return the rotation object associated with the branch
     */
    public Rotation getRotation()
    {
        return rotation;
    }

    /**
     * @return the branch CustomObject
     */
    public CustomObject getCustomObject()
    {
        return customObject;
    }

    /**
     * @return The string representation of this branch as seen in BO3 configs
     */
    public String toBranchString()
    {
        return ',' + customObject.getName() + ',' + rotation.name() + ',' + chance;
    }

    @Override
    public int compareTo(BranchNode that)
    {
        // Branches live in a TreeSet, which drops anything that compares as equal. Equal chances (or
        // ones less than 1 apart, with the old int cast) must not count as duplicates, so fall back to
        // the order they were created in, which is the order in the BO3 file.
        int byChance = Double.compare(this.chance, that.chance);
        if (byChance != 0)
        {
            return byChance;
        }
        return Long.compare(this.creationOrder, that.creationOrder);
    }

}
