package ru.infection;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.CreakingHeart;
import org.bukkit.block.data.type.SculkShrieker;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static ru.infection.InfectionData.*;

final class InfectionEngine {
    private final InfectionPlugin plugin;
    private final InfectionData data;
    final Random random = new Random();

    private final Queue<Job> jobs = new ArrayDeque<>();
    private final Set<UUID> growing = new HashSet<>();
    private final Set<UUID> layering = new HashSet<>();

    private final Map<UUID, Double> credits = new HashMap<>();
    private final Map<UUID, Integer> chunkCursor = new HashMap<>();

    private final Map<String, Set<Pos>> lights = new HashMap<>();
    private final Queue<Scan> scans = new ArrayDeque<>();
    private final Set<String> scanning = new HashSet<>();

    private final Map<Long, Cell> burning = new LinkedHashMap<>();

    private int lightCursor;
    private int rollbackCursor;
    private long ticks;

    record Change(Pos pos, BlockData data, String role) {}

    @FunctionalInterface
    interface Finished {
        void run(boolean changed) throws Exception;
    }

    static final class Job {
        final Heart heart;
        final Queue<Change> changes;
        final Finished finished;
        boolean changed;

        Job(Heart heart, List<Change> changes, Finished finished) {
            this.heart = heart;
            this.changes = new ArrayDeque<>(changes);
            this.finished = finished;
        }
    }

    static final class Scan {
        final UUID world;
        final int cx;
        final int cz;
        int column;

        Scan(Chunk chunk) {
            world = chunk.getWorld().getUID();
            cx = chunk.getX();
            cz = chunk.getZ();
        }
    }

