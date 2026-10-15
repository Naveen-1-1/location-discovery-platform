package io.github.naveen11.placemap.ingest.facts;

import ch.poole.openinghoursparser.OpeningHoursParseException;
import ch.poole.openinghoursparser.OpeningHoursParser;
import ch.poole.openinghoursparser.Rule;
import ch.poole.openinghoursparser.RuleModifier;
import ch.poole.openinghoursparser.TimeSpan;
import ch.poole.openinghoursparser.TokenMgrError;
import ch.poole.openinghoursparser.WeekDayRange;
import java.io.StringReader;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A place's regular week of opening times, Boston local time, for the "open now" filter. Each day has its spans in
 * minutes after that day's midnight; a span may run past midnight (a bar open 17:00–02:00 is 1020–1560 on its day).
 *
 * <p>Only the regular week is kept: holidays, dated and seasonal rules are left out (owner decision), so a
 * place "closed on public holidays" shows as open on one. Hours that cannot be read at all are unknown, never
 * "closed": {@link #fromOsm} and the others return empty, and the filter then hides the place.
 */
public final class WeeklyHours {

    static final int DAY = 1440;
    static final int WEEK = 7 * DAY;

    /** A span on one day, in minutes after that day's midnight; {@code end} may exceed 1440 (overnight). */
    public record Span(int start, int end) {

        public Span {
            if (start < 0 || start >= DAY || end <= start || end > 2 * DAY) {
                throw new IllegalArgumentException("bad span " + start + "-" + end);
            }
        }
    }

    private final Map<DayOfWeek, List<Span>> days;

    private WeeklyHours(Map<DayOfWeek, List<Span>> days) {
        var copy = new EnumMap<DayOfWeek, List<Span>>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            copy.put(day, List.copyOf(days.getOrDefault(day, List.of())));
        }
        this.days = copy;
    }

    public List<Span> on(DayOfWeek day) {
        return days.get(day);
    }

    // ------------------------------------------------------------------------------------------ OpenStreetMap

    /**
     * OSM {@code opening_hours}. Rules apply in order: a rule replaces the spans of the days it names, and one joined
     * by a comma adds to them. A rule with dates, weeks, years, holidays, nth weekdays or sunrise/sunset times is left
     * out; empty if no rule is left or the text does not parse.
     */
    public static Optional<WeeklyHours> fromOsm(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<Rule> rules;
        try {
            rules = new OpeningHoursParser(new StringReader(text)).rules(false);
        } catch (OpeningHoursParseException | TokenMgrError | RuntimeException e) {
            return Optional.empty(); // the generated lexer reports bad characters as an Error, not an exception
        }
        var week = new EnumMap<DayOfWeek, List<Span>>(DayOfWeek.class);
        boolean applied = false;
        for (Rule rule : rules) {
            if (!regularWeek(rule)) {
                continue;
            }
            Optional<List<Span>> spans = spans(rule);
            if (spans.isEmpty()) {
                continue;
            }
            for (DayOfWeek day : days(rule.getDays())) {
                if (rule.isAdditive()) {
                    week.computeIfAbsent(day, d -> new ArrayList<>()).addAll(spans.get());
                } else {
                    week.put(day, new ArrayList<>(spans.get()));
                }
            }
            applied = true;
        }
        return applied ? Optional.of(new WeeklyHours(week)) : Optional.empty();
    }

    private static boolean regularWeek(Rule rule) {
        if (rule.isFallBack() || notEmpty(rule.getYears()) || notEmpty(rule.getWeeks()) || notEmpty(rule.getDates())
                || notEmpty(rule.getHolidays())) {
            return false;
        }
        if (rule.getDays() != null) {
            for (WeekDayRange range : rule.getDays()) {
                if (notEmpty(range.getNths()) || range.getOffset() != 0) {
                    return false;
                }
            }
        }
        RuleModifier modifier = rule.getModifier();
        return modifier == null || modifier.getModifier() != RuleModifier.Modifier.UNKNOWN;
    }

    /** The rule's spans for each day it names; empty if a time cannot be read (sunrise, a single instant). */
    private static Optional<List<Span>> spans(Rule rule) {
        RuleModifier modifier = rule.getModifier();
        if (modifier != null && (modifier.getModifier() == RuleModifier.Modifier.OFF
                || modifier.getModifier() == RuleModifier.Modifier.CLOSED)) {
            return Optional.of(List.of());
        }
        if (rule.isTwentyfourseven() || rule.getTimes() == null || rule.getTimes().isEmpty()) {
            return Optional.of(List.of(new Span(0, DAY))); // "Mo-Fr" without times means the whole day
        }
        var spans = new ArrayList<Span>();
        for (TimeSpan time : rule.getTimes()) {
            if (time.getStartEvent() != null || time.getEndEvent() != null || time.getStart() == TimeSpan.UNDEFINED_TIME) {
                return Optional.empty();
            }
            int start = time.getStart();
            int end = time.getEnd();
            if (end == TimeSpan.UNDEFINED_TIME) {
                if (!time.isOpenEnded()) {
                    return Optional.empty(); // a single instant, "10:00"
                }
                end = Math.max(DAY, start + 60); // "17:00+": at least to midnight
            }
            if (end <= start) {
                end += DAY;
            }
            if (start >= DAY) {
                return Optional.empty();
            }
            spans.add(new Span(start, Math.min(end, 2 * DAY)));
        }
        return Optional.of(spans);
    }

    private static List<DayOfWeek> days(List<WeekDayRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return List.of(DayOfWeek.values());
        }
        var days = new ArrayList<DayOfWeek>();
        for (WeekDayRange range : ranges) {
            int from = range.getStartDay().ordinal(); // MO = 0, as DayOfWeek's ordinal
            int to = range.getEndDay() == null ? from : range.getEndDay().ordinal();
            for (int d = from; ; d = (d + 1) % 7) { // Fr-Mo wraps over the weekend
                days.add(DayOfWeek.values()[d]);
                if (d == to) {
                    break;
                }
            }
        }
        return days;
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    // ------------------------------------------------------------------------------ licences and permits

    private static final Pattern CLOCK = Pattern.compile(
            "^\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)?\\s*$", Pattern.CASE_INSENSITIVE);

    /** Boston's licensed hours: one opening and one closing time, the same every day ("10:00 AM", "MIDNIGHT"). */
    public static Optional<WeeklyHours> daily(String opening, String closing) {
        Optional<Integer> start = clock(opening, null);
        Optional<Integer> end = clock(closing, null);
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        int from = start.get() % DAY;
        int to = end.get() <= from ? end.get() + DAY : end.get();
        var week = new EnumMap<DayOfWeek, List<Span>>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            week.put(day, List.of(new Span(from, to)));
        }
        return Optional.of(new WeeklyHours(week));
    }

    /**
     * Cambridge's permit hours, written by hand per weekday: "4:00 PM - 10:00 PM", "3pm-10pm", "4-9:00 PM",
     * "7:00 p.m. - 10:00 a.m", "Closed". A time without am/pm takes the other one's, as people write it. If any day
     * cannot be read, the whole week is unknown rather than partly closed.
     */
    public static Optional<WeeklyHours> perDay(Map<DayOfWeek, String> written) {
        var week = new EnumMap<DayOfWeek, List<Span>>(DayOfWeek.class);
        boolean any = false;
        for (DayOfWeek day : DayOfWeek.values()) {
            String text = written.get(day);
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            String clean = text.strip().toLowerCase(Locale.ROOT);
            if (clean.equals("closed") || clean.equals("close")) {
                week.put(day, List.of());
                continue;
            }
            String[] parts = clean.split("\\s*(?:-|–|—|\\bto\\b)\\s*");
            if (parts.length != 2) {
                return Optional.empty();
            }
            Optional<Span> span = span(parts[0], parts[1]);
            if (span.isEmpty()) {
                return Optional.empty();
            }
            week.put(day, List.of(span.get()));
            any = true;
        }
        return any ? Optional.of(new WeeklyHours(week)) : Optional.empty();
    }

    private static Optional<Span> span(String startText, String endText) {
        Matcher start = CLOCK.matcher(startText);
        Matcher end = CLOCK.matcher(endText);
        if (!start.matches() || !end.matches()) {
            return Optional.empty();
        }
        String startHalf = half(start.group(3));
        String endHalf = half(end.group(3));
        if (startHalf == null && endHalf == null) {
            return Optional.empty();
        }
        int from;
        int to;
        if (startHalf == null) {
            to = minutes(end, endHalf);
            int same = minutes(start, endHalf);
            from = same <= to ? same : minutes(start, endHalf.equals("am") ? "pm" : "am"); // "11-2pm" is 11 am
        } else if (endHalf == null) {
            from = minutes(start, startHalf);
            int same = minutes(end, startHalf);
            to = same > from ? same : minutes(end, startHalf.equals("am") ? "pm" : "am");
        } else {
            from = minutes(start, startHalf);
            to = minutes(end, endHalf);
        }
        from %= DAY;
        if (to <= from) {
            to += DAY;
        }
        return Optional.of(new Span(from, to));
    }

    /** "10:00 AM", "1:00 am", "MIDNIGHT", "NOON" → minutes after midnight (midnight as a closing time is 1440). */
    static Optional<Integer> clock(String text, String defaultHalf) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String clean = text.strip().toLowerCase(Locale.ROOT);
        if (clean.equals("midnight")) {
            return Optional.of(DAY);
        }
        if (clean.equals("noon")) {
            return Optional.of(720);
        }
        Matcher m = CLOCK.matcher(clean);
        if (!m.matches()) {
            return Optional.empty();
        }
        String half = Optional.ofNullable(half(m.group(3))).orElse(defaultHalf);
        return half == null ? Optional.empty() : Optional.of(minutes(m, half));
    }

    private static String half(String meridiem) {
        return meridiem == null ? null : meridiem.startsWith("a") ? "am" : "pm";
    }

    private static int minutes(Matcher clock, String half) {
        int hour = Integer.parseInt(clock.group(1)) % 12;
        int minute = clock.group(2) == null ? 0 : Integer.parseInt(clock.group(2));
        return (half.equals("pm") ? hour + 12 : hour) * 60 + minute;
    }

    // ------------------------------------------------------------------------------------------ output

    /**
     * The week as a Postgres {@code int4multirange} literal of minutes since Monday 00:00; a span past Sunday midnight
     * continues on Monday morning. Postgres sorts the ranges and merges overlapping and adjacent ones as it reads them.
     */
    public String multirange() {
        var ranges = new ArrayList<String>();
        for (DayOfWeek day : DayOfWeek.values()) {
            int base = day.ordinal() * DAY;
            for (Span span : days.get(day)) {
                int from = base + span.start();
                int to = base + span.end();
                if (to <= WEEK) {
                    ranges.add("[" + from + "," + to + ")");
                } else {
                    ranges.add("[" + from + "," + WEEK + ")");
                    ranges.add("[0," + (to - WEEK) + ")");
                }
            }
        }
        return ranges.stream().collect(Collectors.joining(",", "{", "}"));
    }

    /** "Mon–Fri 07:00–23:00; Sat 09:00–14:00; Sun closed": consecutive days with the same spans grouped. */
    public String describe() {
        String previous = null;
        var lines = new ArrayList<String>();
        DayOfWeek groupStart = null;
        DayOfWeek groupEnd = null;
        for (DayOfWeek day : DayOfWeek.values()) {
            String spans = days.get(day).isEmpty() ? "closed"
                    : days.get(day).stream().map(s -> clockText(s.start()) + "–" + clockText(s.end() == DAY ? -1 : s.end()))
                            .collect(Collectors.joining(", "));
            if (spans.equals(previous)) {
                groupEnd = day;
            } else {
                if (previous != null) {
                    lines.add(dayRange(groupStart, groupEnd) + " " + previous);
                }
                groupStart = day;
                groupEnd = day;
                previous = spans;
            }
        }
        lines.add(dayRange(groupStart, groupEnd) + " " + previous);
        return String.join("; ", lines);
    }

    private static String dayRange(DayOfWeek from, DayOfWeek to) {
        String a = shortName(from);
        return from == to ? a : a + "–" + shortName(to);
    }

    private static String shortName(DayOfWeek day) {
        String name = day.name();
        return name.charAt(0) + name.substring(1, 3).toLowerCase(Locale.ROOT);
    }

    /** -1 is midnight at the end of the day, shown as 24:00. */
    private static String clockText(int minutes) {
        if (minutes < 0) {
            return "24:00";
        }
        int m = minutes % DAY;
        return "%02d:%02d".formatted(m / 60, m % 60);
    }
}
