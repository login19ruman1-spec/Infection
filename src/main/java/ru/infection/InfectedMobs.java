package ru.infection;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

final class InfectionData implements AutoCloseable {
    enum Kind {
        SCULK, PALE;

        Material ground() {
            return this == SCULK
                    ? Material.SCULK
                    : Material.PALE_MOSS_BLOCK;
        }

        Material core() {
            return this == SCULK
                    ? Material.SCULK_CATALYST
                    : Material.RESIN_BLOCK;
        }
    }

    record Pos(UUID world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(
                    b.getWorld().getUID(),
                    b.getX(), b.getY(), b.getZ()
            );
        }

        World worldObject() {
            return Bukkit.getWorld(world);
        }

        Block loadedBlock() {
            World w = worldObject();

            if (w == null
                    || y < w.getMinHeight()
                    || y >= w.getMaxHeight()
                    || !w.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }

            return w.getBlockAt(x, y, z);
        }

        Location location() {
            World w = worldObject();

            return w == null
                    ? null
                    : new Location(w, x + 0.5, y, z + 0.5);
        }
    }

    static final class Heart {
        UUID id;
        UUID world;
        Kind kind;

        int x;
        int y;
        int z;
        int ground;

        int depth;
        int speed;
        int damage;
        int stage;
        int vein;

        boolean defence;
        boolean active = true;

        Pos core() {
            return new Pos(world, x, y, z);
        }

        Location location() {
            return core().location();
        }
    }

    record Cell(
            long id,
            UUID heart,
            Pos pos,
            String before,
            String after,
            String tile,
            String role
    ) {}

    record Column(int x, int z, int y) {}

    private final InfectionPlugin plugin;
    private final Gson gson = new Gson();
    private final Connection sql;
    private final File tiles;

    final Map<UUID, Heart> hearts = new LinkedHashMap<>();

    InfectionData(InfectionPlugin plugin) throws Exception {
        this.plugin = plugin;

        plugin.getDataFolder().mkdirs();

        tiles = new File(plugin.getDataFolder(), "tiles");
        tiles.mkdirs();

        Class.forName("org.sqlite.JDBC");

        File database = new File(
                plugin.getDataFolder(), "infection.db"
        );

        sql = DriverManager.getConnection(
                "jdbc:sqlite:" + database.getAbsolutePath()
        );

        exec("PRAGMA journal_mode=WAL");
        exec("PRAGMA synchronous=NORMAL");
        exec("PRAGMA busy_timeout=5000");

        exec("""
                CREATE TABLE IF NOT EXISTS hearts (
                    id TEXT PRIMARY KEY,
                    json TEXT NOT NULL
                )
                """);

        exec("""
                CREATE TABLE IF NOT EXISTS cells (
                    w TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    h TEXT NOT NULL,
                    cx INTEGER NOT NULL,
                    cz INTEGER NOT NULL,
                    old TEXT NOT NULL,
                    next TEXT NOT NULL,
                    tile TEXT NOT NULL,
                    role TEXT NOT NULL,
                    PRIMARY KEY(w,x,y,z)
                )
                """);

        exec("""
                CREATE INDEX IF NOT EXISTS cells_heart
                ON cells(h)
                """);

        exec("""
                CREATE INDEX IF NOT EXISTS cells_chunk
                ON cells(w,cx,cz)
                """);

        exec("""
                CREATE TABLE IF NOT EXISTS columns (
                    h TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    cx INTEGER NOT NULL,
                    cz INTEGER NOT NULL,
                    done INTEGER NOT NULL DEFAULT 0,
                    due INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(h,x,z)
                )
                """);

        exec("""
                CREATE INDEX IF NOT EXISTS columns_front
                ON columns(h,cx,cz,done,due)
                """);

        for (Object[] row : rows("SELECT json FROM hearts")) {
            Heart heart = gson.fromJson(
                    row[0].toString(), Heart.class
            );

            hearts.put(heart.id, heart);
        }
    }

    void exec(String query, Object... args) throws Exception {
        try (PreparedStatement s = sql.prepareStatement(query)) {
            bind(s, args);
            s.executeUpdate();
        }
    }

    List<Object[]> rows(String query, Object... args)
            throws Exception {

        List<Object[]> result = new ArrayList<>();

        try (PreparedStatement s = sql.prepareStatement(query)) {
            bind(s, args);

            try (ResultSet r = s.executeQuery()) {
                ResultSetMetaData metadata = r.getMetaData();
                int size = metadata.getColumnCount();

                while (r.next()) {
                    Object[] row = new Object[size];

                    for (int i = 0; i < size; i++) {
                        row[i] = r.getObject(i + 1);
                    }

                    result.add(row);
                }
            }
        }

        return result;
    }

    private void bind(PreparedStatement statement, Object[] args)
            throws Exception {

        for (int i = 0; i < args.length; i++) {
            Object value = args[i];

            statement.setObject(
                    i + 1,
                    value instanceof UUID
                            ? value.toString()
                            : value
            );
        }
    }

    void save(Heart heart) throws Exception {
        exec(
                "INSERT OR REPLACE INTO hearts(id,json) VALUES(?,?)",
                heart.id, gson.toJson(heart)
        );

        hearts.put(heart.id, heart);
    }

    void remove(Heart heart) throws Exception {
        heart.active = false;
        save(heart);

        exec("DELETE FROM columns WHERE h=?", heart.id);
    }

    void finishRemoved(Heart heart) throws Exception {
        if (heart.active) {
            return;
        }

        if (!rows(
                "SELECT 1 FROM cells WHERE h=? LIMIT 1",
                heart.id
        ).isEmpty()) {
            return;
        }

        exec("DELETE FROM hearts WHERE id=?", heart.id);
        hearts.remove(heart.id);
    }

    void seed(Heart heart, int x, int z, int y)
            throws Exception {

        exec("""
                INSERT OR IGNORE INTO columns(h,x,z,y,cx,cz)
                VALUES(?,?,?,?,?,?)
                """,
                heart.id, x, z, y, x >> 4, z >> 4
        );
    }

    Column pending(Heart heart, int cx, int cz)
            throws Exception {

        List<Object[]> found = rows("""
                SELECT x,z,y FROM columns
                WHERE h=? AND cx=? AND cz=?
                  AND done=0 AND due<=?
                LIMIT 1
                """,
                heart.id, cx, cz, System.currentTimeMillis()
        );

        if (found.isEmpty()) {
            return null;
        }

        Object[] row = found.getFirst();

        return new Column(
                number(row[0]),
                number(row[1]),
                number(row[2])
        );
    }

    void columnDone(Heart heart, Column c) throws Exception {
        exec("""
                UPDATE columns SET done=1,due=0
                WHERE h=? AND x=? AND z=?
                """, heart.id, c.x(), c.z()
        );
    }

    void postpone(Heart heart, Column c) throws Exception {
        exec("""
                UPDATE columns SET due=?
                WHERE h=? AND x=? AND z=?
                """,
                System.currentTimeMillis() + 2000,
                heart.id, c.x(), c.z()
        );
    }

    long area(Heart heart) throws Exception {
        return ((Number) rows(
                "SELECT COUNT(*) FROM columns WHERE h=? AND done=1",
                heart.id
        ).getFirst()[0]).longValue();
    }

    Heart owner(Location location) throws Exception {
        List<Object[]> result = rows("""
                SELECT h,y FROM columns
                WHERE x=? AND z=? AND done=1
                """,
                location.getBlockX(), location.getBlockZ()
        );

        for (Object[] row : result) {
            Heart h = hearts.get(UUID.fromString(row[0].toString()));

            if (h == null || !h.active
                    || !h.world.equals(location.getWorld().getUID())) {
                continue;
            }

            int top = number(row[1]);
            int y = location.getBlockY();

            if (y >= top - h.depth && y <= top + 18) {
                return h;
            }
        }

        return null;
    }

    Cell cell(Block block) throws Exception {
        List<Cell> result = cells("""
                SELECT rowid,* FROM cells
                WHERE w=? AND x=? AND y=? AND z=?
                """,
                block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ()
        );

        return result.isEmpty() ? null : result.getFirst();
    }

    List<Cell> cells(String query, Object... args)
            throws Exception {

        List<Cell> result = new ArrayList<>();

        for (Object[] row : rows(query, args)) {
            result.add(new Cell(
                    ((Number) row[0]).longValue(),
                    UUID.fromString(row[5].toString()),
                    new Pos(
                            UUID.fromString(row[1].toString()),
                            number(row[2]),
                            number(row[3]),
                            number(row[4])
                    ),
                    row[8].toString(),
                    row[9].toString(),
                    row[10].toString(),
                    row[11].toString()
            ));
        }

        return result;
    }

    boolean replace(
            Heart heart,
            Block block,
            BlockData next,
            String role
    ) throws Exception {

        Cell previous = cell(block);

        if (previous != null
                && !previous.heart().equals(heart.id)) {
            return false;
        }

        String before;
        String tile;

        if (previous == null) {
            before = block.getBlockData().getAsString();
            tile = "";

            if (block.getState() instanceof TileState) {
                tile = UUID.randomUUID() + ".nbt";

                Structure snapshot = Bukkit.getStructureManager()
                        .createStructure();

                snapshot.fill(
                        block.getLocation(),
                        new BlockVector(1, 1, 1),
                        false
                );

                Bukkit.getStructureManager().saveStructure(
                        new File(tiles, tile), snapshot
                );
            }
        } else {
            before = previous.before();
            tile = previous.tile();
        }

        // Запись на диск выполняется до изменения мира.
        exec("""
                INSERT INTO cells(
                    w,x,y,z,h,cx,cz,old,next,tile,role
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(w,x,y,z) DO UPDATE SET
                    next=excluded.next,
                    role=excluded.role
                """,
                block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(),
                heart.id,
                block.getX() >> 4, block.getZ() >> 4,
                before, next.getAsString(), tile, role
        );

        block.setBlockData(next, false);
        return true;
    }

    boolean restore(Cell cell) throws Exception {
        Block block = cell.pos().loadedBlock();

        if (block == null) {
            return false;
        }

        Material expected = Bukkit.createBlockData(cell.after())
                .getMaterial();

        boolean respect = plugin.getConfig().getBoolean(
                "rollback.respect-player-changes", true
        );

        if (!respect || block.getType() == expected) {
            if (cell.tile().isEmpty()) {
                block.setBlockData(
                        Bukkit.createBlockData(cell.before()), false
                );
            } else {
                File file = new File(tiles, cell.tile());

                if (!file.isFile()) {
                    throw new IllegalStateException(
                            "Missing rollback snapshot: " + file.getName()
                    );
                }

                Structure snapshot = Bukkit.getStructureManager()
                        .loadStructure(file);

                if (snapshot == null) {
                    throw new IllegalStateException(
                            "Cannot read rollback snapshot"
                    );
                }

                snapshot.place(
                        block.getLocation(),
                        false,
                        StructureRotation.NONE,
                        Mirror.NONE,
                        -1,
                        1.0f,
                        new Random()
                );
            }
        }

        exec("DELETE FROM cells WHERE rowid=?", cell.id());

        if (!cell.tile().isEmpty()) {
            new File(tiles, cell.tile()).delete();
        }

        return true;
    }

    void reopenSurface(Cell cell) throws Exception {
        exec("""
                UPDATE columns SET done=0,due=?
                WHERE h=? AND x=? AND z=? AND y=?
                """,
                System.currentTimeMillis() + 2000,
                cell.heart(),
                cell.pos().x(),
                cell.pos().z(),
                cell.pos().y()
        );
    }

    static int number(Object value) {
        return ((Number) value).intValue();
    }

    @Override
    public void close() throws Exception {
        sql.close();
    }
}
