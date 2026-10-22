package org.titan2.rear.common.faces;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Time in words to the minute, in Russian or English
 * ("nine twenty-one"). Language is the language parameter (system | ru | en; system: ru -> Russian,
 * otherwise English), letter case is case (lower | upper | title). Hours use clock.hours, minutes
 * clock.minutes (accent by default).
 */
public class WordFace implements ClockFace {
    private static final String[] RU = {
            "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
            "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    };
    private static final String[] RU_TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят"};
    private static final String[] EN = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen",
    };
    private static final String[] EN_TENS = {"", "", "twenty", "thirty", "forty", "fifty"};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public String id() {
        return "words";
    }

    @Override
    public boolean needsSeconds(ClockState s) {
        return false;
    }

    @Override
    public void draw(Canvas c, RectF a, ClockState s) {
        String lang = s.params.optString("language", "system");
        boolean ru = "system".equals(lang) ? "ru".equals(s.locale.getLanguage())
                : "ru".equals(lang);
        String hours, minutes;
        int h = s.hour(), m = s.minute();
        if (ru) {
            hours = ruNumber(h, false) + " " + plural(h, "час", "часа", "часов");
            minutes = m == 0 ? "ровно" : ruNumber(m, true) + " " + plural(m, "минута", "минуты", "минут");
        } else {
            hours = enNumber(h);
            minutes = m == 0 ? "o'clock" : (m < 10 ? "oh " : "") + enNumber(m);
        }
        String cs = s.params.optString("case", "lower");
        if ("upper".equals(cs)) {
            hours = hours.toUpperCase(s.locale);
            minutes = minutes.toUpperCase(s.locale);
        } else if ("title".equals(cs)) {
            hours = title(hours);
            minutes = title(minutes);
        }
        List<String> hl = split(hours), ml = split(minutes);
        List<String> all = new ArrayList<>(hl);
        all.addAll(ml);

        s.applyDigits(mPaint, 100f);
        // size: the longest line by width, all lines by height
        String longest = "";
        for (String l : all) {
            if (l.length() > longest.length()) longest = l;
        }
        mPaint.setTextSize(100f);
        float w = Math.max(1, mPaint.measureText(longest));
        Paint.FontMetrics fm = mPaint.getFontMetrics();
        float lh = fm.descent - fm.ascent;
        float k = Math.min(a.width() * 0.92f / w, a.height() * 0.95f / (lh * all.size()));
        mPaint.setTextSize(100f * k);
        fm = mPaint.getFontMetrics();
        lh = fm.descent - fm.ascent;
        float y = a.centerY() - lh * all.size() / 2f - fm.ascent;
        for (int i = 0; i < all.size(); i++) {
            mPaint.setColor(i < hl.size() ? s.color("clock.hours", s.theme.onSurface)
                    : s.color("clock.minutes", s.theme.accent));
            c.drawText(all.get(i), a.left + a.width() * 0.04f, y, mPaint);
            y += lh;
        }
    }

    private static String title(String t) {
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** One or two words per line: "twenty-one" / "minutes". */
    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > 12) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static String ruNumber(int n, boolean feminine) {
        if (n < 20) {
            if (feminine && n == 1) return "одна";
            if (feminine && n == 2) return "две";
            return RU[n];
        }
        String t = RU_TENS[n / 10];
        int u = n % 10;
        return u == 0 ? t : t + " " + ruNumber(u, feminine);
    }

    static String plural(int n, String one, String few, String many) {
        int n10 = n % 10, n100 = n % 100;
        if (n10 == 1 && n100 != 11) return one;
        if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return few;
        return many;
    }

    static String enNumber(int n) {
        if (n < 20) return EN[n];
        int u = n % 10;
        return EN_TENS[n / 10] + (u == 0 ? "" : "-" + EN[u]);
    }
}
