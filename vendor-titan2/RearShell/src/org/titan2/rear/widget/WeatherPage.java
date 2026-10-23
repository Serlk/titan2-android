package org.titan2.rear.widget;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.util.crdroid.OmniJawsClient;

import org.titan2.rear.R;
import org.titan2.rear.common.RearKeys;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Weather from the system weather service (OmniJaws, crDroid): the shell only reads it.
 * Blocks: city, details (feels like, humidity, wind), hourly, daily forecast.
 * A tap opens the weather app on the main screen.
 */
public class WeatherPage extends WidgetPage implements OmniJawsClient.OmniJawsObserver {
    private static final String TAG = "RearShell";
    private static final int HOURS = 5;
    private static final int DAYS = 4;

    private final OmniJawsClient mClient = OmniJawsClient.get();
    private LinearLayout mContent;
    private TextView mMessage;

    public WeatherPage(Context c) {
        super(c);
    }

    @Override
    public String id() {
        return RearKeys.W_WEATHER;
    }

    @Override
    protected void build() {
        Context c = getContext();
        mContent = new LinearLayout(c);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setGravity(Gravity.CENTER_HORIZONTAL);
        mContent.setPadding(px(12), px(8), px(12), px(18));
        addView(mContent, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        mMessage = new TextView(c);
        mStyle.text(mMessage, 13, mStyle.textDim, false);
        mMessage.setGravity(Gravity.CENTER);
        mMessage.setPadding(px(16), 0, px(16), 0);
        addView(mMessage, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        press(this, this::openWeatherApp);
    }

    @Override
    protected void onActive() {
        mClient.addObserver(getContext(), this);
        mClient.queryWeather(getContext());
    }

    @Override
    protected void onInactive() {
        mClient.removeObserver(getContext(), this);
    }

    @Override
    public void weatherUpdated() {
        post(() -> {
            mClient.queryWeather(getContext());
            onRefresh();
        });
    }

    @Override
    public void weatherError(int errorReason) {
        post(this::onRefresh);
    }

    @Override
    protected void onRefresh() {
        if (mContent == null) return;
        Context c = getContext();
        OmniJawsClient.WeatherInfo w = null;
        boolean enabled = false;
        try {
            enabled = mClient.isOmniJawsEnabled(c);
            w = enabled ? mClient.getWeatherInfo() : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "weather: " + e);
        }
        mContent.removeAllViews();
        if (w == null) {
            mContent.setVisibility(GONE);
            mMessage.setVisibility(VISIBLE);
            mMessage.setText(enabled ? R.string.widget_weather_loading
                    : R.string.widget_weather_off);
            return;
        }
        mMessage.setVisibility(GONE);
        mContent.setVisibility(VISIBLE);

        if (mCfg.bool(RearKeys.WEATHER_CITY) && !TextUtils.isEmpty(w.city)) {
            mContent.addView(text(c, w.city, 12, mStyle.textDim, false), full());
        }
        LinearLayout now = new LinearLayout(c);
        now.setGravity(Gravity.CENTER);
        ImageView icon = icon(c, w.conditionCode, 52);
        now.addView(icon);
        TextView temp = text(c, w.temp + w.tempUnits, 40, mStyle.text, true);
        temp.setPadding(px(8), 0, 0, 0);
        now.addView(temp);
        mContent.addView(now, full());
        mContent.addView(text(c, w.condition, 13, mStyle.text, false), full());

        if (mCfg.bool(RearKeys.WEATHER_DETAILS)) {
            StringBuilder sb = new StringBuilder();
            if (!Float.isNaN(w.feelsLike)) {
                sb.append(c.getString(R.string.widget_weather_feels,
                        Math.round(w.feelsLike) + w.tempUnits));
            }
            if (!TextUtils.isEmpty(w.humidity)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(c.getString(R.string.widget_weather_humidity, w.humidity));
            }
            if (!TextUtils.isEmpty(w.windSpeed)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(w.windSpeed).append(' ').append(w.windUnits);
            }
            if (sb.length() > 0) {
                TextView d = text(c, sb.toString(), 11, mStyle.textDim, false);
                d.setSingleLine(false);
                LinearLayout.LayoutParams lp = full();
                lp.topMargin = px(4);
                mContent.addView(d, lp);
            }
        }

        if (mCfg.bool(RearKeys.WEATHER_HOURLY) && w.hourlyForecasts != null
                && !w.hourlyForecasts.isEmpty()) {
            LinearLayout row = row(c);
            SimpleDateFormat hf = new SimpleDateFormat(
                    android.text.format.DateFormat.is24HourFormat(c) ? "HH:mm" : "h a",
                    Locale.getDefault());
            long now0 = System.currentTimeMillis() - 3600_000L;
            int n = 0;
            for (OmniJawsClient.HourlyForecast h : w.hourlyForecasts) {
                if (h.timestamp < now0) continue;
                row.addView(cell(c, hf.format(new Date(h.timestamp)), h.conditionCode,
                        Math.round(h.temperature) + "°"), cellLp());
                if (++n >= HOURS) break;
            }
            if (n > 0) mContent.addView(row, blockLp());
        }

        if (mCfg.bool(RearKeys.WEATHER_FORECAST) && w.forecasts != null
                && w.forecasts.size() > 1) {
            LinearLayout row = row(c);
            List<OmniJawsClient.DayForecast> days = w.forecasts;
            // the first entry is today: the current conditions already show it
            for (int i = 1; i < days.size() && i <= DAYS; i++) {
                OmniJawsClient.DayForecast d = days.get(i);
                row.addView(cell(c, dayName(d.date), d.conditionCode,
                        d.high + "° " + d.low + "°"), cellLp());
            }
            mContent.addView(row, blockLp());
        }
    }

    private static String dayName(String date) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date);
            return new SimpleDateFormat("EEE", Locale.getDefault()).format(d);
        } catch (ParseException | NullPointerException e) {
            return date != null ? date : "";
        }
    }

    private LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        row.setBackground(mStyle.cardBackground(14));
        row.setPadding(px(4), px(6), px(4), px(6));
        return row;
    }

    private LinearLayout cell(Context c, String top, int code, String bottom) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(text(c, top, 10, mStyle.textDim, false));
        col.addView(icon(c, code, 22));
        col.addView(text(c, bottom, 11, mStyle.onCard, false));
        return col;
    }

    private static LinearLayout.LayoutParams cellLp() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private LinearLayout.LayoutParams blockLp() {
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = px(8);
        return lp;
    }

    private static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        mStyle.text(t, sp, color, bold);
        t.setText(s);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private ImageView icon(Context c, int code, int sizeDp) {
        ImageView v = new ImageView(c);
        Drawable d = null;
        try {
            d = mClient.getWeatherConditionImage(c, code);
        } catch (RuntimeException e) {
            Log.w(TAG, "weather icon: " + e);
        }
        v.setImageDrawable(d);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        return v;
    }

    /** The weather app (or its settings when weather is off) on the main screen. */
    private void openWeatherApp() {
        Context c = getContext();
        Intent i;
        try {
            i = mClient.isOmniJawsEnabled(c) ? mClient.getWeatherActivityIntent(c)
                    : mClient.getSettingsIntent();
        } catch (RuntimeException e) {
            return;
        }
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i, ActivityOptions.makeBasic()
                    .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "weather app: " + e);
        }
    }
}
