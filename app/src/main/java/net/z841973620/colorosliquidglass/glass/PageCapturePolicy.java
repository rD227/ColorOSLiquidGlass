package net.z841973620.colorosliquidglass.glass;

/** A page scroll moves the existing texture; one fresh capture follows the last scroll frame. */
final class PageCapturePolicy {
    private boolean observed;
    private int x;
    private int y;

    boolean moved(int nextX, int nextY) {
        boolean moved = observed && (x != nextX || y != nextY);
        observed = true;
        x = nextX;
        y = nextY;
        return moved;
    }
}
