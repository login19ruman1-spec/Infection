package ru.infection;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;

import java.util.*;

import ru.infection.InfectionData.Heart;
import ru.infection.InfectionData.Kind;

final class InfectedMobs implements Listener {

    static final Set<EntityType> TYPES = EnumSet.of(
            EntityType.ZOMBIE,
            EntityType.SKELETON,
            EntityType.SPIDER,
            EntityType.CREEPER,
            EntityType.VILLAGER,
            EntityType.ZOMBIE_VILLAGER
    );

    private static final class State {
        final LivingEntity entity;
        final Heart heart;
        final List<BlockDisplay> displays;
        Location frozen;

        State(LivingEntity entity, Heart heart,
              List<BlockDisplay> displays) {
            this.entity = entity;
            this.heart = heart;
            this.displays = displays;
        }
    }

    private final InfectionPlugin plugin;
    private final ModelLoader models;

    private final Map<UUID, State> states = new HashMap<>();

    private final NamespacedKey ownerKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey guardKey;

    private final NamespacedKey aiKey;
    private final NamespacedKey gravityKey;
    private final NamespacedKey invisibleKey;

    InfectedMobs(InfectionPlugin plugin, ModelLoader models) {
        this.plugin = plugin;
        this.models = models;

        ownerKey = new NamespacedKey(plugin, "owner");
        kindKey = new NamespacedKey(plugin, "kind");
        guardKey = new NamespacedKey(plugin, "guard");

        aiKey = new NamespacedKey(plugin, "old_ai");
        gravityKey = new NamespacedKey(plugin, "old_gravity");
        invisibleKey = new NamespacedKey(plugin, "old_invisible");

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    int size() {
        return states.size();
    }

    private UUID owner(LivingEntity entity) {
        String value = entity.getPersistentDataContainer().get(
                ownerKey, PersistentDataType.STRING
        );

        if (value == null) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    Kind kind(Entity entity) {
        String value = entity.getPersistentDataContainer().get(
                kindKey, PersistentDataType.STRING
        );

        if (value == null) {
            return null;
        }

        try {
            return Kind.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    boolean isGuard(LivingEntity entity, UUID heart) {
        var data = entity.getPersistentDataContainer();

        return heart.equals(owner(entity))
                && Byte.valueOf((byte) 1).equals(data.get(
                        guardKey, PersistentDataType.BYTE
                ));
    }

    void infect(LivingEntity entity, Heart heart, boolean guard) {
        if (!TYPES.contains(entity.getType())
                || owner(entity) != null) {
            return;
        }

        var data = entity.getPersistentDataContainer();

        data.set(ownerKey, PersistentDataType.STRING,
                heart.id.toString());
        data.set(kindKey, PersistentDataType.STRING,
                heart.kind.name());
        data.set(guardKey, PersistentDataType.BYTE,
                (byte) (guard ? 1 : 0));

        data.set(aiKey, PersistentDataType.BYTE,
                (byte) (entity.hasAI() ? 1 : 0));
        data.set(gravityKey, PersistentDataType.BYTE,
                (byte) (entity.hasGravity() ? 1 : 0));
        data.set(invisibleKey, PersistentDataType.BYTE,
                (byte) (entity.isInvisible() ? 1 : 0));

        attach(entity, heart);
    }

    private void attach(LivingEntity entity, Heart heart) {
        List<BlockDisplay> displays =
                models.spawn(entity, heart.kind);

        if (!displays.isEmpty()) {
            entity.setInvisible(true);
        }

        states.put(
                entity.getUniqueId(),
                new State(entity, heart, displays)
        );
    }

    private boolean old(
            LivingEntity entity,
            NamespacedKey key,
            boolean fallback) {

        Byte value = entity.getPersistentDataContainer().get(
                key, PersistentDataType.BYTE
        );

        return value == null ? fallback : value != 0;
    }

    private void restore(LivingEntity entity) {
        entity.setAI(old(entity, aiKey, true));
        entity.setGravity(old(entity, gravityKey, true));
        entity.setInvisible(old(entity, invisibleKey, false));
    }

    private void detach(State state) {
        state.displays.forEach(Entity::remove);

        if (state.entity.isValid()) {
            restore(state.entity);
        }
    }

    private void cure(LivingEntity entity) {
        State state = states.remove(entity.getUniqueId());

        if (state != null) {
            detach(state);
        } else {
            restore(entity);
        }

        var data = entity.getPersistentDataContainer();

        for (NamespacedKey key : List.of(
                ownerKey, kindKey, guardKey,
                aiKey, gravityKey, invisibleKey)) {
            data.remove(key);
        }
    }

    /*
     * Проверяет и ещё не зарегистрированных мобов,
     * например после загрузки чанка.
     */
    void scan() {
        int maximum = Math.max(1,
                plugin.getConfig().getInt(
                        "mobs.max-loaded-infected", 40));

        double chance = Math.max(0, Math.min(1,
                plugin.getConfig().getDouble(
                        "mobs.infection-chance", 0.1)));

        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (!TYPES.contains(entity.getType())
                        || !entity.isValid()
                        || entity.isDead()) {
                    continue;
                }

                UUID owner = owner(entity);

                if (owner != null) {
                    Heart heart = plugin.data.hearts.get(owner);

                    if (heart == null) {
                        boolean guard = isGuard(entity, owner);
                        cure(entity);

                        if (guard) {
                            entity.remove();
                        }

                        continue;
                    }

                    if (!states.containsKey(entity.getUniqueId())
                            && states.size() < maximum) {
                        attach(entity, heart);
                    }

                    continue;
                }

                if (states.size() >= maximum) {
                    continue;
                }

                Heart heart = plugin.heartAt(entity.getLocation());

                if (heart != null
                        && plugin.random.nextDouble() < chance) {
                    infect(entity, heart, false);
                }
            }
        }
    }

    void tick(long ticks) {
        Iterator<State> iterator = states.values().iterator();

        while (iterator.hasNext()) {
            State state = iterator.next();
            LivingEntity entity = state.entity;

            if (!entity.isValid() || entity.isDead()) {
                state.displays.forEach(Entity::remove);
                iterator.remove();
                continue;
            }

            // Излечение выполняет scan().
            if (!plugin.data.hearts.containsKey(state.heart.id)) {
                continue;
            }

            boolean freeze = state.heart.kind == Kind.PALE
                    && plugin.isDay(entity.getWorld());

            if (freeze) {
                if (state.frozen == null) {
                    state.frozen = entity.getLocation().clone();
                }

                entity.setAI(false);
                entity.setGravity(false);
                entity.setVelocity(new Vector());

                if (entity instanceof Mob mob) {
                    mob.setTarget(null);
                }

                if (entity.getLocation().distanceSquared(
                        state.frozen) > 0.0001) {
                    entity.teleport(state.frozen);
                }
            } else {
                if (state.frozen != null) {
                    entity.setAI(old(entity, aiKey, true));
                    entity.setGravity(
                            old(entity, gravityKey, true));
                    state.frozen = null;
                }

                if (ticks % 100 == 0) {
                    aura(state);
                }
            }

            Location position = state.frozen == null
                    ? entity.getLocation()
                    : state.frozen.clone();

            position.setPitch(0);

            for (BlockDisplay display : state.displays) {
                if (display.isValid()) {
                    display.teleport(position);
                }
            }

            if (ticks % 20 == 0) {
                entity.getWorld().spawnParticle(
                        state.heart.kind == Kind.SCULK
                                ? Particle.SCULK_SOUL
                                : Particle.ASH,
                        entity.getLocation().add(0, 1, 0),
                        3, 0.3, 0.5, 0.3, 0.01
                );
            }
        }
    }

    private void aura(State state) {
        for (Player player : state.entity.getWorld().getPlayers()) {
            if (!plugin.vulnerable(player)) {
                continue;
            }

            double distance = player.getLocation().distanceSquared(
                    state.entity.getLocation()
            );

            if (distance > 36) {
                continue;
            }

            if (state.heart.kind == Kind.SCULK) {
                player.playSound(
                        player.getLocation(),
                        "minecraft:entity.warden.heartbeat",
                        SoundCategory.AMBIENT,
                        0.5f, 0.7f
                );
            } else {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS,
                        40, 0, false, true, true
                ));
            }

            if (state.entity.getType() == EntityType.VILLAGER
                    && distance <= 6.25) {
                plugin.infectionDamage(
                        player, state.heart.kind, 1
                );
            }
        }
    }

