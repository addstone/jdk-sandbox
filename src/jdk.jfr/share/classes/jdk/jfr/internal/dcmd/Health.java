package jdk.jfr.internal.dcmd;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;

/**
 *
 * HEALTH REPORT
 *
 * Example agent that shows how event streaming can be used to gather statistics
 * of a running application.
 *
 * Usage:
 *
 * $ java -javaagent:health-report.jar[=interval=<interval>] MyApp
 *
 * where interval is how often statistics should be written to standard out.
 *
 * Example,
 *
 * $ java -javaagent:health-report.jar MyApp
 *
 * For testing purposes it is also possible to just run Main.java and it will
 * run an allocation loop
 *
 * $ java Main.java
 *
 */
public final class Health {

    private static final String TEMPLATE =
    "=================== HEALTH REPORT === $FLUSH_TIME         ====================\n" +
    "| GC: $GC_NAME            Phys. memory: $PHYSIC_MEM Alloc Rate: $ALLOC_RATE  |\n" +
    "| OC Count    : $OC_COUNT Initial Heap: $INIT_HEAP  Total Alloc: $TOT_ALLOC  |\n" +
    "| OC Pause Avg: $OC_AVG   Used Heap   : $USED_HEAP  Thread Count: $THREADS   |\n" +
    "| OC Pause Max: $OC_MAX   Commit. Heap: $COM_HEAP   Class Count : $CLASSES   |\n" +
    "| YC Count    : $YC_COUNT CPU Machine   : $MACH_CPU Safepoints: $SAFEPOINTS  |\n" +
    "| YC Pause Avg: $YC_AVG   CPU JVM User  : $USR_CPU  Max Safepoint: $MAX_SAFE |\n" +
    "| YC Pause Max: $YC_MAX   CPU JVM System: $SYS_CPU  Max Comp. Time: $MAX_COM |\n" +
    "|--- Top Allocation Methods ------------------------------- -----------------|\n" +
    "| $ALLOCACTION_TOP_FRAME                                            $AL_PE   |\n" +
    "| $ALLOCACTION_TOP_FRAME                                            $AL_PE   |\n" +
    "| $ALLOCACTION_TOP_FRAME                                            $AL_PE   |\n" +
    "| $ALLOCACTION_TOP_FRAME                                            $AL_PE   |\n" +
    "| $ALLOCACTION_TOP_FRAME                                            $AL_PE   |\n" +
    "|--- Hot Methods ------------------------------------------------------------|\n" +
    "| $EXECUTION_TOP_FRAME                                              $EX_PE   |\n" +
    "| $EXECUTION_TOP_FRAME                                              $EX_PE   |\n" +
    "| $EXECUTION_TOP_FRAME                                              $EX_PE   |\n" +
    "| $EXECUTION_TOP_FRAME                                              $EX_PE   |\n" +
    "| $EXECUTION_TOP_FRAME                                              $EX_PE   |\n" +
    "==============================================================================\n";

    public final static Field FLUSH_TIME = new Field();

    public final static Field GC_NAME = new Field();
    public final static Field OC_COUNT= new Field(Option.COUNT);
    public final static Field OC_AVG = new Field(Option.AVERAGE, Option.DURATION);
    public final static Field OC_MAX = new Field(Option.MAX, Option.DURATION);
    public final static Field YC_COUNT = new Field(Option.COUNT);
    public final static Field YC_AVG = new Field(Option.AVERAGE, Option.DURATION);
    public final static Field YC_MAX= new Field(Option.MAX, Option.DURATION);

    public final static Field PHYSIC_MEM = new Field(Option.BYTES);
    public final static Field INIT_HEAP = new Field(Option.BYTES);
    public final static Field USED_HEAP = new Field(Option.BYTES);
    public final static Field COM_HEAP = new Field(Option.BYTES);
    public final static Field MACH_CPU = new Field(Option.PERCENTAGE);
    public final static Field USR_CPU= new Field(Option.PERCENTAGE);
    public final static Field SYS_CPU = new Field(Option.PERCENTAGE);

