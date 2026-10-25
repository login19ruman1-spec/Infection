package ru.infection;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class InfectionData {

    enum Kind {
        SCULK, PALE;

        Material core() {
            return this == SCULK
                    ? Material.SCULK_CATALYST
                    : Material.CREAKING_HEART;
        }

        Material ground() {
            return this == SCULK
                    ? Material.SCULK
                    : Material.PALE_MOSS_BLOCK;
        }

        String title() {
            return this == SCULK ? "Скалк" : "Бледный мох";
        }

        String folder() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Pos(UUID world, int x, int y, int z) {

        static Pos of(Block block) {
            return new Pos(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        }

        Block loadedBlock() {
            World w = Bukkit.getWorld(world);

            if (w == null
                    || y < w.getMinHeight()
                    || y >= w.getMaxHeight()
                    || !w.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }

            return w.getBlockAt(x, y, z);
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        String key() {
            return world + "," + x + "," + y + "," + z;
        }

        static Pos parse(String text) {
            String[] parts = text.split(",");

            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "Некорректная позиция блока"
                );
            }

            return new Pos(
                    UUID.fromString(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3])
            );
        }
    }

    static final class Heart {
        UUID id;
        Kind kind;
        Pos pos;

        int radius;
        int spreadSeconds;
        int damageAfter;

        boolean defence;
        int guardLimit;
        int cooldown;

        // Временные данные: не требуют сохранения.
        long nextWave;
        final List<Pos> frontier = new ArrayList<>();

        boolean contains(Location location) {
            Location centre = pos.location();

            return centre != null
                    && centre.getWorld().equals(location.getWorld())
                    && centre.distanceSquared(location)
                    <= (double) radius * radius;
        }
    }

    record Edit(UUID owner, String before, String after) {}

    final Map<UUID, Heart> hearts = new LinkedHashMap<>();
    final Map<Pos, Edit> edits = new LinkedHashMap<>();

    private final ArrayDeque<Pos> rollbackQueue =
            new ArrayDeque<>();

    private final Path file;

    InfectionData(Path file) throws Exception {
        this.file = file;
        load();
    }

    boolean canEdit(Heart heart, Block block) {
        Edit edit = edits.get(Pos.of(block));

        return edit == null
                || (edit.owner().equals(heart.id)
                && edit.after().equals(
                        block.getBlockData().getAsString()
                ));
    }

    /*
     * Сначала сохраняем журнал, затем изменяем мир.
     * Используется только для блоков без содержимого:
     * земля, камень, воздух, листья, древесина и заражение.
     */
    boolean apply(Heart heart, Map<Block, BlockData> batch,
                  int maximumRecords) throws IOException {

        if (batch.isEmpty()) {
            return true;
        }

        Map<Pos, Edit> next = new LinkedHashMap<>(edits);

        for (var entry : batch.entrySet()) {
            Block block = entry.getKey();
            Pos pos = Pos.of(block);

            if (pos.loadedBlock() == null
                    || !heart.contains(block.getLocation())
                    || !canEdit(heart, block)) {
                return false;
            }

            Edit previous = edits.get(pos);

            String before = previous == null
                    ? block.getBlockData().getAsString()
                    : previous.before();

            next.put(pos, new Edit(
                    heart.id,
                    before,
                    entry.getValue().getAsString()
            ));
        }

        if (next.size() > maximumRecords) {
            return false;
        }

        write(next);

        edits.clear();
        edits.putAll(next);

        for (var entry : batch.entrySet()) {
            entry.getKey().setBlockData(
                    entry.getValue().clone(), false
            );
        }

        return true;
    }

    void save() throws IOException {
        write(edits);
    }

    void removeHeart(UUID id) throws IOException {
        Heart removed = hearts.remove(id);

        if (removed == null) {
            return;
        }

        try {
            save();
        } catch (IOException ex) {
            hearts.put(id, removed);
            throw ex;
        }

        rebuildRollbackQueue();
    }

    /*
     * Сердца, которых больше нет, оставляют записи
     * в очереди восстановления.
     *
     * Незагруженные чанки принудительно не загружаются.
     */
    void rollbackStep(int budget) throws IOException {
        int checks = Math.min(
                rollbackQueue.size(), budget * 4
        );

        List<Pos> completed = new ArrayList<>();

        while (checks-- > 0 && completed.size() < budget) {
            Pos pos = rollbackQueue.pollFirst();

            if (pos == null) {
                break;
            }

            Edit edit = edits.get(pos);

            if (edit == null || hearts.containsKey(edit.owner())) {
                continue;
            }

            Block block = pos.loadedBlock();

            if (block == null) {
                rollbackQueue.addLast(pos);
                continue;
            }

            // Не затираем последующие изменения игроков.
            if (block.getBlockData().getAsString()
                    .equals(edit.after())) {
                block.setBlockData(
                        Bukkit.createBlockData(edit.before()),
                        false
                );
            }

            completed.add(pos);
        }

        if (!completed.isEmpty()) {
            Map<Pos, Edit> next = new LinkedHashMap<>(edits);
            completed.forEach(next::remove);

            // Восстановление произошло до удаления записи.
            // При обычном повторном запуске старую запись
            // можно безопасно проверить ещё раз.
            write(next);

            edits.clear();
            edits.putAll(next);
        }
    }

    private void rebuildRollbackQueue() {
        rollbackQueue.clear();

        for (var entry : edits.entrySet()) {
            if (!hearts.containsKey(entry.getValue().owner())) {
                rollbackQueue.addLast(entry.getKey());
            }
        }
    }

    private void load() throws Exception {
        if (!Files.exists(file)) {
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());

        ConfigurationSection heartSection =
                yaml.getConfigurationSection("hearts");

        if (heartSection != null) {
            for (String key : heartSection.getKeys(false)) {
                ConfigurationSection row =
                        Objects.requireNonNull(
                                heartSection.getConfigurationSection(key)
                        );

                Heart heart = new Heart();

                heart.id = UUID.fromString(key);
                heart.kind = Kind.valueOf(
                        Objects.requireNonNull(row.getString("kind"))
                );

                heart.pos = Pos.parse(
                        Objects.requireNonNull(row.getString("pos"))
                );

                heart.radius = clamp(
                        row.getInt("radius", 32), 8, 128);
                heart.spreadSeconds = clamp(
                        row.getInt("spread", 3), 1, 60);
                heart.damageAfter = clamp(
                        row.getInt("danger", 90), 30, 600);

                heart.defence = row.getBoolean("defence", true);
                heart.guardLimit = clamp(
                        row.getInt("guards", 6), 0, 30);
                heart.cooldown = clamp(
                        row.getInt("cooldown", 20), 5, 120);

                heart.frontier.add(new Pos(
                        heart.pos.world(),
                        heart.pos.x(),
                        heart.pos.y() - 1,
                        heart.pos.z()
                ));

                hearts.put(heart.id, heart);
            }
        }

        ConfigurationSection blockSection =
                yaml.getConfigurationSection("blocks");

        if (blockSection != null) {
            for (String key : blockSection.getKeys(false)) {
                ConfigurationSection row =
                        Objects.requireNonNull(
                                blockSection.getConfigurationSection(key)
                        );

                Pos pos = Pos.parse(key);

                Edit edit = new Edit(
                        UUID.fromString(Objects.requireNonNull(
                                row.getString("owner"))),
                        Objects.requireNonNull(row.getString("before")),
                        Objects.requireNonNull(row.getString("after"))
                );

                BlockData after =
                        Bukkit.createBlockData(edit.after());

                Bukkit.createBlockData(edit.before());
                edits.put(pos, edit);

                Heart heart = hearts.get(edit.owner());

                if (heart != null
                        && after.getMaterial() == heart.kind.ground()
                        && heart.frontier.size() < 512) {
                    heart.frontier.add(pos);
                }
            }
        }

        rebuildRollbackQueue();
    }

    private void write(Map<Pos, Edit> rows) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();

        for (Heart heart : hearts.values()) {
            String path = "hearts." + heart.id + ".";

            yaml.set(path + "kind", heart.kind.name());
            yaml.set(path + "pos", heart.pos.key());
            yaml.set(path + "radius", heart.radius);
            yaml.set(path + "spread", heart.spreadSeconds);
            yaml.set(path + "danger", heart.damageAfter);
            yaml.set(path + "defence", heart.defence);
            yaml.set(path + "guards", heart.guardLimit);
            yaml.set(path + "cooldown", heart.cooldown);
        }

        for (var entry : rows.entrySet()) {
            String path = "blocks." + entry.getKey().key() + ".";
            Edit edit = entry.getValue();

            yaml.set(path + "owner", edit.owner().toString());
            yaml.set(path + "before", edit.before());
            yaml.set(path + "after", edit.after());
        }

        Files.createDirectories(file.getParent());

        Path temporary = file.resolveSibling(
                file.getFileName() + ".tmp"
        );

        Files.writeString(
                temporary,
                yaml.saveToString(),
                StandardCharsets.UTF_8
        );

        try {
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
