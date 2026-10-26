package ru.infection;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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

    private final Map<UUID, Long> wave = new HashMap<>();
    private final Map<UUID, Exposure> exposure = new HashMap<>();

    final Map<UUID, Kind> deathReason = new HashMap<>();

    static final class Exposure {
        UUID heart;
        int seconds;
    }

    InfectedMobs(InfectionPlugin plugin) {
        this.plugin = plugin;
        data = plugin.data;
        engine = plugin.engine;

        heartKey = new NamespacedKey(plugin, "infected_heart");
        kindKey = new NamespacedKey(plugin, "infected_kind");
        guardKey = new NamespacedKey(plugin, "guard");

        aiKey = new NamespacedKey(plugin, "original_ai");
        gravityKey = new NamespacedKey(plugin, "original_gravity");
        invulnerableKey = new NamespacedKey(plugin, "original_invulnerable");
        healthKey = new NamespacedKey(plugin, "original_max_health");
    }

    boolean infected(Entity entity) {
        return entity.getPersistentDataContainer().has(
                heartKey, PersistentDataType.STRING
        );
    }

    boolean guard(Entity entity) {
        return entity.getPersistentDataContainer().has(
                guardKey, PersistentDataType.BYTE
        );
    }

    Heart heart(Entity entity) {
        String value = entity.getPersistentDataContainer().get(
                heartKey, PersistentDataType.STRING
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
                kindKey, PersistentDataType.STRING
        );

        try {
            return value == null ? null : Kind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    boolean frozen(Entity entity) {
        Kind kind = kind(entity);

        return infected(entity)
                && kind == Kind.PALE
                && !night(entity.getWorld());
    }

    static boolean night(World world) {
        long time = world.getTime();
        return time >= 13000 && time < 23000;
    }

    static boolean vulnerable(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE;
    }

    private void flag(
            PersistentDataContainer container,
            NamespacedKey key,
            boolean value
    ) {
        container.set(
                key,
                PersistentDataType.BYTE,
                (byte) (
