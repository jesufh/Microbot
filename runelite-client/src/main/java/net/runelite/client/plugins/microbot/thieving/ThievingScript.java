package net.runelite.client.plugins.microbot.thieving;

import com.google.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.ObjectComposition;
import net.runelite.client.plugins.microbot.thieving.enums.ThievingNpc;
import net.runelite.client.plugins.microbot.util.cache.Rs2NpcCache;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.security.Login;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Slf4j
public class ThievingScript extends Script {
    private final ThievingConfig config;
    private final ThievingPlugin plugin;

    private static final int DARKMEYER_REGION = 14388;

    public State currentState = State.IDLE;

    private Rs2NpcModel thievingNpc = null;

    @Getter
    private volatile boolean underAttack;

    private long lastShadowVeil = 0;
    private static final ActionTimer DOOR_TIMER = new ActionTimer();
    private final long[] doorCloseTime = new long[3];
    private int doorCloseIndex = 0;
    private long lastAction = Long.MAX_VALUE;

    public static int getCloseDoorTime() {
        return DOOR_TIMER.getRemainingTime();
    }

    @Inject
    public ThievingScript(final ThievingConfig config, final ThievingPlugin plugin) {
        this.config = config;
        this.plugin = plugin;
    }

    private Predicate<Rs2NpcModel> getThievingNpcFilter() {
        Predicate<Rs2NpcModel> filter = npc -> true;
        if (net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin.getHighlightedNpcs().isEmpty()) {
            switch (config.THIEVING_NPC()) {
                case VYRES:
                    filter = npc -> ThievingData.VYRES.contains(npc.getName());
                    break;
                case ARDOUGNE_KNIGHT:
                    filter = npc -> {
                        final String name = npc.getName();
                        return name != null && name.toLowerCase().contains("knight of ardougne");
                    };
                    if (config.ardougneAreaCheck()) filter = filter.and(npc -> ThievingData.ARDOUGNE_AREA.contains(npc.getWorldLocation()));
                    break;
                case ELVES:
                    filter = npc -> ThievingData.ELVES.contains(npc.getName());
                    break;
                case WEALTHY_CITIZEN:
                    filter = npc -> "Wealthy citizen".equalsIgnoreCase(npc.getName());
                    filter = filter.and(npc -> npc != null && npc.isInteracting() && npc.getInteracting() != null);
                    break;
                default:
                    filter = npc -> {
                        final String name = npc.getName();
                        return name != null && name.toLowerCase().contains(config.THIEVING_NPC().getName());
                    };
                    break;
            }
        }
        return filter;
    }

    private Rs2NpcModel getThievingNpc() {
        final Rs2NpcModel npc = Rs2NpcCache.getAllNpcs()
                .filter(getThievingNpcFilter())
                .filter(n -> !isNpcNull(n))
                .min(Comparator.comparingInt(Rs2NpcModel::getDistanceFromPlayer)).orElse(null);
        if (npc == null) return null;
        log.info("Found new NPC={} to thieve @ {}", npc.getName(), toString(npc.getWorldLocation()));
        return npc;
    }

    private boolean isBeingAttackByNpc() {
        final Player me = Microbot.getClient().getLocalPlayer();
        if (me == null) return false;

        final Rs2NpcModel[] npcs = Rs2NpcCache.getAllNpcs().toArray(Rs2NpcModel[]::new);
        if (npcs.length == 0) return false;

        return Microbot.getClientThread().runOnClientThreadOptional(() -> Arrays.stream(npcs)
                .filter(getThievingNpcFilter().negate())
                .filter(npc -> !isNpcNull(npc))
                .anyMatch(n -> me.equals(n.getInteracting()))).orElse(false);
    }

