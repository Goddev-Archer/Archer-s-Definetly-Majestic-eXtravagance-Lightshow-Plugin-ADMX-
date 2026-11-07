package de.archer.admx.render.geom;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextDisplayTriangleTest {

    @Test
    void testUnitTriangleCreation() {
        Vector3f p1 = new Vector3f(0f, 0f, 0f);
        Vector3f p2 = new Vector3f(1f, 0f, 0f);
        Vector3f p3 = new Vector3f(0f, 1f, 0f);

        TextDisplayTriangle triangle = TextDisplayTriangle.calculate(p1, p2, p3);

        assertNotNull(triangle);
        assertEquals(3, triangle.pieceTransforms().size(), "Must consist of 3 sheared sub-quads");
        assertEquals(1.0f, triangle.width(), 0.001f);
        assertEquals(1.0f, triangle.height(), 0.001f);

        // Flächennormale muss in +Z Richtung zeigen
        assertEquals(0.0f, triangle.normal().x, 0.001f);
        assertEquals(0.0f, triangle.normal().y, 0.001f);
        assertEquals(1.0f, triangle.normal().z, 0.001f);
    }

    @Test
    void testLaserFanGeometry() {
        Vector3f apex = new Vector3f(0f, 10f, 0f);
        Vector3f left = new Vector3f(-5f, 0f, 10f);
        Vector3f right = new Vector3f(5f, 0f, 10f);

        TextDisplayTriangle fan = TextDisplayTriangle.calculate(apex, left, right);

        assertNotNull(fan);
        assertEquals(3, fan.pieceTransforms().size());
        assertTrue(fan.width() > 0f);
        assertTrue(fan.height() > 0f);
        for (Matrix4f transform : fan.pieceTransforms()) {
            assertNotNull(transform);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    assertFalse(Float.isNaN(transform.get(i, j)), "Matrix element must not be NaN");
                    assertFalse(Float.isInfinite(transform.get(i, j)), "Matrix element must not be Infinite");
                }
            }
        }
    }

    @Test
    void testExactMinecraftClientVerticesMatchTriangleCorners() {
        // Simuliert exakt Minecrafts DisplayRenderer$TextDisplayRenderer (26.3):
        // submitTextBackground(-1, -1, 4, 9) nach rotateY(PI) * scale(-0.025) * translate(-1, -9, 0)
        Matrix4f mcInternal = new Matrix4f()
                .rotateY((float) Math.PI)
                .scale(-0.025f, -0.025f, -0.025f)
                .translate(-1.0f, -9.0f, 0.0f);

        Vector4f rawBottomLeft = mcInternal.transform(new Vector4f(-1f, 9f, 0f, 1f));
        Vector4f rawBottomRight = mcInternal.transform(new Vector4f(4f, 9f, 0f, 1f));
        Vector4f rawTopLeft = mcInternal.transform(new Vector4f(-1f, -1f, 0f, 1f));
        Vector4f rawTopRight = mcInternal.transform(new Vector4f(4f, -1f, 0f, 1f));

        // 1. Prüfe TEXT_DISPLAY_UNIT_SQUARE -> muss exakt [0,1] x [0,1] ergeben
        Matrix4f unitSq = TextDisplayTriangle.TEXT_DISPLAY_UNIT_SQUARE;
        assertVector3Equals(new Vector3f(0f, 0f, 0f), transform3(unitSq, rawBottomLeft));
        assertVector3Equals(new Vector3f(1f, 0f, 0f), transform3(unitSq, rawBottomRight));
        assertVector3Equals(new Vector3f(0f, 1f, 0f), transform3(unitSq, rawTopLeft));
        assertVector3Equals(new Vector3f(1f, 1f, 0f), transform3(unitSq, rawTopRight));

        // 2. Prüfe ein beliebiges 3D-Dreieck im Raum (Apex -> p1 -> p2)
        Vector3f apex = new Vector3f(0f, 0f, 0f);
        Vector3f p1 = new Vector3f(-3.5f, -2.0f, -18.0f);
        Vector3f p2 = new Vector3f(4.2f, 1.5f, -18.0f);

        TextDisplayTriangle tri = TextDisplayTriangle.calculate(apex, p1, p2);
        Matrix4f leftPiece = tri.pieceTransforms().get(0);
        Matrix4f rightPiece = tri.pieceTransforms().get(1);
        Matrix4f topPiece = tri.pieceTransforms().get(2);

        // Äußere Ecken des zusammengesetzten Dreiecks müssen exakt auf apex, p1, p2 liegen!
        assertVector3Equals(apex, transform3(leftPiece, rawBottomLeft));
        assertVector3Equals(p1, transform3(rightPiece, rawBottomRight));
        assertVector3Equals(p2, transform3(topPiece, rawTopLeft));

        // Nahtstellen zwischen Left, Right und Top müssen lückenlos aufeinanderliegen:
        Vector3f midBottom = new Vector3f(apex).add(p1).mul(0.5f);
        Vector3f midLeft = new Vector3f(apex).add(p2).mul(0.5f);
        Vector3f midHypotenuse = new Vector3f(p1).add(p2).mul(0.5f);

        assertVector3Equals(midBottom, transform3(leftPiece, rawBottomRight));
        assertVector3Equals(midBottom, transform3(rightPiece, rawBottomLeft));

        assertVector3Equals(midLeft, transform3(leftPiece, rawTopLeft));
        assertVector3Equals(midLeft, transform3(topPiece, rawBottomLeft));

        assertVector3Equals(midHypotenuse, transform3(leftPiece, rawTopRight));

        // Right piece: (0,0)->midBottom, (1,0)->p1, (0,1)->midLeft, (1,1)->midHypotenuse
        assertVector3Equals(midLeft, transform3(rightPiece, rawTopLeft));
        assertVector3Equals(midHypotenuse, transform3(rightPiece, rawTopRight));

        // Top piece: (0,0)->midLeft, (1,0)->midBottom, (0,1)->p2, (1,1)->midHypotenuse
        assertVector3Equals(midBottom, transform3(topPiece, rawBottomRight));
        assertVector3Equals(midHypotenuse, transform3(topPiece, rawTopRight));
    }

    private static Vector3f transform3(Matrix4f m, Vector4f v) {
        Vector4f r = m.transform(new Vector4f(v));
        return new Vector3f(r.x, r.y, r.z);
    }

    private static void assertVector3Equals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-4f, "X mismatch");
        assertEquals(expected.y, actual.y, 1e-4f, "Y mismatch");
        assertEquals(expected.z, actual.z, 1e-4f, "Z mismatch");
    }
}
