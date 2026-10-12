package io.github.naveen11.placemap.api.places;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A place's regular week of opening times as minute ranges since Monday 00:00, Boston time, read from the
 * {@code open_week} multirange the importer stores.
 */
public record OpenWeek(List<int[]> ranges) {

    private static final Pattern RANGE = Pattern.compile("\\[(\\d+),(\\d+)\\)");

    /** "{[480,1080),[1920,2520)}" → the ranges; null for null (hours unknown). */
    public static OpenWeek parse(String multirange) {
        if (multirange == null) {
            return null;
        }
        var ranges = new ArrayList<int[]>();
        Matcher m = RANGE.matcher(multirange);
        while (m.find()) {
            ranges.add(new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        }
        return new OpenWeek(List.copyOf(ranges));
    }

    public boolean openAt(int minuteOfWeek) {
        for (int[] range : ranges) {
            if (minuteOfWeek >= range[0] && minuteOfWeek < range[1]) {
                return true;
            }
        }
        return false;
    }
}