    private State getCurrentState() {
        if (underAttack || isBeingAttackByNpc()) {
            if (!underAttack) underAttack = true;
            return State.BANK;
        }

        if (!hasReqs()) return State.BANK;

        if (config.useFood() && Rs2Player.getHealthPercentage() <= config.hitpoints()) return State.EAT;

        if (Rs2Inventory.isFull()) return State.DROP;

        if (config.THIEVING_NPC() == ThievingNpc.VYRES) {
            // delayed door closing logic
            List<TileObject> doors = getDoors(Rs2Player.getWorldLocation(), 4);
            if (doors.isEmpty()) {
                DOOR_TIMER.unset();
            } else if (DOOR_TIMER.isSet()) {
                if (DOOR_TIMER.isTime()) {
                    final long current = System.currentTimeMillis();
                    // did we close the door 3 times in the last 2min? (probably someone troll opening door)
                    if (Arrays.stream(doorCloseTime).allMatch(time -> time - 120_000 > current)) {
                        Arrays.fill(doorCloseTime, 0);
                        return State.HOP;
                    }
                    doorCloseTime[doorCloseIndex] = current;
                    doorCloseIndex = (doorCloseIndex+1) % doorCloseTime.length;
                    return State.CLOSE_DOOR;
                }
            } else {
                // delayed door closing
                log.info("Found {} open door(s).", doors.size());
                DOOR_TIMER.set(System.currentTimeMillis()+3_000+(int) (Math.random()*4_000));
            }
        }

        if (shouldOpenCoinPouches()) return State.COIN_POUCHES;

        if (isNpcNull(thievingNpc) && (thievingNpc = getThievingNpc()) == null) return State.WALK_TO_START;

        if (config.THIEVING_NPC() == ThievingNpc.VYRES) {
            final WorldPoint[] housePolygon = ThievingData.VYRE_HOUSES.get(thievingNpc.getName());

            if (Rs2NpcCache.getAllNpcs()
                    .filter(Rs2NpcModel.matches(true, "Vyrewatch Sentinel"))
                    .anyMatch(npc -> isPointInPolygon(housePolygon, npc.getWorldLocation()))) {
                log.info("Vyrewatch Sentinel inside house");
                return State.HOP;
            }

            if (!isPointInPolygon(housePolygon, thievingNpc.getWorldLocation())) {
                if (!sleepUntil(() -> isPointInPolygon(housePolygon, thievingNpc.getWorldLocation()), 8_000 + (int)(Math.random() * 4_000))) {
                    log.info("Vyre outside house @ {}", toString(thievingNpc.getWorldLocation()));
                    return State.HOP;
                }
            }

            if (!isPointInPolygon(housePolygon, Rs2Player.getWorldLocation())) {
                Rs2Walker.walkTo(thievingNpc.getWorldLocation());
            }
        }

        if (shouldCastShadowVeil()) return State.SHADOW_VEIL;
        return State.PICKPOCKET;
    }

    public void loop() {
        if (!Microbot.isLoggedIn() || !super.run()) return;
        if (initialPlayerLocation == null) initialPlayerLocation = Rs2Player.getWorldLocation();

        currentState = getCurrentState();

        if (Rs2Player.isStunned() && (currentState != State.PICKPOCKET || !config.ignoreStuns())) {
            currentState = State.STUNNED;
            sleepUntil(() -> !Rs2Player.isStunned(), 8_000);
            return;
        }

        switch(currentState) {
            case BANK:
                bankAndEquip();
                return;
            case EAT:
                Rs2Player.eatAt(config.hitpoints());
                return;
            case DROP:
                dropAllExceptImportant();
                if (Rs2Inventory.isFull()) Rs2Player.eatAt(99);
                return;
            case HOP:
                hopWorld();
                return;
            case COIN_POUCHES:
                Rs2Inventory.interact("coin pouch", "Open-all");
                return;
            case WALK_TO_START:
                Rs2Walker.walkTo(initialPlayerLocation, 0);
                Rs2Player.waitForWalking();
                return;
            case SHADOW_VEIL:
                castShadowVeil();
                return;
            case CLOSE_DOOR:
                if (isNpcNull(thievingNpc)) return;
                if (isPointInPolygon(ThievingData.VYRE_HOUSES.get(thievingNpc.getName()), Rs2Player.getWorldLocation())) {
                    if (closeNearbyDoor(4)) DOOR_TIMER.unset();
                } else if (isPointInPolygon(ThievingData.VYRE_HOUSES.get(thievingNpc.getName()), thievingNpc.getWorldLocation())) {
                    Rs2Walker.walkTo(thievingNpc.getWorldLocation());
                }
                return;
            case PICKPOCKET:
                if (equipSet(ThievingData.ROGUE_SET)) {
                    log.info("Equipped rogue set");
                    return;
                }

                if (!Rs2Equipment.isWearing("dodgy necklace") && Rs2Inventory.hasItem("dodgy necklace")) {
                    if (Rs2Player.isStunned()) sleepUntil(() -> !Rs2Player.isStunned());
                    log.info("Equipping dodgy necklace");
                    Rs2Inventory.wield("dodgy necklace");
                    sleepUntil(() -> Rs2Equipment.isWearing("dodgy necklace") || Rs2Player.isStunned(), 1_800);
                    return;
                }

                // limit is so breaks etc. don't cause a high last action time
                long timeSince = Math.min(System.currentTimeMillis()-lastAction, 1_000);
                if (timeSince < 250) {
                    sleep((int) (250-timeSince) + 50, (int) (250-timeSince) + 250);
                    timeSince = 350;
                }
                double rand = Math.random();
                if ((timeSince / 500_000d) > rand) sleep(5_000, 10_000); // around every 500s
                if ((timeSince / 30_000d) > rand) sleep(300, 700); // around every 30s

                var highlighted = net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin.getHighlightedNpcs();
                if (highlighted.isEmpty()) {
                    if (isNpcNull(thievingNpc)) return;
                    Rs2Npc.pickpocket(thievingNpc);
                } else {
                    Rs2Npc.pickpocket(highlighted);
                }
                lastAction = System.currentTimeMillis();
                return;
            default:
                // idk
                break;
        }
    }

