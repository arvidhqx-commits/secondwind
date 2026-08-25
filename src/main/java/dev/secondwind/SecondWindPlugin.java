package dev.secondwind;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SecondWindPlugin extends JavaPlugin implements Listener {

    private static final class Downed {
        long bleedOutAt;
        UUID rescuer;
        long reviveStartedAt;
    }

    private final Map<UUID, Downed> downed = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 10L, 10L);
        getLogger().info("SecondWind " + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        // Never leave anyone stuck in the downed state across restarts.
        for (UUID id : downed.keySet().toArray(new UUID[0])) {
            Player p = getServer().getPlayer(id);
            if (p != null) revive(p, null);
        }
        downed.clear();
    }

    private boolean disabledIn(Player p) {
        return getConfig().getStringList("disabled-worlds").stream()
                .anyMatch(w -> w.equalsIgnoreCase(p.getWorld().getName()));
    }

    private String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    // ------------------------------------------------------------------ down

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (disabledIn(player)) return;

        Downed state = downed.get(player.getUniqueId());
        if (state != null) {
            // While downed: block all damage except an execute hit.
            boolean execute = getConfig().getBoolean("allow-execute", true)
                    && event instanceof EntityDamageByEntityEvent byEntity
                    && attacker(byEntity) != null;
            if (execute) {
                bleedOut(player, true);
            }
            event.setCancelled(true);
            return;
        }

        // Void, suicide and world border kill outright — no downed state there.
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID
                || cause == EntityDamageEvent.DamageCause.SUICIDE
                || cause == EntityDamageEvent.DamageCause.WORLD_BORDER
                || cause == EntityDamageEvent.DamageCause.KILL) {
            return;
        }

        if (player.getHealth() - event.getFinalDamage() > 0) return;

        // Would be lethal: down instead of death.
        event.setCancelled(true);
        down(player);
    }

    private Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    private void down(Player player) {
        Downed state = new Downed();
        state.bleedOutAt = System.currentTimeMillis()
                + getConfig().getLong("bleed-seconds", 30) * 1000L;
        downed.put(player.getUniqueId(), state);

        player.setHealth(Math.min(getConfig().getDouble("downed-health", 4.0), maxHealth(player)));
        player.setFoodLevel(Math.min(player.getFoodLevel(), 6));
        int bleedTicks = (int) (getConfig().getLong("bleed-seconds", 30) * 20 + 100);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, bleedTicks, 4, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, bleedTicks, 2, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, bleedTicks, 1, false, false));
        if (getConfig().getBoolean("glowing", true)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, bleedTicks, 0, false, false));
        }
        player.showTitle(Title.title(
                Msg.parse(msg("downed-title")),
                Msg.parse(msg("downed-subtitle").replace("{seconds}",
                        String.valueOf(getConfig().getLong("bleed-seconds", 30))))));
    }

    private double maxHealth(Player player) {
        try {
            var attr = player.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) return attr.getValue();
        } catch (Throwable ignored) {
        }
        return 20.0;
    }

    // ---------------------------------------------------------------- revive

    private void tick() {
        long now = System.currentTimeMillis();
        long reviveMillis = getConfig().getLong("revive-seconds", 4) * 1000L;
        double range = getConfig().getDouble("revive-range", 3.0);

        Iterator<Map.Entry<UUID, Downed>> it = downed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Downed> entry = it.next();
            Player victim = getServer().getPlayer(entry.getKey());
            Downed state = entry.getValue();
            if (victim == null || !victim.isOnline() || victim.isDead()) {
                it.remove();
                continue;
            }
            if (state.bleedOutAt < now) {
                it.remove();
                bleedOut(victim, false);
                continue;
            }

            // Find a sneaking rescuer in range.
            Player rescuer = null;
            for (Player near : victim.getWorld().getPlayers()) {
                if (near.equals(victim) || !near.isSneaking()) continue;
                if (!near.hasPermission("secondwind.revive")) continue;
                if (downed.containsKey(near.getUniqueId())) continue;
                if (near.getLocation().distanceSquared(victim.getLocation()) <= range * range) {
                    rescuer = near;
                    break;
                }
            }

            long secondsLeft = Math.max(0, (state.bleedOutAt - now) / 1000);
            if (rescuer == null) {
                state.rescuer = null;
                victim.sendActionBar(Msg.parse(msg("downed-subtitle")
                        .replace("{seconds}", String.valueOf(secondsLeft))));
                continue;
            }

            if (state.rescuer == null || !state.rescuer.equals(rescuer.getUniqueId())) {
                state.rescuer = rescuer.getUniqueId();
                state.reviveStartedAt = now;
            }
            int progress = (int) Math.min(100, (now - state.reviveStartedAt) * 100 / reviveMillis);
            Component bar = Msg.parse(msg("reviving")
                    .replace("{player}", victim.getName())
                    .replace("{progress}", String.valueOf(progress)));
            rescuer.sendActionBar(bar);
            victim.sendActionBar(bar);
            if (progress >= 100) {
                it.remove();
                revive(victim, rescuer);
            }
        }
    }

    private void revive(Player victim, Player rescuer) {
        downed.remove(victim.getUniqueId());
        clearEffects(victim);
        victim.setHealth(Math.min(getConfig().getDouble("revived-health", 8.0), maxHealth(victim)));
        if (rescuer != null) {
            victim.sendMessage(Msg.parse(msg("revived").replace("{player}", rescuer.getName())));
            rescuer.sendMessage(Msg.parse(msg("revived-rescuer").replace("{player}", victim.getName())));
        }
    }

    private void bleedOut(Player victim, boolean executed) {
        downed.remove(victim.getUniqueId());
        clearEffects(victim);
        getServer().broadcast(Msg.parse(msg("bled-out").replace("{player}", victim.getName())));
        victim.setHealth(0.0);
    }

    private void clearEffects(Player player) {
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.GLOWING);
    }

    // ----------------------------------------------------------------- misc

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Downed state = downed.remove(event.getPlayer().getUniqueId());
        if (state != null && getConfig().getBoolean("die-on-quit", true)) {
            clearEffects(event.getPlayer());
            event.getPlayer().setHealth(0.0);
        } else if (state != null) {
            revive(event.getPlayer(), null);
        }
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd,
                             String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("secondwind.reload")) {
                sender.sendMessage(Component.text("[SecondWind] No permission."));
                return true;
            }
            reloadConfig();
            sender.sendMessage(Component.text("[SecondWind] Reloaded."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("giveup")
                && sender instanceof Player player && downed.containsKey(player.getUniqueId())) {
            downed.remove(player.getUniqueId());
            clearEffects(player);
            player.setHealth(0.0);
            return true;
        }
        sender.sendMessage(Component.text(
                "[SecondWind] " + downed.size() + " player(s) downed. /" + label + " giveup | reload"));
        return true;
    }
}
