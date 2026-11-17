package at.esoren.customtrees;

import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Grafting reuses GrowthSapling profiles and the existing persistent planting/Core generation pipeline. */
final class SaplingBreeding {
    static final NamespacedKey GENERATION=new NamespacedKey("customtrees","breeding_generation");
    static final NamespacedKey COOLDOWN=new NamespacedKey("customtrees","breeding_ready");
    private final CustomTreesPlugin plugin;
    SaplingBreeding(CustomTreesPlugin plugin){this.plugin=plugin;}
    static GrowthSapling.Spec inherit(GrowthSapling.Spec first,GrowthSapling.Spec second,RandomGenerator random,double mutation) {
        if(first.species()!=second.species())throw new IllegalArgumentException("Parents must have the same species");
        var crown=random.nextBoolean()?first.crownForm():second.crownForm();
        var size=random.nextBoolean()?first.growthClass():second.growthClass();
        // At most one bounded mutation, keeping every offspring within the existing four growth classes.
        if(random.nextDouble()<mutation) {
            if(random.nextBoolean()) {
                var forms=GrowthSapling.CrownForm.values();crown=forms[random.nextInt(forms.length)];
            } else {
                var sizes=GrowthSapling.GrowthClass.values();size=sizes[Math.clamp(size.ordinal()+(random.nextBoolean()?1:-1),0,sizes.length-1)];
            }
        }
        return new GrowthSapling.Spec(first.species(),crown,size);
    }
    static int generation(ItemStack item) {
        Integer generation=item.getItemMeta().getPersistentDataContainer().get(GENERATION,PersistentDataType.INTEGER);
        if(generation!=null && (generation<0||generation>=1000000))throw new IllegalArgumentException("Invalid breeding generation");
        return generation==null?0:generation;
    }
    void breed(Player player) {
        if(!player.hasPermission("customtrees.breed") || !plugin.getConfig().getBoolean("breeding.enabled",true)) {plugin.message(player,"breeding.unavailable");return;}
        if(player.getGameMode()!=GameMode.SURVIVAL && player.getGameMode()!=GameMode.ADVENTURE){plugin.message(player,"breeding.survival");return;}
        long now=System.currentTimeMillis();
        long ready=player.getPersistentDataContainer().getOrDefault(COOLDOWN,PersistentDataType.LONG,0L);
        if(now<ready){plugin.message(player,"breeding.cooldown","seconds",(ready-now+999)/1000);return;}
        ItemStack main=player.getInventory().getItemInMainHand().clone(),off=player.getInventory().getItemInOffHand().clone();
        var first=GrowthSapling.read(main);var second=GrowthSapling.read(off);
        if(main.getAmount()!=1 || off.getAmount()!=1 || first.isEmpty() || second.isEmpty() || first.get().species()!=second.get().species()) {plugin.message(player,"breeding.parents");return;}
        try {
            int generation=Math.max(generation(main),generation(off))+1;
            double chance=plugin.getConfig().getDouble("breeding.mutation-chance",0.1);
            chance=Double.isFinite(chance)?Math.clamp(chance,0,0.25):0;
            var child=inherit(first.get(),second.get(),java.util.concurrent.ThreadLocalRandom.current(),chance);
            var lang=plugin.lang();String language=lang.language(player);
            ItemStack item=GrowthSapling.create(plugin.core().compat().platform(),lang,language,child.species(),child.crownForm(),child.growthClass());
            var meta=item.getItemMeta();meta.getPersistentDataContainer().set(GENERATION,PersistentDataType.INTEGER,generation);
            var lore=new ArrayList<>(Optional.ofNullable(plugin.core().compat().platform().lore(meta)).orElse(List.of()));
            lore.add(lang.component(player,"breeding.generation","generation",generation));plugin.core().compat().platform().lore(meta,lore);item.setItemMeta(meta);
            SaplingBreedEvent event=new SaplingBreedEvent(player,main,off,item);Bukkit.getPluginManager().callEvent(event);
            if(event.isCancelled()){plugin.message(player,"breeding.cancelled");return;}
            // An event listener may have changed hands, permissions or the player lifecycle.
            if(!player.isOnline() || player.isDead() || !player.hasPermission("customtrees.breed")
                    || (player.getGameMode()!=GameMode.SURVIVAL && player.getGameMode()!=GameMode.ADVENTURE)
                    || !main.equals(player.getInventory().getItemInMainHand()) || !off.equals(player.getInventory().getItemInOffHand())) {plugin.message(player,"breeding.cancelled");return;}
            long until=now+Math.clamp(plugin.getConfig().getLong("breeding.cooldown-seconds",30),0,3600)*1000;
            try {
                player.getInventory().setItemInMainHand(item);player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                player.getPersistentDataContainer().set(COOLDOWN,PersistentDataType.LONG,until);player.saveData();
            } catch(RuntimeException failure) {
                player.getInventory().setItemInMainHand(main);player.getInventory().setItemInOffHand(off);
                player.getPersistentDataContainer().set(COOLDOWN,PersistentDataType.LONG,ready);throw failure;
            }
            plugin.message(player,"breeding.success","generation",generation);
        }catch(RuntimeException failure){plugin.message(player,"breeding.unavailable");plugin.getLogger().warning("Sapling breeding refused safely: "+failure.getMessage());}
    }
}
