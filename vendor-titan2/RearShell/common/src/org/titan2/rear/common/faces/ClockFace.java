package org.titan2.rear.common.faces;

import android.graphics.Canvas;
import android.graphics.RectF;

/**
 * Clock face: draws the time in the given area. The date, battery and notifications below it
 * are drawn by ClockView (the same for all faces).
 */
public interface ClockFace {
    /** id for themes and settings */
    String id();

    /** true: redraw every second, otherwise once a minute */
    boolean needsSeconds(ClockState s);

    void draw(Canvas c, RectF area, ClockState s);
}
