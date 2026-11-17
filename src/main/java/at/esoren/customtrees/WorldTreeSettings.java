package at.esoren.customtrees;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

record WorldTreeSettings(boolean enabled, int treeHeight, int mushroomHeight, int netherHeight,
        int treeRadius, int mushroomRadius, int netherRadius, int blocksPerTick, Map<Integer,Dimensions> fungi,
        boolean replaceStone, int maxSkippedPercent) {
    record Dimensions(int mushroomHeight,int mushroomRadius,int netherHeight,int netherRadius) {}
    static WorldTreeSettings load(FileConfiguration config) {
        Map<Integer,Dimensions> sizes=new HashMap<>();
        int[][] defaults={{1,8,3,8,3},{2,16,5,14,5},{3,28,9,24,8},{5,44,15,36,12}};
        for(int[] size:defaults) {
            String prefix="fungi."+size[0]+"x"+size[0]+".";
            sizes.put(size[0],new Dimensions(value(config,prefix+"mushroom-height",size[1],6,192),
                    value(config,prefix+"mushroom-radius",size[2],2,40),
                    value(config,prefix+"nether-height",size[3],6,192),value(config,prefix+"nether-radius",size[4],2,40)));
        }
        return new WorldTreeSettings(config.getBoolean("world-trees.enabled", true),
                value(config,"tree-height",160,32,192), value(config,"mushroom-height",80,32,192),
                value(config,"nether-height",48,32,192), value(config,"tree-radius",44,12,48),
                value(config,"mushroom-radius",24,12,40), value(config,"nether-radius",18,12,40),
                value(config,"blocks-per-tick",512,64,4096),Map.copyOf(sizes),
                config.getBoolean("world-trees.replace-stone", false), value(config,"max-skipped-percent",100,0,100));
    }
    private static int value(FileConfiguration config, String key, int fallback, int min, int max) {
        Object raw = config.get("world-trees." + key, fallback);
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < min || number.intValue() > max)
            throw new TreeSettingsException("world-tree.invalid-setting", "setting", key, "min", min, "max", max);
        return number.intValue();
    }
    int height(Material plant, World.Environment environment) {
        if (environment == World.Environment.NETHER || fungus(plant)) return netherHeight;
        return mushroom(plant) ? mushroomHeight : treeHeight;
    }
    int radius(Material plant) { return fungus(plant) ? netherRadius : mushroom(plant) ? mushroomRadius : treeRadius; }
    int height(Material plant,World.Environment environment,int width) {
        if(width==10)return height(plant,environment);
        Dimensions size=fungi.get(width);
        return fungus(plant) || environment==World.Environment.NETHER?size.netherHeight():size.mushroomHeight();
    }
    int radius(Material plant,int width) {
        if(width==10)return radius(plant);
        Dimensions size=fungi.get(width);
        return fungus(plant)?size.netherRadius():size.mushroomRadius();
    }
    private static boolean mushroom(Material plant) { return plant == Material.RED_MUSHROOM || plant == Material.BROWN_MUSHROOM; }
    private static boolean fungus(Material plant) { return plant == Material.CRIMSON_FUNGUS || plant == Material.WARPED_FUNGUS; }
}
