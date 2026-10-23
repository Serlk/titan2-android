package org.titan2.rear.widget;

import android.content.Context;

import org.titan2.rear.common.RearKeys;

/** Creates a widget page by its id (RearKeys.W_*); null for an unknown id. */
public final class Widgets {
    private Widgets() {}

    public static WidgetPage create(Context c, String id) {
        switch (id) {
            case RearKeys.W_MUSIC:
                return new MusicPage(c);
            case RearKeys.W_WEATHER:
                return new WeatherPage(c);
            case RearKeys.W_STEPS:
                return new StepsPage(c);
            case RearKeys.W_COMPASS:
                return new CompassPage(c);
            case RearKeys.W_LEVEL:
                return new LevelPage(c);
            case RearKeys.W_TIMER:
                return new TimerPage(c);
            case RearKeys.W_STOPWATCH:
                return new StopwatchPage(c);
            default:
                return null;
        }
    }
}
