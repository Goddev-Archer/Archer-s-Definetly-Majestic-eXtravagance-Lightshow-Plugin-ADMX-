package de.archer.admx.render.geom;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Berechnet Transformations-Matrizen für TextDisplay-Entities, um beliebige 3D-Dreiecke
 * (z. B. für volumetrische Laserflächen, Fächer oder Bühnen-Meshes) im Minecraft-Raum darzustellen.
 */
public record TextDisplayTriangle(
        List<Matrix4f> pieceTransforms,
        Vector3f xAxis,
        Vector3f yAxis,
        Vector3f normal,
        float width,
        float height,
        Quaternionf rotation,
        float shear
) {

    /**
     * Minecraft TextDisplay Einheitsquadrat (normiert auf [0..1] x [0..1] im lokalen Entity-Raum
     * bei einem einzelnen Leerzeichen " ").
     *
     * Hintergrund aus Minecrafts DisplayRenderer$TextDisplayRenderer (26.3):
     * Der Client transformiert die Hintergrund-Eckpunkte (-1, -1) bis (4, 9) mit:
     *   rotateY(PI) * scale(-0.025, -0.025, -0.025) * translate(-1.0, -9.0, 0.0)
     * Dadurch liegt das unskalierte Hintergrund-Quad exakt auf:
     *   X in [-0.050, +0.075] (Breite 0.125 = 1/8)
     *   Y in [ 0.000, +0.250] (Höhe   0.250 = 1/4)
     * Um dieses Quad auf [0..1] x [0..1] abzubilden, muss zuerst um (8, 4, 1) skaliert
     * und in den lokalen Koordinaten um (+0.05, 0, 0) verschoben werden!
     */
    public static final Matrix4f TEXT_DISPLAY_UNIT_SQUARE = new Matrix4f()
            .scale(8.0f, 4.0f, 1.0f)
            .translate(0.05f, 0.0f, 0.0f);

    /**
     * Einheitsdreieck bestehend aus drei gescherten TextDisplay-Teilflächen (Left, Right, Top).
     */
    public static final List<Matrix4f> TEXT_DISPLAY_UNIT_TRIANGLE = List.of(
            // Left
            new Matrix4f().scale(0.5f).mul(TEXT_DISPLAY_UNIT_SQUARE),
            // Right
            MatrixUtils.shearYX(new Matrix4f().scale(0.5f).translate(1f, 0f, 0f), -1f).mul(TEXT_DISPLAY_UNIT_SQUARE),
            // Top
            MatrixUtils.shearXY(new Matrix4f().scale(0.5f).translate(0f, 1f, 0f), -1f).mul(TEXT_DISPLAY_UNIT_SQUARE)
    );

    /**
     * Berechnet die TextDisplay-Transformationen für ein Dreieck mit drei Punkten im 3D-Raum.
     *
     * @param point1 Erster Eckpunkt (Ursprung des lokalen Dreiecks)
     * @param point2 Zweiter Eckpunkt (Basisvektor)
     * @param point3 Dritter Eckpunkt (Spitze)
     * @return Resultierende Transformationen für die 3 Teilflächen
     */
    public static TextDisplayTriangle calculate(Vector3f point1, Vector3f point2, Vector3f point3) {
        Vector3f p2 = new Vector3f(point2).sub(point1);
        Vector3f p3 = new Vector3f(point3).sub(point1);

        Vector3f zAxis = new Vector3f(p2).cross(p3).normalize();
        Vector3f xAxis = new Vector3f(p2).normalize();
        Vector3f yAxis = new Vector3f(zAxis).cross(xAxis).normalize();

        float width = p2.length();
        float height = p3.dot(yAxis);
        float p3Width = p3.dot(xAxis);

        // Orthonormale Drehmatrix R = [xAxis | yAxis | zAxis], die (1,0,0) -> xAxis,
        // (0,1,0) -> yAxis und (0,0,1) -> zAxis abbildet (ohne Spiegelung an der X-Achse!):
        Matrix3f basis = new Matrix3f(
                xAxis.x, xAxis.y, xAxis.z,
                yAxis.x, yAxis.y, yAxis.z,
                zAxis.x, zAxis.y, zAxis.z
        );
        Quaternionf rotation = new Quaternionf().setFromNormalized(basis);

        float shear = (width > 0.0001f) ? (p3Width / width) : 0f;

        Matrix4f baseTransform = new Matrix4f()
                .translate(point1)
                .rotate(rotation)
                .scale(width, height, 1f);
        baseTransform = MatrixUtils.shearYX(baseTransform, shear);

        List<Matrix4f> transforms = List.of(
                new Matrix4f(baseTransform).mul(TEXT_DISPLAY_UNIT_TRIANGLE.get(0)),
                new Matrix4f(baseTransform).mul(TEXT_DISPLAY_UNIT_TRIANGLE.get(1)),
                new Matrix4f(baseTransform).mul(TEXT_DISPLAY_UNIT_TRIANGLE.get(2))
        );

        return new TextDisplayTriangle(transforms, xAxis, yAxis, zAxis, width, height, rotation, shear);
    }

    /**
     * Berechnet eine einzelne, überlappungsfreie TextDisplay-Transformation für zwei miteinander
     * verbundene, koplanare Dreiecke (v00 -> p2 -> v11) und (v00 -> v11 -> p3), die sich die
     * Diagonale (v00 -> v11) teilen, wobei v11 = p2 + p3 - point1 gilt.
     * Verhindert jegliches Z-Fighting und Mehrfach-Alpha-Überlappungen bei transparenten Wellen-Meshes.
     *
     * @param point1 Ursprungsecke v00 (wird auf (0,0) abgebildet)
     * @param point2 Zweite Ecke v01 entlang lokaler X-Achse (wird auf (1,0) abgebildet)
     * @param point3 Dritte Ecke v10 entlang lokaler Y-Achse (wird auf (0,1) abgebildet; v11 liegt exakt auf (1,1))
     * @return Einzelne Matrix4f für ein TextDisplay-Entity ohne interne Überlappung
     */
    public static Matrix4f calculateConnectedTrianglePair(Vector3f point1, Vector3f point2, Vector3f point3) {
        Vector3f p2 = new Vector3f(point2).sub(point1);
        Vector3f p3 = new Vector3f(point3).sub(point1);

        Vector3f zAxis = new Vector3f(p2).cross(p3).normalize();
        Vector3f xAxis = new Vector3f(p2).normalize();
        Vector3f yAxis = new Vector3f(zAxis).cross(xAxis).normalize();

        float width = p2.length();
        float height = p3.dot(yAxis);
        float p3Width = p3.dot(xAxis);

        Matrix3f basis = new Matrix3f(
                xAxis.x, xAxis.y, xAxis.z,
                yAxis.x, yAxis.y, yAxis.z,
                zAxis.x, zAxis.y, zAxis.z
        );
        Quaternionf rotation = new Quaternionf().setFromNormalized(basis);

        float shear = (width > 0.0001f) ? (p3Width / width) : 0f;

        Matrix4f baseTransform = new Matrix4f()
                .translate(point1)
                .rotate(rotation)
                .scale(width, height, 1f);
        baseTransform = MatrixUtils.shearYX(baseTransform, shear);

        return baseTransform.mul(TEXT_DISPLAY_UNIT_SQUARE);
    }
}
