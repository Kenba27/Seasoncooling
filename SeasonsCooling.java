package vn.seasonscooling;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import tr.alperendemir.seasons.Seasons;
import tr.alperendemir.seasons.effects.TemperatureEffects;
import tr.alperendemir.seasons.player.PlayerManager;
import tr.alperendemir.seasons.temperature.TemperatureCalculator;

public class SeasonsCooling extends JavaPlugin implements Listener {

    // ---- Trang thai tung nguoi choi ----
    final Map<UUID, Long> coolUntil = new ConcurrentHashMap<>();
    final Map<UUID, Double> coolLast = new ConcurrentHashMap<>();
    final Map<UUID, Long> warmUntil = new ConcurrentHashMap<>();
    final Map<UUID, Double> warmLast = new ConcurrentHashMap<>();

    // ---- Cau hinh: mat ----
    long waterMs, melonMs, teaMs;
    double minTemp, maxTemp;
    String teaName;

    // ---- Cau hinh: am ----
    long hotWaterMs, stewMs, potatoMs;
    double warmMin, warmMax;
    String hotWaterName;      // de so khop (chu thuong)
    String hotWaterDisplay;   // ten hien thi tren vat pham

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        Seasons seasons = (Seasons) getServer().getPluginManager().getPlugin("Seasons");
        if (seasons == null) {
            getLogger().severe("Khong tim thay plugin Seasons!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            hook(seasons.getPlayerManager());
        } catch (Throwable t) {
            getLogger().severe("Khong the hook vao Seasons: " + t);
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Da hook TemperatureCalculator cua Seasons.");
    }

    void loadSettings() {
        waterMs = getConfig().getLong("durations.water", 60) * 1000L;
        melonMs = getConfig().getLong("durations.watermelon", 30) * 1000L;
        teaMs = getConfig().getLong("durations.iced-tea", 120) * 1000L;
        minTemp = getConfig().getDouble("temperature.min", 38.0);
        maxTemp = getConfig().getDouble("temperature.max", 40.0);
        if (maxTemp < minTemp) { double t = minTemp; minTemp = maxTemp; maxTemp = t; }
        teaName = getConfig().getString("iced-tea-name", "Trà đá").toLowerCase(Locale.ROOT);

        hotWaterMs = getConfig().getLong("warming.durations.hot-water", 120) * 1000L;
        stewMs = getConfig().getLong("warming.durations.stew", 60) * 1000L;
        potatoMs = getConfig().getLong("warming.durations.baked-potato", 30) * 1000L;
        warmMin = getConfig().getDouble("warming.temperature.min", 10.0);
        warmMax = getConfig().getDouble("warming.temperature.max", 12.0);
        if (warmMax < warmMin) { double t = warmMin; warmMin = warmMax; warmMax = t; }
        hotWaterDisplay = getConfig().getString("warming.hot-water-name", "Nước ấm");
        hotWaterName = hotWaterDisplay.toLowerCase(Locale.ROOT);
    }

    void hook(PlayerManager pm) throws Exception {
        CoolingCalculator calc = new CoolingCalculator();

        Field f = PlayerManager.class.getDeclaredField("temperatureCalculator");
        f.setAccessible(true);
        f.set(pm, calc);

        Field fe = PlayerManager.class.getDeclaredField("temperatureEffects");
        fe.setAccessible(true);
        Object effects = fe.get(pm);
        if (effects instanceof TemperatureEffects) {
            Field fc = TemperatureEffects.class.getDeclaredField("temperatureCalculator");
            fc.setAccessible(true);
            fc.set(effects, calc);
        }
    }

    // Tinh nhiet do "tru" dao dong nhe trong [lo, hi]
    private static double wander(Map<UUID, Double> last, UUID id, double lo, double hi) {
        double cur = last.getOrDefault(id, (lo + hi) / 2.0);
        cur += (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * 0.6;
        cur = Math.max(lo, Math.min(hi, cur));
        last.put(id, cur);
        return cur;
    }

    class CoolingCalculator extends TemperatureCalculator {
        @Override
        public double calculateTemperature(Player player) {
            double real = super.calculateTemperature(player);
            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();

            Long cool = coolUntil.get(id);
            if (cool != null && now >= cool) {
                coolUntil.remove(id);
                coolLast.remove(id);
                cool = null;
            }
            Long warm = warmUntil.get(id);
            if (warm != null && now >= warm) {
                warmUntil.remove(id);
                warmLast.remove(id);
                warm = null;
            }

            // Dang mat: chi GIAM, khong bao gio lam lanh them
            if (cool != null) {
                if (real <= maxTemp) return real;
                return Math.min(real, wander(coolLast, id, minTemp, maxTemp));
            }
            // Dang am: chi TANG, khong bao gio lam nong them
            if (warm != null) {
                if (real >= warmMin) return real;
                return Math.max(real, wander(warmLast, id, warmMin, warmMax));
            }
            return real;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack item = e.getItem();
        Material type = item.getType();
        long coolMs = 0;
        long warmMs = 0;

        if (type == Material.MELON_SLICE) {
            coolMs = melonMs;
        } else if (type == Material.POTION) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof PotionMeta pm
                    && pm.getBasePotionType() == PotionType.WATER
                    && !pm.hasCustomEffects()) {
                coolMs = waterMs;
            }
        } else if (type == Material.HONEY_BOTTLE) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String name = ChatColor.stripColor(meta.getDisplayName());
                if (name != null) {
                    name = name.toLowerCase(Locale.ROOT);
                    if (name.contains(teaName)) coolMs = teaMs;
                    else if (name.contains(hotWaterName)) warmMs = hotWaterMs;
                }
            }
        } else if (type == Material.MUSHROOM_STEW
                || type == Material.RABBIT_STEW
                || type == Material.BEETROOT_SOUP) {
            warmMs = stewMs;
        } else if (type == Material.BAKED_POTATO) {
            warmMs = potatoMs;
        }

