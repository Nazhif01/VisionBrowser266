package dev.nazhif.visionbrowser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Original VisionBrowser timed-lyrics model and parser.
 *
 * Supports standard LRC timestamps, multiple timestamps on one line,
 * LRC offset metadata, and enhanced LRC word timestamps. This class is
 * intentionally independent from third-party lyrics implementations.
 */
final class LyricsEngine {
    private static final Pattern LINE_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TIME = Pattern.compile("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

    record Word(double start, double end, String text) { }
    record Line(double start, double end, String text, List<Word> words) { }
    record Track(List<Line> lines, double offsetSeconds) {
        Track {
            lines = List.copyOf(lines);
        }

        int indexAt(double seconds) {
            int low = 0, high = lines.size() - 1, result = 0;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (lines.get(mid).start() <= seconds) {
                    result = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return result;
        }

        int wordAt(int lineIndex, double seconds) {
            if (lineIndex < 0 || lineIndex >= lines.size()) return -1;
            List<Word> words = lines.get(lineIndex).words();
            int active = -1;
            for (int i = 0; i < words.size(); i++) {
                if (seconds >= words.get(i).start()) active = i;
                else break;
            }
            return active;
        }
    }

    private record RawLine(double start, String body) { }
    private record RawWord(double start, String text) { }

    private LyricsEngine() { }

    static Track parse(String source) {
        if (source == null || source.isBlank()) return new Track(List.of(), 0);

        double offset = 0;
        Matcher offsetMatcher = OFFSET.matcher(source);
        if (offsetMatcher.find()) {
            try { offset = Integer.parseInt(offsetMatcher.group(1)) / 1000.0; }
            catch (NumberFormatException ignored) { }
        }

        List<RawLine> rawLines = new ArrayList<>();
        for (String raw : source.split("\\R")) {
            Matcher times = LINE_TIME.matcher(raw);
            List<Double> stamps = new ArrayList<>();
            int bodyStart = 0;
            while (times.find()) {
                stamps.add(timestamp(times.group(1), times.group(2), times.group(3)));
                bodyStart = times.end();
            }
            if (stamps.isEmpty()) continue;
            String body = raw.substring(bodyStart).trim();
            if (body.isEmpty()) continue;
            for (double stamp : stamps) rawLines.add(new RawLine(Math.max(0, stamp + offset), body));
        }

        rawLines.sort(Comparator.comparingDouble(RawLine::start));
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < rawLines.size(); i++) {
            RawLine raw = rawLines.get(i);
            double lineEnd = i + 1 < rawLines.size()
                    ? Math.max(raw.start() + 0.12, rawLines.get(i + 1).start())
                    : raw.start() + 4.0;
            List<RawWord> timedWords = parseWords(raw.body());
            List<Word> words = new ArrayList<>();
            if (timedWords.isEmpty()) {
                String text = stripWordTags(raw.body()).trim();
                if (!text.isEmpty()) words.add(new Word(raw.start(), lineEnd, text));
            } else {
                for (int w = 0; w < timedWords.size(); w++) {
                    RawWord word = timedWords.get(w);
                    double end = w + 1 < timedWords.size()
                            ? Math.max(word.start() + 0.04, timedWords.get(w + 1).start())
                            : lineEnd;
                    words.add(new Word(Math.max(0, word.start() + offset), end, word.text()));
                }
            }
            String text = joinWords(words);
            if (text.isEmpty()) text = stripWordTags(raw.body()).trim();
            if (!text.isEmpty()) lines.add(new Line(raw.start(), lineEnd, text, List.copyOf(words)));
        }

        // Recalculate line and word ends after sorting and duplicate timestamp expansion.
        List<Line> normalized = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            double end = i + 1 < lines.size()
                    ? Math.max(line.start() + 0.12, lines.get(i + 1).start())
                    : line.end();
            List<Word> words = new ArrayList<>();
            for (int w = 0; w < line.words().size(); w++) {
                Word word = line.words().get(w);
                double wordEnd = w + 1 < line.words().size()
                        ? Math.max(word.start() + 0.04, line.words().get(w + 1).start())
                        : end;
                words.add(new Word(word.start(), Math.max(word.start() + 0.04, wordEnd), word.text()));
            }
            normalized.add(new Line(line.start(), end, line.text(), List.copyOf(words)));
        }
        return new Track(normalized, offset);
    }

    static String displayWord(List<Word> words, int index) {
        if (index < 0 || index >= words.size()) return "";
        String current = words.get(index).text().trim();
        if (current.isEmpty() || index + 1 >= words.size()) return current;
        String next = words.get(index + 1).text().trim();
        if (needsSeparator(current, next)) return current + " ";
        return current;
    }

    static String joinWords(List<Word> words) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < words.size(); i++) joined.append(displayWord(words, i));
        return joined.toString().trim();
    }

    private static boolean needsSeparator(String previous, String current) {
        if (previous.isEmpty() || current.isEmpty()) return false;
        char first = current.charAt(0);
        char last = previous.charAt(previous.length() - 1);
        if (".,!?;:%)]}»”’".indexOf(first) >= 0) return false;
        if ("([{«“‘".indexOf(last) >= 0) return false;
        return true;
    }

    static boolean hasWordTiming(String source) {
        if (source == null || source.isBlank()) return false;
        return WORD_TIME.matcher(source).find();
    }

    private static List<RawWord> parseWords(String body) {
        List<RawWord> words = new ArrayList<>();
        Matcher matcher = WORD_TIME.matcher(body);
        List<Double> times = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            String preceding = body.substring(cursor, matcher.start());
            if (!times.isEmpty()) chunks.add(preceding);
            times.add(timestamp(matcher.group(1), matcher.group(2), matcher.group(3)));
            cursor = matcher.end();
        }
        if (times.isEmpty()) return words;
        chunks.add(body.substring(cursor));
        for (int i = 0; i < times.size(); i++) {
            String chunk = chunks.get(i).replaceAll("\\s+", " ");
            if (!chunk.isEmpty()) words.add(new RawWord(times.get(i), chunk));
        }
        if (words.isEmpty()) return List.of();
        // Enhanced LRC timestamps are absolute track timestamps.
        return words;
    }

    private static String stripWordTags(String text) {
        return WORD_TIME.matcher(text).replaceAll("");
    }

    private static double timestamp(String minutes, String seconds, String fraction) {
        double millis = 0;
        if (fraction != null && !fraction.isEmpty()) {
            String normalized = fraction.length() == 1 ? fraction + "00"
                    : fraction.length() == 2 ? fraction + "0" : fraction.substring(0, 3);
            millis = Integer.parseInt(normalized) / 1000.0;
        }
        return Integer.parseInt(minutes) * 60.0 + Integer.parseInt(seconds) + millis;
    }
}
