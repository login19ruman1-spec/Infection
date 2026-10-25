package ru.infection;

import com.google.gson.*;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class ModelLoader {

    private record Part(Material material, Matrix4f matrix) {}

    private final InfectionPlugin plugin;

    private final Map<String, List<Part>> models =
            new HashMap<>();

    private final Gson gson =
            new GsonBuilder().setPrettyPrinting().create();

    private static final List<String> MOB_NAMES = List.of(
            "zombie", "skeleton", "spider",
            "creeper", "villager", "zombie_villager"
    );

    ModelLoader(InfectionPlugin plugin) {
        this.plugin = plugin;
    }

    private Path path(String kind, String mob) {
        return plugin.getDataFolder().toPath()
                .resolve("models")
                .resolve(kind)
                .resolve(mob + ".bbmodel");
    }

    void reload() throws IOException {
        if (plugin.getConfig().getBoolean(
                "models.generate-examples", true)) {
            generateExamples();
        }

        Map<String, List<Part>> next = new HashMap<>();

        for (String kind : List.of("sculk", "pale")) {
            Files.createDirectories(
                    plugin.getDataFolder().toPath()
                            .resolve("models").resolve(kind)
            );

            for (String mob : MOB_NAMES) {
                Path file = path(kind, mob);
                String key = kind + "/" + mob;

                if (!Files.isRegularFile(file)) {
                    continue;
                }

                try {
                    next.put(key, read(file, kind));
                } catch (Exception ex) {
                    if (models.containsKey(key)) {
                        next.put(key, models.get(key));
                    }

                    plugin.getLogger().warning(
                            "Ошибка модели " + key + ": "
                            + ex.getMessage()
                    );
                }
            }
        }

        models.clear();
        models.putAll(next);
    }

    List<BlockDisplay> spawn(
            LivingEntity entity,
            InfectionData.Kind kind) {

        String key = kind.folder() + "/"
                + entity.getType().name()
                .toLowerCase(Locale.ROOT);

        List<Part> parts = models.get(key);

        if (parts == null) {
            return List.of();
        }

        List<BlockDisplay> result = new ArrayList<>();

        try {
            for (Part part : parts) {
                BlockDisplay display = entity.getWorld().spawn(
                        entity.getLocation(),
                        BlockDisplay.class,
                        d -> {
                            d.setBlock(
                                    part.material().createBlockData()
                            );

                            d.setTransformationMatrix(
                                    new Matrix4f(part.matrix())
                            );

                            d.setPersistent(false);
                            d.setInvulnerable(true);
                            d.setGravity(false);
                            d.setTeleportDuration(5);
                            d.setViewRange(0.8f);
                            d.setDisplayWidth(4);
                            d.setDisplayHeight(4);

                            d.getPersistentDataContainer().set(
                                    plugin.displayKey,
                                    org.bukkit.persistence
                                            .PersistentDataType.BYTE,
                                    (byte) 1
                            );
                        }
                );

                result.add(display);
            }

            return result;
        } catch (RuntimeException ex) {
            result.forEach(Entity::remove);
            throw ex;
        }
    }

    private List<Part> read(Path file, String kind)
            throws IOException {

        if (Files.size(file) > 2_000_000) {
            throw new IOException("Слишком большой файл модели");
        }

        JsonObject root;

        try (Reader reader = Files.newBufferedReader(
                file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        checkGroups(root.get("groups"));
        checkGroups(root.get("outliner"));

        if (!root.has("elements")) {
            throw new IOException("Не найден раздел elements");
        }

        int maximum = InfectionData.clamp(
                plugin.getConfig().getInt("models.max-cubes", 24),
                1, 64
        );

        List<Part> result = new ArrayList<>();

        for (JsonElement element : root.getAsJsonArray("elements")) {
            JsonObject cube = element.getAsJsonObject();

            if (cube.has("visibility")
                    && !cube.get("visibility").getAsBoolean()) {
                continue;
            }

            String type = cube.has("type")
                    ? cube.get("type").getAsString()
                    : "cube";

            if (!type.equals("cube")) {
                throw new IOException(
                        "Поддерживаются только кубы, найден " + type
                );
            }

            if (result.size() >= maximum) {
                throw new IOException(
                        "Слишком много кубов, разрешено " + maximum
                );
            }

            float[] from = vector(cube, "from", null);
            float[] to = vector(cube, "to", null);
            float[] pivot = vector(
                    cube, "origin", new float[]{0, 0, 0});
            float[] rotation = vector(
                    cube, "rotation", new float[]{0, 0, 0});

            for (int i = 0; i < 3; i++) {
                if (Math.abs(from[i]) > 128
                        || Math.abs(to[i]) > 128
                        || Math.abs(pivot[i]) > 128
                        || Math.abs(rotation[i]) > 360
                        || to[i] <= from[i]) {
                    throw new IOException(
                            "Недопустимые размеры или поворот куба"
                    );
                }
            }

            Material material = kind.equals("sculk")
                    ? Material.SCULK : Material.PALE_OAK_WOOD;

            // Необязательное поле для выбора блоковой текстуры.
            if (cube.has("material")) {
                Material selected = Material.matchMaterial(
                        cube.get("material").getAsString()
                );

                if (selected == null
                        || !selected.isBlock()
                        || selected.isAir()) {
                    throw new IOException(
                            "Некорректный material у куба"
                    );
                }

                material = selected;
            }

            Matrix4f matrix = new Matrix4f()
                    .translate(
                            pivot[0] / 16f,
                            pivot[1] / 16f,
                            pivot[2] / 16f)
                    .rotateXYZ(
                            (float) Math.toRadians(rotation[0]),
                            (float) Math.toRadians(rotation[1]),
                            (float) Math.toRadians(rotation[2]))
                    .translate(
                            (from[0] - pivot[0]) / 16f,
                            (from[1] - pivot[1]) / 16f,
                            (from[2] - pivot[2]) / 16f)
                    .scale(
                            (to[0] - from[0]) / 16f,
                            (to[1] - from[1]) / 16f,
                            (to[2] - from[2]) / 16f);

            result.add(new Part(material, matrix));
        }

        if (result.isEmpty()) {
            throw new IOException("Нет видимых кубов");
        }

        return List.copyOf(result);
    }

    private float[] vector(
            JsonObject object, String name, float[] fallback)
            throws IOException {

        if (!object.has(name)) {
            if (fallback != null) {
                return fallback.clone();
            }
            throw new IOException("Нет поля " + name);
        }

        JsonArray array = object.getAsJsonArray(name);

        if (array.size() != 3) {
            throw new IOException(
                    "Нужны три координаты в " + name
            );
        }

        float[] values = new float[3];

        for (int i = 0; i < 3; i++) {
            values[i] = array.get(i).getAsFloat();

            if (!Float.isFinite(values[i])) {
                throw new IOException("Некорректная координата");
            }
        }

        return values;
    }

    private void checkGroups(JsonElement element)
            throws IOException {

        if (element == null
                || element.isJsonNull()
                || element.isJsonPrimitive()) {
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                checkGroups(child);
            }
            return;
        }

        JsonObject object = element.getAsJsonObject();

        if (object.has("rotation")) {
            for (float angle : vector(
                    object, "rotation", new float[]{0, 0, 0})) {
                if (Math.abs(angle) > 0.001f) {
                    throw new IOException(
                            "Повороты групп пока не поддерживаются"
                    );
                }
            }
        }

        checkGroups(object.get("children"));
    }

    private void generateExamples() throws IOException {
        for (String kind : List.of("sculk", "pale")) {
            for (String mob : MOB_NAMES) {
                Path file = path(kind, mob);

                Files.createDirectories(file.getParent());

                if (Files.exists(file)) {
                    continue;
                }

                String body = kind.equals("sculk")
                        ? "SCULK" : "PALE_OAK_WOOD";
                String growth = kind.equals("sculk")
                        ? "SCULK" : "PALE_MOSS_BLOCK";
                String eyes = kind.equals("sculk")
                        ? "SEA_LANTERN" : "OCHRE_FROGLIGHT";

                JsonObject root = new JsonObject();
                JsonObject meta = new JsonObject();

                meta.addProperty("format_version", "4.10");
                meta.addProperty("model_format", "free");
                meta.addProperty("box_uv", false);

                root.add("meta", meta);
                root.addProperty("name", kind + "_" + mob);
                root.add("textures", new JsonArray());

                JsonObject resolution = new JsonObject();
                resolution.addProperty("width", 16);
                resolution.addProperty("height", 16);
                root.add("resolution", resolution);

                JsonArray cubes = new JsonArray();

                if (mob.equals("spider")) {
                    cube(cubes, "body",
                            -6, 4, -5, 6, 10, 5, body);
                    cube(cubes, "head",
                            -4, 4, -10, 4, 10, -5, growth);

                    for (int i = 0; i < 4; i++) {
                        float z = -4 + i * 3;

                        cube(cubes, "left_" + i,
                                -13, 3, z, -5, 5, z + 1, body);
                        cube(cubes, "right_" + i,
                                5, 3, z, 13, 5, z + 1, body);
                    }

                    cube(cubes, "eyes",
                            -3, 7, -10.5f, 3, 9, -10, eyes);

                } else if (mob.equals("creeper")) {
                    cube(cubes, "body",
                            -4, 6, -2, 4, 18, 2, body);
                    cube(cubes, "head",
                            -4, 18, -4, 4, 26, 4, growth);

                    for (int x : new int[]{-4, 0}) {
                        for (int z : new int[]{-5, 1}) {
                            cube(cubes, "foot",
                                    x, 0, z, x + 4, 6, z + 4,
                                    body);
                        }
                    }

                    cube(cubes, "eyes",
                            -3, 22, -4.5f, 3, 24, -4, eyes);

                } else {
                    cube(cubes, "body",
                            -4, 12, -2, 4, 24, 2, body);
                    cube(cubes, "head",
                            -4, 24, -4, 4, 32, 4, growth);
                    cube(cubes, "left_leg",
                            -4, 0, -2, -1, 12, 2, body);
                    cube(cubes, "right_leg",
                            1, 0, -2, 4, 12, 2, body);
                    cube(cubes, "left_arm",
                            -7, 12, -2, -4, 24, 2, body);
                    cube(cubes, "right_arm",
                            4, 12, -2, 7, 24, 2, body);
                    cube(cubes, "eyes",
                            -3, 28, -4.5f, 3, 30, -4, eyes);

                    if (mob.contains("villager")) {
                        cube(cubes, "nose",
                                -1, 25, -7, 1, 29, -4, body);
                    }

                    if (mob.equals("skeleton")) {
                        cube(cubes, "growth",
                                -2, 32, -1, 2, 36, 1, growth);
                    }
                }

                root.add("elements", cubes);

                JsonArray outliner = new JsonArray();
                for (JsonElement element : cubes) {
                    outliner.add(
                            element.getAsJsonObject().get("uuid")
                    );
                }
                root.add("outliner", outliner);

                Files.writeString(
                        file, gson.toJson(root),
                        StandardCharsets.UTF_8
                );
            }
        }
    }

    private void cube(
            JsonArray elements, String name,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            String material) {

        JsonObject cube = new JsonObject();

        cube.addProperty("name", name);
        cube.addProperty("type", "cube");
        cube.addProperty("uuid", UUID.randomUUID().toString());
        cube.addProperty("visibility", true);
        cube.addProperty("material", material);

        cube.add("from", gson.toJsonTree(
                new float[]{x1, y1, z1}));
        cube.add("to", gson.toJsonTree(
                new float[]{x2, y2, z2}));
        cube.add("origin", gson.toJsonTree(
                new float[]{0, 0, 0}));
        cube.add("rotation", gson.toJsonTree(
                new float[]{0, 0, 0}));

        JsonObject faces = new JsonObject();

        for (String side : List.of(
                "north", "south", "east",
                "west", "up", "down")) {

            JsonObject face = new JsonObject();

            face.add("uv", gson.toJsonTree(
                    new int[]{0, 0, 16, 16}));
            face.add("texture", JsonNull.INSTANCE);

            faces.add(side, face);
        }

        cube.add("faces", faces);
        elements.add(cube);
    }
}
