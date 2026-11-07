package de.archer.admx.render.geom;

import org.joml.Matrix4f;

/**
 * Mathematische Hilfsfunktionen für JOML-Transformationen und Scherungs-Matrizen
 * zur Konstruktion beliebig verformter TextDisplay-Polygone.
 */
public final class MatrixUtils {

    private MatrixUtils() {
    }

    /**
     * Erzeugt eine Scherungsmatrix (Shear Matrix) in 3D.
     */
    public static Matrix4f shearMatrix(float xy, float xz, float yx, float yz, float zx, float zy) {
        return new Matrix4f(
                1f, xy, xz, 0f,
                yx, 1f, yz, 0f,
                zx, zy, 1f, 0f,
                0f, 0f, 0f, 1f
        );
    }

    /**
     * Scherung entlang der YX-Achse (entspricht Matrix4f.shear(yx = ...)).
     */
    public static Matrix4f shearYX(Matrix4f matrix, float yx) {
        return matrix.mul(shearMatrix(0f, 0f, yx, 0f, 0f, 0f));
    }

    /**
     * Scherung entlang der XY-Achse (entspricht Matrix4f.shear(xy = ...)).
     */
    public static Matrix4f shearXY(Matrix4f matrix, float xy) {
        return matrix.mul(shearMatrix(xy, 0f, 0f, 0f, 0f, 0f));
    }
}