    public final static Field ALLOC_RATE = new Field(Option.BYTES_PER_SECOND);
    public final static Field TOT_ALLOC = new Field(Option.TOTAL, Option.BYTES);
    public final static Field THREADS = new Field();
    public final static Field CLASSES = new Field();
    public final static Field SAFEPOINTS = new Field(Option.COUNT);
    public final static Field MAX_SAFE = new Field(Option.MAX, Option.DURATION);
    public final static Field MAX_COM = new Field(Option.MAX, Option.DURATION);

    public final static Field ALLOCACTION_TOP_FRAME = new Field();
    public final static Field AL_PE = new Field(Option.NORMALIZED, Option.TOTAL);

    public final static Field EXECUTION_TOP_FRAME = new Field();
    public final static Field EX_PE = new Field(Option.NORMALIZED, Option.COUNT);

    private static RecordingStream rs;

    public static void start() {
        Duration duration = Duration.ofSeconds(1);

        rs = new RecordingStream();
        // Event configuration
        rs.enable("jdk.CPULoad").withPeriod(duration);
        rs.enable("jdk.YoungGarbageCollection").withoutThreshold();
        rs.enable("jdk.OldGarbageCollection").withoutThreshold();
        rs.enable("jdk.GCHeapSummary").withPeriod(duration);
        rs.enable("jdk.PhysicalMemory").withPeriod(duration);
        rs.enable("jdk.GCConfiguration").withPeriod(duration);
        rs.enable("jdk.SafepointBegin");
        rs.enable("jdk.SafepointEnd");
        rs.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
        rs.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
        rs.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
        rs.enable("jdk.JavaThreadStatistics").withPeriod(duration);
        rs.enable("jdk.ClassLoadingStatistics").withPeriod(duration);
        rs.enable("jdk.Compilation").withoutThreshold();
        rs.enable("jdk.GCHeapConfiguration").withPeriod(duration);
        rs.enable("jdk.Flush").withoutThreshold();

        // Dispatch handlers
        rs.onEvent("jdk.CPULoad", Health::onCPULoad);
        rs.onEvent("jdk.YoungGarbageCollection", Health::onYoungColletion);
        rs.onEvent("jdk.OldGarbageCollection", Health::onOldCollection);
        rs.onEvent("jdk.GCHeapSummary", Health::onGCHeapSummary);
        rs.onEvent("jdk.PhysicalMemory", Health::onPhysicalMemory);
        rs.onEvent("jdk.GCConfiguration", Health::onGCConfiguration);
        rs.onEvent("jdk.SafepointBegin", Health::onSafepointBegin);
        rs.onEvent("jdk.SafepointEnd", Health::onSafepointEnd);
        rs.onEvent("jdk.ObjectAllocationOutsideTLAB", Health::onObjectAllocationOutsideTLAB);
        rs.onEvent("jdk.ObjectAllocationInNewTLAB", Health::onObjectAllocationInNewTLAB);
        rs.onEvent("jdk.ExecutionSample", Health::onExecutionSample);
        rs.onEvent("jdk.JavaThreadStatistics", Health::onJavaThreadStatistics);
        rs.onEvent("jdk.ClassLoadingStatistics", Health::onClassLoadingStatistics);
        rs.onEvent("jdk.Compilation", Health::onCompilation);
        rs.onEvent("jdk.GCHeapConfiguration", Health::onGCHeapConfiguration);
        rs.onEvent("jdk.Flush", Health::onFlushpoint);

        rs.onFlush(Health::printReport);
        rs.startAsync();
        System.out.println("Health started");
    }

    private static void onCPULoad(RecordedEvent event) {
        MACH_CPU.addSample(event.getDouble("machineTotal"));
        SYS_CPU.addSample(event.getDouble("jvmSystem"));
        USR_CPU.addSample(event.getDouble("jvmUser"));
    }

    private static void onYoungColletion(RecordedEvent event) {
        long nanos = event.getDuration().toNanos();
        YC_COUNT.addSample(nanos);
        YC_MAX.addSample(nanos);
        YC_AVG.addSample(nanos);
    }

