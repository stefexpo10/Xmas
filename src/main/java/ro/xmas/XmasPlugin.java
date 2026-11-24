package ro.xmas;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class XmasPlugin extends JavaPlugin {

    private boolean active = false;
    private BukkitTask task;
    private BukkitTask restoreTask;
    private int tickCounter = 0;

    // starea initiala a vremii, ca sa o putem reda la /xmas off
    private final Map<UUID, Boolean> originalStorm = new HashMap<>();
    // pozitiile unde am pus zapada, ca sa o putem sterge la /xmas off
    private final Set<Location> snowBlocks = new HashSet<>();

    private boolean snowLayers;
    private int radius;
    private int attempts;
    private int maxBlocks;
    private boolean realWeather;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("XmasPlugin pornit. Foloseste /xmas on");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (restoreTask != null) {
            restoreTask.cancel();
            restoreTask = null;
        }
        restoreWeather();
        removeSnow(Integer.MAX_VALUE);
        active = false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(Component.text("Folosește: /xmas <on|off>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on" -> {
                if (active) {
                    sender.sendMessage(Component.text("Crăciunul e deja pornit!", NamedTextColor.YELLOW));
                } else {
                    start();
                    sender.sendMessage(Component.text("Crăciunul a pornit! ❄", NamedTextColor.GREEN));
                }
            }
            case "off" -> {
                if (!active) {
                    sender.sendMessage(Component.text("Crăciunul nu e pornit.", NamedTextColor.YELLOW));
                } else {
                    stop();
                    sender.sendMessage(Component.text("Crăciunul s-a oprit.", NamedTextColor.GREEN));
                }
            }
            default -> sender.sendMessage(Component.text("Folosește: /xmas <on|off>", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String o : List.of("on", "off")) {
                if (o.startsWith(args[0].toLowerCase())) {
                    options.add(o);
                }
            }
            return options;
        }
        return List.of();
    }

    private void start() {
        reloadConfig();
        snowLayers = getConfig().getBoolean("snow-layers", true);
        radius = Math.max(4, getConfig().getInt("radius", 24));
        attempts = Math.max(10, getConfig().getInt("attempts-per-second", 120));
        maxBlocks = Math.max(100, getConfig().getInt("max-snow-blocks", 20000));
        realWeather = getConfig().getBoolean("real-weather", true);

        if (restoreTask != null) {
            restoreTask.cancel();
            restoreTask = null;
        }

        active = true;
        tickCounter = 0;
        showTitle();
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 0L, 10L);
    }

    private void stop() {
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        restoreWeather();

        // stergem zapada treptat, ca sa nu dea lag serverul
        restoreTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            removeSnow(2000);
            if (snowBlocks.isEmpty() && restoreTask != null) {
                restoreTask.cancel();
                restoreTask = null;
            }
        }, 1L, 1L);
    }

    private void showTitle() {
        Title title = Title.title(
                Component.text("A venit Crăciunul!", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Crăciun fericit tuturor!", NamedTextColor.GREEN),
                Title.Times.times(
                        Duration.ofMillis(500),   // apare
                        Duration.ofSeconds(4),    // sta 4 secunde
                        Duration.ofMillis(500)    // dispare
                )
        );
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    private void tick() {
        tickCounter++;

        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            if (realWeather) {
                originalStorm.putIfAbsent(w.getUID(), w.hasStorm());
                if (!w.hasStorm()) {
                    w.setStorm(true);
                }
                w.setThundering(false);
            }
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            // fulgi de zapada in jurul jucatorului (se vad in orice biom)
            Location above = p.getLocation().add(0, 4, 0);
            p.spawnParticle(Particle.SNOWFLAKE, above, 80, 14, 5, 14, 0.01);

            // o data pe secunda punem zapada pe jos
            if (snowLayers && tickCounter % 2 == 0) {
                placeSnowAround(p);
            }
        }
    }

    private void placeSnowAround(Player p) {
        World w = p.getWorld();
        Location c = p.getLocation();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < attempts; i++) {
            if (snowBlocks.size() >= maxBlocks) {
                return;
            }
            int x = c.getBlockX() + rnd.nextInt(-radius, radius + 1);
            int z = c.getBlockZ() + rnd.nextInt(-radius, radius + 1);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            Block top = w.getHighestBlockAt(x, z);
            Material type = top.getType();
            if (type == Material.SNOW || type == Material.SNOW_BLOCK || top.isLiquid() || !type.isSolid()) {
                continue;
            }

            Block above = top.getRelative(BlockFace.UP);
            if (!above.getType().isAir()) {
                continue;
            }
            if (!above.canPlace(Material.SNOW.createBlockData())) {
                continue;
            }

            above.setType(Material.SNOW, true);
            snowBlocks.add(above.getLocation());
        }
    }

    private void removeSnow(int limit) {
        Iterator<Location> it = snowBlocks.iterator();
        int count = 0;
        while (it.hasNext() && count < limit) {
            Location loc = it.next();
            it.remove();
            count++;
            if (loc.getWorld() == null) {
                continue;
            }
            Block b = loc.getBlock();
            if (b.getType() == Material.SNOW) {
                b.setType(Material.AIR, true);
            }
        }
    }

    private void restoreWeather() {
        for (Map.Entry<UUID, Boolean> e : originalStorm.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w != null) {
                w.setStorm(e.getValue());
                w.setThundering(false);
            }
        }
        originalStorm.clear();
    }
}
