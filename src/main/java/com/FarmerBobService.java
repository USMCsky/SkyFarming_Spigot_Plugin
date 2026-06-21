package com;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class FarmerBobService {
    private static final String MAIN_MENU_TITLE = ChatColor.DARK_GREEN + "Farmer Bob";
    private static final String SEED_MENU_TITLE = ChatColor.GREEN + "Bob's Seed Shop";
    private static final int CHEST_LINK_RANGE = 20;
    private static final int HARVEST_RADIUS = 8;
    private static final long HARVEST_INTERVAL_TICKS = 40L;
    private static final String READY_HOLOGRAM_TEXT = ChatColor.YELLOW + "Auto-harvesting nearby crops";
    private static final String NO_CHEST_HOLOGRAM_TEXT = ChatColor.YELLOW + "Link a chest for auto-harvest";
    private static final String FULL_CHEST_HOLOGRAM_TEXT = ChatColor.RED + "Storage full - empty linked chest";
    private static final String TOO_FAR_HOLOGRAM_TEXT = ChatColor.RED + "Linked chest is too far away";
    private static final Map<Material, Material> REPLANT_ITEMS = createReplantItems();
    private static final Map<Material, Integer> SELL_PRICES = createSellPrices();
    private static final List<QuestTemplate> QUEST_TEMPLATES = List.of(
        new QuestTemplate(Material.WHEAT, "Harvest 64 wheat", 64, 120),
        new QuestTemplate(Material.CARROTS, "Harvest 48 carrots", 48, 130),
        new QuestTemplate(Material.POTATOES, "Harvest 48 potatoes", 48, 130),
        new QuestTemplate(Material.BEETROOTS, "Harvest 48 beetroot", 48, 135),
        new QuestTemplate(Material.NETHER_WART, "Harvest 32 nether wart", 32, 145),
        new QuestTemplate(Material.TORCHFLOWER_CROP, "Harvest 16 torchflowers", 16, 160)
    );
    private static final List<SeedOffer> SEED_OFFERS = List.of(
        new SeedOffer(10, Material.WHEAT_SEEDS, "Wheat Seeds", 32, 24),
        new SeedOffer(11, Material.BEETROOT_SEEDS, "Beetroot Seeds", 32, 28),
        new SeedOffer(12, Material.CARROT, "Carrots", 32, 34),
        new SeedOffer(14, Material.POTATO, "Potatoes", 32, 34),
        new SeedOffer(15, Material.NETHER_WART, "Nether Wart", 16, 40),
        new SeedOffer(16, Material.TORCHFLOWER_SEEDS, "Torchflower Seeds", 8, 45)
    );

    private final JavaPlugin plugin;
    private final Map<UUID, PlayerBobData> playerData = new HashMap<>();
    private final Map<UUID, ActiveBob> activeBobs = new HashMap<>();
    private final Map<Integer, SeedOffer> seedOffersBySlot = new HashMap<>();
    private final Set<UUID> pendingChestLinks = new HashSet<>();
    private final org.bukkit.NamespacedKey bobOwnerKey;
    private final org.bukkit.NamespacedKey bobTypeKey;

    private File dataFile;
    private FileConfiguration dataConfig;
    private boolean saveQueued;
    private BukkitTask harvestTask;

    public FarmerBobService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bobOwnerKey = new org.bukkit.NamespacedKey(plugin, "farmer_bob_owner");
        this.bobTypeKey = new org.bukkit.NamespacedKey(plugin, "farmer_bob_type");

        for (SeedOffer offer : SEED_OFFERS) {
            seedOffersBySlot.put(offer.slot(), offer);
        }
    }

    public void load() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder");
        }

        dataFile = new File(dataFolder, "farmerbob-data.yml");
        if (!dataFile.exists()) {
            try {
                if (!dataFile.createNewFile()) {
                    throw new IllegalStateException("Could not create farmerbob-data.yml");
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create farmerbob-data.yml", exception);
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        cleanupTaggedEntities();
        loadPlayerData();
        respawnSavedBobs();
        startHarvestTask();
    }

    public void shutdown() {
        stopHarvestTask();
        despawnAllBobs();
        saveData();
    }

    public static Material getReplantItem(Material cropType) {
        return REPLANT_ITEMS.get(cropType);
    }

    public static boolean isSupportedCrop(Material cropType) {
        return REPLANT_ITEMS.containsKey(cropType);
    }

    public static Integer getSellPrice(Material material) {
        return SELL_PRICES.get(material);
    }

    public void spawnBob(Player player, Location location) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        data.location = normalizeLocation(location);
        ensureQuest(data);
        spawnActiveBob(data);
        refreshBobState(data);
        markDirty();
        player.sendMessage(ChatColor.GREEN + "Farmer Bob is ready at your current spot.");
    }

    public void moveBob(Player player, Location location) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        if (data.location == null) {
            spawnBob(player, location);
            return;
        }

        data.location = normalizeLocation(location);
        spawnActiveBob(data);
        refreshBobState(data);
        markDirty();
        player.sendMessage(ChatColor.GREEN + "Farmer Bob moved to your new location.");
    }

    public void respawnBob(Player player) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        if (data.location == null) {
            player.sendMessage(ChatColor.RED + "You do not have a saved Farmer Bob. Use /farmerbob spawn first.");
            return;
        }

        spawnActiveBob(data);
        refreshBobState(data);
        player.sendMessage(ChatColor.GREEN + "Farmer Bob has been respawned at his saved location.");
    }

    public void removeBob(Player player) {
        UUID ownerId = player.getUniqueId();
        PlayerBobData data = getOrCreateData(ownerId);
        if (data.location == null && !activeBobs.containsKey(ownerId)) {
            player.sendMessage(ChatColor.RED + "You do not have a Farmer Bob to remove.");
            return;
        }

        pendingChestLinks.remove(ownerId);
        data.location = null;
        data.linkedChestLocation = null;
        data.activityState = BobActivityState.NO_CHEST;
        despawnActiveBob(ownerId);
        markDirty();
        player.sendMessage(ChatColor.YELLOW + "Farmer Bob has been removed. Use /farmerbob spawn to place him again.");
    }

    public void sendBalance(Player player) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        player.sendMessage(ChatColor.GOLD + "Farm Coins: " + ChatColor.YELLOW + data.balance);
    }

    public void recordHarvest(Player player, Material cropType, int amount) {
        recordHarvest(player.getUniqueId(), player, cropType, amount);
    }

    public boolean isManagedBobEntity(Entity entity) {
        return getBobType(entity) != null;
    }

    public boolean isBobVillager(Entity entity) {
        return "villager".equals(getBobType(entity));
    }

    public boolean isBobMenu(Inventory inventory) {
        return inventory.getHolder() instanceof BobMenuHolder;
    }

    public boolean isAwaitingChestLink(Player player) {
        return pendingChestLinks.contains(player.getUniqueId());
    }

    public void cancelChestLink(Player player) {
        pendingChestLinks.remove(player.getUniqueId());
    }

    public void handleInteraction(Player player, Entity clickedEntity) {
        UUID ownerId = getOwnerId(clickedEntity);
        if (ownerId == null) {
            return;
        }

        if (!ownerId.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "This Farmer Bob belongs to another player.");
            return;
        }

        cancelChestLink(player);
        ensureQuest(getOrCreateData(ownerId));
        openMainMenu(player);
    }

    public boolean handleChestLinkClick(Player player, Block block) {
        if (!isAwaitingChestLink(player)) {
            return false;
        }

        PlayerBobData data = getOrCreateData(player.getUniqueId());
        if (data.location == null || data.location.getWorld() == null) {
            pendingChestLinks.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Spawn Farmer Bob before linking a chest.");
            return true;
        }

        if (!(block.getState() instanceof Chest)) {
            player.sendMessage(ChatColor.RED + "That is not a chest. Right-click a chest within 20 blocks of Bob.");
            return true;
        }

        if (!sameWorld(data.location, block.getLocation()) || data.location.distanceSquared(block.getLocation()) > CHEST_LINK_RANGE * CHEST_LINK_RANGE) {
            player.sendMessage(ChatColor.RED + "That chest is too far away. It must be within 20 blocks of Bob.");
            return true;
        }

        data.linkedChestLocation = normalizeBlockLocation(block.getLocation());
        pendingChestLinks.remove(player.getUniqueId());
        markDirty();
        refreshBobState(data);
        player.sendMessage(ChatColor.GREEN + "Farmer Bob linked that chest for auto-harvest storage.");
        openMainMenu(player);
        return true;
    }

    public void handleLinkedChestBreak(Block block) {
        Location brokenLocation = normalizeBlockLocation(block.getLocation());

        for (PlayerBobData data : playerData.values()) {
            if (!sameBlock(data.linkedChestLocation, brokenLocation)) {
                continue;
            }

            clearLinkedChest(data, true);
        }
    }

    public void handleMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getView().getTopInventory().getHolder() instanceof BobMenuHolder holder)) {
            return;
        }

        if (!holder.ownerId().equals(player.getUniqueId())) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "You can only use your own Farmer Bob menu.");
            return;
        }

        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        if (holder.menuType() == MenuType.MAIN) {
            handleMainMenuClick(player, event.getSlot());
            return;
        }

        if (holder.menuType() == MenuType.SEEDS) {
            handleSeedMenuClick(player, event.getSlot());
        }
    }

    private void handleMainMenuClick(Player player, int slot) {
        switch (slot) {
            case 10 -> {
                sellAllCrops(player);
                openMainMenu(player);
            }
            case 12 -> openSeedMenu(player);
            case 14 -> {
                claimQuestReward(player);
                openMainMenu(player);
            }
            case 16 -> openMainMenu(player);
            case 22 -> beginChestLink(player);
            case 24 -> {
                unlinkChest(player);
                openMainMenu(player);
            }
            default -> {
            }
        }
    }

    private void handleSeedMenuClick(Player player, int slot) {
        if (slot == 22) {
            openMainMenu(player);
            return;
        }

        SeedOffer offer = seedOffersBySlot.get(slot);
        if (offer == null) {
            return;
        }

        purchaseSeedOffer(player, offer);
        openSeedMenu(player);
    }

    private void openMainMenu(Player player) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        DailyQuest quest = ensureQuest(data);

        BobMenuHolder holder = new BobMenuHolder(MenuType.MAIN, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, MAIN_MENU_TITLE);
        holder.setInventory(inventory);

        inventory.setItem(10, createItem(Material.HOPPER, ChatColor.GOLD + "Sell All Crops",
            List.of(
                ChatColor.GRAY + "Turn all supported crops in",
                ChatColor.GRAY + "your inventory into Farm Coins.",
                ChatColor.YELLOW + "Wheat $2  Carrot $3  Potato $3",
                ChatColor.YELLOW + "Beetroot $4  Nether Wart $5  Torchflower $12"
            )));
        inventory.setItem(12, createItem(Material.WHEAT_SEEDS, ChatColor.GOLD + "Buy Seeds",
            List.of(
                ChatColor.GRAY + "Restock from Bob's seed shop.",
                ChatColor.YELLOW + "Click to browse seed offers."
            )));
        inventory.setItem(14, createQuestItem(quest));
        inventory.setItem(16, createItem(Material.SUNFLOWER, ChatColor.GOLD + "Farm Coins",
            List.of(
                ChatColor.GRAY + "Current balance:",
                ChatColor.YELLOW + String.valueOf(data.balance)
            )));
        inventory.setItem(20, createStorageStatusItem(data));
        inventory.setItem(22, createChestActionItem(data));
        inventory.setItem(24, createChestClearItem(data));

        player.openInventory(inventory);
    }

    private void openSeedMenu(Player player) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        BobMenuHolder holder = new BobMenuHolder(MenuType.SEEDS, player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, SEED_MENU_TITLE);
        holder.setInventory(inventory);

        for (SeedOffer offer : SEED_OFFERS) {
            inventory.setItem(offer.slot(), createItem(offer.material(),
                ChatColor.GREEN + offer.displayName(),
                List.of(
                    ChatColor.GRAY + "Amount: " + offer.amount(),
                    ChatColor.YELLOW + "Cost: " + offer.price() + " Farm Coins"
                )));
        }

        inventory.setItem(22, createItem(Material.ARROW, ChatColor.YELLOW + "Back",
            List.of(
                ChatColor.GRAY + "Return to Bob's main menu.",
                ChatColor.YELLOW + "Balance: " + data.balance + " Farm Coins"
            )));

        player.openInventory(inventory);
    }

    private void sellAllCrops(Player player) {
        int totalSold = 0;
        int totalCoins = 0;

        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null) {
                continue;
            }

            Integer price = SELL_PRICES.get(item.getType());
            if (price == null) {
                continue;
            }

            totalSold += item.getAmount();
            totalCoins += item.getAmount() * price;
            player.getInventory().setItem(slot, null);
        }

        if (totalCoins == 0) {
            player.sendMessage(ChatColor.RED + "You do not have any crops Bob can buy right now.");
            return;
        }

        PlayerBobData data = getOrCreateData(player.getUniqueId());
        data.balance += totalCoins;
        markDirty();
        player.sendMessage(ChatColor.GREEN + "Farmer Bob bought " + totalSold + " crops for " + totalCoins + " Farm Coins.");
    }

    private void purchaseSeedOffer(Player player, SeedOffer offer) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        if (data.balance < offer.price()) {
            player.sendMessage(ChatColor.RED + "You need " + offer.price() + " Farm Coins for that order.");
            return;
        }

        data.balance -= offer.price();
        player.getInventory().addItem(new ItemStack(offer.material(), offer.amount())).values().forEach(leftover ->
            player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );
        markDirty();
        player.sendMessage(ChatColor.GREEN + "Bob handed over " + offer.amount() + " " + offer.displayName() + ".");
    }

    private void claimQuestReward(Player player) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        DailyQuest quest = ensureQuest(data);

        if (quest.claimed) {
            player.sendMessage(ChatColor.YELLOW + "You already claimed today's quest reward.");
            return;
        }

        if (quest.progress < quest.target) {
            player.sendMessage(ChatColor.YELLOW + "Quest progress: " + quest.progress + "/" + quest.target + " " + humanizeCrop(quest.cropType));
            return;
        }

        quest.claimed = true;
        data.balance += quest.reward;
        markDirty();
        player.sendMessage(ChatColor.GREEN + "Quest complete. Farmer Bob paid " + quest.reward + " Farm Coins.");
    }

    private void beginChestLink(Player player) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        if (data.location == null) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "Spawn Farmer Bob before linking a chest.");
            return;
        }

        pendingChestLinks.add(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(ChatColor.YELLOW + "Right-click a chest within 20 blocks of Farmer Bob to link it.");
    }

    private void unlinkChest(Player player) {
        PlayerBobData data = getOrCreateData(player.getUniqueId());
        pendingChestLinks.remove(player.getUniqueId());

        if (data.linkedChestLocation == null) {
            player.sendMessage(ChatColor.YELLOW + "Farmer Bob does not have a linked chest right now.");
            return;
        }

        data.linkedChestLocation = null;
        markDirty();
        refreshBobState(data);
        player.sendMessage(ChatColor.YELLOW + "Farmer Bob unlinked his chest and paused auto-harvest.");
    }

    private ItemStack createQuestItem(DailyQuest quest) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + quest.description);
        lore.add(ChatColor.GRAY + "Progress: " + quest.progress + "/" + quest.target);
        lore.add(ChatColor.YELLOW + "Reward: " + quest.reward + " Farm Coins");
        lore.add(quest.claimed
            ? ChatColor.GREEN + "Already claimed today."
            : (quest.progress >= quest.target
                ? ChatColor.GREEN + "Click to claim your reward."
                : ChatColor.YELLOW + "Keep harvesting to finish it."));

        return createItem(Material.BOOK, ChatColor.GOLD + "Daily Quest", lore);
    }

    private ItemStack createStorageStatusItem(PlayerBobData data) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Auto-harvested crops go to");
        lore.add(ChatColor.GRAY + "one linked chest per Bob.");

        if (data.linkedChestLocation == null) {
            lore.add(ChatColor.YELLOW + "No linked chest");
            lore.add(ChatColor.GRAY + "Quests and trades still work.");
            lore.add(ChatColor.GRAY + "Click Link Chest to assign one.");
            return createItem(Material.CHEST, ChatColor.GOLD + "Linked Chest", lore);
        }

        lore.add(ChatColor.YELLOW + formatBlockLocation(data.linkedChestLocation));
        lore.add(ChatColor.GRAY + "Status: " + describeActivityState(data.activityState));
        lore.add(ChatColor.GRAY + "Chest must stay within 20 blocks.");
        return createItem(Material.CHEST, ChatColor.GOLD + "Linked Chest", lore);
    }

    private ItemStack createChestActionItem(PlayerBobData data) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Select or replace Bob's");
        lore.add(ChatColor.GRAY + "linked chest for auto-harvest.");
        lore.add(ChatColor.YELLOW + "Next step: right-click a chest");
        lore.add(ChatColor.YELLOW + "within 20 blocks of Bob.");

        String title = data.linkedChestLocation == null ? "Link Chest" : "Relink Chest";
        return createItem(Material.TRIPWIRE_HOOK, ChatColor.GOLD + title, lore);
    }

    private ItemStack createChestClearItem(PlayerBobData data) {
        if (data.linkedChestLocation == null) {
            return createItem(Material.PAPER, ChatColor.GOLD + "Storage Rules",
                List.of(
                    ChatColor.GRAY + "Bob pauses auto-harvest if the",
                    ChatColor.GRAY + "linked chest is full or too far away.",
                    ChatColor.GRAY + "Manual quests and trades still work."
                ));
        }

        return createItem(Material.BARRIER, ChatColor.RED + "Unlink Chest",
            List.of(
                ChatColor.GRAY + "Disconnect the current chest",
                ChatColor.GRAY + "and pause auto-harvest.",
                ChatColor.YELLOW + "Current: " + formatBlockLocation(data.linkedChestLocation)
            ));
    }

    private ItemStack createItem(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private DailyQuest ensureQuest(PlayerBobData data) {
        String today = LocalDate.now().toString();
        if (data.quest != null && today.equals(data.quest.dayKey)) {
            return data.quest;
        }

        QuestTemplate template = selectTemplate(data.ownerId, today);
        data.quest = new DailyQuest(today, template.cropType(), template.description(), template.target(), template.reward(), 0, false);
        markDirty();
        return data.quest;
    }

    private QuestTemplate selectTemplate(UUID ownerId, String dayKey) {
        int index = Math.floorMod(Objects.hash(ownerId, dayKey), QUEST_TEMPLATES.size());
        return QUEST_TEMPLATES.get(index);
    }

    private PlayerBobData getOrCreateData(UUID ownerId) {
        return playerData.computeIfAbsent(ownerId, PlayerBobData::new);
    }

    private void recordHarvest(UUID ownerId, Player player, Material cropType, int amount) {
        PlayerBobData data = playerData.get(ownerId);
        if (data == null || amount <= 0) {
            return;
        }

        DailyQuest quest = ensureQuest(data);
        if (quest.claimed || quest.cropType != cropType || quest.progress >= quest.target) {
            return;
        }

        int updatedProgress = Math.min(quest.target, quest.progress + amount);
        if (updatedProgress == quest.progress) {
            return;
        }

        quest.progress = updatedProgress;
        markDirty();

        if (updatedProgress == quest.target) {
            Player questOwner = player != null ? player : Bukkit.getPlayer(ownerId);
            if (questOwner != null) {
                questOwner.sendMessage(ChatColor.GREEN + "Farmer Bob: your daily quest is ready to claim.");
            }
        }
    }

    private void respawnSavedBobs() {
        for (PlayerBobData data : playerData.values()) {
            if (data.location == null) {
                continue;
            }

            spawnActiveBob(data);
            refreshBobState(data);
        }
    }

    private void spawnActiveBob(PlayerBobData data) {
        if (data.location == null || data.location.getWorld() == null) {
            return;
        }

        despawnActiveBob(data.ownerId);

        World world = data.location.getWorld();
        UUID ownerId = data.ownerId;

        Villager villager = world.spawn(data.location, Villager.class, entity -> {
            tagEntity(entity, ownerId, "villager");
            entity.setCustomName(ChatColor.GOLD + "Farmer Bob");
            entity.setCustomNameVisible(true);
            entity.setProfession(Villager.Profession.FARMER);
            entity.setAI(false);
            entity.setAware(false);
            entity.setInvulnerable(true);
            entity.setCanPickupItems(false);
            entity.setCollidable(false);
            entity.setPersistent(true);
            entity.setRemoveWhenFarAway(false);
            entity.setSilent(true);
        });

        ArmorStand hologram = world.spawn(data.location.clone().add(0.0D, 2.3D, 0.0D), ArmorStand.class, entity -> {
            tagEntity(entity, ownerId, "hologram");
            entity.setMarker(true);
            entity.setVisible(false);
            entity.setCustomNameVisible(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setCollidable(false);
            entity.setBasePlate(false);
            entity.setSilent(true);
        });

        activeBobs.put(ownerId, new ActiveBob(villager, hologram));
        updateHologram(data);
    }

    private void despawnActiveBob(UUID ownerId) {
        ActiveBob activeBob = activeBobs.remove(ownerId);
        if (activeBob == null) {
            return;
        }

        if (activeBob.villager().isValid()) {
            activeBob.villager().remove();
        }

        if (activeBob.hologram().isValid()) {
            activeBob.hologram().remove();
        }
    }

    private void despawnAllBobs() {
        for (UUID ownerId : List.copyOf(activeBobs.keySet())) {
            despawnActiveBob(ownerId);
        }
    }

    private void cleanupTaggedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isManagedBobEntity(entity)) {
                    entity.remove();
                }
            }
        }
    }

    private void tagEntity(Entity entity, UUID ownerId, String type) {
        PersistentDataContainer dataContainer = entity.getPersistentDataContainer();
        dataContainer.set(bobOwnerKey, PersistentDataType.STRING, ownerId.toString());
        dataContainer.set(bobTypeKey, PersistentDataType.STRING, type);
    }

    private UUID getOwnerId(Entity entity) {
        String ownerId = entity.getPersistentDataContainer().get(bobOwnerKey, PersistentDataType.STRING);
        if (ownerId == null) {
            return null;
        }

        try {
            return UUID.fromString(ownerId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String getBobType(Entity entity) {
        return entity.getPersistentDataContainer().get(bobTypeKey, PersistentDataType.STRING);
    }

    private Location normalizeLocation(Location location) {
        return new Location(
            location.getWorld(),
            location.getBlockX() + 0.5D,
            location.getBlockY(),
            location.getBlockZ() + 0.5D,
            location.getYaw(),
            location.getPitch()
        );
    }

    private Location normalizeBlockLocation(Location location) {
        return new Location(
            location.getWorld(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
    }

    private void loadPlayerData() {
        playerData.clear();
        ConfigurationSection playersSection = dataConfig.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }

        for (String key : playersSection.getKeys(false)) {
            UUID ownerId;
            try {
                ownerId = UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid Farmer Bob owner UUID: " + key);
                continue;
            }

            ConfigurationSection playerSection = playersSection.getConfigurationSection(key);
            if (playerSection == null) {
                continue;
            }

            PlayerBobData data = new PlayerBobData(ownerId);
            data.balance = playerSection.getInt("balance");
            data.location = loadLocation(playerSection.getConfigurationSection("location"));
            data.linkedChestLocation = loadLocation(playerSection.getConfigurationSection("linked-chest"));
            data.quest = loadQuest(playerSection.getConfigurationSection("quest"));
            playerData.put(ownerId, data);
        }
    }

    private Location loadLocation(ConfigurationSection section) {
        if (section == null) {
            return null;
        }

        String worldName = section.getString("world");
        if (worldName == null) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Could not load Farmer Bob world: " + worldName);
            return null;
        }

        return new Location(
            world,
            section.getDouble("x"),
            section.getDouble("y"),
            section.getDouble("z"),
            (float) section.getDouble("yaw"),
            (float) section.getDouble("pitch")
        );
    }

    private DailyQuest loadQuest(ConfigurationSection section) {
        if (section == null) {
            return null;
        }

        String dayKey = section.getString("day");
        String cropTypeName = section.getString("crop");
        String description = section.getString("description");
        if (dayKey == null || cropTypeName == null || description == null) {
            return null;
        }

        Material cropType = Material.matchMaterial(cropTypeName.toUpperCase(Locale.ROOT));
        if (cropType == null) {
            return null;
        }

        return new DailyQuest(
            dayKey,
            cropType,
            description,
            section.getInt("target"),
            section.getInt("reward"),
            section.getInt("progress"),
            section.getBoolean("claimed")
        );
    }

    private void markDirty() {
        if (saveQueued) {
            return;
        }

        saveQueued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveQueued = false;
            saveData();
        }, 20L);
    }

    private void saveData() {
        if (dataConfig == null || dataFile == null) {
            return;
        }

        dataConfig.set("players", null);
        ConfigurationSection playersSection = dataConfig.createSection("players");

        for (PlayerBobData data : playerData.values()) {
            ConfigurationSection playerSection = playersSection.createSection(data.ownerId.toString());
            playerSection.set("balance", data.balance);

            if (data.location != null) {
                saveLocation(playerSection.createSection("location"), data.location);
            }

            if (data.linkedChestLocation != null) {
                saveLocation(playerSection.createSection("linked-chest"), data.linkedChestLocation);
            }

            if (data.quest != null) {
                ConfigurationSection questSection = playerSection.createSection("quest");
                questSection.set("day", data.quest.dayKey);
                questSection.set("crop", data.quest.cropType.name());
                questSection.set("description", data.quest.description);
                questSection.set("target", data.quest.target);
                questSection.set("reward", data.quest.reward);
                questSection.set("progress", data.quest.progress);
                questSection.set("claimed", data.quest.claimed);
            }
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save farmerbob-data.yml: " + exception.getMessage());
        }
    }

    private void saveLocation(ConfigurationSection section, Location location) {
        section.set("world", location.getWorld().getName());
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", location.getYaw());
        section.set("pitch", location.getPitch());
    }

    private void startHarvestTask() {
        stopHarvestTask();
        harvestTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runHarvestCycle, HARVEST_INTERVAL_TICKS, HARVEST_INTERVAL_TICKS);
    }

    private void stopHarvestTask() {
        if (harvestTask == null) {
            return;
        }

        harvestTask.cancel();
        harvestTask = null;
    }

    private void runHarvestCycle() {
        for (Map.Entry<UUID, ActiveBob> entry : activeBobs.entrySet()) {
            PlayerBobData data = playerData.get(entry.getKey());
            if (data == null || data.location == null) {
                continue;
            }

            autoHarvest(data);
        }
    }

    private void autoHarvest(PlayerBobData data) {
        Chest linkedChest = resolveLinkedChest(data);
        if (linkedChest == null) {
            return;
        }

        Block ripeCrop = findRipeCrop(data.location);
        if (ripeCrop == null) {
            setActivityState(data, BobActivityState.READY);
            return;
        }

        List<ItemStack> drops = createStoredDrops(ripeCrop);
        if (!canFitAll(linkedChest.getInventory(), drops)) {
            setActivityState(data, BobActivityState.FULL);
            return;
        }

        for (ItemStack drop : drops) {
            linkedChest.getInventory().addItem(drop.clone());
        }

        replantCrop(ripeCrop);
        setActivityState(data, BobActivityState.READY);
        recordHarvest(data.ownerId, null, ripeCrop.getType(), 1);
    }

    private Chest resolveLinkedChest(PlayerBobData data) {
        if (data.linkedChestLocation == null) {
            setActivityState(data, BobActivityState.NO_CHEST);
            return null;
        }

        if (data.location == null || data.location.getWorld() == null) {
            return null;
        }

        if (!sameWorld(data.location, data.linkedChestLocation) || data.location.distanceSquared(data.linkedChestLocation) > CHEST_LINK_RANGE * CHEST_LINK_RANGE) {
            setActivityState(data, BobActivityState.TOO_FAR);
            return null;
        }

        Block block = data.linkedChestLocation.getBlock();
        if (!(block.getState() instanceof Chest chest)) {
            clearLinkedChest(data, true);
            return null;
        }

        return chest;
    }

    private void clearLinkedChest(PlayerBobData data, boolean notifyOwner) {
        if (data.linkedChestLocation == null) {
            return;
        }

        data.linkedChestLocation = null;
        markDirty();
        setActivityState(data, BobActivityState.NO_CHEST);

        if (!notifyOwner) {
            return;
        }

        Player owner = Bukkit.getPlayer(data.ownerId);
        if (owner != null) {
            owner.sendMessage(ChatColor.YELLOW + "Farmer Bob lost his linked chest. Quests and trades still work, but auto-harvest is paused.");
        }
    }

    private void refreshBobState(PlayerBobData data) {
        if (data.linkedChestLocation == null) {
            setActivityState(data, BobActivityState.NO_CHEST);
            return;
        }

        Chest chest = resolveLinkedChest(data);
        if (chest == null) {
            return;
        }

        Block ripeCrop = findRipeCrop(data.location);
        if (ripeCrop == null) {
            setActivityState(data, BobActivityState.READY);
            return;
        }

        List<ItemStack> drops = createStoredDrops(ripeCrop);
        setActivityState(data, canFitAll(chest.getInventory(), drops) ? BobActivityState.READY : BobActivityState.FULL);
    }

    private void setActivityState(PlayerBobData data, BobActivityState nextState) {
        if (data.activityState == nextState) {
            return;
        }

        data.activityState = nextState;
        updateHologram(data);

        Player owner = Bukkit.getPlayer(data.ownerId);
        if (owner == null) {
            return;
        }

        switch (nextState) {
            case FULL -> owner.sendMessage(ChatColor.YELLOW + "Farmer Bob paused because his linked chest is full.");
            case TOO_FAR -> owner.sendMessage(ChatColor.YELLOW + "Farmer Bob paused because his linked chest is more than 20 blocks away.");
            default -> {
            }
        }
    }

    private void updateHologram(PlayerBobData data) {
        ActiveBob activeBob = activeBobs.get(data.ownerId);
        if (activeBob == null || !activeBob.hologram().isValid()) {
            return;
        }

        activeBob.hologram().setCustomName(switch (data.activityState) {
            case READY -> READY_HOLOGRAM_TEXT;
            case FULL -> FULL_CHEST_HOLOGRAM_TEXT;
            case TOO_FAR -> TOO_FAR_HOLOGRAM_TEXT;
            case NO_CHEST -> NO_CHEST_HOLOGRAM_TEXT;
        });
    }

    private Block findRipeCrop(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();

        for (int y = baseY - 1; y <= baseY + 1; y++) {
            for (int x = baseX - HARVEST_RADIUS; x <= baseX + HARVEST_RADIUS; x++) {
                for (int z = baseZ - HARVEST_RADIUS; z <= baseZ + HARVEST_RADIUS; z++) {
                    int deltaX = x - baseX;
                    int deltaZ = z - baseZ;
                    if (deltaX * deltaX + deltaZ * deltaZ > HARVEST_RADIUS * HARVEST_RADIUS) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    if (isRipeCrop(block)) {
                        return block;
                    }
                }
            }
        }

        return null;
    }

    private boolean isRipeCrop(Block block) {
        if (!isSupportedCrop(block.getType())) {
            return false;
        }

        if (!(block.getBlockData() instanceof Ageable ageable)) {
            return false;
        }

        return ageable.getAge() == ageable.getMaximumAge();
    }

    private List<ItemStack> createStoredDrops(Block block) {
        Collection<ItemStack> drops = block.getDrops();
        Material replantItem = getReplantItem(block.getType());
        List<ItemStack> storedDrops = new ArrayList<>();
        boolean replanted = false;

        for (ItemStack drop : drops) {
            ItemStack toStore = drop.clone();
            if (!replanted && toStore.getType() == replantItem && toStore.getAmount() > 0) {
                toStore.setAmount(toStore.getAmount() - 1);
                replanted = true;
            }

            if (toStore.getAmount() > 0) {
                storedDrops.add(toStore);
            }
        }

        return storedDrops;
    }

    private void replantCrop(Block block) {
        Ageable ageable = (Ageable) block.getBlockData();
        ageable.setAge(0);
        block.setBlockData(ageable);
    }

    private boolean canFitAll(Inventory inventory, List<ItemStack> items) {
        ItemStack[] contents = inventory.getStorageContents();
        ItemStack[] simulated = new ItemStack[contents.length];

        for (int index = 0; index < contents.length; index++) {
            simulated[index] = contents[index] == null ? null : contents[index].clone();
        }

        for (ItemStack item : items) {
            int remaining = item.getAmount();

            for (int index = 0; index < simulated.length && remaining > 0; index++) {
                ItemStack existing = simulated[index];
                if (existing == null || !existing.isSimilar(item)) {
                    continue;
                }

                int transferable = Math.min(existing.getMaxStackSize() - existing.getAmount(), remaining);
                if (transferable <= 0) {
                    continue;
                }

                existing.setAmount(existing.getAmount() + transferable);
                remaining -= transferable;
            }

            for (int index = 0; index < simulated.length && remaining > 0; index++) {
                if (simulated[index] != null) {
                    continue;
                }

                int stackAmount = Math.min(item.getMaxStackSize(), remaining);
                ItemStack stackedItem = item.clone();
                stackedItem.setAmount(stackAmount);
                simulated[index] = stackedItem;
                remaining -= stackAmount;
            }

            if (remaining > 0) {
                return false;
            }
        }

        return true;
    }

    private boolean sameWorld(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    private boolean sameBlock(Location first, Location second) {
        if (first == null || second == null || !sameWorld(first, second)) {
            return false;
        }

        return first.getBlockX() == second.getBlockX()
            && first.getBlockY() == second.getBlockY()
            && first.getBlockZ() == second.getBlockZ();
    }

    private String formatBlockLocation(Location location) {
        return location.getWorld().getName() + " "
            + location.getBlockX() + ", "
            + location.getBlockY() + ", "
            + location.getBlockZ();
    }

    private String describeActivityState(BobActivityState activityState) {
        return switch (activityState) {
            case READY -> "Running";
            case FULL -> "Paused - chest full";
            case TOO_FAR -> "Paused - chest too far";
            case NO_CHEST -> "Paused - no linked chest";
        };
    }

    private static String humanizeCrop(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Map<Material, Material> createReplantItems() {
        Map<Material, Material> replantItems = new EnumMap<>(Material.class);
        replantItems.put(Material.WHEAT, Material.WHEAT_SEEDS);
        replantItems.put(Material.BEETROOTS, Material.BEETROOT_SEEDS);
        replantItems.put(Material.CARROTS, Material.CARROT);
        replantItems.put(Material.POTATOES, Material.POTATO);
        replantItems.put(Material.NETHER_WART, Material.NETHER_WART);
        replantItems.put(Material.TORCHFLOWER_CROP, Material.TORCHFLOWER_SEEDS);
        return replantItems;
    }

    private static Map<Material, Integer> createSellPrices() {
        Map<Material, Integer> prices = new EnumMap<>(Material.class);
        prices.put(Material.WHEAT, 2);
        prices.put(Material.CARROT, 3);
        prices.put(Material.POTATO, 3);
        prices.put(Material.BEETROOT, 4);
        prices.put(Material.NETHER_WART, 5);
        prices.put(Material.TORCHFLOWER_SEEDS, 12);
        return prices;
    }

    private enum MenuType {
        MAIN,
        SEEDS
    }

    private enum BobActivityState {
        READY,
        FULL,
        TOO_FAR,
        NO_CHEST
    }

    private record QuestTemplate(Material cropType, String description, int target, int reward) {
    }

    private record SeedOffer(int slot, Material material, String displayName, int amount, int price) {
    }

    private static final class PlayerBobData {
        private final UUID ownerId;
        private Location location;
        private Location linkedChestLocation;
        private int balance;
        private DailyQuest quest;
        private BobActivityState activityState = BobActivityState.NO_CHEST;

        private PlayerBobData(UUID ownerId) {
            this.ownerId = ownerId;
        }
    }

    private static final class DailyQuest {
        private final String dayKey;
        private final Material cropType;
        private final String description;
        private final int target;
        private final int reward;
        private int progress;
        private boolean claimed;

        private DailyQuest(String dayKey, Material cropType, String description, int target, int reward, int progress, boolean claimed) {
            this.dayKey = dayKey;
            this.cropType = cropType;
            this.description = description;
            this.target = target;
            this.reward = reward;
            this.progress = progress;
            this.claimed = claimed;
        }
    }

    private record ActiveBob(Villager villager, ArmorStand hologram) {
    }

    private static final class BobMenuHolder implements InventoryHolder {
        private final MenuType menuType;
        private final UUID ownerId;
        private Inventory inventory;

        private BobMenuHolder(MenuType menuType, UUID ownerId) {
            this.menuType = menuType;
            this.ownerId = ownerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private MenuType menuType() {
            return menuType;
        }

        private UUID ownerId() {
            return ownerId;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
