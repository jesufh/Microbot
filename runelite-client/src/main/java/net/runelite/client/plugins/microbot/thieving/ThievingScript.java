package net.runelite.client.plugins.microbot.thieving;

import com.google.inject.Inject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.cache.Rs2NpcCache;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ThievingScript extends Script {
    private final ThievingConfig config;
    private final ThievingPlugin plugin;
    private static final List<Integer> DARKMEYER_REGIONS = List.of(14388, 14389, 14644);
    private static final int DARKMEYER_ALTAR_ID = 39234;
    private static final WorldPoint DARKMEYER_ALTAR_LOCATION = new WorldPoint(3605, 3355, 0);
    private ScheduledFuture<?> prayerOffMonitorFuture = null;
    private Rs2NpcModel npc = null;
    private enum State {IDLE, BANK, PICKPOCKET, UNDER_ATTACK, NPC_OUT_AREA}
    public State currentState = State.IDLE;

    private static final Set<String> VYRE_SET = Set.of("Vyre noble shoes", "Vyre noble legs", "Vyre noble top");
    private static final Set<String> ROGUE_SET = Set.of("Rogue mask", "Rogue top", "Rogue trousers", "Rogue boots", "Rogue gloves");

    private static final Map<String, WorldPoint[]> VYRE_HOUSES = Map.of(
        "Vallessia von Pitt", new WorldPoint[]{new WorldPoint(3662, 3379, 0), new WorldPoint(3662, 3380, 0), new WorldPoint(3666, 3380, 0), new WorldPoint(3666, 3377, 0), new WorldPoint(3665, 3377, 0), new WorldPoint(3665, 3379, 0)},
        "Misdrievus Shadum", new WorldPoint[]{new WorldPoint(3608, 3346, 0), new WorldPoint(3611, 3346, 0), new WorldPoint(3611, 3343, 0), new WorldPoint(3608, 3343, 0)},
        "Natalidae Shadum", new WorldPoint[]{new WorldPoint(3608, 3342, 0), new WorldPoint(3611, 3342, 0), new WorldPoint(3611, 3337, 0), new WorldPoint(3608, 3337, 0)},
        "Vonnetta Varnis", new WorldPoint[]{new WorldPoint(3640, 3384, 0), new WorldPoint(3640, 3388, 0), new WorldPoint(3645, 3388, 0), new WorldPoint(3645, 3384, 0)},
        "Nakasa Jovkai", new WorldPoint[]{new WorldPoint(3608, 3322, 0), new WorldPoint(3608, 3327, 0), new WorldPoint(3612, 3327, 0), new WorldPoint(3612, 3322, 0)}
    );

    @Inject
    public ThievingScript(final ThievingConfig config, final ThievingPlugin plugin) {
        this.config = config;
        this.plugin = plugin;
    }

    public boolean run() {
        Microbot.isCantReachTargetDetectionEnabled = true;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn() || !super.run()) return;
                if (initialPlayerLocation == null) initialPlayerLocation = Rs2Player.getWorldLocation();
                if (Rs2Player.isStunned()) return;

                switch(currentState) {
                    case IDLE:
                        if (!hasReqs()) {
                            currentState = State.BANK;
                        } else if (shouldRechargePrayer()) {
                            rechargePrayerAtAltar();
                        } else {
                            currentState = State.PICKPOCKET;
                        }
                        break;
                    case BANK:
                        if (bankAndEquip()) {
                            currentState = State.IDLE;
                        }
                        break;
                    case PICKPOCKET:
                        wearIfNot("dodgy necklace");
                        if (!autoEatAndDrop()) {
                            currentState = State.IDLE;
                            return;
                        }
                        if (npc == null) npc = getCurrentPickpocketNpc();
                        if (npc == null) {
                            Rs2Walker.walkTo(initialPlayerLocation, 0);
                            Rs2Player.waitForWalking();
                            return;
                        }
                        if (!isNpcInsideCheck(npc)) break;
                        equipSet(ROGUE_SET);
                        while (!Rs2Player.isStunned() && isRunning()) {
                            if (!Microbot.isLoggedIn()) break;
                            if (isBeingAttackedByNpc(npc)) break;
                            if (config.shadowVeil()) castShadowVeil();
                            openCoinPouches();
                            if (Rs2Npc.pickpocket(npc)) sleep(100, 200);
                        }
                        break;
                    case UNDER_ATTACK:
                        if (bankAndEquip()) {
                            HopToWorld();
                            currentState = State.IDLE;
                        }
                        break;
                }
            } catch (Exception ex) {
                Microbot.logStackTrace(getClass().getSimpleName(), ex);
            }
        }, 0, 60, TimeUnit.MILLISECONDS);
        return true;
    }

    // --- PICKPOCKET LOGIC ---

    private Rs2NpcModel getCurrentPickpocketNpc() {
        switch (config.THIEVING_NPC()) {
            case WEALTHY_CITIZEN:
                return Rs2Npc.getNpcs("Wealthy citizen", true)
                        .filter(x -> x != null && x.isInteracting() && x.getInteracting() != null)
                        .findFirst().orElse(null);
            case ELVES:
                Set<String> elfs = new HashSet<>(Arrays.asList("Anaire","Aranwe","Aredhel","Caranthir","Celebrian","Celegorm","Cirdan","Curufin","Earwen","Edrahil",
                        "Elenwe","Elladan","Enel","Erestor","Enerdhil","Enelye","Feanor","Findis","Finduilas","Fingolfin","Fingon","Galathil","Gelmir",
                        "Glorfindel","Guilin","Hendor","Idril","Imin","Iminye","Indis","Ingwe","Ingwion","Lenwe","Lindir","Maeglin","Mahtan","Miriel",
                        "Mithrellas","Nellas","Nerdanel","Nimloth","Oropher","Orophin","Saeros","Salgant","Tatie","Thingol","Turgon","Vaire","Goreu"));
                return Rs2Npc.getNpcs().filter(x -> elfs.contains(x.getName())).findFirst().orElse(null);
            case VYRES:
                Set<String> vyres = new HashSet<>(Arrays.asList("Natalidae Shadum","Misdrievus Shadum","Vallessia von Pitt","Vonnetta Varnis","Nakasa Jovkai"));
                return Rs2Npc.getNpcs().filter(x -> vyres.contains(x.getName())).findFirst().orElse(null);
            case ARDOUGNE_KNIGHT:
                Rs2NpcModel knight = Rs2Npc.getNpc("knight of ardougne");
                WorldArea ardougneArea = new WorldArea(2649, 3280, 7, 8, 0);
                if (knight != null && (!config.ardougneAreaCheck() || ardougneArea.contains(knight.getWorldLocation()))) return knight;
                return null;
            default:
                return Rs2Npc.getNpc(config.THIEVING_NPC().getName());
        }
    }

    private boolean hasReqs() {
        boolean hasFood = Rs2Inventory.getInventoryFood().size() >= config.foodAmount();
        boolean hasDodgy = Rs2Inventory.hasItem("Dodgy necklace") || config.dodgyNecklaceAmount() == 0;
        if (config.shadowVeil()) {
            boolean hasCosmic = Rs2Inventory.hasItem("Cosmic rune");
            boolean hasStaff = Rs2Equipment.isWearing("Lava battlestaff");
            boolean hasRunes = hasStaff || Rs2Inventory.hasItem("Earth rune", "Fire rune");
            return hasFood && hasDodgy && hasCosmic && hasRunes;
        }
        return hasFood && hasDodgy;
    }

    private boolean isPointInPolygon(WorldPoint[] polygon, WorldPoint point) {
        int n = polygon.length;
        if (n < 3) return false;
        int plane = polygon[0].getPlane();
        if (point.getPlane() != plane) return false;
        int px = point.getX(), py = point.getY();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int xi = polygon[i].getX(), yi = polygon[i].getY();
            int xj = polygon[j].getX(), yj = polygon[j].getY();
            int dx = xj - xi, dy = yj - yi, dxp = px - xi, dyp = py - yi;
            int cross = dx * dyp - dy * dxp;
            if (cross == 0 && Math.min(xi, xj) <= px && px <= Math.max(xi, xj) && Math.min(yi, yj) <= py && py <= Math.max(yi, yj)) return true;
            boolean intersect = ((yi > py) != (yj > py)) && (px < (double)(xj - xi) * (py - yi) / (double)(yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private boolean autoEatAndDrop() {
        if (config.useFood()) {
            if (Rs2Inventory.getInventoryFood().isEmpty()) {
                openCoinPouches();
                return false;
            }
            Rs2Player.eatAt(config.hitpoints());
        }
        if (Rs2Inventory.isFull()) dropAllExceptImportant();
        return true;
    }

    private void castShadowVeil() {
        if (!Rs2Magic.isShadowVeilActive() && Rs2Magic.canCast(MagicAction.SHADOW_VEIL)) {
            Rs2Magic.cast(MagicAction.SHADOW_VEIL);
        }
    }

    private void openCoinPouches() {
        int threshold = Math.max(1, Math.min(plugin.getMaxCoinPouch(), config.coinPouchTreshHold() + (int)(Math.random() * 7 - 3)));
        if (Rs2Inventory.hasItemAmount("coin pouch", threshold, true)) {
            Rs2Inventory.interact("coin pouch", "Open-all");
        }
    }

    private void wearIfNot(String item) {
        if (!Rs2Equipment.isWearing(item) && Rs2Inventory.contains(item)) {
            Rs2Inventory.wield(item);
        }
    }

    private void closeNearbyDoor() {
        if (!DARKMEYER_REGIONS.contains(Rs2Player.getWorldLocation().getRegionID())) return;
        Rs2GameObject.getAll(o -> {
            ObjectComposition comp = Rs2GameObject.convertToObjectComposition(o);
            return comp != null && Arrays.asList(comp.getActions()).contains("Close");
        }, Rs2Player.getWorldLocation(), 10).stream()
            .filter(door -> Rs2GameObject.canReach(door.getWorldLocation()))
            .forEach(door -> {
                if (Rs2GameObject.interact(door, "Close")) Rs2Player.waitForWalking();
            });
    }

    private void equipSet(Set<String> set) {
        for (String item : set) {
            if (!Rs2Equipment.isWearing(item)) {
                if (Rs2Inventory.contains(item)) {
                    Rs2Inventory.wear(item);
                } else if (Rs2Bank.hasBankItem(item)) {
                    int regionPlayer = Rs2Player.getWorldLocation().getRegionID();
                    if (DARKMEYER_REGIONS.contains(regionPlayer)) {
                        Rs2Bank.withdrawItem(item);
                    } else {
                        Rs2Bank.withdrawAndEquip(item);
                    }
                    Rs2Inventory.waitForInventoryChanges(3000);
                }
            }
        }
    }

    private boolean bankAndEquip() {
        BankLocation bank = Rs2Bank.getNearestBank();
        if (bank == BankLocation.DARKMEYER || bank == BankLocation.HALLOWED_SEPULCHRE) equipSet(VYRE_SET);
        boolean opened = Rs2Bank.isNearBank(bank, 8) ? Rs2Bank.openBank() : Rs2Bank.walkToBankAndUseBank(bank);
        if (!opened || !Rs2Bank.isOpen()) return false;
        Rs2Bank.depositAll();
        
        boolean successfullyWithdrawFood = Rs2Bank.withdrawX(true, config.food().getName(), config.foodAmount(), true);
        Rs2Inventory.waitForInventoryChanges(3000);

        if (!successfullyWithdrawFood) {
            Microbot.showMessage("No " + config.food().getName() + " found in bank.");
            shutdown();
            return false;
        }
        if (config.eatFullHpBank()) {
            while (!Rs2Player.isFullHealth() && Rs2Player.useFood()) {
                Rs2Player.waitForAnimation();
            }
            Set<String> keep = new HashSet<>();
            Rs2Inventory.getInventoryFood().forEach(food -> keep.add(food.getName()));
            Rs2Bank.depositAll(x -> !keep.contains(x.getName()));
            int foodActual = Rs2Inventory.getInventoryFood().size();
            int foodMiss = config.foodAmount() - foodActual;
            if (foodMiss > 0) {
                Rs2Bank.withdrawX(false, config.food().getName(), foodMiss, true);
                Rs2Inventory.waitForInventoryChanges(3000);
            }
        }
        boolean successDodgy = Rs2Bank.withdrawDeficit("Dodgy necklace", config.dodgyNecklaceAmount());
        Rs2Inventory.waitForInventoryChanges(3000);

        if (!successDodgy) {
            Microbot.showMessage("No Dodgy necklace found in bank.");
            shutdown();
            return false;
        }

        if (config.shadowVeil()) {
            List<String> runesShadowVeil = Arrays.asList("Earth rune", "Fire rune");
            boolean banklavaStaff = Rs2Equipment.isWearing("Lava battlestaff") || Rs2Inventory.contains("Lava battlestaff") || Rs2Bank.hasItem("Lava battlestaff");
            boolean bankrunes = Rs2Bank.hasItem(runesShadowVeil);
            boolean bankcosmicRune = Rs2Bank.hasItem("Cosmic rune");
            if (!banklavaStaff && !bankrunes) {
                Microbot.showMessage("No Lava battlestaff and runes (Earth, Fire) found in bank.");
                shutdown();
                return false;
            }
            if (!bankcosmicRune) {
                Microbot.showMessage("No Cosmic rune found in bank.");
                shutdown();
                return false;
            }
            if (banklavaStaff) {
                Rs2Bank.withdrawItem("Lava battlestaff");
                Rs2Inventory.waitForInventoryChanges(3000);
                if (Rs2Inventory.contains("Lava battlestaff")) {
                    Rs2Inventory.wear("Lava battlestaff");
                    Rs2Inventory.waitForInventoryChanges(3000);
                }
            } else {
                Rs2Bank.withdrawAll(true, "Fire rune", true);
                Rs2Inventory.waitForInventoryChanges(3000);
                Rs2Bank.withdrawAll(true, "Earth rune", true);
                Rs2Inventory.waitForInventoryChanges(3000);
            }
            Rs2Bank.withdrawAll(true, "Cosmic rune", true);
            Rs2Inventory.waitForInventoryChanges(3000);
        }
        equipSet(ROGUE_SET);
        Rs2Bank.closeBank();
        return true;
    }

    private void dropAllExceptImportant() {
        Set<String> keep = new HashSet<>();
        if (config.DoNotDropItemList() != null && !config.DoNotDropItemList().isEmpty())
            keep.addAll(Arrays.asList(config.DoNotDropItemList().split(",")));
        Rs2Inventory.getInventoryFood().forEach(food -> keep.add(food.getName()));
        keep.add("dodgy necklace"); keep.add("coins"); keep.add("coin pouch"); keep.add("book of the dead"); keep.add("drakan's medallion");
        if (config.shadowVeil()) Collections.addAll(keep, "Fire rune", "Earth rune", "Cosmic rune");
        keep.addAll(VYRE_SET); keep.addAll(ROGUE_SET);
        Rs2Inventory.dropAllExcept(config.keepItemsAboveValue(), keep.toArray(new String[0]));
    }

    private boolean isNpcInsideCheck(Rs2NpcModel npcObject) {
        if (!DARKMEYER_REGIONS.contains(Rs2Player.getWorldLocation().getRegionID())) return true;
        WorldPoint[] housePolygon = VYRE_HOUSES.get(npcObject.getName());
        boolean npcInside = isPointInPolygon(housePolygon, npcObject.getWorldLocation());
        boolean playerInside = isPointInPolygon(housePolygon, Rs2Player.getWorldLocation());
        //if (isBeingAttackedByNpc(npcObject)) return false;
        if (playerInside && !npcInside) {
            Microbot.log("Vyre is out of his area, waiting...");
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 3500) {
                if (!Microbot.isLoggedIn()) return false;
                npcInside = isPointInPolygon(housePolygon, npcObject.getWorldLocation());
                playerInside = isPointInPolygon(housePolygon, Rs2Player.getWorldLocation());
                if (npcInside && playerInside) return true;
            }
            HopToWorld();
            return false;
        } else if (playerInside && npcInside) {
            closeNearbyDoor();
            return true;
        }
        return true;
    }

    private void HopToWorld() {
        int attempts = 0;
        int maxtries = 5;
        Microbot.log("Hopping world, please wait...");
        while (attempts < maxtries) {
            int world = Login.getRandomWorld(true, null);
            Microbot.hopToWorld(world);
            boolean hopSuccess = Rs2Player.getWorld() == world;
            if (hopSuccess) break;
            attempts++;
        }
    }

    // --- COMBAT/DEFENSE ---

    public boolean isBeingAttackedByNpc(Rs2NpcModel pickpocketNpc) {
        if (!DARKMEYER_REGIONS.contains(Rs2Player.getWorldLocation().getRegionID())) return false;

        final Rs2NpcModel[] npcs = Rs2NpcCache.getAllNpcs()
                .filter(npc -> pickpocketNpc == null || npc.getIndex() != pickpocketNpc.getIndex())
                .toArray(Rs2NpcModel[]::new);
        
        final Player me = Microbot.getClient().getLocalPlayer();
        if (me == null) return false;
        final Rs2NpcModel npc = Microbot.getClientThread().runOnClientThreadOptional(() -> Arrays.stream(npcs)
                        .filter(n -> me.equals(n.getInteracting())).findFirst().orElse(null)).orElse(null);

        if (npc != null) {
            Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MELEE, true);
            PrayerCheckThread(npc, 10, Rs2PrayerEnum.PROTECT_MELEE);
            currentState = State.UNDER_ATTACK;
            return true;
        }

        return false;
    }

    private boolean shouldRechargePrayer() {
        if (!DARKMEYER_REGIONS.contains(Rs2Player.getWorldLocation().getRegionID())) return false;
        int currentPrayer = Microbot.getClient().getBoostedSkillLevel(net.runelite.api.Skill.PRAYER);
        int maxPrayer = Microbot.getClient().getRealSkillLevel(net.runelite.api.Skill.PRAYER);
        return maxPrayer > 0 && ((double)currentPrayer / maxPrayer) < 0.2;
    }

    private void rechargePrayerAtAltar() {
        if (Rs2Player.getWorldLocation().distanceTo(DARKMEYER_ALTAR_LOCATION) > 3) {
            Rs2Walker.walkTo(DARKMEYER_ALTAR_LOCATION, 1);
            Rs2Player.waitForWalking();
        }
        var altar = Rs2GameObject.getGameObject(DARKMEYER_ALTAR_ID);
        if (altar != null) {
            Microbot.log("Recharging prayer...");
            Rs2GameObject.interact(altar, "Pray-at");
        }
    }

    private void PrayerCheckThread(Rs2NpcModel attacker, int maxDistance, Rs2PrayerEnum prayer) {
        if (prayerOffMonitorFuture != null && !prayerOffMonitorFuture.isDone()) {
            prayerOffMonitorFuture.cancel(true);
        }
        prayerOffMonitorFuture = scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                if (!Microbot.isLoggedIn() || attacker == null || attacker.isDead() || !Rs2Prayer.isPrayerActive(prayer)) {
                    prayerOffMonitorFuture.cancel(true);
                    return;
                }
                int distance = Rs2Player.getWorldLocation().distanceTo(attacker.getWorldLocation());
                if (distance > maxDistance) {
                    Rs2Prayer.toggle(prayer, false);
                    prayerOffMonitorFuture.cancel(true);
                }
            } catch (Exception e) {
                prayerOffMonitorFuture.cancel(true);
            }
        }, 0, 300, TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        Rs2Walker.setTarget(null);
        Microbot.isCantReachTargetDetectionEnabled = false;
        currentState = State.IDLE;
    }
}