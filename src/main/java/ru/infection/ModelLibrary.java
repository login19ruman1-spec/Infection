package ru.infection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ru.infection.InfectionData.Kind;

final class ModelLibrary {
    private final InfectionPlugin plugin;

    private final Map<String, List<Cube>> models = new HashMap<>();

    record Cube(Vector3f from, Vector3f to) {}

    ModelLibrary(InfectionPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        models.clear();

        String[] names = {
                "zombie",
                "skeleton",
                "spider",
                "creeper",
                "villager",
                "zombie_villager"
        };

        for (Kind kind : Kind.values()) {
            File folder = new File(
                    plugin.getDataFolder(),
                    "models/" + kind.name().toLowerCase()
            );

            folder.mkdirs();

            for (String name : names) {
                File file = new File(folder, name + ".bbmodel");

                if (!file.isFile()) {
                    continue;
                }

                try (FileReader reader = new FileReader(file)) {
                    JsonObject root = JsonParser.parseReader(reader)
                            .getAsJsonObject();

                    JsonArray elements = root.getAsJsonArray("elements");

                    if (elements == null) {
                        throw new IllegalArgumentException(
                                "Нет массива elements"
                        );
                    }

                    List<Cube> cubes = new ArrayList<>();

                    for (JsonElement element : elements) {
                        JsonObject cube = element.getAsJsonObject();

                        if (!cube.has("from") || !cube.has("to")) {
                            throw new IllegalArgumentException(
                                    "Поддерживаются только кубические элементы"
                            );
                        }

                        if (cube.has("rotation")) {
                            Vector3f rotation = vector(
                                    cube.getAsJsonArray("rotation")
                            );

                            if (rotation.lengthSquared() > 0.0001f) {
                                throw new IllegalArgumentException(
                                        "В этой версии кубы должны быть "
                                                + "без собственного поворота"
                                );
                            }
                        }

                        cubes.add(new Cube(
                                vector(cube.getAsJsonArray("from")),
                                vector(cube.getAsJsonArray("to"))
                        ));
                    }

                    // Повороты групп нельзя молча игнорировать.
                    if (root.has("outliner")) {
                        validateGroups(root.get("outliner"));
                    }

                    int maximum = Math.max(
                            1,
                            plugin.getConfig().getInt(
                                    "models.max-cubes-per-model", 128
                            )
                    );

                    if (cubes.isEmpty() || cubes.size() > maximum) {
                        throw new IllegalArgumentException(
                                "Неподходящее количество кубов"
                        );
                    }

                    models.put(
                            kind.name() + ":" + name,
                            List.copyOf(cubes)
                    );

                    plugin.getLogger().info(
                            "Модель загружена: "
                                    + kind.name() + "/" + name
                    );
                } catch (Exception exception) {
                    plugin.getLogger().warning(
                            "Не загружена " + file.getName()
                                    + ": " + exception.getMessage()
                                    + ". Используется тестовая модель."
                    );
                }
            }
        }
    }

    private void validateGroups(JsonElement element) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                validateGroups(child);
            }

            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        JsonObject group = element.getAsJsonObject();

        if (group.has("rotation")
                && vector(group.getAsJsonArray("rotation"))
                    .lengthSquared() > 0.0001f) {

            throw new IllegalArgumentException(
                    "Повороты групп пока не поддерживаются"
            );
        }

        if (group.has("children")) {
            validateGroups(group.get("children"));
        }
    }

    private Vector3f vector(JsonArray array) {
        if (array == null || array.size() != 3) {
            throw new IllegalArgumentException(
                    "Ожидался вектор из трёх чисел"
            );
        }

        Vector3f vector = new Vector3f(
                array.get(0).getAsFloat(),
                array.get(1).getAsFloat(),
                array.get(2).getAsFloat()
        );

        if (!Float.isFinite(vector.x)
                || !Float.isFinite(vector.y)
                || !Float.isFinite(vector.z)) {
            throw new IllegalArgumentException(
                    "Координаты модели должны быть конечными"
            );
        }

        return vector;
    }

    boolean isVisual(Entity entity) {
        return entity.getPersistentDataContainer().has(
                plugin.visualEntityKey,
                PersistentDataType.BYTE
        );
    }

    void clear(LivingEntity entity) {
        for (Entity passenger : new ArrayList<>(entity.getPassengers())) {
            if (isVisual(passenger)) {
                passenger.remove();
            }
        }
    }

    void attach(LivingEntity entity, Kind kind) {
        clear(entity);

        if (!plugin.getConfig().getBoolean("models.enabled", true)) {
            return;
        }

        String name = entity.getType().name().toLowerCase();

        List<Cube> cubes = models.get(kind.name() + ":" + name);

        if (cubes == null) {
            // Тестовый нарост, а не скрытый невидимый моб.
            cubes = List.of(
                    new Cube(
                            new Vector3f(-5, 17, -4),
                            new Vector3f(5, 23, 4)
                    ),
                    new Cube(
                            new Vector3f(-7, 9, -3),
                            new Vector3f(-3, 18, 3)
                    ),
                    new Cube(
                            new Vector3f(3, 9, -3),
                            new Vector3f(7, 18, 3)
                    )
            );
        }

        float scale = (float) Math.max(
                0.05,
                plugin.getConfig().getDouble("models.scale", 1.0)
        );

        Material material = kind == Kind.SCULK
                ? Material.SCULK
                : Material.PALE_OAK_WOOD;

        for (Cube cube : cubes) {
            Vector3f from = new Vector3f(cube.from())
                    .mul(scale / 16.0f);

            Vector3f size = new Vector3f(cube.to())
                    .sub(cube.from())
                    .mul(scale / 16.0f);

            if (size.x <= 0 || size.y <= 0 || size.z <= 0) {
                continue;
            }

            // У пассажира своя точка крепления.
            from.y -= (float) entity.getHeight();

            BlockDisplay display = entity.getWorld().spawn(
                    entity.getLocation(),
                    BlockDisplay.class
            );

            display.setBlock(material.createBlockData());
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);

            display.getPersistentDataContainer().set(
                    plugin.visualEntityKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );

            display.setTransformation(new Transformation(
                    from,
                    new Quaternionf(),
                    size,
                    new Quaternionf()
            ));

            entity.addPassenger(display);
        }
    }
}
