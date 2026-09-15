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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SecondWindPlugin extends JavaPlugin implements Listener {

    static final class Downed {
        long downedAt;
        long bleedOutAt;
        UUID rescuer;
        long reviveStartedAt;
        /** Effects of OUR types the player already had (from other plugins/items) — restored on exit. */
        final List<PotionEffect> prior = new ArrayList<>();
    }

    private static final PotionEffectType[] OUR_EFFECTS = {
            PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE,
            PotionEffectType.WEAKNESS, PotionEffectType.GLOWING};

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

        // Void, suicide, /kill and world border kill outright — also while downed.
        // Otherwise a downed player falls through the void immortal for bleed-seconds,
        // and an admin cannot /kill them.
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean alwaysLethal = cause == EntityDamageEvent.DamageCause.VOID
                || cause == EntityDamageEvent.DamageCause.SUICIDE
                || cause == EntityDamageEvent.DamageCause.WORLD_BORDER
                || cause == EntityDamageEvent.DamageCause.KILL;

        Downed state = downed.get(player.getUniqueId());
        if (state != null) {
            if (alwaysLethal) {
                // Leave the downed state and let vanilla apply the damage.
                clearEffects(player, downed.remove(player.getUniqueId()));
                return;
            }
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

        if (alwaysLethal) return;

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
        state.downedAt = System.currentTimeMillis();
        state.bleedOutAt = state.downedAt + getConfig().getLong("bleed-seconds", 30) * 1000L;
        // What the player already carries of our effect types belongs to someone else
        // (another plugin, a spectral arrow) — remember it, restore it on exit.
        for (PotionEffectType type : OUR_EFFECTS) {
            PotionEffect had = player.getPotionEffect(type);
            if (had != null) state.prior.add(had);
        }
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
        // Snapshot: revive()/bleedOut() remove from the map themselves.
        for (Map.Entry<UUID, Downed> entry : new java.util.ArrayList<>(downed.entrySet())) {
            Player victim = getServer().getPlayer(entry.getKey());
            if (victim == null || !victim.isOnline() || victim.isDead()) {
                downed.remove(entry.getKey());
                continue;
            }
            tickVictim(victim, entry.getValue(), now, victim.getWorld().getPlayers());
        }
    }

    /** One scheduler step for a downed player; {@code nearby} are the rescuer candidates. */
    void tickVictim(Player victim, Downed state, long now, Iterable<? extends Player> nearby) {
        long reviveMillis = getConfig().getLong("revive-seconds", 4) * 1000L;
        double range = getConfig().getDouble("revive-range", 3.0);

        if (state.bleedOutAt < now) {
            bleedOut(victim, false);
            return;
        }

        // Find a sneaking rescuer in range.
        Player rescuer = null;
        for (Player near : nearby) {
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
            return;
        }

        if (state.rescuer == null || !state.rescuer.equals(rescuer.getUniqueId())) {
            state.rescuer = rescuer.getUniqueId();
            state.reviveStartedAt = now;
        }
        int progress = reviveMillis <= 0 ? 100        // revive-seconds: 0 = instant, no division by zero
                : (int) Math.min(100, (now - state.reviveStartedAt) * 100 / reviveMillis);
        Component bar = Msg.parse(msg("reviving")
                .replace("{player}", victim.getName())
                .replace("{progress}", String.valueOf(progress)));
        rescuer.sendActionBar(bar);
        victim.sendActionBar(bar);
        if (progress >= 100) {
            revive(victim, rescuer);
        }
    }

    private void revive(Player victim, Player rescuer) {
        clearEffects(victim, downed.remove(victim.getUniqueId()));
        victim.setHealth(Math.min(getConfig().getDouble("revived-health", 8.0), maxHealth(victim)));
        if (rescuer != null) {
            victim.sendMessage(Msg.parse(msg("revived").replace("{player}", rescuer.getName())));
            rescuer.sendMessage(Msg.parse(msg("revived-rescuer").replace("{player}", victim.getName())));
        }
    }

    private void bleedOut(Player victim, boolean executed) {
        clearEffects(victim, downed.remove(victim.getUniqueId()));
        getServer().broadcast(Msg.parse(msg("bled-out").replace("{player}", victim.getName())));
        victim.setHealth(0.0);
    }

    /** Removes our effects and puts back what the player had before going down. */
    private void clearEffects(Player player, Downed state) {
        for (PotionEffectType type : OUR_EFFECTS) player.removePotionEffect(type);
        if (state == null) return;
        int elapsedTicks = (int) ((System.currentTimeMillis() - state.downedAt) / 50L);
        for (PotionEffect had : state.prior) {
            if (had.getDuration() < 0) {          // infinite: hand back as is
                player.addPotionEffect(had);
                continue;
            }
            int remaining = had.getDuration() - elapsedTicks;
            if (remaining > 0) player.addPotionEffect(had.withDuration(remaining));
        }
    }

    // ----------------------------------------------------------------- misc

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Downed state = downed.remove(event.getPlayer().getUniqueId());
        if (state != null && getConfig().getBoolean("die-on-quit", true)) {
            clearEffects(event.getPlayer(), state);
            event.getPlayer().setHealth(0.0);
        } else if (state != null) {
            clearEffects(event.getPlayer(), state);
            event.getPlayer().setHealth(Math.min(getConfig().getDouble("revived-health", 8.0),
                    maxHealth(event.getPlayer())));
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
            clearEffects(player, downed.remove(player.getUniqueId()));
            player.setHealth(0.0);
            return true;
        }
        sender.sendMessage(Component.text(
                "[SecondWind] " + downed.size() + " player(s) downed. /" + label + " giveup | reload"));
        return true;
    }
}