    private static void onOldCollection(RecordedEvent event) {
        long nanos = event.getDuration().toNanos();
        OC_COUNT.addSample(nanos);
        OC_MAX.addSample(nanos);
        OC_AVG.addSample(nanos);
    }

    private static void onGCHeapSummary(RecordedEvent event) {
        USED_HEAP.addSample(event.getLong("heapUsed"));
        COM_HEAP.addSample(event.getLong("heapSpace.committedSize"));
    }

    private static void onPhysicalMemory(RecordedEvent event) {
        PHYSIC_MEM.addSample(event.getLong("totalSize"));
    }

    private static void onCompilation(RecordedEvent event) {
        MAX_COM.addSample(event.getDuration().toNanos());
    }

    private static void onGCConfiguration(RecordedEvent event) {
        String gc = event.getString("oldCollector");
        String yc = event.getString("youngCollector");
        if (yc != null) {
            gc += "/" + yc;
        }
        GC_NAME.addSample(gc);
    }

    private final static Map<Long, Instant> safepointBegin = new HashMap<>();

    private static void onSafepointBegin(RecordedEvent event) {
        safepointBegin.put(event.getValue("safepointId"), event.getEndTime());
    }

    private static void onSafepointEnd(RecordedEvent event) {
        long id = event.getValue("safepointId");
        Instant begin = safepointBegin.get(id);
        if (begin != null) {
            long nanos = Duration.between(begin, event.getEndTime()).toNanos();
            safepointBegin.remove(id);
            SAFEPOINTS.addSample(nanos);
            MAX_SAFE.addSample(nanos);
        }
    }

    private static void onObjectAllocationOutsideTLAB(RecordedEvent event) {
        onAllocationSample(event, event.getLong("allocationSize"));
    }

    private static void onObjectAllocationInNewTLAB(RecordedEvent event) {
        onAllocationSample(event, event.getLong("tlabSize"));
    }

    private static double totalAllocated;
    private static long firstAllocationTime = -1;
    private static void onAllocationSample(RecordedEvent event, long size) {
        String topFrame = topFrame(event.getStackTrace());
        if (topFrame != null) {
            ALLOCACTION_TOP_FRAME.addSample(topFrame, size);
            AL_PE.addSample(topFrame, size);
        }
        TOT_ALLOC.addSample(size);
        // ALLOC_RATE.addRate(timetsamp, amount);
        long timestamp = event.getEndTime().toEpochMilli();
        totalAllocated += size;
        if (firstAllocationTime > 0) {
            long elapsedTime = timestamp - firstAllocationTime;
            if (elapsedTime > 0) {
                double rate = 1000.0 * (totalAllocated / elapsedTime);
                ALLOC_RATE.addSample(rate);
            }
        } else {
            firstAllocationTime = timestamp;
        }
    }

    private static void onExecutionSample(RecordedEvent event) {
        String topFrame = topFrame(event.getStackTrace());
        EXECUTION_TOP_FRAME.addSample(topFrame, 1);
        EX_PE.addSample(topFrame, 1);
    }

    private static void onJavaThreadStatistics(RecordedEvent event) {
        THREADS.addSample(event.getDouble("activeCount"));
    }

    private static void onClassLoadingStatistics(RecordedEvent event) {
        long diff = event.getLong("loadedClassCount") - event.getLong("unloadedClassCount");
        CLASSES.addSample(diff);
    }

    private static void onGCHeapConfiguration(RecordedEvent event) {
        INIT_HEAP.addSample(event.getLong("initialSize"));
    }

    private final static DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static void onFlushpoint(RecordedEvent event) {
        Instant i = event.getEndTime();
        LocalDateTime l = LocalDateTime.ofInstant(i, ZoneOffset.systemDefault());
        FLUSH_TIME.addSample(FORMATTER.format(l));
    }

    // # # # TEMPLATE AND SAMPLING # # #

    private enum Option {
        BYTES, PERCENTAGE, DURATION, BYTES_PER_SECOND, NORMALIZED, COUNT, AVERAGE, TOTAL, MAX
    }

