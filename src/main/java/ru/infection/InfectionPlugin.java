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
import org.bukkit.event.player.PlayerInteractEvent;
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
                                    3
                            )
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

                Location soundLocation = player.getLocation().add(
                        random.nextDouble() * 10 - 5,
                        random.nextDouble() * 3,
                        random.nextDouble() * 10 - 5
                );

                player.playSound(
                        soundLocation,
                        sounds[random.nextInt(sounds.length)],
                        SoundCategory.AMBIENT,
                        0.45f,
                        0.65f + random.nextFloat() * 0.55f
                );
            }

            boolean visualsDisabled =
                    player.getPersistentDataContainer().has(
                            visualsKey,
                            PersistentDataType.BYTE
                    );

            if (getConfig().getBoolean(
                    "hallucinations.visual-effects", true)
                    && !visualsDisabled) {

                if (state.seconds >= 15) {
                    player.spawnParticle(
                            heart.kind == Kind.SCULK
                                    ? Particle.SCULK_SOUL
                                    : Particle.ASH,
                            player.getEyeLocation(),
                            5,
                            0.9, 0.5, 0.9,
                            0.01
                    );
                }

                if (state.seconds >= 30) {
                    player.addPotionEffect(new PotionEffect(
                            PotionEffectType.DARKNESS,
                            50,
                            0,
                            false,
                            false,
                            true
                    ));
                }

                if (state.seconds >= 60
                        && state.seconds % 15 == 0) {
                    player.addPotionEffect(new PotionEffect(
                            PotionEffectType.NAUSEA,
                            100,
                            0,
                            false,
                            false,
                            true
                    ));
                }
            }

            if (state.seconds == heart.damageAfter - 10) {
                player.sendMessage(Component.text(
                        "The infection is becoming dangerous. "
                                + "Leave this area."
                ));
            }

            if (state.seconds >= heart.damageAfter) {
                double damage = Math.max(
                        0,
                        getConfig().getDouble(
                                "hallucinations.damage-per-second",
                                1.0
                        )
                );

                if (damage > 0) {
                    infectionDamage(player, heart.kind, damage);
                }
            }
        }
    }

    void infectionDamage(Player player, Kind kind, double amount) {
        if (!vulnerable(player)
                || !Double.isFinite(amount)
                || amount <= 0) {
            return;
        }

        UUID id = player.getUniqueId();
        damageReason.put(id, kind);

        try {
            player.damage(amount);
        } finally {
            damageReason.remove(id);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Kind reason = damageReason.get(player.getUniqueId());

        if (reason != null) {
            event.deathMessage(Component.text(
                    player.getName()
                            + (reason == Kind.SCULK
                            ? " was consumed by the sculk infection."
                            : " was consumed by the pale infection.")
            ));
        }

        exposure.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        exposure.remove(id);
        damageReason.remove(id);
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    "Use this command as a player."
            );
            return true;
        }

        if (args.length > 0
                && args[0].equalsIgnoreCase("visuals")) {

            var persistent = player.getPersistentDataContainer();

            if (persistent.has(
                    visualsKey, PersistentDataType.BYTE)) {

                persistent.remove(visualsKey);

                player.sendMessage(Component.text(
                        "Infection visual effects enabled."
                ));
            } else {
                persistent.set(
                        visualsKey,
                        PersistentDataType.BYTE,
                        (byte) 1
                );

                player.sendMessage(Component.text(
                        "Infection visual effects disabled. "
                                + "Sounds and infection damage remain active."
                ));
            }

            return true;
        }

        if (!player.hasPermission("infection.admin")) {
            player.sendMessage(Component.text(
                    "You do not have permission to manage hearts."
            ));
            return true;
        }

        if (args.length == 0) {
            help(player);
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> {
                    if (args.length != 2) {
                        player.sendMessage(Component.text(
                                "Usage: /infection create sculk|pale"
                        ));
                        return true;
                    }

                    Kind kind;

                    if (args[1].equalsIgnoreCase("sculk")) {
                        kind = Kind.SCULK;
                    } else if (args[1].equalsIgnoreCase("pale")) {
                        kind = Kind.PALE;
                    } else {
                        player.sendMessage(Component.text(
                                "Choose sculk or pale."
                        ));
                        return true;
                    }

                    createHeart(player, kind);
                }

                case "gui" -> {
                    Heart heart = near(player);

                    if (heart == null) {
                        player.sendMessage(Component.text(
                                "Stand within 8 blocks of a heart."
                        ));
                    } else {
                        player.openInventory(
                                new Menu(heart).getInventory()
                        );
                    }
                }

                case "remove" -> {
                    Heart heart = near(player);

                    if (heart == null) {
                        player.sendMessage(Component.text(
                                "Stand within 8 blocks of a heart."
                        ));
                    } else {
                        removeHeart(heart);

                        player.sendMessage(Component.text(
                                "Heart removed. Recorded blocks will "
                                        + "be restored in loaded chunks."
                        ));
                    }
                }

                case "list" -> {
                    player.sendMessage(Component.text(
                            "Active hearts: " + data.hearts.size()
                    ));

                    for (Heart heart : data.hearts.values()) {
                        Location location = heart.pos.location();

                        String worldName = location == null
                                ? "unavailable world"
                                : location.getWorld().getName();

                        player.sendMessage(Component.text(
                                heart.kind.name()
                                        + " | " + worldName
                                        + " | " + heart.pos.x()
                                        + ", " + heart.pos.y()
                                        + ", " + heart.pos.z()
                                        + " | radius " + heart.radius
                        ));
                    }
                }

                case "reload" -> {
                    reloadConfig();
                    models.reload();
                    mobs.reloadModels();

                    player.sendMessage(Component.text(
                            "Configuration and models reloaded. "
                                    + "Existing hearts keep their GUI settings."
                    ));
                }

                default -> help(player);
            }
        } catch (Exception ex) {
            getLogger().severe(
                    "Command failed: " + ex.getMessage()
            );
            ex.printStackTrace();

            player.sendMessage(Component.text(
                    "The operation failed. Check the server console."
            ));
        }

        return true;
    }

    private void help(Player player) {
        player.sendMessage(Component.text(
                "/infection create sculk - create a sculk heart"
        ));
        player.sendMessage(Component.text(
                "/infection create pale - create a pale heart"
        ));
        player.sendMessage(Component.text(
                "/infection gui - configure the nearest heart"
        ));
        player.sendMessage(Component.text(
                "/infection remove - remove the nearest heart"
        ));
        player.sendMessage(Component.text(
                "/infection list - list active hearts"
        ));
        player.sendMessage(Component.text(
                "/infection reload - reload configuration and models"
        ));
        player.sendMessage(Component.text(
                "/infection visuals - toggle personal visual effects"
        ));
    }

    private void createHeart(Player player, Kind kind)
            throws Exception {

        Block ground = player.getTargetBlockExact(6);

        if (ground == null
                || !NATURAL.contains(ground.getType())) {
            player.sendMessage(Component.text(
                    "Look at natural ground within 6 blocks."
            ));
            return;
        }

        Block core = loaded(
                ground.getLocation().add(0, 1, 0)
        );

        if (core == null || !core.getType().isAir()) {
            player.sendMessage(Component.text(
                    "There must be an empty block above the ground."
            ));
            return;
        }

        if (coreAt(core) != null
                || data.edits.containsKey(Pos.of(core))
                || data.edits.containsKey(Pos.of(ground))) {
            player.sendMessage(Component.text(
                    "This position is already part of recorded infection."
            ));
            return;
        }

        Heart heart = new Heart();

        heart.id = UUID.randomUUID();
        heart.kind = kind;
        heart.pos = Pos.of(core);

        heart.radius = InfectionData.clamp(
                getConfig().getInt("defaults.radius", 32),
                8, 128
        );

        heart.spreadSeconds = InfectionData.clamp(
                getConfig().getInt(
                        "defaults.spread-seconds", 3),
                1, 60
        );

        heart.damageAfter = InfectionData.clamp(
                getConfig().getInt(
                        "defaults.damage-after-seconds", 90),
                30, 600
        );

        heart.defence = getConfig().getBoolean(
                "defaults.defence-enabled", true
        );

        heart.guardLimit = InfectionData.clamp(
                getConfig().getInt(
                        "defaults.guard-limit", 6),
                0, 30
        );

        heart.cooldown = InfectionData.clamp(
                getConfig().getInt(
                        "defaults.defence-cooldown-seconds", 20),
                5, 120
        );

        heart.frontier.add(Pos.of(ground));

        Map<Block, BlockData> batch = new LinkedHashMap<>();

        batch.put(core, kind.core().createBlockData());
        batch.put(ground, kind.ground().createBlockData());

        data.hearts.put(heart.id, heart);

        boolean applied;

        try {
            applied = data.apply(
                    heart,
                    batch,
                    Math.max(
                            100,
                            getConfig().getInt(
                                    "spread.max-recorded-blocks",
                                    30000
                            )
                    )
            );
        } catch (Exception ex) {
            // If the journal was already written, removing the
            // heart schedules its recorded changes for rollback.
            data.removeHeart(heart.id);
            throw ex;
        }

        if (!applied) {
            data.hearts.remove(heart.id);

            player.sendMessage(Component.text(
                    "Heart creation was refused. "
                            + "Check the recorded-block limit and position."
            ));
            return;
        }

        player.sendMessage(Component.text(
                kind.name()
                        + " heart created. Right-click it "
                        + "or use /infection gui to configure it."
        ));
    }

    private void removeHeart(Heart heart) throws Exception {
        data.removeHeart(heart.id);

        // Loaded guards disappear and ordinary mobs are cured.
        // Unloaded mobs are handled when they become loaded.
        mobs.scan();
    }

    // ---------- Heart GUI ----------

    private ItemStack menuItem(
            Material material,
            String title,
            String... description) {

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(title));

        List<Component> lore = new ArrayList<>();

        for (String line : description) {
            lore.add(Component.text(line));
        }

        meta.lore(lore);
        item.setItemMeta(meta);

        return item;
    }

    private void draw(Inventory inventory, Heart heart) {
        inventory.clear();

        inventory.setItem(4, menuItem(
                heart.kind.core(),
                heart.kind.name() + " heart",
                "Settings apply to this heart only."
        ));

        inventory.setItem(10, menuItem(
                Material.COMPASS,
                "Radius: " + heart.radius,
                "Left click: +8",
                "Right click: -8",
                "Range: 8-128 blocks"
        ));

        inventory.setItem(11, menuItem(
                Material.CLOCK,
                "Spread interval: " + heart.spreadSeconds + " s",
                "Left click: +1 second (slower)",
                "Right click: -1 second (faster)",
                "Range: 1-60 seconds"
        ));

        inventory.setItem(12, menuItem(
                Material.SPIDER_EYE,
                "Damage starts after: " + heart.damageAfter + " s",
                "Left click: +15 seconds",
                "Right click: -15 seconds",
                "Range: 30-600 seconds"
        ));

        inventory.setItem(14, menuItem(
                Material.SHIELD,
                "Defence: " + (heart.defence ? "ON" : "OFF"),
                "Click to toggle."
        ));

        inventory.setItem(15, menuItem(
                Material.ZOMBIE_HEAD,
                "Guard limit: " + heart.guardLimit,
                "Left click: +1",
                "Right click: -1",
                "Range: 0-30 guards"
        ));

        inventory.setItem(16, menuItem(
                Material.REPEATER,
                "Wave cooldown: " + heart.cooldown + " s",
                "Left click: +5 seconds",
                "Right click: -5 seconds",
                "Range: 5-120 seconds"
        ));

        inventory.setItem(22, menuItem(
                Material.BARRIER,
                "Remove this heart",
                "Shift + right click to confirm.",
                "Starts rollback of recorded blocks."
        ));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof Menu menu)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!player.hasPermission("infection.admin")) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0
                || slot >= event.getView()
                        .getTopInventory().getSize()) {
            return;
        }

        Heart heart = data.hearts.get(menu.heart);

        if (heart == null) {
            player.closeInventory();
            player.sendMessage(Component.text(
                    "This heart no longer exists."
            ));
            return;
        }

        try {
            if (slot == 22) {
                if (event.getClick() == ClickType.SHIFT_RIGHT) {
                    removeHeart(heart);
                    player.closeInventory();

                    player.sendMessage(Component.text(
                            "Heart removed. Rollback has been scheduled "
                                    + "for its recorded blocks."
                    ));
                }
                return;
            }

            if (event.getClick() != ClickType.LEFT
                    && event.getClick() != ClickType.RIGHT) {
                return;
            }

            int direction = event.getClick() == ClickType.LEFT
                    ? 1 : -1;

            switch (slot) {
                case 10 -> heart.radius = InfectionData.clamp(
                        heart.radius + direction * 8, 8, 128);

                case 11 -> heart.spreadSeconds = InfectionData.clamp(
                        heart.spreadSeconds + direction, 1, 60);

                case 12 -> heart.damageAfter = InfectionData.clamp(
                        heart.damageAfter + direction * 15,
                        30, 600);

                case 14 -> heart.defence = !heart.defence;

                case 15 -> heart.guardLimit = InfectionData.clamp(
                        heart.guardLimit + direction, 0, 30);

                case 16 -> heart.cooldown = InfectionData.clamp(
                        heart.cooldown + direction * 5, 5, 120);

                default -> {
                    return;
                }
            }

            data.save();
            draw(menu.inventory, heart);

        } catch (Exception ex) {
            getLogger().severe(
                    "Could not save heart settings: "
                            + ex.getMessage()
            );
            ex.printStackTrace();

            player.closeInventory();
            player.sendMessage(Component.text(
                    "The change could not be saved. "
                            + "Check the server console."
            ));

            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // ---------- Heart interaction and protection ----------

    @EventHandler(ignoreCancelled = true)
    public void onHeartInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }

        Heart heart = coreAt(event.getClickedBlock());

        if (heart == null) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();

        if (player.hasPermission("infection.admin")) {
            player.openInventory(
                    new Menu(heart).getInventory()
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onHeartDamage(BlockDamageEvent event) {
        Heart heart = coreAt(event.getBlock());

        if (heart != null) {
            // Existing cooldown and guard limits still apply.
            defend(heart);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onHeartBreak(BlockBreakEvent event) {
        Heart heart = coreAt(event.getBlock());

        if (heart == null) {
            return;
        }

        // Do not give a vanilla block drop.
        // The recorded original block is restored by rollback.
        event.setCancelled(true);

        try {
            removeHeart(heart);

            event.getPlayer().sendMessage(Component.text(
                    "Heart destroyed. Its infection is receding."
            ));
        } catch (Exception ex) {
            getLogger().severe(
                    "Could not remove heart: " + ex.getMessage()
            );
            ex.printStackTrace();

            event.getPlayer().sendMessage(Component.text(
                    "The heart could not be removed. "
                            + "Check the server console."
            ));
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void protectFromEntityExplosion(
            EntityExplodeEvent event) {

        event.blockList().removeIf(
                block -> coreAt(block) != null
        );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void protectFromBlockExplosion(
            BlockExplodeEvent event) {

        event.blockList().removeIf(
                block -> coreAt(block) != null
        );
    }

    @EventHandler(ignoreCancelled = true)
    public void protectFromPistonExtend(
            BlockPistonExtendEvent event) {

        for (Block block : event.getBlocks()) {
            if (coreAt(block) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void protectFromPistonRetract(
            BlockPistonRetractEvent event) {

        for (Block block : event.getBlocks()) {
            if (coreAt(block) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
