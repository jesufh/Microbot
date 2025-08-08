package net.runelite.client.plugins.microbot.thieving;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

import java.util.Map;
import java.util.Set;

public final class ThievingData {
    public static final WorldPoint OUTSIDE_HALLOWED_BANK = new WorldPoint(3654,3384,0);

    public static final WorldArea ARDOUGNE_AREA = new WorldArea(2649, 3280, 7, 8, 0);

    public static final Set<String> VYRE_SET = Set.of(
            "Vyre noble shoes",
            "Vyre noble legs",
            "Vyre noble top"
    );

    public static final Set<String> ROGUE_SET = Set.of(
            "Rogue mask",
            "Rogue top",
            "Rogue trousers",
            "Rogue boots",
            "Rogue gloves"
    );

    public static final Map<String, WorldPoint[]> VYRE_HOUSES = Map.of(
            "Vallessia von Pitt", new WorldPoint[]{new WorldPoint(3661, 3378, 0), new WorldPoint(3661, 3381, 0), new WorldPoint(3667, 3381, 0), new WorldPoint(3667, 3376, 0), new WorldPoint(3664, 3376, 0), new WorldPoint(3664, 3378, 0)},
            "Misdrievus Shadum", new WorldPoint[]{new WorldPoint(3608, 3346, 0), new WorldPoint(3611, 3346, 0), new WorldPoint(3611, 3343, 0), new WorldPoint(3608, 3343, 0)},
            "Natalidae Shadum", new WorldPoint[]{new WorldPoint(3608, 3342, 0), new WorldPoint(3611, 3342, 0), new WorldPoint(3611, 3337, 0), new WorldPoint(3608, 3337, 0)},
            "Vonnetta Varnis", new WorldPoint[]{new WorldPoint(3640, 3384, 0), new WorldPoint(3640, 3388, 0), new WorldPoint(3645, 3388, 0), new WorldPoint(3645, 3384, 0)},
            "Nakasa Jovkai", new WorldPoint[]{new WorldPoint(3608, 3322, 0), new WorldPoint(3608, 3327, 0), new WorldPoint(3612, 3327, 0), new WorldPoint(3612, 3322, 0)}
            // add more...
    );

    public static final Set<String> ELVES = Set.of("Anaire","Aranwe","Aredhel","Caranthir","Celebrian","Celegorm",
            "Cirdan","Curufin","Earwen","Edrahil", "Elenwe","Elladan","Enel","Erestor","Enerdhil","Enelye","Feanor",
            "Findis","Finduilas","Fingolfin", "Fingon","Galathil","Gelmir","Glorfindel","Guilin","Hendor","Idril",
            "Imin","Iminye","Indis","Ingwe", "Ingwion","Lenwe","Lindir","Maeglin","Mahtan","Miriel","Mithrellas",
            "Nellas","Nerdanel","Nimloth", "Oropher","Orophin","Saeros","Salgant","Tatie","Thingol","Turgon","Vaire",
            "Goreu");

    public static final Set<String> VYRES = VYRE_HOUSES.keySet();
}