    private static final class Record {
        private final Object key;
        private int count = 1;
        private double total;
        private double max;
        private Object value = null;
        public Record(Object key, String sample) {
            this.key = key;
            this.value = sample;
        }

        public Record(Object object, double sample) {
            this.key = object;
            this.value = sample;
            this.max = sample;
            this.total = sample;
        }

        public long getCount() {
            return count;
        }

        public double getAverage() {
            return total / count;
        }

        public double getMax() {
            return max;
        }

        public double getTotal() {
            return total;
        }

        public int hashCode() {
            return key == null ? 0 : key.hashCode();
        }

        public boolean equals(Object o) {
            if (o instanceof Record) {
                Record that = (Record) o;
                return Objects.equals(that.key, this.key);
            }
            return false;
        }
    }

    private static final class Field implements Iterable<Record> {
        private final HashMap<Object, Record> histogram = new HashMap<>();
        private final Option[] options;
        private double norm;

        public Field(Option... options) {
            this.options = options;
        }

        public void addSample(double sample) {
            addSample(this, sample);
        }

        public void addSample(String sample) {
            histogram.merge(this, new Record(this, sample), (a, b) -> {
                a.count++;
                a.value = sample;
                return a;
            });
        }

        public void addSample(Object key, double sample) {
            histogram.merge(key, new Record(key, sample), (a, b) -> {
                a.count++;
                a.total += sample;
                a.value = sample;
                a.max = Math.max(a.max, sample);
                return a;
            });
        }

