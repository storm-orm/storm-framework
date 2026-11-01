package st.orm.tck;


import java.util.ArrayList;
import java.util.List;

/**
 * Reads the advice a log entry carries: each piece of advice starts on a line of its own after {@code advice:}, and
 * the lines that continue it, its remedies and its link, are indented one level deeper.
 */
final class LoggedAdvice {

    private static final String PREFIX = "\tadvice: ";

    private LoggedAdvice() {
    }

    /**
     * Returns the advice of a log entry, each with its lines joined by {@code \n}.
     *
     * @param message the formatted log entry.
     * @return the advice, in order.
     */
    static List<String> in(String message) {
        var advice = new ArrayList<String>();
        StringBuilder current = null;
        for (var line : message.split("\\R")) {
            if (line.startsWith(PREFIX)) {
                if (current != null) {
                    advice.add(current.toString());
                }
                current = new StringBuilder(line.substring(PREFIX.length()));
            } else if (current != null && line.startsWith("\t\t")) {
                current.append('\n').append(line.substring(2));
            } else if (current != null) {
                advice.add(current.toString());
                current = null;
            }
        }
        if (current != null) {
            advice.add(current.toString());
        }
        return advice;
    }

    /**
     * Returns whether the line is part of an advice block: its first line or one that continues it.
     *
     * @param line a line of a log entry.
     * @return {@code true} for an advice line.
     */
    static boolean isAdviceLine(String line) {
        return line.startsWith(PREFIX) || line.startsWith("\t\t");
    }
}