        UUID id = e.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();

        if (coolMs > 0) {
            coolUntil.merge(id, now + coolMs, Math::max);
            // Mat thi het am (hai hieu ung khong chong nhau)
            warmUntil.remove(id);
            warmLast.remove(id);
        } else if (warmMs > 0) {
            warmUntil.merge(id, now + warmMs, Math::max);
            coolUntil.remove(id);
            coolLast.remove(id);
        }
    }

    // Cam chai nuoc, chuot phai vao lua trai dang chay -> thanh "Nuoc am" (honey bottle, stack duoc)
    @EventHandler
    public void onCampfire(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.CAMPFIRE && block.getType() != Material.SOUL_CAMPFIRE) return;
        if (!(block.getBlockData() instanceof Campfire cf) || !cf.isLit()) return;

        ItemStack held = e.getItem();
        if (held == null || held.getType() != Material.POTION) return;
        if (!(held.getItemMeta() instanceof PotionMeta pm)
                || pm.getBasePotionType() != PotionType.WATER
                || pm.hasCustomEffects()) return;

        e.setCancelled(true);
        Player player = e.getPlayer();

        ItemStack warm = new ItemStack(Material.HONEY_BOTTLE, 1);
        ItemMeta meta = warm.getItemMeta();
        meta.setDisplayName(ChatColor.RESET + "" + ChatColor.GOLD + hotWaterDisplay);
        warm.setItemMeta(meta);

        // Tru 1 chai nuoc trong tay vua click
        ItemStack inHand = player.getInventory().getItem(hand);
        if (inHand.getAmount() <= 1) {
            player.getInventory().setItem(hand, warm);
        } else {
            inHand.setAmount(inHand.getAmount() - 1);
            player.getInventory().setItem(hand, inHand);
            Map<Integer, ItemStack> left = player.getInventory().addItem(warm);
            for (ItemStack rest : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        player.playSound(block.getLocation(), Sound.ITEM_BOTTLE_FILL, 1.0f, 1.0f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        coolUntil.remove(id);
        coolLast.remove(id);
        warmUntil.remove(id);
        warmLast.remove(id);
    }
}