    public boolean run() {
        Microbot.isCantReachTargetDetectionEnabled = true;
        lastAction = System.currentTimeMillis();
        underAttack = false;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                loop();
            } catch (Exception ex) {
                Microbot.logStackTrace(getClass().getSimpleName(), ex);
            }
        }, 0, 20, TimeUnit.MILLISECONDS);
        return true;
    }

    private boolean hasReqs() {
        boolean hasReqs = true;
        if (Rs2Inventory.getInventoryFood().isEmpty()) {
            log.info("Missing food");
            hasReqs = false;
        }
        if (config.dodgyNecklaceAmount() > 0 && !Rs2Inventory.hasItem("Dodgy necklace")) {
            log.info("Missing dodgy necklaces");
            hasReqs = false;
        }

        if (config.shadowVeil()) {
            if (Rs2Inventory.itemQuantity("Cosmic rune") < 5) {
                log.info("Missing cosmic runes");
                hasReqs = false;
            }
            boolean hasRunes = Rs2Equipment.isWearing("Lava battlestaff") || Rs2Inventory.hasItem("Earth rune", "Fire rune");
            if (!hasRunes) {
                log.info("Missing lava battle staff or earth & fire runes");
                hasReqs = false;
            }
        }
        return hasReqs;
    }

    private boolean isPointInPolygon(WorldPoint[] polygon, WorldPoint point) {
        if (polygon == null || point == null) return false;
        int n = polygon.length;
        if (n < 3) return false;

        int plane = polygon[0].getPlane();
        if (point.getPlane() != plane) return false;

        int px = point.getX();
        int py = point.getY();
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            int xi = polygon[i].getX(), yi = polygon[i].getY();
            int xj = polygon[j].getX(), yj = polygon[j].getY();

            // we check if the point is on the border
            int dx = xj - xi;
            int dy = yj - yi;
            int dxp = px - xi;
            int dyp = py - yi;

            int cross = dx * dyp - dy * dxp;
            if (cross == 0 && // the coords area collinear
                    Math.min(xi, xj) <= px && px <= Math.max(xi, xj) &&
                    Math.min(yi, yj) <= py && py <= Math.max(yi, yj)) {
                return true; // so it is on an edge of the polygon
            }

            // apply the ray-casting algorithm
            boolean intersect = ((yi > py) != (yj > py)) &&
                    (px < (double)(xj - xi) * (py - yi) / (double)(yj - yi) + xi);
            if (intersect) inside = !inside;
        }

        return inside;
    }

    private boolean shouldCastShadowVeil() {
        if (!config.shadowVeil()) return false;
        return lastShadowVeil + 60_000 <= System.currentTimeMillis() || !Rs2Magic.isShadowVeilActive();
    }

    private void castShadowVeil() {
        if (!shouldCastShadowVeil()) return;
        if (!Rs2Magic.canCast(MagicAction.SHADOW_VEIL)) {
            log.error("Cannot cast shadow veil");
            return;
        }
        if (!Rs2Magic.cast(MagicAction.SHADOW_VEIL)) {
            log.error("Failed to cast shadow veil");
            return;
        }
        if (!sleepUntil(Rs2Magic::isShadowVeilActive, 10_000)) {
            log.error("Failed to await shadow veil active");
            return;
        }
        lastShadowVeil = System.currentTimeMillis();
    }

    private boolean shouldOpenCoinPouches() {
        int threshold = Math.max(1, Math.min(plugin.getMaxCoinPouch(), config.coinPouchTreshHold() + (int)(Math.random() * 7 - 3)));
        return Rs2Inventory.hasItemAmount("coin pouch", threshold, true);
    }

    private boolean isNpcNull(Rs2NpcModel npc) {
        if (npc == null) return true;
        final String name = npc.getName();
        if (name == null) return true;
        if (name.isBlank() || name.equalsIgnoreCase("null")) return true;
        final WorldPoint worldPoint = npc.getWorldLocation();
        if (worldPoint == null) return true;
        final WorldPoint myLoc = Rs2Player.getWorldLocation();
        if (myLoc == null || myLoc.distanceTo(worldPoint) >= 20) return true;
        return false;
    }

    private String toString(WorldPoint point) {
        if (point == null) return "(-1,-1,-1)";
        return "(" + point.getX() + "," + point.getY() + "," + point.getPlane() + ")";
    }

    private List<TileObject> getDoors(WorldPoint wp, int radius) {
        // this take 1.5s off client thread
        return Microbot.getClientThread().runOnClientThreadOptional(() -> Rs2GameObject.getAll(
                o -> {
                    ObjectComposition comp = Rs2GameObject.convertToObjectComposition(o);
                    return comp != null && Arrays.asList(comp.getActions()).contains("Close");
                }, wp, radius
        )).orElse(Collections.emptyList());
    };

    private boolean closeNearbyDoor(int radius) {
        for (TileObject door : getDoors(Rs2Player.getWorldLocation(), radius)) {
            final WorldPoint doorWp = door.getWorldLocation();
            if (!Rs2GameObject.interact(door, "Close")) return false;
            if (door.getWorldLocation().distanceTo(Rs2Player.getWorldLocation()) > 1) {
                if (!sleepUntil(() -> Rs2Player.isMoving() || Rs2Player.isStunned(), 1_200)) return false;
                if (Rs2Player.isStunned()) return false;
                sleepUntil(() -> !Rs2Player.isMoving());
            }
            if (!sleepUntil(() -> getDoors(doorWp, 1).isEmpty() || Rs2Player.isStunned(), 1_200)) {
                log.warn("Failed to wait closing door @ {}", toString(doorWp));
                return false;
            }
            if (Rs2Player.isStunned()) return false;
            log.info("Closed door @ {}", toString(doorWp));
        }
        return true;
    }

    private boolean equipSet(Set<String> set) {
        boolean changed = false;
        for (String item : set) {
            if (!Rs2Equipment.isWearing(item)) {
                if (Rs2Inventory.contains(item)) {
                    Rs2Inventory.wear(item);
                    Rs2Inventory.waitForInventoryChanges(3000);
                    changed = true;
                } else if (Rs2Bank.hasBankItem(item)) {
                    if (Rs2Player.getWorldLocation().getRegionID() == DARKMEYER_REGION) {
                        Rs2Bank.withdrawItem(item);
                    } else {
                        Rs2Bank.withdrawAndEquip(item);
                    }
                    Rs2Inventory.waitForInventoryChanges(3000);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private String[] getExclusions() {
        ArrayList<String> exclusions = new ArrayList<>();
        if (config.shadowVeil()) {
            exclusions.add("Cosmic rune");
            exclusions.add("Earth rune");
            exclusions.add("Fire rune");
        }
        return exclusions.toArray(String[]::new);
    }

    private void bankAndEquip() {
        BankLocation bank;
        if (config.THIEVING_NPC() == ThievingNpc.VYRES && ThievingData.OUTSIDE_HALLOWED_BANK.distanceTo(Rs2Player.getWorldLocation()) < 20) {
            log.info("Near Hallowed");
            bank = BankLocation.HALLOWED_SEPULCHRE;
        } else {
            log.info("Not Near Hallowed");
            bank = Rs2Bank.getNearestBank();
            if (bank == BankLocation.DARKMEYER) equipSet(ThievingData.VYRE_SET);
        }

        boolean opened = Rs2Bank.isNearBank(bank, 8) ? Rs2Bank.openBank() : Rs2Bank.walkToBankAndUseBank(bank);
        if (!opened || !Rs2Bank.isOpen()) return;
        Rs2Bank.depositAllExcept(getExclusions());

        boolean successfullyWithdrawFood = Rs2Bank.withdrawX(true, config.food().getName(), config.foodAmount(), true);
        Rs2Inventory.waitForInventoryChanges(3000);

        if (!successfullyWithdrawFood) {
            Microbot.showMessage("No " + config.food().getName() + " found in bank.");
            shutdown();
            return;
        }

        boolean ateFood = false;
        if (config.eatFullHpBank()) {
            while (!Rs2Player.isFullHealth() && Rs2Player.useFood()) {
                Rs2Player.waitForAnimation();
                ateFood = true;
            }

            if (ateFood) {
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
        }

        boolean successDodgy = Rs2Bank.withdrawDeficit("Dodgy necklace", config.dodgyNecklaceAmount());
        Rs2Inventory.waitForInventoryChanges(3000);

        if (!successDodgy) {
            Microbot.showMessage("No Dodgy necklace found in bank.");
            shutdown();
            return;
        }

        if (config.shadowVeil()) {
            if (!Rs2Equipment.isWearing("Lava battlestaff")) {
                if (!Rs2Inventory.contains("Lava battlestaff") &&
                        !(Rs2Inventory.contains("Earth rune") && Rs2Inventory.contains("Fire rune"))) {
                    if (Rs2Bank.hasItem("Lava battlestaff")) {
                        Rs2Bank.withdrawItem("Lava battlestaff");
                        Rs2Inventory.waitForInventoryChanges(3_000);
                    } else if (Rs2Bank.hasItem("Earth rune") && Rs2Bank.hasItem("Fire rune")) {
                        Rs2Bank.withdrawAll(true, "Fire rune", true);
                        Rs2Inventory.waitForInventoryChanges(3000);
                        Rs2Bank.withdrawAll(true, "Earth rune", true);
                        Rs2Inventory.waitForInventoryChanges(3000);
                    } else {
                        Microbot.showMessage("No Lava battlestaff and runes (Earth, Fire) found in bank.");
                        shutdown();
                        return;
                    }
                }
                if (Rs2Inventory.contains("Lava battlestaff")) {
                    Rs2Inventory.wear("Lava battlestaff");
                    Rs2Inventory.waitForInventoryChanges(3000);
                }
            }

            Rs2Bank.withdrawAll(true, "Cosmic rune", true);
            Rs2Inventory.waitForInventoryChanges(3000);
            if (!Rs2Inventory.hasItem("Cosmic rune")) {
                Microbot.showMessage("No Cosmic runes found.");
                shutdown();
                return;
            }
        }

        equipSet(ThievingData.ROGUE_SET);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen());

        if (underAttack) {
            underAttack = false;
            hopWorld();
        }

        if (Rs2Walker.walkTo(initialPlayerLocation)) {
            sleepUntil(() -> !Rs2Player.isMoving(), 1_200);
            if (config.THIEVING_NPC() == ThievingNpc.VYRES) {
                thievingNpc = getThievingNpc();
                if (thievingNpc != null) {
                    if (isPointInPolygon(ThievingData.VYRE_HOUSES.get(thievingNpc.getName()), thievingNpc.getWorldLocation()))
                        closeNearbyDoor(4);
                }
            }
        }
    }

    private void dropAllExceptImportant() {
        Set<String> keep = new HashSet<>();
        if (config.DoNotDropItemList() != null && !config.DoNotDropItemList().isEmpty())
            keep.addAll(Arrays.asList(config.DoNotDropItemList().split(",")));
        Rs2Inventory.getInventoryFood().forEach(food -> keep.add(food.getName()));
        keep.add("dodgy necklace"); keep.add("coins"); keep.add("coin pouch"); keep.add("book of the dead"); keep.add("drakan's medallion");
        if (config.shadowVeil()) Collections.addAll(keep, "Fire rune", "Earth rune", "Cosmic rune");
        keep.addAll(ThievingData.VYRE_SET); keep.addAll(ThievingData.ROGUE_SET);
        Rs2Inventory.dropAllExcept(config.keepItemsAboveValue(), keep.toArray(new String[0]));
    }

    private boolean waitUntilBothInPolygon(WorldPoint[] polygon, Rs2NpcModel npc, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (!Microbot.isLoggedIn()) return false;
            boolean npcInside = isPointInPolygon(polygon, npc.getWorldLocation());
            boolean playerInside = isPointInPolygon(polygon, Rs2Player.getWorldLocation());
            if (npcInside && playerInside) {
                return true;
            }
            sleep(250, 350);
        }
        return false;
    }

    private void hopWorld() {
        thievingNpc = null;
        final int maxAttempts = 5;

        log.info("Hopping world, please wait...");
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int world = Login.getRandomWorld(true, null);
            Microbot.hopToWorld(world);
            boolean hopSuccess = sleepUntil(() -> Rs2Player.getWorld() == world && Microbot.loggedIn, 10_000);
            if (hopSuccess) return;
            sleep(250, 350);
        }
        log.error("Failed to hop world");
    }

    @Override
    public void shutdown() {
        super.shutdown();
        Rs2Walker.setTarget(null);
        Microbot.isCantReachTargetDetectionEnabled = false;
    }
}
