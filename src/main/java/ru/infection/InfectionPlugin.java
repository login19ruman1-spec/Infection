package ru.infection;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;

import java.util.*;

import ru.infection.InfectionData.Heart;
import ru.infection.InfectionData.Kind;
import ru.infection.InfectionData.Pos;

public final class InfectionPlugin extends JavaPlugin
        implements Listener, CommandExecutor {

    InfectionData data;
    NamespacedKey displayKey;

    final Random random = new Random();

    private ModelLoader models;
    private InfectedMobs mobs;
    private NamespacedKey visualsKey;

    private long ticks;

    private final Map<UUID, Exposure> exposure =
            new HashMap<>();

    private final Map<UUID, Kind> damageReason =
            new HashMap<>();

    private static final class Exposure {
        UUID heart;
        int seconds;
    }

    private static final Set<Material> NATURAL =
            EnumSet.of(
                    Material.STONE,
                    Material.DEEPSLATE,
                    Material.GRANITE,
                    Material.DIORITE,
                    Material.ANDESITE,
                    Material.TUFF,
                    Material.CALCITE,
                    Material.DIRT,
                    Material.GRASS_BLOCK,
                    Material.COARSE_DIRT,
                    Material.ROOTED_DIRT,
                    Material.PODZOL,
                    Material.MYCELIUM,
                    Material.MOSS_BLOCK,
                    Material.PALE_MOSS_BLOCK,
                    Material.SAND,
                    Material.RED_SAND,
                    Material.GRAVEL
            );

    private static final BlockFace[] DIRECTIONS = {
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    };

    private final class Menu implements InventoryHolder {
        final UUID heart;
        final Inventory inventory;

        Menu(Heart h) {
            heart = h.id;
            inventory = Bukkit.createInventory(
                    this, 27,
                    Component.text("Сердце: " + h.kind.title())
            );
            draw(inventory, h);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        displayKey = new NamespacedKey(this, "model_display");
        visualsKey = new NamespacedKey(this, "visuals_disabled");

        try {
            data = new InfectionData(
                    getDataFolder().toPath().resolve("state.yml")
            );

            models = new ModelLoader(this);
            models.reload();

            // Удаляем оставшиеся загруженные части старых моделей.
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof BlockDisplay
                            && entity.getPersistentDataContainer().has(
                                    displayKey,
                                    PersistentDataType.BYTE)) {
                        entity.remove();
                    }
                }
            }

            mobs = new InfectedMobs(this, models);

            Objects.requireNonNull(
                    getCommand("infection")
            ).setExecutor(this);

            Bukkit.getPluginManager().registerEvents(this, this);

            Bukkit.getScheduler().runTaskTimer(
                    this, () -> {
                        try {
                            tick();
                        } catch (Exception ex) {
                            getLogger().severe(
                                    "Работа остановлена из-за ошибки: "
                                    + ex.getMessage()
                            );
                            ex.printStackTrace();
                            Bukkit.getPluginManager()
                                    .disablePlugin(this);
                        }
                    },
                    5L, 5L
            );

        } catch (Exception ex) {
            getLogger().severe(
                    "Плагин не запущен: " + ex.getMessage()
            );
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (mobs != null) {
            mobs.close();
        }

        exposure.clear();
        damageReason.clear();

        // Состояние мира сохраняется перед каждой порцией
        // изменений и при настройке/удалении сердец.
    }

    private void tick() throws Exception {
        ticks += 5;

        for (Heart heart :
                new ArrayList<>(data.hearts.values())) {

            Block core = heart.pos.loadedBlock();

            if (core == null) {
                continue;
            }

            if (core.getType() != heart.kind.core()) {
                data.removeHeart(heart.id);
                continue;
            }

            if (ticks % (heart.spreadSeconds * 20L) == 0) {
                spread(heart);
            }
        }

        data.rollbackStep(InfectionData.clamp(
                getConfig().getInt(
                        "rollback.blocks-per-step", 80),
                1, 500
        ));

        if (ticks % 20 == 0) {
            mobs.scan();

            for (Heart heart : data.hearts.values()) {
                defend(heart);
            }

            playersTick();
        }

        mobs.tick(ticks);
    }

    boolean isDay(World world) {
        long time = world.getTime();
        return time < 13000 || time >= 23000;
    }

    boolean vulnerable(Player player) {
        return !player.isDead()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    Heart heartAt(Location location) {
        Heart best = null;
        double distance = Double.MAX_VALUE;

        for (Heart heart : data.hearts.values()) {
            if (!heart.contains(location)) {
                continue;
            }

            Location centre = heart.pos.location();

            if (centre == null) {
                continue;
            }

            double next = centre.distanceSquared(location);

            if (next < distance) {
                distance = next;
                best = heart;
            }
        }

        return best;
    }

    private Heart near(Player player) {
        Heart best = null;
        double distance = 64;

        for (Heart heart : data.hearts.values()) {
            Location location = heart.pos.location();

            if (location == null
                    || !location.getWorld().equals(
                            player.getWorld())) {
                continue;
            }

            double next = location.distanceSquared(
                    player.getLocation()
            );

            if (next <= distance) {
                distance = next;
                best = heart;
            }
        }

        return best;
    }

    private Heart coreAt(Block block) {
        Pos pos = Pos.of(block);

        for (Heart heart : data.hearts.values()) {
            if (heart.pos.equals(pos)) {
                return heart;
            }
        }

        return null;
    }

    private Block loaded(Location location) {
        if (location.getWorld() == null) {
            return null;
        }

        return new Pos(
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        ).loadedBlock();
    }

    // ---------- Распространение ----------

    private void spread(Heart heart) throws Exception {
        if (heart.frontier.isEmpty()) {
            heart.frontier.add(new Pos(
                    heart.pos.world(),
                    heart.pos.x(),
                    heart.pos.y() - 1,
                    heart.pos.z()
            ));
        }

        int attempts = InfectionData.clamp(
                getConfig().getInt("spread.attempts", 8),
                1, 64
        );

        int frontierLimit = InfectionData.clamp(
                getConfig().getInt(
                        "spread.frontier-limit", 512),
                16, 4096
        );

        Map<Block, BlockData> batch = new LinkedHashMap<>();

        // Сначала заражаем опорный блок под сердцем.
        Block base = new Pos(
                heart.pos.world(),
                heart.pos.x(),
                heart.pos.y() - 1,
                heart.pos.z()
        ).loadedBlock();

        if (base != null
                && NATURAL.contains(base.getType())
                && heart.contains(base.getLocation())
                && data.canEdit(heart, base)) {
            batch.put(
                    base,
                    heart.kind.ground().createBlockData()
            );
        }

        for (int i = 0; i < attempts; i++) {
            Pos sourcePos = heart.frontier.get(
                    random.nextInt(heart.frontier.size())
            );

            Block source = sourcePos.loadedBlock();

            if (source == null) {
                continue;
            }

            BlockFace face = DIRECTIONS[
                    random.nextInt(DIRECTIONS.length)
            ];

            Block target = loaded(
                    source.getLocation().add(
                            face.getModX(),
                            face.getModY(),
                            face.getModZ()
                    )
            );

            if (target == null
                    || !heart.contains(target.getLocation())
                    || coreAt(target) != null
                    || !data.canEdit(heart, target)) {
                continue;
            }

            Material current = batch.containsKey(target)
                    ? batch.get(target).getMaterial()
                    : target.getType();

            if (current != heart.kind.ground()
                    && !NATURAL.contains(current)) {
                continue;
            }

            if (current != heart.kind.ground()) {
                batch.put(
                        target,
                        heart.kind.ground().createBlockData()
                );

                if (heart.kind == Kind.SCULK) {
                    vein(heart, target, batch);
                } else if (random.nextDouble()
                        < getConfig().getDouble(
                                "spread.tree-chance", 0.02)) {
                    tree(heart, target, batch);
                }
            }

            Pos pos = Pos.of(target);

            if (!heart.frontier.contains(pos)) {
                heart.frontier.add(pos);
            }

            if (heart.frontier.size() > frontierLimit) {
                heart.frontier.remove(0);
            }
        }

        data.apply(
                heart,
                batch,
                Math.max(100, getConfig().getInt(
                        "spread.max-recorded-blocks", 30000))
        );
    }

    private boolean free(
            Heart heart, Block block,
            Map<Block, BlockData> batch) {

        return block != null
                && block.getType().isAir()
                && !batch.containsKey(block)
                && heart.contains(block.getLocation())
                && data.canEdit(heart, block);
    }

    private void vein(
            Heart heart,
            Block ground,
            Map<Block, BlockData> batch) {

        Block above = loaded(
                ground.getLocation().add(0, 1, 0)
        );

        if (!free(heart, above, batch)) {
            return;
        }

        MultipleFacing data = (MultipleFacing)
                Material.SCULK_VEIN.createBlockData();

        data.setFace(BlockFace.DOWN, true);
        batch.put(above, data);
    }

    private void tree(
            Heart heart,
            Block ground,
            Map<Block, BlockData> batch) {

        int height = 4 + random.nextInt(3);
        Map<Block, BlockData> plan = new LinkedHashMap<>();

        for (int y = height - 2; y <= height + 1; y++) {
            int radius = y == height + 1 ? 1 : 2;

            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) == radius
                            && Math.abs(z) == radius) {
                        continue;
                    }

                    Block block = loaded(
                            ground.getLocation().add(x, y, z)
                    );

                    if (!free(heart, block, batch)) {
                        return;
                    }

                    Leaves leaves = (Leaves)
                            Material.PALE_OAK_LEAVES
                                    .createBlockData();

                    leaves.setPersistent(true);
                    plan.put(block, leaves);
                }
            }
        }

        for (int y = 1; y <= height; y++) {
            Block block = loaded(
                    ground.getLocation().add(0, y, 0)
            );

            if (!free(heart, block, batch)) {
                return;
            }

            plan.put(
                    block,
                    (y == 2
                            ? Material.CREAKING_HEART
                            : Material.PALE_OAK_LOG)
                            .createBlockData()
            );
        }

        batch.putAll(plan);
    }

    // ---------- Защита сердца ----------

    private void defend(Heart heart) {
        if (!heart.defence
                || ticks < heart.nextWave
                || heart.guardLimit <= 0) {
            return;
        }

        Block core = heart.pos.loadedBlock();
        Location centre = heart.pos.location();

        if (core == null
                || centre == null
                || core.getType() != heart.kind.core()) {
            return;
        }

        World world = centre.getWorld();

        if (heart.kind == Kind.PALE && isDay(world)) {
            return;
        }

        double radius = Math.min(
                heart.radius,
                Math.max(1, getConfig().getDouble(
                        "defence.detection-radius", 16))
        );

        Player target = null;
        double closest = radius * radius;

        for (Player player : world.getPlayers()) {
            if (!vulnerable(player)) {
                continue;
            }

            double distance = player.getLocation()
                    .distanceSquared(centre);

            if (distance <= closest) {
                closest = distance;
                target = player;
            }
        }

        if (target == null) {
            return;
        }

        int guards = 0;

        for (LivingEntity entity : world.getLivingEntities()) {
            if (mobs.isGuard(entity, heart.id)) {
                guards++;
            }
        }

        int count = Math.min(
                heart.guardLimit - guards,
                InfectionData.clamp(
                        getConfig().getInt(
                                "defence.wave-size", 2),
                        0, 10)
        );

        if (count <= 0) {
            return;
        }

        List<EntityType> types = new ArrayList<>();

        for (String name :
                getConfig().getStringList("defence.types")) {
            try {
                EntityType type = EntityType.valueOf(
                        name.toUpperCase(Locale.ROOT)
                );

                if (InfectedMobs.TYPES.contains(type)) {
                    types.add(type);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (types.isEmpty()) {
            return;
        }

        heart.nextWave = ticks + heart.cooldown * 20L;

        int maximum = Math.max(
                1,
                getConfig().getInt(
                        "mobs.max-loaded-infected", 40)
        );

        for (int i = 0; i < count; i++) {
            if (mobs.size() >= maximum) {
                break;
            }

            Location spawn = spawnPlace(heart);

            if (spawn == null) {
                continue;
            }

            EntityType type = types.get(
                    random.nextInt(types.size())
            );

            LivingEntity entity =
                    (LivingEntity) world.spawnEntity(spawn, type);

            mobs.infect(entity, heart, true);

            entity.setPersistent(true);
            entity.setRemoveWhenFarAway(false);

            if (entity instanceof Mob mob) {
                mob.setTarget(target);
            }
        }
    }

    private Location spawnPlace(Heart heart) {
        Location centre = heart.pos.location();

        if (centre == null) {
            return null;
        }

        int range = InfectionData.clamp(
                getConfig().getInt(
                        "defence.spawn-radius", 5),
                2, 12
        );

        for (int attempt = 0; attempt < 32; attempt++) {
            int dx = random.nextInt(range * 2 + 1) - range;
            int dz = random.nextInt(range * 2 + 1) - range;

            for (int dy = 3; dy >= -3; dy--) {
                Location point = centre.clone().add(dx, dy, dz);
                Map<Block, Boolean> check = new HashMap<>();

                boolean valid = true;

                // Объём с запасом для паука.
                for (int x = 0; x <= 1 && valid; x++) {
                    for (int z = 0; z <= 1 && valid; z++) {
                        Block floor = loaded(
                                point.clone().add(x, -1, z)
                        );

                        if (floor == null
                                || !floor.getType().isSolid()
                                || floor.getType() == Material.CACTUS
                                || floor.getType() == Material.MAGMA_BLOCK) {
                            valid = false;
                            break;
                        }

                        for (int y = 0; y <= 1; y++) {
                            Block air = loaded(
                                    point.clone().add(x, y, z)
                            );

                            if (air == null
                                    || !air.getType().isAir()) {
                                valid = false;
                                break;
                            }

                            check.put(air, true);
                        }
                    }
                }

                if (!valid) {
                    continue;
                }

                Location result = point.clone().add(1, 0, 1);

                if (!heart.contains(result)) {
                    continue;
                }

                for (Entity entity :
                        result.getWorld().getNearbyEntities(
                                result, 1, 1.5, 1)) {
                    if (entity instanceof LivingEntity) {
                        valid = false;
                        break;
                    }
                }

                if (valid) {
                    return result;
                }
            }
        }

        return null;
    }

    // ---------- Галлюцинации ----------

    private void playersTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();

            if (!vulnerable(player)) {
                exposure.remove(id);
                continue;
            }

            Heart heart = heartAt(player.getLocation());
            Exposure state = exposure.get(id);

            if (heart == null) {
                if (state != null) {
                    state.seconds -= Math.max(
                            1,
                            getConfig().getInt(
                                    "hallucinations.recovery-per-second",
                                    3)
                    );

                    if (state.seconds <= 0
                            || !data.hearts.containsKey(state.heart)) {
                        exposure.remove(id);
                    }
                }
                continue;
            }

            if (state == null) {
                state = new Exposure();
                exposure.put(id, state);
            }

            state.heart = heart.id;
            state.seconds = Math.min(3600, state.seconds + 1);

            if (state.seconds >= 10
                    && getConfig().getBoolean(
                            "hallucinations.sounds", true)
                    && random.nextDouble() < 0.18) {

                String[] sounds = heart.kind == Kind.SCULK
                        ? new String[]{
                            "minecraft:entity.warden.heartbeat",
                            "minecraft:block.sculk_shrieker.shriek",
                            "minecraft:ambient.cave"
                        }
                        : new String[]{
                            "minecraft:entity.creaking.ambient",
                            "minecraft:entity.creaking.activate",
                            "minecraft:ambient.cave"
                        };

                player.playSound(
                        player.getLocation().add(
                                random.nextDouble() * 10 - 5,
                                random.nextDouble() *
