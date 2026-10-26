package ru.infection;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ru.infection.InfectionData.*;

final class InfectedMobs {
    private final InfectionPlugin plugin;
    private final InfectionData data;
    private final InfectionEngine engine;

    final NamespacedKey heartKey;
    final NamespacedKey kindKey;
    final NamespacedKey guardKey;

    private final NamespacedKey aiKey;
    private final NamespacedKey gravityKey;
    private final NamespacedKey invulnerableKey;
    private final NamespacedKey healthKey;

    private final Set<EntityType> supported = Set.of(
            EntityType.ZOMBIE,
            EntityType.SKELETON,
            EntityType.SPIDER,
            EntityType.CREEPER,
            EntityType.VILLAGER,
            EntityType.ZOMBIE_VILLAGER
    );

    private final Map<UUID, Long> waves = new HashMap<>();
    private final Map<UUID, Exposure> exposures = new HashMap<>();

    final Map<UUID, Kind> deathReason = new HashMap<>();

    private long seconds;

    private static final class Exposure {
        UUID heart;
        int seconds;
    }

    InfectedMobs(InfectionPlugin plugin) {
        this.plugin = plugin;
        this.data = plugin.data;
        this.engine = plugin.engine;

        heartKey = new NamespacedKey(plugin, "infected_heart");
        kindKey = new NamespacedKey(plugin, "infected_kind");
        guardKey = new NamespacedKey(plugin, "guard");

        aiKey = new NamespacedKey(plugin, "original_ai");
        gravityKey = new NamespacedKey(plugin, "original_gravity");
        invulnerableKey = new NamespacedKey(
                plugin, "original_invulnerable"
        );
        healthKey = new NamespacedKey(
                plugin, "original_max_health"
        );
    }

    boolean infected(Entity entity) {
        return entity.getPersistentDataContainer().has(
                heartKey,
                PersistentDataType.STRING
        );
    }

    boolean guard(Entity entity) {
        return entity.getPersistentDataContainer().has(
                guardKey,
                PersistentDataType.BYTE
        );
    }

