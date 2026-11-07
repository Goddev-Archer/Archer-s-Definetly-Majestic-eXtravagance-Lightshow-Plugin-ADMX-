package de.archer.admx.render.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gruppiert mehrere Render-Items (z. B. Base + Yoke + Head + Beams einer Fixture).
 */
public final class RenderGroup implements RenderItem {

    private final Map<Object, RenderItem> children = new LinkedHashMap<>();

    public RenderGroup set(Object subHandle, RenderItem item) {
        children.put(subHandle, item);
        return this;
    }

    public RenderItem get(Object subHandle) {
        return children.get(subHandle);
    }

    public Map<Object, RenderItem> children() {
        return Collections.unmodifiableMap(children);
    }

    @Override
    public void submit(Object handle) {
        for (Map.Entry<Object, RenderItem> entry : children.entrySet()) {
            entry.getValue().submit(new GroupKey(handle, entry.getKey()));
        }
    }

    /**
     * Liefert das oberste Wurzel-Handle (z. B. die Fixture-UUID) einer geschachtelten GroupKey-Kette.
     */
    public static Object extractRootHandle(Object key) {
        Object curr = key;
        while (curr instanceof GroupKey gk) {
            curr = gk.parent();
        }
        return curr;
    }

    /**
     * Unveränderlicher zusammengesetzter Schlüssel mit vorberechnetem Hash-Code,
     * um bei tief verschachtelten RenderGroups (z. B. Fixture -> Beam -> Face -> Piece)
     * wiederholte rekursive Hash-Berechnungen in ConcurrentHashMap-Lookups zu vermeiden.
     */
    public static final class GroupKey {
        private final Object parent;
        private final Object child;
        private final int cachedHash;

        public GroupKey(Object parent, Object child) {
            this.parent = parent;
            this.child = child;
            this.cachedHash = 31 * Objects.hashCode(parent) + Objects.hashCode(child);
        }

        public Object parent() {
            return parent;
        }

        public Object child() {
            return child;
        }

        @Override
        public int hashCode() {
            return cachedHash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof GroupKey other)) return false;
            return this.cachedHash == other.cachedHash
                    && Objects.equals(this.child, other.child)
                    && Objects.equals(this.parent, other.parent);
        }

        @Override
        public String toString() {
            return parent + "/" + child;
        }
    }
}