    void reloadModels() {
        close();
        scan();
    }

    void close() {
        states.values().forEach(this::detach);
        states.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            State state = states.remove(entity.getUniqueId());

            if (state != null) {
                detach(state);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (kind(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = true)
    public void onFrozenDamage(EntityDamageEvent event) {
        if (kind(event.getEntity()) == Kind.PALE
                && plugin.isDay(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Entity attacker = event.getDamager();

        if (attacker instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }

        Kind kind = kind(attacker);

        if (kind == null) {
            return;
        }

        if (kind == Kind.PALE
                && plugin.isDay(attacker.getWorld())) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getEntity() instanceof Player player)
                || !plugin.vulnerable(player)) {
            return;
        }

        PotionEffectType effect;

        if (attacker.getType() == EntityType.SPIDER) {
            effect = PotionEffectType.POISON;
        } else if (kind == Kind.SCULK) {
            effect = PotionEffectType.WEAKNESS;
        } else {
            effect = PotionEffectType.SLOWNESS;
        }

        player.addPotionEffect(new PotionEffect(
                effect, 60, 0, false, true, true
        ));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (kind(event.getEntity()) == Kind.PALE
                && plugin.isDay(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (kind(event.getEntity()) != null) {
            // Защитники не разрушают мир вне журнала отката.
            event.blockList().clear();
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        State state = states.remove(
                event.getEntity().getUniqueId());

        if (state != null) {
            state.displays.forEach(Entity::remove);
        }

        UUID owner = owner(event.getEntity());

        if (owner != null && isGuard(event.getEntity(), owner)) {
            // Защита от бесконечной фермы дропа защитников.
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }
}