    Heart heart(Entity entity) {
        String value = entity.getPersistentDataContainer().get(
                heartKey,
                PersistentDataType.STRING
        );

        if (value == null) {
            return null;
        }

        try {
            return data.hearts.get(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    Kind kind(Entity entity) {
        String value = entity.getPersistentDataContainer().get(
                kindKey,
                PersistentDataType.STRING
        );

        try {
            return value == null ? null : Kind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static boolean night(World world) {
        long time = world.getTime();
        return time >= 13000 && time < 23000;
    }

    static boolean vulnerable(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE;
    }

    boolean frozen(Entity entity) {
        return infected(entity)
                && kind(entity) == Kind.PALE
                && !night(entity.getWorld());
    }

    private void flag(
            PersistentDataContainer container,
            NamespacedKey key,
            boolean value
    ) {
        container.set(
                key,
                PersistentDataType.BYTE,
                (byte) (value ? 1 : 0)
        );
    }

    private boolean originalFlag(
            PersistentDataContainer container,
            NamespacedKey key,
            boolean fallback
    ) {
        Byte value = container.get(
                key,
                PersistentDataType.BYTE
        );

        return value == null ? fallback : value == 1;
    }

    void infect(
            LivingEntity entity,
            Heart heart,
            boolean defender
    ) {
        if (!supported.contains(entity.getType())
                || infected(entity)
                || entity.isDead()) {
            return;
        }

        PersistentDataContainer pdc =
                entity.getPersistentDataContainer();

        flag(pdc, aiKey, entity.hasAI());
        flag(pdc, gravityKey, entity.hasGravity());
        flag(pdc, invulnerableKey, entity.isInvulnerable());

        AttributeInstance health = entity.getAttribute(
                Attribute.MAX_HEALTH
        );

        if (health != null) {
            double original = health.getBaseValue();

            pdc.set(
                    healthKey,
                    PersistentDataType.DOUBLE,
                    original
            );

            double multiplier = Math.max(
                    1.0,
                    plugin.getConfig().getDouble(
                            "mobs.health-multiplier", 1.3
                    )
            );

            double maximum = Math.min(
                    1024.0,
                    original * multiplier
            );

            double fraction = entity.getHealth()
                    / Math.max(1.0, health.getValue());

            health.setBaseValue(maximum);

            entity.setHealth(Math.max(
                    0.1,
                    Math.min(
                            health.getValue(),
                            health.getValue() * fraction
                    )
            ));
        }

        pdc.set(
                heartKey,
                PersistentDataType.STRING,
                heart.id.toString()
        );

        pdc.set(
                kindKey,
                PersistentDataType.STRING,
                heart.kind.name()
        );

        if (defender) {
            flag(pdc, guardKey, true);
        }

        applyState(entity);
        plugin.models.attach(entity, heart.kind);
    }

    private void restoreFlags(LivingEntity entity) {
        PersistentDataContainer pdc =
                entity.getPersistentDataContainer();

        entity.setAI(originalFlag(pdc, aiKey, true));
        entity.setGravity(
                originalFlag(pdc, gravityKey, true)
        );
        entity.setInvulnerable(
                originalFlag(pdc, invulnerableKey, false)
        );
    }

    private void applyState(LivingEntity entity) {
        if (frozen(entity)) {
            entity.setAI(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setVelocity(new Vector());

            if (entity instanceof Mob mob) {
                mob.setTarget(null);
            }
        } else {
            restoreFlags(entity);
        }
    }

    void cure(LivingEntity entity) {
        plugin.models.clear(entity);

        if (!infected(entity)) {
            return;
        }

        restoreFlags(entity);

        PersistentDataContainer pdc =
                entity.getPersistentDataContainer();

        Double original = pdc.get(
                healthKey,
                PersistentDataType.DOUBLE
        );

        AttributeInstance health = entity.getAttribute(
                Attribute.MAX_HEALTH
        );

        if (original != null && health != null) {
            health.setBaseValue(original);

            if (!entity.isDead()) {
                entity.setHealth(Math.min(
                        entity.getHealth(),
                        health.getValue()
                ));
            }
        }

        pdc.remove(heartKey);
        pdc.remove(kindKey);
        pdc.remove(guardKey);
        pdc.remove(aiKey);
        pdc.remove(gravityKey);
        pdc.remove(invulnerableKey);
        pdc.remove(healthKey);
    }

    void loaded(LivingEntity entity) {
        if (!infected(entity)) {
            return;
        }

        Heart heart = heart(entity);

        if (heart == null || !heart.active) {
            if (guard(entity)) {
                plugin.models.clear(entity);
                entity.remove();
            } else {
                cure(entity);
            }

            return;
        }

        applyState(entity);
        plugin.models.attach(entity, heart.kind);
    }

    void removeHeart(Heart heart) {
        waves.remove(heart.id);

        World world = Bukkit.getWorld(heart.world);

        if (world == null) {
            return;
        }

        for (LivingEntity entity :
                new ArrayList<>(world.getLivingEntities())) {

            Heart owner = heart(entity);

            if (owner == null || !owner.id.equals(heart.id)) {
                continue;
            }

            if (guard(entity)) {
                plugin.models.clear(entity);
                entity.remove();
            } else {
                cure(entity);
            }
        }
    }

    private Player nearestPlayer(Location location, double radius) {
        Player nearest = null;
        double best = radius * radius;

        for (Player player : location.getWorld().getPlayers()) {
            if (!vulnerable(player)
                    || player.isDead()
                    || !player.isValid()) {
                continue;
            }

            double distance = player.getLocation()
                    .distanceSquared(location);

            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }

        return nearest;
    }

    private int loadedGuards(Heart heart) {
        World world = Bukkit.getWorld(heart.world);

        if (world == null) {
            return 0;
        }

        int count = 0;

        for (LivingEntity entity : world.getLivingEntities()) {
            Heart owner = heart(entity);

            if (guard(entity)
                    && owner != null
                    && owner.id.equals(heart.id)
                    && !entity.isDead()) {
                count++;
            }
        }

        return count;
    }

    void provoke(Heart heart) {
        if (!heart.active || !heart.defence) {
            return;
        }

        if (heart.kind == Kind.PALE) {
            World world = Bukkit.getWorld(heart.world);

            if (world == null || !night(world)) {
                return;
            }
        }

        long now = System.currentTimeMillis();

        if (waves.getOrDefault(heart.id, 0L) > now) {
            return;
        }

        long cooldown = Math.max(
                1,
                plugin.getConfig().getInt(
                        "defence.wave-cooldown-seconds", 12
                )
        ) * 1000L;

        waves.put(heart.id, now + cooldown);

        int base = Math.max(
                0,
                plugin.getConfig().getInt(
                        "defence.base-guards", 2
                )
        );

        int perLevel = Math.max(
                0,
                plugin.getConfig().getInt(
                        "defence.guards-per-level", 2
                )
        );

        int cap = Math.max(
                1,
                plugin.getConfig().getInt(
                        "defence.max-loaded-guards-per-heart", 24
                )
        );

        int desired = (int) Math.min(
                cap,
                (long) base + (long) heart.stage * perLevel
        );

        int missing = Math.max(
                0,
                desired - loadedGuards(heart)
        );

        for (int i = 0; i < missing; i++) {
            spawnGuard(heart);
        }
    }

    private void spawnGuard(Heart heart) {
        World world = Bukkit.getWorld(heart.world);

        if (world == null) {
            return;
        }

        EntityType[] types = {
                EntityType.ZOMBIE,
                EntityType.SKELETON,
                EntityType.SPIDER,
                EntityType.CREEPER,
                EntityType.VILLAGER,
                EntityType.ZOMBIE_VILLAGER
        };

        for (int attempt = 0; attempt < 12; attempt++) {
            int x = heart.x + engine.random.nextInt(17) - 8;
            int z = heart.z + engine.random.nextInt(17) - 8;

            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            int y = world.getHighestBlockYAt(
                    x, z,
                    org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES
            ) + 1;

            if (y + 1 >= world.getMaxHeight()
                    || !world.getBlockAt(x, y, z).isPassable()
                    || !world.getBlockAt(x, y + 1, z).isPassable()
                    || world.getBlockAt(x, y - 1, z).isLiquid()) {
                continue;
            }

            Pos pos = new Pos(heart.world, x, y, z);

            if (engine.protectedAt(pos, heart.kind)) {
                continue;
            }

            Entity spawned = world.spawnEntity(
                    new Location(world, x + 0.5, y, z + 0.5),
                    types[engine.random.nextInt(types.length)]
            );

            if (spawned instanceof LivingEntity living
                    && living.isValid()) {
                infect(living, heart, true);

                Player target = nearestPlayer(
                        living.getLocation(), 32
                );

                if (living instanceof Mob mob && target != null) {
                    mob.setTarget(target);
                }
            }

            return;
        }
    }

    private void updateMob(LivingEntity entity)
            throws Exception {

        if (!infected(entity)) {
            if (!supported.contains(entity.getType())
                    || engine.random.nextDouble()
                    >= plugin.getConfig().getDouble(
                            "mobs.infection-chance-per-second",
                            0.25
                    )) {
                return;
            }

            Heart owner = data.owner(entity.getLocation());

            if (owner != null
                    && !engine.protectedAt(
                            Pos.of(entity.getLocation().getBlock()),
                            owner.kind
                    )) {
                infect(entity, owner, false);
            }

            return;
        }

        Heart owner = heart(entity);

        if (owner == null || !owner.active) {
            loaded(entity);
            return;
        }

        applyState(entity);

        if (frozen(entity)) {
            return;
        }

        Player target = nearestPlayer(
                entity.getLocation(), 24
        );

        if (target != null && entity instanceof Mob mob) {
            mob.setTarget(target);
        }

        // У жителей нет обычной атаки ближнего боя.
        // Для заражённых жителей используется движение
        // к игроку и контактный урон.
        if (entity instanceof Villager && target != null) {
            Vector direction = target.getLocation().toVector()
                    .subtract(entity.getLocation().toVector());

            direction.setY(0);

            if (direction.lengthSquared() > 0.1) {
                direction.normalize().multiply(0.22);

                direction.setY(
                        entity.isOnGround()
                                ? 0.12
                                : entity.getVelocity().getY()
                );

                entity.setVelocity(direction);
            }

            double radius = Math.max(
                    0.5,
                    plugin.getConfig().getDouble(
                            "mobs.villager-aura-radius", 2.2
                    )
            );

            if (target.getLocation().distanceSquared(
                    entity.getLocation()
            ) <= radius * radius) {

                target.damage(
                        Math.max(
                                0,
                                plugin.getConfig().getDouble(
                                        "mobs.villager-aura-damage",
                                        1.0
                                )
                        ),
                        entity
                );
            }
        }
    }

    private void updateExposure(Player player)
            throws Exception {

        if (!vulnerable(player) || player.isDead()) {
            forget(player.getUniqueId());
            return;
        }

        Heart owner = data.owner(player.getLocation());

        if (owner != null
                && engine.protectedAt(
                        Pos.of(player.getLocation().getBlock()),
                        owner.kind
                )) {
            owner = null;
        }

        Exposure exposure = exposures.get(player.getUniqueId());

        if (owner == null) {
            if (exposure != null) {
                exposure.seconds -= Math.max(
                        1,
                        plugin.getConfig().getInt(
                                "hallucinations.recovery-per-second",
                                3
                        )
                );

                if (exposure.seconds <= 0) {
                    exposures.remove(player.getUniqueId());
                }
            }

            return;
        }

        if (exposure == null
                || !owner.id.equals(exposure.heart)) {
            exposure = new Exposure();
            exposure.heart = owner.id;

            exposures.put(player.getUniqueId(), exposure);
        }

        exposure.seconds++;

        if (plugin.getConfig().getBoolean(
                "hallucinations.visual-effects", true
        ) && exposure.seconds >= Math.max(3, owner.damage / 3)) {

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.DARKNESS,
                    50, 0,
                    false, false, false
            ));

            if (exposure.seconds >= owner.damage * 2L / 3) {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.NAUSEA,
                        80, 0,
                        false, false, false
                ));
            }
        }

        if (plugin.getConfig().getBoolean(
                "hallucinations.sounds", true
        ) && exposure.seconds % 5 == 0) {

            Location sound = player.getLocation().add(
                    engine.random.nextDouble() * 8 - 4,
                    0,
                    engine.random.nextDouble() * 8 - 4
            );

            player.playSound(
                    sound,
                    owner.kind == Kind.SCULK
                            ? "minecraft:entity.warden.heartbeat"
                            : "minecraft:entity.creaking.ambient",
                    SoundCategory.AMBIENT,
                    0.7f,
                    0.8f + engine.random.nextFloat() * 0.3f
            );
        }

        if (exposure.seconds >= owner.damage) {
            double damage = Math.max(
                    0,
                    plugin.getConfig().getDouble(
                            "hallucinations.damage-per-second",
                            1.0
                    )
            );

            if (damage > 0) {
                deathReason.put(player.getUniqueId(), owner.kind);

                try {
                    player.damage(damage);
                } finally {
                    deathReason.remove(player.getUniqueId());
                }
            }
        }
    }

    // Вызывать раз в секунду, а не каждый серверный тик.
    void tick() throws Exception {
        seconds++;

        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity :
                    new ArrayList<>(world.getLivingEntities())) {

                if (!(entity instanceof Player)
                        && entity.isValid()
                        && !entity.isDead()) {
                    updateMob(entity);
                }
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            updateExposure(player);
        }

        for (Heart heart : new ArrayList<>(data.hearts.values())) {
            Location location = heart.location();

            if (!heart.active
                    || location == null
                    || heart.core().loadedBlock() == null) {
                continue;
            }

            if (nearestPlayer(location, 24) != null) {
                provoke(heart);
            }

            if (seconds % 2 == 0) {
                location.getWorld().spawnParticle(
                        heart.kind == Kind.SCULK
                                ? Particle.SCULK_SOUL
                                : Particle.ASH,
                        location.clone().add(0, 0.5, 0),
                        8, 1.4, 1.4, 1.4, 0.02
                );
            }
        }
    }

    void forget(UUID player) {
        exposures.remove(player);
        deathReason.remove(player);
    }

    void shutdown() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (infected(entity)) {
                    // Сохраняем метку заражения, но не оставляем
                    // замороженное состояние при отключённом плагине.
                    restoreFlags(entity);
                    plugin.models.clear(entity);
                }
            }
        }

        exposures.clear();
        deathReason.clear();
        waves.clear();
    }
}