        public boolean hasOption(Option option) {
            for (Option o : options) {
                if (o == option) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Iterator<Record> iterator() {
            List<Record> records = new ArrayList<>(histogram.values());
            Collections.sort(records, (a, b) -> Long.compare(b.getCount(), a.getCount()));
            if (hasOption(Option.TOTAL)) {
                Collections.sort(records, (a, b) -> Double.compare(b.getTotal(), a.getTotal()));
            }
            if (hasOption(Option.NORMALIZED)) {
                norm = 0.0;
                for (Record r : records) {
                    if (hasOption(Option.TOTAL)) {
                        norm += r.getTotal();
                    }
                    if (hasOption(Option.COUNT)) {
                        norm += r.getCount();
                    }
                }
            }
            return records.iterator();
        }

        public double getNorm() {
            return norm;
        }
    }

    private static void printReport() {
        try {
            StringBuilder template = new StringBuilder(TEMPLATE);
            for (java.lang.reflect.Field f : Health.class.getDeclaredFields()) {
                String variable = "$" + f.getName();
                if (f.getType() == Field.class) {
                    writeParam(template, variable, (Field) f.get(null));
                }
            }
            System.out.println(template.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writeParam(StringBuilder template, String variable, Field param) {
        Iterator<Record> it = param.iterator();
        int lastIndex = 0;
        while (true) {
            int index = template.indexOf(variable, lastIndex);
            if (index == -1) {
                return;
            }
            lastIndex = index + 1;
            Record record = it.hasNext() ? it.next() : null;
            Object value = null;
            if (record != null) {
                value = (record.key == param) ? record.value : record.key;
            }
            if (value != null) {
                if (param.hasOption(Option.MAX)) {
                    value = record.getMax();
                }
                if (param.hasOption(Option.COUNT)) {
                    value = record.getCount();
                }
                if (param.hasOption(Option.AVERAGE)) {
                    value = record.getAverage();
                }
                if (param.hasOption(Option.TOTAL)) {
                    value = record.getTotal();
                }
            }
            if (param.hasOption(Option.COUNT)) {
                value = value == null ? 0 : value;
            }
            if (param.hasOption(Option.BYTES)) {
                value = formatBytes((Number) value);
            }
            if (param.hasOption(Option.DURATION)) {
                value = formatDuration((Number) value);
            }
            if (param.hasOption(Option.BYTES_PER_SECOND)) {
                if (value != null) {
                    value = formatBytes((Number) value) + "/s";
                }
            }
            if (param.hasOption(Option.NORMALIZED)) {
                if (value != null) {
                    double d = ((Number) value).doubleValue() / param.getNorm();
                    value = formatPercentage(d);
                }
            }
            if (param.hasOption(Option.PERCENTAGE)) {
                value = formatPercentage((Number) value);
            }
            String text;
            if (value == null) {
                text = record == null ? "" : "N/A";
            } else {
                text = String.valueOf(value);
            }
            int length = Math.max(text.length(), variable.length());
            for (int i = 0; i < length; i++) {
                char c = i < text.length() ? text.charAt(i) : ' ';
                template.setCharAt(index + i, c);
            }
        }
    }

    // # # # FORMATTING # # #

    enum TimespanUnit {
        NANOSECONDS("ns", 1000), MICROSECONDS("us", 1000), MILLISECONDS("ms", 1000), SECONDS("s", 60), MINUTES("m", 60),
        HOURS("h", 24), DAYS("d", 7);

        final String text;
        final long amount;

        TimespanUnit(String unit, long amount) {
            this.text = unit;
            this.amount = amount;
        }
    }

    private static String formatDuration(Number value) {
        if (value == null) {
            return "N/A";
        }
        double t = value.doubleValue();
        TimespanUnit result = TimespanUnit.NANOSECONDS;
        for (TimespanUnit unit : TimespanUnit.values()) {
            result = unit;
            if (t < 1000) {
                break;
            }
            t = t / unit.amount;
        }
        return String.format("%.1f %s", t, result.text);
    }

    private static String formatPercentage(Number value) {
        if (value == null) {
            return "N/A";
        }
        return String.format("%6.2f %%", value.doubleValue() * 100);
    }

    private static String formatBytes(Number value) {
        if (value == null) {
            return "N/A";
        }
        long bytes = value.longValue();
        if (bytes >= 1024 * 1024l) {
            return bytes / (1024 * 1024L) + " MB";
        }
        if (bytes >= 1024) {
            return bytes / 1024 + " kB";
        }
        return bytes + " bytes";
    }

    private static String topFrame(RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        List<RecordedFrame> frames = stackTrace.getFrames();
        if (!frames.isEmpty()) {
            RecordedFrame topFrame = frames.get(0);
            if (topFrame.isJavaFrame()) {
                return formatMethod(topFrame.getMethod());
            }
        }
        return null;
    }

    private static String formatMethod(RecordedMethod m) {
        StringBuilder sb = new StringBuilder();
        String typeName = m.getType().getName();
        typeName = typeName.substring(typeName.lastIndexOf('.') + 1);
        sb.append(typeName).append(".").append(m.getName());
        sb.append("(");
        StringJoiner sj = new StringJoiner(", ");
        String md = m.getDescriptor().replace("/", ".");
        String parameter = md.substring(1, md.lastIndexOf(")"));
        for (String qualifiedName : decodeDescriptors(parameter)) {
            sj.add(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        }
        sb.append(sj.length() > 10 ? "..." : sj);
        sb.append(")");
        return sb.toString();
    }

    private static List<String> decodeDescriptors(String descriptor) {
        List<String> descriptors = new ArrayList<>();
        for (int index = 0; index < descriptor.length(); index++) {
            String arrayBrackets = "";
            while (descriptor.charAt(index) == '[') {
                arrayBrackets += "[]";
                index++;
            }
            char c = descriptor.charAt(index);
            String type;
            switch (c) {
            case 'L':
                int endIndex = descriptor.indexOf(';', index);
                type = descriptor.substring(index + 1, endIndex);
                index = endIndex;
                break;
            case 'I':
                type = "int";
                break;
            case 'J':
                type = "long";
                break;
            case 'Z':
                type = "boolean";
                break;
            case 'D':
                type = "double";
                break;
            case 'F':
                type = "float";
                break;
            case 'S':
                type = "short";
                break;
            case 'C':
                type = "char";
                break;
            case 'B':
                type = "byte";
                break;
            default:
                type = "<unknown-descriptor-type>";
            }
            descriptors.add(type + arrayBrackets);
        }
        return descriptors;
    }
}

