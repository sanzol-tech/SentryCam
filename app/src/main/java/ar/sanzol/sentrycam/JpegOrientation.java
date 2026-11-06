package ar.sanzol.sentrycam;

/** Computes {@code CaptureRequest.JPEG_ORIENTATION} from device rotation. Pure Java. */
public final class JpegOrientation {

    /** Same value as {@code OrientationEventListener.ORIENTATION_UNKNOWN}. */
    public static final int UNKNOWN = -1;

    private JpegOrientation() {
    }

    /** Rounds a raw 0..359 sensor reading to 0, 90, 180 or 270; UNKNOWN stays UNKNOWN. */
    public static int roundToQuadrant(int degrees) {
        if (degrees == UNKNOWN) {
            return UNKNOWN;
        }
        int normalized = ((degrees % 360) + 360) % 360;
        return ((normalized + 45) / 90 * 90) % 360;
    }

    /**
     * @param sensorOrientation  {@code CameraCharacteristics.SENSOR_ORIENTATION}
     * @param deviceQuadrant     0, 90, 180 or 270 (device rotated clockwise from natural)
     * @param frontFacing        true for the front camera
     */
    public static int compute(int sensorOrientation, int deviceQuadrant, boolean frontFacing) {
        int device = deviceQuadrant == UNKNOWN ? 0 : deviceQuadrant;
        if (frontFacing) {
            device = -device;
        }
        return ((sensorOrientation + device) % 360 + 360) % 360;
    }
}
