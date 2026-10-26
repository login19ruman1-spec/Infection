package ru.infection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

import static ru.infection.InfectionData.*;

public final class InfectionPlugin
        extends JavaPlugin
        implements Listener, TabExecutor {

    InfectionData data;
    InfectionEngine engine;
    InfectedMobs mobs;
    ModelLibrary models;

    NamespacedKey visualEntityKey;

    private BukkitTask task;
    private int ticks;
    private boolean failed;

    private static final class Menu implements InventoryHolder {
        final UUID heart;
        private final Inventory inventory;

        Menu(UUID heart) {
            this.heart = heart;

            inventory = Bukkit.createInventory(
                    this,
                    27,
                    "Управление заражением"
            );
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        visualEntityKey = new NamespacedKey(
                this, "model_visual"
        );

        try {
            data = new InfectionData(this);
            engine = new InfectionEngine(this, data);

            models = new ModelLibrary(this);
            models.reload();

            mobs = new InfectedMobs(this);

            Objects.requireNonNull(
                    getCommand("infection"),
                    "Команда infection отсутствует в plugin.yml"
            ).setExecutor(this);

            Objects.requireNonNull(
                    getCommand("infection")
            ).setTabCompleter(this);

            Bukkit.getPluginManager().registerEvents(this, this);

            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (models.isVisual(entity)) {
                        entity.remove();
                    }
                }

                for (LivingEntity entity : world.getLivingEntities()) {
                    if (!(entity instanceof Player)) {
                        mobs.loaded(entity);
                    }
                }
            }

            task = Bukkit.getScheduler().runTaskTimer(
                    this,
                    () -> {
                        if (failed) {
                            return;
                        }

                        try {
                            engine.tick();

                            if (++ticks % 20 == 0) {
                                mobs.tick();
                            }
                        } catch (Exception exception) {
                            fail(exception);
                        }
                    },
                    1L,
                    1L
            );

            getLogger().info(
                    "Infection включён. Команды: /infection"
            );
        } catch (Exception exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Не удалось включить Infection",
                    exception
            );

            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }

        if (mobs != null) {
            mobs.shutdown();
        }

        if (data != null) {
            try {
                data.close();
            } catch (Exception exception) {
                getLogger().log(
                        Level.SEVERE,
                        "Не удалось закрыть журнал заражения",
                        exception
                );
            }
        }
    }

    private void fail(Exception exception) {
        if (failed) {
            return;
        }

        failed = true;

        getLogger().log(
                Level.SEVERE,
                "Ошибка Infection. Работа остановлена, "
                        + "чтобы не продолжать изменения мира "
                        + "при неисправном журнале.",
                exception
        );

        Bukkit.getPluginManager().disablePlugin(this);
    }

    private String kindName(Kind kind) {
        return kind == Kind.SCULK
                ? "Скалковое заражение"
                : "Бледное заражение";
    }

    private Heart selected(Player player) throws Exception {
        Block target = player.getTargetBlockExact(32);

        if (target != null) {
            Cell cell = data.cell(target);

            if (cell != null) {
                Heart owner = data.hearts.get(cell.heart());

                if (owner != null && owner.active) {
                    return owner;
                }
            }
        }

        Heart nearest = null;
        double distance = 128 * 128;

        for (Heart heart : data.hearts.values()) {
            if (!heart.active
                    || !heart.world.equals(
                            player.getWorld().getUID()
                    )) {
                continue;
            }

            Location location = heart.location();

            if (location == null) {
                continue;
            }

            double candidate = location.distanceSquared(
                    player.getLocation()
            );

            if (candidate < distance) {
                distance = candidate;
                nearest = heart;
            }
        }

        return nearest;
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§6Infection — команды:");
        sender.sendMessage(
                "§e/infection sculk §7— создать скалковое сердце"
        );
        sender.sendMessage(
                "§e/infection pale §7— создать бледное сердце"
        );
        sender.sendMessage(
                "§e/infection menu §7— панель выбранного сердца"
        );
        sender.sendMessage(
                "§e/infection remove §7— удалить выбранное сердце"
        );
        sender.sendMessage(
                "§e/infection list §7— список сердец"
        );
        sender.sendMessage(
                "§e/infection reload §7— перечитать настройки и модели"
        );
        sender.sendMessage(
                "§7При создании смотри на поверхность. "
                        + "Для выбора смотри на заражение "
                        + "или подойди к нужному сердцу."
        );
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("infection.admin")) {
            sender.sendMessage("§cНедостаточно прав.");
            return true;
        }

        if (args.length == 0) {
            help(sender);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        try {
            if (action.equals("reload")) {
                reloadConfig();
                models.reload();

                for (World world : Bukkit.getWorlds()) {
                    for (LivingEntity entity :
                            new ArrayList<>(world.getLivingEntities())) {

                        if (mobs.infected(entity)) {
                            mobs.loaded(entity);
                        }
                    }
                }

                sender.sendMessage(
                        "§aНастройки и модели перечитаны. "
                                + "Параметры существующих сердец "
                                + "меняются в их панели."
                );

                return true;
            }

            if (action.equals("list")) {
                int count = 0;

                for (Heart heart : data.hearts.values()) {
                    if (!heart.active) {
                        continue;
                    }

                    count++;

                    World world = Bukkit.getWorld(heart.world);

                    sender.sendMessage(
                            "§e" + kindName(heart.kind)
                                    + " §7— "
                                    + (world == null
                                        ? "мир не загружен"
                                        : world.getName())
                                    + " §f"
                                    + heart.x + " "
                                    + heart.y + " "
                                    + heart.z
                    );
                }

                sender.sendMessage(
                        "§7Активных сердец: §f" + count
                );

                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage(
                        "Эта команда выполняется игроком в мире."
                );
                return true;
            }

            if (action.equals("sculk") || action.equals("pale")) {
                Block ground = player.getTargetBlockExact(32);

                if (ground == null || ground.getType().isAir()) {
                    player.sendMessage(
                            "§cПосмотри на поверхность "
                                    + "на расстоянии до 32 блоков."
                    );
                    return true;
                }

                Heart heart;

                try {
                    heart = engine.create(
                            ground.getLocation(),
                            action.equals("sculk")
                                    ? Kind.SCULK
                                    : Kind.PALE
                    );
                } catch (IllegalArgumentException exception) {
                    player.sendMessage(
                            "§c" + exception.getMessage()
                    );
                    return true;
                }

                player.sendMessage(
                        "§aСердце создано. Вены будут "
                                + "постепенно опускаться к земле."
                );

                openMenu(player, heart);
                return true;
            }

            if (action.equals("menu") || action.equals("remove")) {
                Heart heart = selected(player);

                if (heart == null) {
                    player.sendMessage(
                            "§cПоблизости нет активного сердца. "
                                    + "Подойди ближе или посмотри "
                                    + "на заражённый блок."
                    );
                    return true;
                }

                if (action.equals("menu")) {
                    openMenu(player, heart);
                } else {
                    destroy(heart);

                    player.sendMessage(
                            "§aСердце отключено. "
                                    + "Территория восстанавливается "
                                    + "в загруженных чанках; "
                                    + "остальная восстановится "
                                    + "после их загрузки."
                    );
                }

                return true;
            }

            help(sender);
        } catch (Exception exception) {
            sender.sendMessage(
                    "§cОперация не выполнена. "
                            + "Подробности записаны в консоль."
            );

            fail(exception);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!sender.hasPermission("infection.admin")
                || args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);

        return List.of(
                "sculk", "pale", "menu",
                "remove", "list", "reload"
        ).stream().filter(
                value -> value.startsWith(prefix)
        ).toList();
    }

    private ItemStack item(
            Material material,
            String name,
            String... lore
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(name);
        meta.setLore(List.of(lore));

        item.setItemMeta(meta);
        return item;
    }

    private void openMenu(Player player, Heart heart)
            throws Exception {

        Menu menu = new Menu(heart.id);
        Inventory inventory = menu.getInventory();

        inventory.setItem(4, item(
                heart.kind.core(),
                "§6" + kindName(heart.kind),
                "§7Площадь: §f" + data.area(heart) + " колонок",
                "§7Глубина: §f" + heart.depth,
                "§7Скорость: §f" + heart.speed + " колонок/сек",
                "§7Уровень защитных слоёв: §f" + heart.stage,
                "§7Радиус распространения не ограничен"
        ));

        inventory.setItem(9, item(
                Material.RED_DYE,
                "§cУменьшить глубину",
                "§7Обычное нажатие: −1",
                "§7Shift: −10"
        ));

        inventory.setItem(10, item(
                Material.IRON_SHOVEL,
                "§eГлубина: " + heart.depth,
                "§7Количество блоков вниз",
                "§7для новых заражаемых колонок"
        ));

        inventory.setItem(11, item(
                Material.LIME_DYE,
                "§aУвеличить глубину",
                "§7Обычное нажатие: +1",
                "§7Shift: +10"
        ));

        inventory.setItem(12, item(
                Material.RED_DYE,
                "§cУменьшить скорость",
                "§7Обычное нажатие: −1",
                "§7Shift: −10"
        ));

        inventory.setItem(13, item(
                Material.SUGAR,
                "§eСкорость: " + heart.speed,
                "§7Колонок в секунду",
                "§7Фактический темп зависит",
                "§7от общего бюджета изменений"
        ));

        inventory.setItem(14, item(
                Material.LIME_DYE,
                "§aУвеличить скорость",
                "§7Обычное нажатие: +1",
                "§7Shift: +10"
        ));

        inventory.setItem(15, item(
                Material.RED_DYE,
                "§cУрон начнётся раньше",
                "§7Уменьшить время на 5 секунд"
        ));

        inventory.setItem(16, item(
                Material.CLOCK,
                "§eДо урона: " + heart.damage + " сек.",
                "§7Непрерывное пребывание",
                "§7в заражённой области"
        ));

        inventory.setItem(17, item(
                Material.LIME_DYE,
                "§aУрон начнётся позже",
                "§7Увеличить время на 5 секунд"
        ));

        inventory.setItem(21, item(
                heart.defence ? Material.SHIELD : Material.BARRIER,
                "§eНовая защита: "
                        + (heart.defence ? "включена" : "выключена"),
                "§7Переключает появление охраны",
                "§7и дальнейший рост защитных слоёв",
                "§7Уже созданные слои не удаляет"
        ));

        inventory.setItem(23, item(
                Material.TNT,
                "§cУничтожить сердце",
                "§7Запустить восстановление территории",
                "§cПодтверждение: Shift + правое нажатие"
        ));

        inventory.setItem(26, item(
                Material.OAK_DOOR,
                "§eЗакрыть"
        ));

        player.openInventory(inventory);
    }

    @EventHandler
    public void menuClick(InventoryClickEvent event) {
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
                || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        Heart heart = data.hearts.get(menu.heart);

        if (heart == null || !heart.active) {
            player.closeInventory();
            return;
        }

        if (slot == 26) {
            player.closeInventory();
            return;
        }

        int step = event.isShiftClick() ? 10 : 1;

        try {
            switch (slot) {
                case 9 -> heart.depth = Math.max(
                        1, heart.depth - step
                );

                case 11 -> {
                    World world = Bukkit.getWorld(heart.world);

                    if (world != null) {
                        heart.depth = (int) Math.min(
                                (long) world.getMaxHeight()
                                        - world.getMinHeight(),
                                (long) heart.depth + step
                        );
                    }
                }

                case 12 -> heart.speed = Math.max(
                        1, heart.speed - step
                );

                case 14 -> heart.speed = (int) Math.min(
                        Integer.MAX_VALUE,
                        (long) heart.speed + step
                );

                case 15 -> heart.damage = Math.max(
                        5, heart.damage - 5
                );

                case 17 -> heart.damage = (int) Math.min(
                        Integer.MAX_VALUE,
                        (long) heart.damage + 5
                );

                case 21 -> heart.defence = !heart.defence;

                case 23 -> {
                    if (event.isShiftClick()
                            && event.isRightClick()) {
                        destroy(heart);
                        player.closeInventory();

                        player.sendMessage(
                                "§aСердце отключено. "
                                        + "Начат откат территории."
                        );
                    }

                    return;
                }

                default -> {
                    return;
                }
            }

            data.save(heart);
            openMenu(player, heart);
        } catch (Exception exception) {
            player.closeInventory();
            fail(exception);
        }
    }

    @EventHandler
    public void menuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private void destroy(Heart heart) throws Exception {
        if (!heart.active) {
            return;
        }

        data.remove(heart);
        mobs.removeHeart(heart);
    }

    private void refreshLightLater(Block block) {
        if (!InfectionEngine.lightMaterial(block.getType())) {
            return;
        }

        Bukkit.getScheduler().runTask(
                this,
                () -> engine.lightChanged(block)
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void place(BlockPlaceEvent event) {
        refreshLightLater(event.getBlockPlaced());
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void breakBlock(BlockBreakEvent event) {
        Block block = event.getBlock();

        try {
            Cell cell = data.cell(block);

            if (cell != null) {
                // Заражённый блок не должен одновременно
                // давать добычу и затем восстанавливаться.
                event.setDropItems(false);
                event.setExpToDrop(0);

                Heart heart = data.hearts.get(cell.heart());

                if (heart != null && heart.active) {
                    if (cell.role().equals("core")) {
                        destroy(heart);

                        event.getPlayer().sendMessage(
                                "§aСердцевина уничтожена. "
                                        + "Заражение отступает."
                        );
                    } else {
                        mobs.provoke(heart);
                    }
                }
            }

            refreshLightLater(block);
        } catch (Exception exception) {
            event.setCancelled(true);
            fail(exception);
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void damageBlock(BlockDamageEvent event) {
        try {
            Cell cell = data.cell(event.getBlock());

            if (cell == null) {
                return;
            }

            Heart heart = data.hearts.get(cell.heart());

            if (heart != null && heart.active) {
                mobs.provoke(heart);
            }
        } catch (Exception exception) {
            fail(exception);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null
                || event.getAction()
                    != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();

        // После использования лопаты, огнива и т. п.
        // проверяем фактическое состояние костра.
        refreshLightLater(block);

        if (!event.getPlayer().isSneaking()
                || !event.getPlayer().hasPermission(
                        "infection.admin"
                )
                || event.useInteractedBlock()
                    == org.bukkit.event.Event.Result.DENY) {
            return;
        }

        try {
            Cell cell = data.cell(block);

            if (cell == null || !cell.role().equals("core")) {
                return;
            }

            Heart heart = data.hearts.get(cell.heart());

            if (heart == null || !heart.active) {
                return;
            }

            event.setCancelled(true);
            openMenu(event.getPlayer(), heart);
        } catch (Exception exception) {
            fail(exception);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void entityDamage(EntityDamageEvent event) {
        if (models.isVisual(event.getEntity())
                || mobs.frozen(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void infectedAttack(EntityDamageByEntityEvent event) {
        Entity attacker = event.getDamager();

        if (attacker instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }

        if (!mobs.infected(attacker)) {
            return;
        }

        if (mobs.frozen(attacker)) {
            event.setCancelled(true);
            return;
        }

        double multiplier = Math.max(
                0,
                getConfig().getDouble(
                        "mobs.attack-multiplier", 1.5
                )
        );

        event.setDamage(event.getDamage() * multiplier);
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void combust(EntityCombustEvent event) {
        if (mobs.frozen(event.getEntity())) {
            event.setCancelled(true);
            event.getEntity().setFireTicks(0);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void target(EntityTargetLivingEntityEvent event) {
        if (mobs.frozen(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void entityDeath(EntityDeathEvent event) {
        if (mobs.infected(event.getEntity())) {
            models.clear(event.getEntity());
        }
    }

    @EventHandler
    public void playerDeath(PlayerDeathEvent event) {
        Kind reason = mobs.deathReason.get(
                event.getEntity().getUniqueId()
        );

        if (reason != null) {
            event.setDeathMessage(
                    event.getEntity().getName()
                            + (reason == Kind.SCULK
                                ? " был поглощён скалком"
                                : " исчез в бледном заражении")
            );
        }

        mobs.forget(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        mobs.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoad(ChunkLoadEvent event) {
        engine.scan(event.getChunk());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void chunkUnload(ChunkUnloadEvent event) {
        engine.unload(event.getChunk());
    }

    @EventHandler
    public void entitiesLoad(EntitiesLoadEvent event) {
        List<Entity> entities = new ArrayList<>(
                event.getEntities()
        );

        Bukkit.getScheduler().runTask(this, () -> {
            for (Entity entity : entities) {
                if (!entity.isValid()) {
                    continue;
                }

                if (models.isVisual(entity)) {
                    entity.remove();
                }
            }

            for (Entity entity : entities) {
                if (entity.isValid()
                        && entity instanceof LivingEntity living
                        && !(entity instanceof Player)) {
                    mobs.loaded(living);
                }
            }
        });
    }

    private void explosionBlocks(List<Block> blocks)
            throws Exception {

        var iterator = blocks.iterator();

        while (iterator.hasNext()) {
            Block block = iterator.next();
            Cell cell = data.cell(block);

            if (cell != null) {
                iterator.remove();

                if (!cell.role().equals("core")) {
                    // Разрушаем оболочку без выпадения предметов,
                    // сохраняя исходный блок в журнале отката.
                    block.setType(Material.AIR, false);
                }
            } else if (
                    InfectionEngine.lightMaterial(block.getType())
            ) {
                Bukkit.getScheduler().runTask(
                        this,
                        () -> engine.lightChanged(block)
                );
            }
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void entityExplode(EntityExplodeEvent event) {
        if (mobs.frozen(event.getEntity())) {
            event.setCancelled(true);
            return;
        }

        try {
            explosionBlocks(event.blockList());
        } catch (Exception exception) {
            event.setCancelled(true);
            fail(exception);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void blockExplode(BlockExplodeEvent event) {
        try {
            explosionBlocks(event.blockList());
        } catch (Exception exception) {
            event.setCancelled(true);
            fail(exception);
        }
    }

    private boolean tracked(Block block) throws Exception {
        return data.cell(block) != null;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void pistonExtend(BlockPistonExtendEvent event) {
        try {
            for (Block block : event.getBlocks()) {
                if (tracked(block)
                        || tracked(block.getRelative(
                                event.getDirection()
                        ))) {
                    event.setCancelled(true);
                    return;
                }
            }
        } catch (Exception exception) {
            event.setCancelled(true);
            fail(exception);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void pistonRetract(BlockPistonRetractEvent event) {
        try {
            for (Block block : event.getBlocks()) {
                if (tracked(block)
                        || tracked(block.getRelative(
                                event.getDirection().getOppositeFace()
                        ))) {
                    event.setCancelled(true);
                    return;
                }
            }
        } catch (Exception exception) {
            event.setCancelled(true);
            fail(exception);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void spread(BlockSpreadEvent event) {
        try {
            // Катализаторы и другие блоки заражения
            // не должны менять мир в обход журнала.
            if (tracked(event.getSource())
                    || tracked(event.getBlock())) {
                event.setCancelled(true);
            }
        } catch (Exception exception) {
            event.setCancelled(true);
            fail(exception);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void burn(BlockBurnEvent event) {
        try {
            if (tracked(event.getBlock())) {
                // Обычный пожар не заменяет управляемую
                // очистку заражения от факелов и костров.
                event.setCancelled(true);
            }
        } catch (Exception exception) {
            event.setCancelled(true);
            fail(exception);
        }
    }
}
