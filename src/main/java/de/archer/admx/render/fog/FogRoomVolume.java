package de.archer.admx.render.fog;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Physikalische Raumberechnung für Nebelmaschinen:
 * Berechnet per beschränktem 3D/2.5D-Flood-Fill (BFS), wie groß der füllbare Raum ist,
 * berücksichtigt Wände, Decken und Bodenhöhenunterschiede und begrenzt die Ausbreitung
 * strikt auf maximal {@link FogType#MAX_REACH_BLOCKS} (30 Blöcke).
 */
public final class FogRoomVolume {

    private static final int MAX_VISITED_COLUMNS = 3200;
    private static final int[][] CARDINAL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Repräsentiert eine physikalisch erreichbare Raumsäule (x, z) mit Bodenhöhe, Deckenhöhe,
     * Distanz zur Nebelmaschine und Information, ob hier die 30-Block-Maximalgrenze ohne Wand erreicht wurde.
     */
    public record ColumnCell(
            int x,
            int z,
            int floorY,
            int ceilingY,
            float distance,
            boolean openMaxBoundary
    ) {
        public float roomHeight() {
            return Math.max(1, ceilingY - floorY);
        }
    }

    private final int originX;
    private final int originY;
    private final int originZ;
    private final float maxReach;
    private final Map<Long, ColumnCell> columns;
    private final List<ColumnCell> openBoundaryCells;
    private final boolean reachedMaxLimit;

    private FogRoomVolume(
            int originX,
            int originY,
            int originZ,
            float maxReach,
            Map<Long, ColumnCell> columns,
            List<ColumnCell> openBoundaryCells,
            boolean reachedMaxLimit
    ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.maxReach = maxReach;
        this.columns = Collections.unmodifiableMap(columns);
        this.openBoundaryCells = Collections.unmodifiableList(openBoundaryCells);
        this.reachedMaxLimit = reachedMaxLimit;
    }

    public static long columnKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Berechnet das physikalisch füllbare Raumvolumen ab der Düsenposition (startX, startY, startZ).
     *
     * @param startX      Block-X der Nebeldüse
     * @param startY      Block-Y der Nebeldüse
     * @param startZ      Block-Z der Nebeldüse
     * @param fogType     Art des Nebels (Kaltnebel vs. Raumnebel)
     * @param maxRadius   Maximaler Ausbreitungsradius (wird auf höchstens 30 Blöcke geklemmt)
     * @param passability Abfrage für Block-Durchlässigkeit
     */
    public static FogRoomVolume compute(
            int startX,
            int startY,
            int startZ,
            FogType fogType,
            float maxRadius,
            BlockPassabilityProvider passability
    ) {
        float clampedMax = Math.clamp(maxRadius, 1.0f, FogType.MAX_REACH_BLOCKS);
        float maxDistSq = clampedMax * clampedMax;

        Map<Long, ColumnCell> visitedColumns = new HashMap<>();
        Queue<int[]> queue = new ArrayDeque<>();

        // Falls der Startblock direkt an einer Kante liegt, suche nächsten passierbaren Punkt
        int seedY = startY;
        if (!passability.isPassable(startX, seedY, startZ)) {
            if (passability.isPassable(startX, seedY + 1, startZ)) {
                seedY = seedY + 1;
            } else {
                return new FogRoomVolume(startX, startY, startZ, clampedMax, Map.of(), List.of(), false);
            }
        }

        int initialFloor = findFloorY(startX, seedY, startZ, passability, 6);
        int ceilSearchHeight = (int) Math.ceil(fogType.maxVerticalSpread());
        int initialCeil = findCeilingY(startX, initialFloor, startZ, passability, ceilSearchHeight);

        long startKey = columnKey(startX, startZ);
        visitedColumns.put(startKey, new ColumnCell(startX, startZ, initialFloor, initialCeil, 0f, false));
        queue.add(new int[]{startX, initialFloor, startZ});

        boolean hitOuterLimit = false;

        while (!queue.isEmpty() && visitedColumns.size() < MAX_VISITED_COLUMNS) {
            int[] curr = queue.poll();
            int cx = curr[0];
            int cy = curr[1];
            int cz = curr[2];

            for (int[] d : CARDINAL_DIRS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                long nKey = columnKey(nx, nz);
                if (visitedColumns.containsKey(nKey)) {
                    continue;
                }

                int dx = nx - startX;
                int dz = nz - startZ;
                float distSq = dx * dx + dz * dz;

                // Prüfen, ob ein Übergang von (cx, cy, cz) nach (nx, ?, nz) physikalisch möglich ist
                int reachableY = findTransitionY(cx, cy, cz, nx, nz, initialFloor, fogType, passability);
                if (reachableY == Integer.MIN_VALUE) {
                    continue; // Massive Wand oder Hindernis blockiert den Raum!
                }

                if (distSq > maxDistSq) {
                    // Raum geht hier noch weiter, aber die 30-Block-Grenze ist erreicht!
                    hitOuterLimit = true;
                    ColumnCell prev = visitedColumns.get(columnKey(cx, cz));
                    if (prev != null && !prev.openMaxBoundary()) {
                        visitedColumns.put(
                                columnKey(cx, cz),
                                new ColumnCell(prev.x(), prev.z(), prev.floorY(), prev.ceilingY(), prev.distance(), true)
                        );
                    }
                    continue;
                }

                int nFloor = findFloorY(nx, reachableY, nz, passability, 2);
                if (fogType == FogType.LOW_FOG && nFloor > initialFloor) {
                    continue; // Schwerer Kaltnebel klettert physikalisch niemals bergauf über das Ursprungs-Bodenniveau
                }
                if (nFloor < initialFloor - 2 || nFloor > initialFloor + 1) {
                    continue; // Verhindert das Überfließen über Klippen oder Berghänge
                }
                int nCeil = findCeilingY(nx, nFloor, nz, passability, ceilSearchHeight);
                float dist = (float) Math.sqrt(distSq);

                visitedColumns.put(nKey, new ColumnCell(nx, nz, nFloor, nCeil, dist, false));
                queue.add(new int[]{nx, nFloor, nz});
            }
        }

        List<ColumnCell> boundaryList = visitedColumns.values().stream()
                .filter(ColumnCell::openMaxBoundary)
                .toList();

        return new FogRoomVolume(startX, startY, startZ, clampedMax, visitedColumns, boundaryList, hitOuterLimit);
    }

    private static int findTransitionY(
            int cx,
            int cy,
            int cz,
            int nx,
            int nz,
            int initialFloor,
            FogType fogType,
            BlockPassabilityProvider passability
    ) {
        if (passability.isPassable(nx, cy, nz)) {
            return cy;
        }
        if (fogType == FogType.LOW_FOG) {
            if (cy < initialFloor && passability.isPassable(cx, cy + 1, cz) && passability.isPassable(nx, cy + 1, nz)) {
                return cy + 1;
            }
            return Integer.MIN_VALUE;
        }
        if (cy + 1 <= initialFloor + 1 && passability.isPassable(cx, cy + 1, cz) && passability.isPassable(nx, cy + 1, nz)) {
            return cy + 1;
        }
        return Integer.MIN_VALUE;
    }

    private static int findFloorY(int x, int startY, int z, BlockPassabilityProvider passability, int maxDrop) {
        int y = startY;
        for (int i = 0; i < maxDrop; i++) {
            if (passability.isPassable(x, y - 1, z)) {
                y--;
            } else {
                break;
            }
        }
        return y;
    }

    private static int findCeilingY(int x, int floorY, int z, BlockPassabilityProvider passability, int maxRise) {
        int y = floorY + 1;
        for (int i = 0; i < maxRise; i++) {
            if (passability.isPassable(x, y, z)) {
                y++;
            } else {
                break;
            }
        }
        return y;
    }

    public Collection<ColumnCell> columns() {
        return columns.values();
    }

    public int columnCount() {
        return columns.size();
    }

    public ColumnCell getColumn(int x, int z) {
        return columns.get(columnKey(x, z));
    }

    public List<ColumnCell> openBoundaryCells() {
        return openBoundaryCells;
    }

    public boolean reachedMaxLimit() {
        return reachedMaxLimit;
    }

    public float maxReach() {
        return maxReach;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    /**
     * Berechnet die lokale Nebeldichte [0.0 .. 1.0] an einer beliebigen 3D-Weltposition (z. B. Spieler-Augen oder Strahlpunkt).
     */
    public float getFogDensityAt(double wx, double wy, double wz, FogType fogType, float currentRadius) {
        int bx = (int) Math.floor(wx);
        int bz = (int) Math.floor(wz);
        ColumnCell cell = getColumn(bx, bz);
        if (cell == null) {
            return 0f;
        }

        float effectiveRadius = Math.min(currentRadius, maxReach);
        if (cell.distance() > effectiveRadius) {
            return 0f;
        }

        double relY = wy - cell.floorY();
        if (relY < -0.25 || wy > cell.ceilingY() + 0.25) {
            return 0f;
        }

        float verticalLimit = Math.min(fogType.maxVerticalSpread(), cell.roomHeight());
        if (relY > verticalLimit) {
            return 0f;
        }

        float verticalFactor;
        if (fogType == FogType.LOW_FOG) {
            verticalFactor = Math.clamp(1.0f - (float) (relY / Math.max(0.8f, verticalLimit)), 0f, 1f);
        } else {
            float normY = (float) (relY / Math.max(1.5f, verticalLimit));
            verticalFactor = Math.clamp(1.0f - 0.45f * normY * normY, 0.25f, 1.0f);
        }

        float radialRatio = cell.distance() / Math.max(1.0f, effectiveRadius);
        float radialFactor = Math.clamp((1.0f - radialRatio) * 2.5f, 0.15f, 1.0f);

        return Math.clamp(verticalFactor * radialFactor, 0f, 1f);
    }
}
