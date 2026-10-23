package sgm.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Tiny logger: prints to a pluggable sink and keeps the last lines for the manager UI. */
public final class Log {
    private Log() {
    }

    public interface Sink {
        void write(char level, String line);
    }

    public static volatile boolean verbose;
    public static volatile Sink sink = new Sink() {
        @Override
        public void write(char level, String line) {
            System.err.println(line);
        }
    };

    private static final int KEEP = 500;
    private static final LinkedList<String> recent = new LinkedList<String>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static void d(String msg) {
        if (verbose) {
            log('D', msg);
        }
    }

    public static void i(String msg) {
        log('I', msg);
    }

    public static void w(String msg) {
        log('W', msg);
    }

    public static void e(String msg, Throwable t) {
        if (t != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            msg = msg + "\n" + sw;
        }
        log('E', msg);
    }

    private static void log(char level, String msg) {
        String line;
        synchronized (recent) {
            line = TIME.format(new Date()) + " " + level + " " + msg;
            recent.add(line);
            while (recent.size() > KEEP) {
                recent.removeFirst();
            }
        }
        Sink s = sink;
        if (s != null) {
            s.write(level, line);
        }
    }

    public static List<String> recent() {
        synchronized (recent) {
            return new ArrayList<String>(recent);
        }
    }
}