    InfectionEngine(InfectionPlugin plugin, InfectionData data) {
        this.plugin = plugin;
        this.data = data;

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scan(chunk);
            }
        }
    }

    static String key(UUID world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    void scan(Chunk chunk) {
        String key = key(
                chunk.getWorld().getUID(),
                chunk.getX(), chunk.getZ()
        );

        if (scanning.add(key)) {
            lights.remove(key);
            scans.add(new Scan(chunk));
        }
    }

    void unload(Chunk chunk) {
        lights.remove(key(
                chunk.getWorld().getUID(),
                chunk.getX(), chunk.getZ()
        ));
    }

    Kind lightKind(Block block) {
        Material type = block.getType();

        if (type == Material.TORCH
                || type == Material.WALL_TORCH) {
            return Kind.PALE;
        }

        if (type == Material.SOUL_TORCH
                || type == Material.SOUL_WALL_TORCH) {
            return Kind.SCULK;
        }

        if (type == Material.CAMPFIRE
                || type == Material.SOUL_CAMPFIRE) {

            if (!(block.getBlockData() instanceof Lightable lit)
                    || !lit.isLit()) {
                return null;
            }

            return type == Material.CAMPFIRE
                    ? Kind.PALE
                    : Kind.SCULK;
        }

        return null;
    }

    static boolean lightMaterial(Material material) {
        return material == Material.TORCH
                || material == Material.WALL_TORCH
                || material == Material.SOUL_TORCH
                || material == Material.SOUL_WALL_TORCH
                || material == Material.CAMPFIRE
                || material == Material.SOUL_CAMPFIRE;
    }

    void lightChanged(Block block) {
        Pos pos = Pos.of(block);
        String key = key(
                pos.world(), pos.x() >> 4, pos.z() >> 4
        );

        Set<Pos> set = lights.computeIfAbsent(
                key, ignored -> new HashSet<>()
        );

        if (lightKind(block) == null) {
            set.remove(pos);
        } else {
            set.add(pos);
        }
    }

    boolean protectedAt(Pos pos, Kind kind) {
        int cx = pos.x() >> 4;
        int cz = pos.z() >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Set<Pos> candidates = lights.get(
                        key(pos.world(), cx + dx, cz + dz)
                );

                if (candidates == null) {
                    continue;
                }

                for (Pos source : candidates) {
                    int x = source.x() - pos.x();
                    int y = source.y() - pos.y();
                    int z = source.z() - pos.z();

                    if (Math.abs(x) > 7
                            || Math.abs(y) > 7
                            || Math.abs(z) > 7
                            || x * x + y * y + z * z > 49) {
                        continue;
                    }

                    Block block = source.loadedBlock();

                    if (block != null && lightKind(block) == kind) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void scanLights() {
        int budget = Math.max(1, plugin.getConfig().getInt(
                "performance.light-scan-columns-per-tick", 8
        ));

        while (budget-- > 0 && !scans.isEmpty()) {
            Scan scan = scans.peek();
            World world = Bukkit.getWorld(scan.world);

            if (world == null
                    || !world.isChunkLoaded(scan.cx, scan.cz)) {
                scanning.remove(key(scan.world, scan.cx, scan.cz));
                scans.remove();
                continue;
            }

            int x = (scan.cx << 4) + scan.column % 16;
            int z = (scan.cz << 4) + scan.column / 16;

            for (int y = world.getMinHeight();
                 y < world.getMaxHeight(); y++) {

                Block block = world.getBlockAt(x, y, z);

                if (lightMaterial(block.getType())) {
                    lightChanged(block);
                }
            }

            scan.column++;

            if (scan.column >= 256) {
                scanning.remove(key(scan.world, scan.cx, scan.cz));
                scans.remove();
            }
        }
    }

    private boolean indexed(UUID world, int x, int z) {
        return !scanning.contains(
                key(world, x >> 4, z >> 4)
        );
    }

    private boolean safe(Block block) {
        Material type = block.getType();

        if (lightMaterial(type)) {
            return false;
        }

        return switch (type) {
            case BEDROCK,
                 BARRIER,
                 END_PORTAL,
                 END_PORTAL_FRAME,
                 NETHER_PORTAL,
                 COMMAND_BLOCK,
                 CHAIN_COMMAND_BLOCK,
                 REPEATING_COMMAND_BLOCK,
                 STRUCTURE_BLOCK,
                 STRUCTURE_VOID,
                 JIGSAW,
                 LIGHT -> false;
            default -> true;
        };
    }

    private boolean put(
            Heart heart, Pos pos, BlockData next, String role
    ) throws Exception {

        Block block = pos.loadedBlock();

        if (block == null || !safe(block)) {
            return false;
        }

        if (!role.equals("core")
                && protectedAt(pos, heart.kind)) {
            return false;
        }

        return data.replace(heart, block, next, role);
    }

    Heart create(Location ground, Kind kind) throws Exception {
        World world = ground.getWorld();

        Heart heart = new Heart();
        heart.id = UUID.randomUUID();
        heart.world = world.getUID();
        heart.kind = kind;

        heart.x = ground.getBlockX();
        heart.z = ground.getBlockZ();
        heart.ground = ground.getBlockY();

        int height = Math.clamp(
                plugin.getConfig().getInt(
                        "defaults.heart-height", 7
                ), 4, 10
        );

        heart.y = heart.ground + height;

        if (heart.y + 2 >= world.getMaxHeight()) {
            throw new IllegalArgumentException(
                    "Недостаточно места по высоте."
            );
        }

        heart.depth = Math.clamp(
                plugin.getConfig().getInt("defaults.depth", 8),
                1,
                world.getMaxHeight() - world.getMinHeight()
        );

        heart.speed = Math.max(1, plugin.getConfig().getInt(
                "defaults.columns-per-second", 24
        ));

        heart.damage = Math.max(5, plugin.getConfig().getInt(
                "defaults.damage-after-seconds", 45
        ));

        heart.defence = plugin.getConfig().getBoolean(
                "defaults.defence", true
        );

        List<Change> body = new ArrayList<>();

        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean shape =
                            dy == 2 ? Math.abs(dx) == 1 :
                            dy == 1 ? Math.abs(dx) <= 2 :
                            dy == 0 ? Math.abs(dx) <= 2 :
                            dy == -1 ? Math.abs(dx) <= 1 :
                            dx == 0;

                    if (!shape) {
                        continue;
                    }

                    Pos pos = new Pos(
                            heart.world,
                            heart.x + dx,
                            heart.y + dy,
                            heart.z + dz
                    );

                    Block block = pos.loadedBlock();

                    if (block == null
                            || !block.getType().isAir()
                            || data.cell(block) != null
                            || protectedAt(pos, kind)) {

                        throw new IllegalArgumentException(
                                "Для сердца нужно свободное место "
                                        + "без другого заражения и защиты огнём."
                        );
                    }

                    boolean core = dx == 0 && dy == 0 && dz == 0;

                    Material material = core
                            ? kind.core()
                            : kind == Kind.SCULK
                                ? Material.SCULK
                                : Material.PALE_OAK_WOOD;

                    body.add(new Change(
                            pos,
                            material.createBlockData(),
                            core ? "core" : "skin"
                    ));
                }
            }
        }

        data.save(heart);

        try {
            for (Change change : body) {
                put(
                        heart,
                        change.pos(),
                        change.data(),
                        change.role()
                );
            }
        } catch (Exception exception) {
            data.remove(heart);
            throw exception;
        }

        return heart;
    }

    void tick() throws Exception {
        ticks++;
        scanLights();

        int burnBudget = Math.max(1, plugin.getConfig().getInt(
                "performance.restore-per-tick", 80
        ));

        var burningIterator = burning.entrySet().iterator();

        while (burnBudget-- > 0 && burningIterator.hasNext()) {
            Cell cell = burningIterator.next().getValue();
            burningIterator.remove();

            Heart heart = data.hearts.get(cell.heart());

            if (heart == null || !heart.active
                    || cell.role().equals("core")
                    || !protectedAt(cell.pos(), heart.kind)) {
                continue;
            }

            if (data.restore(cell)) {
                data.reopenSurface(cell);

                Location location = cell.pos().location();

                if (location != null) {
                    location.getWorld().spawnParticle(
                            org.bukkit.Particle.SMOKE,
                            location.clone().add(0, 0.5, 0),
                            2, 0.2, 0.2, 0.2, 0.01
                    );
                }
            }
        }

        int budget = Math.max(1, plugin.getConfig().getInt(
                "performance.changes-per-tick", 60
        ));

        int stalled = 0;

        while (budget > 0 && !jobs.isEmpty()) {
            Job job = jobs.remove();

            if (!job.heart.active) {
                job.finished.run(false);
                continue;
            }

            Change change = job.changes.peek();

            if (change == null) {
                job.finished.run(job.changed);
                continue;
            }

            if (change.pos().loadedBlock() == null) {
                jobs.add(job);

                if (++stalled >= jobs.size()) {
                    break;
                }

                continue;
            }

            stalled = 0;
            job.changes.remove();

            if (put(
                    job.heart,
                    change.pos(),
                    change.data(),
                    change.role()
            )) {
                job.changed = true;

                if (change.data() instanceof CreakingHeart) {
                    change.pos().loadedBlock()
                            .getState().update(true, true);
                }
            }

            budget--;

            if (job.changes.isEmpty()) {
                job.finished.run(job.changed);
            } else {
                jobs.add(job);
            }
        }

        for (Heart heart : new ArrayList<>(data.hearts.values())) {
            if (!heart.active) {
                continue;
            }

            if (ticks % 20 == 0) {
                growVeins(heart);
            }

            if (heart.vein < heart.y - heart.ground) {
                continue;
            }

            double credit = Math.min(
                    heart.speed,
                    credits.getOrDefault(heart.id, 0.0)
                            + heart.speed / 20.0
            );

            if (credit >= 1 && !growing.contains(heart.id)) {
                if (scheduleColumn(heart)) {
                    credit -= 1;
                }
            }

            credits.put(heart.id, credit);

            if (ticks % 100 == 0 && heart.defence) {
                int step = Math.max(1, plugin.getConfig().getInt(
                        "defence.area-step", 512
                ));

                int target = (int) Math.sqrt(
                        data.area(heart) / (double) step
                );

                if (target > heart.stage
                        && !layering.contains(heart.id)) {
                    scheduleLayer(heart, heart.stage + 1);
                }
            }
        }

        if (ticks % 5 == 0) {
            findBurning();
            rollback();
        }

        if (ticks % 200 == 0) {
            for (Heart heart : new ArrayList<>(data.hearts.values())) {
                data.finishRemoved(heart);
            }
        }
    }

    private void growVeins(Heart heart) throws Exception {
        if (heart.core().loadedBlock() == null) {
            return;
        }

        int distance = heart.y - heart.ground;

        if (heart.vein >= distance) {
            return;
        }

        int y = heart.y - heart.vein - 1;
        boolean changed = false;

        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                Pos pos = new Pos(
                        heart.world,
                        heart.x + dx,
                        y,
                        heart.z + dz
                );

                changed |= put(
                        heart, pos,
                        heart.kind.ground().createBlockData(),
                        "vein"
                );
            }
        }

        if (!changed) {
            return;
        }

        heart.vein++;
        data.save(heart);

        if (heart.vein >= distance) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    data.seed(
                            heart,
                            heart.x + dx,
                            heart.z + dz,
                            heart.ground
                    );
                }
            }
        }
    }

    private boolean scheduleColumn(Heart heart)
            throws Exception {

        World world = Bukkit.getWorld(heart.world);

        if (world == null) {
            return false;
        }

        Chunk[] chunks = world.getLoadedChunks();

        if (chunks.length == 0) {
            return false;
        }

        int cursor = chunkCursor.getOrDefault(heart.id, 0);
        int attempts = Math.min(
                chunks.length,
                Math.max(1, plugin.getConfig().getInt(
                        "performance.frontier-chunks-per-search", 24
                ))
        );

        Column column = null;

        for (int i = 0; i < attempts; i++) {
            Chunk chunk = chunks[Math.floorMod(cursor++, chunks.length)];

            if (!indexed(
                    heart.world,
                    chunk.getX() << 4,
                    chunk.getZ() << 4
            )) {
                continue;
            }

            column = data.pending(
                    heart, chunk.getX(), chunk.getZ()
            );

            if (column != null) {
                break;
            }
        }

        chunkCursor.put(heart.id, cursor);

        if (column == null) {
            return false;
        }

        Column selected = column;

        Pos surface = new Pos(
                heart.world,
                selected.x(), selected.y(), selected.z()
        );

        if (protectedAt(surface, heart.kind)) {
            data.postpone(heart, selected);
            return false;
        }

        List<Change> plan = new ArrayList<>();

        for (int i = 0; i < heart.depth; i++) {
            int y = selected.y() - i;

            if (y < world.getMinHeight()) {
                break;
            }

            Block block = world.getBlockAt(
                    selected.x(), y, selected.z()
            );

            if (!block.getType().isAir() && safe(block)) {
                plan.add(new Change(
                        Pos.of(block),
                        heart.kind.ground().createBlockData(),
                        "ground"
                ));
            }
        }

        growing.add(heart.id);

        jobs.add(new Job(heart, plan, changed -> {
            growing.remove(heart.id);

            if (!heart.active) {
                return;
            }

            if (!changed) {
                data.postpone(heart, selected);
                return;
            }

            data.columnDone(heart, selected);

            seedNeighbour(
                    heart, selected.x() + 1,
                    selected.z(), selected.y()
            );

            seedNeighbour(
                    heart, selected.x() - 1,
                    selected.z(), selected.y()
            );

            seedNeighbour(
                    heart, selected.x(),
                    selected.z() + 1, selected.y()
            );

            seedNeighbour(
                    heart, selected.x(),
                    selected.z() - 1, selected.y()
            );

            decorate(heart, selected);
        }));

        return true;
    }

    private void seedNeighbour(
            Heart heart, int x, int z, int fallback
    ) throws Exception {

        World world = Bukkit.getWorld(heart.world);
        int top = fallback;

        if (world != null && world.isChunkLoaded(x >> 4, z >> 4)) {
            top = world.getHighestBlockYAt(
                    x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES
            );

            // Не считать украшения самого плагина новой поверхностью.
            while (top > world.getMinHeight()) {
                Block block = world.getBlockAt(x, top, z);
                Cell cell = data.cell(block);

                if (cell == null || cell.role().equals("ground")) {
                    break;
                }

                top--;
            }
        }

        data.seed(heart, x, z, top);
    }

    private void decorate(Heart heart, Column column)
            throws Exception {

        World world = Bukkit.getWorld(heart.world);

        if (world == null) {
            return;
        }

        Pos above = new Pos(
                heart.world,
                column.x(), column.y() + 1, column.z()
        );

        Block block = above.loadedBlock();

        if (block == null || !block.getType().isAir()) {
            return;
        }

        List<Change> plan = new ArrayList<>();

        if (heart.kind == Kind.SCULK) {
            double chance = plugin.getConfig().getDouble(
                    "decorations.sculk-block-chance", 0.18
            );

            if (random.nextDouble() < chance) {
                Material[] types = {
                        Material.SCULK_SENSOR,
                        Material.SCULK_SHRIEKER,
                        Material.SCULK_CATALYST,
                        Material.SCULK_VEIN
                };

                BlockData decoration = types[
                        random.nextInt(types.length)
                ].createBlockData();

                if (decoration instanceof SculkShrieker shrieker) {
                    shrieker.setCanSummon(true);
                }

                if (decoration instanceof MultipleFacing facing
                        && facing.getAllowedFaces().contains(
                                BlockFace.DOWN
                        )) {
                    facing.setFace(BlockFace.DOWN, true);
                }

                plan.add(new Change(above, decoration, "decor"));
            }

            if (random.nextDouble()
                    < plugin.getConfig().getDouble(
                            "decorations.tentacle-chance", 0.025
                    )) {

                int height = 4 + random.nextInt(8);
                double direction = random.nextDouble() * Math.PI * 2;

                for (int i = 0; i < height; i++) {
                    int bend = i * i / Math.max(1, height * 2);

                    Pos pos = new Pos(
                            heart.world,
                            column.x() + (int) Math.round(
                                    Math.cos(direction) * bend
                            ),
                            column.y() + i,
                            column.z() + (int) Math.round(
                                    Math.sin(direction) * bend
                            )
                    );

                    plan.add(new Change(
                            pos,
                            Material.SCULK.createBlockData(),
                            "decor"
                    ));
                }
            }
        } else if (random.nextDouble()
                < plugin.getConfig().getDouble(
                        "decorations.pale-tree-chance", 0.035
                )) {

            plan.addAll(treePlan(heart, column));
        }

        if (!plan.isEmpty()) {
            jobs.add(new Job(heart, plan, ignored -> {}));
        }
    }

    private List<Change> treePlan(Heart heart, Column column) {
        World world = Bukkit.getWorld(heart.world);

        if (world == null) {
            return List.of();
        }

        // Не позволять генератору дерева незаметно загрузить соседние чанки.
        for (int x = column.x() - 16; x <= column.x() + 16; x += 16) {
            for (int z = column.z() - 16; z <= column.z() + 16; z += 16) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return List.of();
                }
            }
        }

        Map<Pos, BlockData> captured = new LinkedHashMap<>();

        Predicate<BlockState> capture = state -> {
            captured.put(
                    Pos.of(state.getBlock()),
                    state.getBlockData().clone()
            );

            // Генератор ничего не ставит напрямую:
            // все изменения пройдут через наш журнал.
            return false;
        };

        world.generateTree(
                new Location(
                        world,
                        column.x(), column.y() + 1, column.z()
                ),
                random,
                org.bukkit.TreeType.PALE_OAK,
                capture
        );

        if (captured.isEmpty()) {
            return List.of();
        }

        // Гарантировать сердце внутри непрерывного вертикального ствола.
        Pos core = null;

        for (Map.Entry<Pos, BlockData> entry : captured.entrySet()) {
            Pos pos = entry.getKey();

            if (entry.getValue().getMaterial() != Material.PALE_OAK_LOG) {
                continue;
            }

            Pos below = new Pos(
                    pos.world(), pos.x(), pos.y() - 1, pos.z()
            );

            Pos above = new Pos(
                    pos.world(), pos.x(), pos.y() + 1, pos.z()
            );

            if (captured.containsKey(below)
                    && captured.containsKey(above)
                    && captured.get(below).getMaterial()
                        == Material.PALE_OAK_LOG
                    && captured.get(above).getMaterial()
                        == Material.PALE_OAK_LOG) {
                core = pos;
                break;
            }
        }

        if (core == null) {
            return List.of();
        }

        Pos below = new Pos(
                core.world(), core.x(), core.y() - 1, core.z()
        );

        Pos above = new Pos(
                core.world(), core.x(), core.y() + 1, core.z()
        );

        BlockData verticalLog = Bukkit.createBlockData(
                "minecraft:pale_oak_log[axis=y]"
        );

        captured.put(below, verticalLog.clone());
        captured.put(above, verticalLog.clone());
        captured.remove(core);

        List<Change> plan = new ArrayList<>();

        for (Map.Entry<Pos, BlockData> entry : captured.entrySet()) {
            plan.add(new Change(
                    entry.getKey(), entry.getValue(), "tree"
            ));
        }

        CreakingHeart creaking = (CreakingHeart)
                Material.CREAKING_HEART.createBlockData();

        creaking.setAxis(org.bukkit.Axis.Y);
        creaking.setNatural(true);
        creaking.setActive(true);

        // Сердце ставится последним, когда оба бревна уже существуют.
        plan.add(new Change(core, creaking, "tree"));

        return plan;
    }

    private void scheduleLayer(Heart heart, int level) {
        layering.add(heart.id);

        int radius = 2 + level;
        List<Change> plan = new ArrayList<>();

        World world = Bukkit.getWorld(heart.world);

        if (world == null) {
            layering.remove(heart.id);
            return;
        }

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int y = heart.y + dy;

                if (y < world.getMinHeight()
                        || y >= world.getMaxHeight()) {
                    continue;
                }

                for (int dz = -radius; dz <= radius; dz++) {
                    int distance = Math.abs(dx)
                            + Math.abs(dy)
                            + Math.abs(dz);

                    if (distance != radius) {
                        continue;
                    }

                    plan.add(new Change(
                            new Pos(
                                    heart.world,
                                    heart.x + dx,
                                    y,
                                    heart.z + dz
                            ),
                            heart.kind == Kind.SCULK
                                    ? Material.REINFORCED_DEEPSLATE
                                        .createBlockData()
                                    : Material.PALE_OAK_WOOD
                                        .createBlockData(),
                            "layer"
                    ));
                }
            }
        }

        jobs.add(new Job(heart, plan, changed -> {
            layering.remove(heart.id);

            if (heart.active) {
                heart.stage = level;
                data.save(heart);
            }
        }));
    }

    private void findBurning() throws Exception {
        List<Pos> sources = new ArrayList<>();

        for (Set<Pos> set : lights.values()) {
            sources.addAll(set);
        }

        if (sources.isEmpty()) {
            return;
        }

        // Один источник за проход; остальные идут по кругу.
        Pos source = sources.get(
                Math.floorMod(lightCursor++, sources.size())
        );

        Block light = source.loadedBlock();

        if (light == null) {
            return;
        }

        Kind kind = lightKind(light);

        if (kind == null) {
            return;
        }

        List<Cell> nearby = data.cells("""
                SELECT rowid,* FROM cells
                WHERE w=?
                  AND x BETWEEN ? AND ?
                  AND y BETWEEN ? AND ?
                  AND z BETWEEN ? AND ?
                """,
                source.world(),
                source.x() - 7, source.x() + 7,
                source.y() - 7, source.y() + 7,
                source.z() - 7, source.z() + 7
        );

        for (Cell cell : nearby) {
            Heart heart = data.hearts.get(cell.heart());

            if (heart == null || !heart.active
                    || heart.kind != kind
                    || cell.role().equals("core")) {
                continue;
            }

            if (protectedAt(cell.pos(), kind)) {
                burning.put(cell.id(), cell);
            }
        }
    }

    private void rollback() throws Exception {
        List<Chunk> chunks = new ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
            chunks.addAll(List.of(world.getLoadedChunks()));
        }

        if (chunks.isEmpty()) {
            return;
        }

        Chunk chunk = chunks.get(
                Math.floorMod(rollbackCursor++, chunks.size())
        );

        int budget = Math.max(1, plugin.getConfig().getInt(
                "performance.restore-per-tick", 80
        ));

        for (Heart heart : new ArrayList<>(data.hearts.values())) {
            if (heart.active
                    || !heart.world.equals(chunk.getWorld().getUID())) {
                continue;
            }

            for (Cell cell : data.cells("""
                    SELECT rowid,* FROM cells
                    WHERE h=? AND cx=? AND cz=?
                    ORDER BY y DESC
                    LIMIT ?
                    """,
                    heart.id, chunk.getX(), chunk.getZ(), budget
            )) {
                if (data.restore(cell)) {
                    budget--;
                }

                if (budget <= 0) {
                    return;
                }
            }
        }
    }
}
