package sgm.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Prints formula results for every variant/tier/level so tests/parity.py can diff them with Python. */
public final class ParityDump {
    public static void main(String[] args) throws Exception {
        Content c = Content.load(new File(args[0]));
        Formulas f = new Formulas(c);
        Players p = new Players(c, f, Json.obj());
        List<Object> out = new ArrayList<Object>();
        for (int tier = 0; tier < 4; tier++) {
            Formulas.Ladder l = f.charLadder(tier);
            for (int lvl = 1; lvl <= l.cap; lvl++) {
                out.add(Json.arr("ladder", (long) tier, (long) lvl, l.xpForLevel(lvl, false), l.xpForLevel(lvl, true)));
            }
        }
        for (int lvl = 1; lvl <= 100; lvl++) {
            out.add(Json.arr("player", (long) lvl, f.playerLadder.xpForLevel(lvl, false)));
        }
        for (Map<String, Object> v : c.all("VariantCharacterData")) {
            Map<String, Object> tree = c.get(v.get("skillTree"));
            List<Object> nodes = new ArrayList<Object>();
            for (Object r : Json.getList(tree, "purchaseOrder")) {
                Map<String, Object> n = c.get(r);
                if (n != null) {
                    nodes.add(n.get("guid"));
                }
            }
            float[] none = p.skillBuffs(new ArrayList<Object>());
            float[] all = p.skillBuffs(nodes);
            for (int tier = Json.i(v, "initialTier"); tier < 4; tier++) {
                for (int lvl = 1; lvl <= Formulas.levelCap(tier); lvl += 7) {
                    for (float[] b : new float[][]{none, all}) {
                        int lb = f.lifebar(v, tier, lvl, b[0]);
                        int at = f.attack(v, tier, lvl, b[1]);
                        out.add(Json.arr("stats", v.get("guid"), (long) tier, (long) lvl, b == all, (long) lb, (long) at,
                                (long) f.fighterScore(lb, at, b[2], 0, 0), (long) f.fighterScore(lb, at, b[2], 3, 0),
                                (long) f.fighterScore(lb, at, b[2], 0, 40), (long) f.fighterScore(lb, at, b[2], 0, 100)));
                    }
                }
            }
        }
        long[][] fs = {{1000, 1500}, {1499, 1500}, {500, 2000}, {2000, 1000}, {12345, 23456}, {1, 99999}};
        for (long[] x : fs) {
            out.add(Json.arr("xp", x[0], x[1], f.teamXp(x[0], x[1], true), f.teamXp(x[0], x[1], false)));
        }
        System.out.println(Json.write(out));
    }
}
