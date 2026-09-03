import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class App {
    private static int PORT = 5000;
    private static final String DB_URL = "jdbc:sqlite:smart_attendance.db";
    private static boolean useSqlite = false;

    // Fallback JSON-file database implementation for zero-dependency execution
    private static final String FALLBACK_FILE = "smart_attendance_data.json";

    // ================================================================
    //  DYNAMIC DRIVER LOADING & DATABASE SETUP
    // ================================================================
    static class DriverShim implements Driver {
        private final Driver driver;
        DriverShim(Driver d) { this.driver = d; }
        @Override public boolean acceptsURL(String u) throws SQLException { return driver.acceptsURL(u); }
        @Override public Connection connect(String u, Properties p) throws SQLException { return driver.connect(u, p); }
        @Override public int getMajorVersion() { return driver.getMajorVersion(); }
        @Override public int getMinorVersion() { return driver.getMinorVersion(); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException { return driver.getPropertyInfo(u, p); }
        @Override public boolean jdbcCompliant() { return driver.jdbcCompliant(); }
        @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException { return driver.getParentLogger(); }
    }

    static {
        loadSqliteDriver();
    }

    private static synchronized void loadSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
            useSqlite = true;
            System.out.println("✅ SQLite JDBC Driver found on system classpath.");
            return;
        } catch (Throwable ignored) {}

        // Dynamically load sqlite-jdbc.jar, slf4j-api.jar, slf4j-nop.jar if present in directory
        try {
            List<URL> urls = new ArrayList<>();
            String[] jarNames = {"sqlite-jdbc.jar", "slf4j-api.jar", "slf4j-nop.jar"};
            File currentDir = new File(".").getAbsoluteFile();

            for (String jName : jarNames) {
                File f = new File(currentDir, jName);
                if (!f.exists()) {
                    try {
                        File codeSourceDir = new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
                        f = new File(codeSourceDir, jName);
                    } catch (Exception ignored) {}
                }
                if (f.exists()) {
                    urls.add(f.toURI().toURL());
                }
            }

            if (!urls.isEmpty()) {
                URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), App.class.getClassLoader());
                Driver driver = (Driver) Class.forName("org.sqlite.JDBC", true, classLoader).getDeclaredConstructor().newInstance();
                DriverManager.registerDriver(new DriverShim(driver));
                useSqlite = true;
                System.out.println("✅ SQLite JDBC Driver dynamically loaded from local JAR files.");
                return;
            }
        } catch (Throwable t) {
            System.err.println("⚠️ Could not dynamically load SQLite JDBC driver: " + t.getMessage());
        }
        System.out.println("ℹ️ SQLite JDBC driver unavailable. Using built-in JSON file storage fallback.");
    }

    public static Connection getDb() throws SQLException {
        if (!useSqlite) {
            throw new SQLException("SQLite driver not active");
        }
        return DriverManager.getConnection(DB_URL);
    }

    public static void initDatabase() {
        if (useSqlite) {
            try (Connection conn = getDb(); Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS students (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        dob TEXT NOT NULL,
                        student_class TEXT NOT NULL,
                        roll_no TEXT NOT NULL UNIQUE,
                        section TEXT NOT NULL,
                        parent_phone TEXT NOT NULL,
                        alternate_phone TEXT NOT NULL,
                        barcode TEXT NOT NULL UNIQUE
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS attendance (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        student_id INTEGER NOT NULL,
                        attendance_date TEXT NOT NULL,
                        attendance_time TEXT NOT NULL,
                        status TEXT NOT NULL,
                        FOREIGN KEY(student_id) REFERENCES students(id)
                    )
                """);
                System.out.println("✅ SQLite Database initialized: smart_attendance.db");
                return;
            } catch (Exception e) {
                System.err.println("⚠️ SQLite Database initialization failed, switching to JSON file storage: " + e.getMessage());
                useSqlite = false;
            }
        }
        FileStorage.init();
    }

    // ================================================================
    //  FALLBACK FILE STORAGE (Zero-dependency JSON DB)
    // ================================================================
    static class FileStorage {
        private static final List<Student> students = new ArrayList<>();
        private static final List<Attendance> attendanceLogs = new ArrayList<>();
        private static final AtomicInteger studentIdSeq = new AtomicInteger(1);
        private static final AtomicInteger attendanceIdSeq = new AtomicInteger(1);

        public static synchronized void init() {
            File file = new File(FALLBACK_FILE);
            if (!file.exists()) {
                System.out.println("✅ JSON File Storage initialized: " + FALLBACK_FILE);
                return;
            }
            try {
                String content = Files.readString(Paths.get(FALLBACK_FILE), StandardCharsets.UTF_8);
                // Simple parsing for students & attendance
                students.clear();
                attendanceLogs.clear();
                int maxSid = 0;
                int maxAid = 0;

                // Load students array
                int sIdx = content.indexOf("\"students\": [");
                int aIdx = content.indexOf("\"attendance\": [");

                if (sIdx != -1) {
                    String sBlock = aIdx != -1 ? content.substring(sIdx, aIdx) : content.substring(sIdx);
                    String[] sItems = sBlock.split("\\{");
                    for (String item : sItems) {
                        if (!item.contains("\"id\"")) continue;
                        Student s = new Student();
                        s.id = parseJsonInt(item, "id");
                        s.name = parseJsonStr(item, "name");
                        s.dob = parseJsonStr(item, "dob");
                        s.studentClass = parseJsonStr(item, "studentClass");
                        s.rollNo = parseJsonStr(item, "rollNo");
                        s.section = parseJsonStr(item, "section");
                        s.parentPhone = parseJsonStr(item, "parentPhone");
                        s.alternatePhone = parseJsonStr(item, "alternatePhone");
                        s.barcode = parseJsonStr(item, "barcode");
                        students.add(s);
                        if (s.id > maxSid) maxSid = s.id;
                    }
                }

                if (aIdx != -1) {
                    String aBlock = content.substring(aIdx);
                    String[] aItems = aBlock.split("\\{");
                    for (String item : aItems) {
                        if (!item.contains("\"id\"")) continue;
                        Attendance a = new Attendance();
                        a.id = parseJsonInt(item, "id");
                        a.studentId = parseJsonInt(item, "studentId");
                        a.attendanceDate = parseJsonStr(item, "attendanceDate");
                        a.attendanceTime = parseJsonStr(item, "attendanceTime");
                        a.status = parseJsonStr(item, "status");
                        attendanceLogs.add(a);
                        if (a.id > maxAid) maxAid = a.id;
                    }
                }

                studentIdSeq.set(maxSid + 1);
                attendanceIdSeq.set(maxAid + 1);
                System.out.println("✅ Loaded " + students.size() + " students and " + attendanceLogs.size() + " attendance records from JSON.");
            } catch (Exception e) {
                System.err.println("Error reading JSON fallback file: " + e.getMessage());
            }
        }

        public static synchronized void save() {
            try {
                StringBuilder json = new StringBuilder();
                json.append("{\n  \"students\": [\n");
                for (int i = 0; i < students.size(); i++) {
                    Student s = students.get(i);
                    json.append(String.format(Locale.US,
                        "    {\"id\": %d, \"name\": \"%s\", \"dob\": \"%s\", \"studentClass\": \"%s\", \"rollNo\": \"%s\", \"section\": \"%s\", \"parentPhone\": \"%s\", \"alternatePhone\": \"%s\", \"barcode\": \"%s\"}%s\n",
                        s.id, esc(s.name), esc(s.dob), esc(s.studentClass), esc(s.rollNo), esc(s.section), esc(s.parentPhone), esc(s.alternatePhone), esc(s.barcode),
                        i < students.size() - 1 ? "," : ""));
                }
                json.append("  ],\n  \"attendance\": [\n");
                for (int i = 0; i < attendanceLogs.size(); i++) {
                    Attendance a = attendanceLogs.get(i);
                    json.append(String.format(Locale.US,
                        "    {\"id\": %d, \"studentId\": %d, \"attendanceDate\": \"%s\", \"attendanceTime\": \"%s\", \"status\": \"%s\"}%s\n",
                        a.id, a.studentId, esc(a.attendanceDate), esc(a.attendanceTime), esc(a.status),
                        i < attendanceLogs.size() - 1 ? "," : ""));
                }
                json.append("  ]\n}");
                Files.writeString(Paths.get(FALLBACK_FILE), json.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
        }

        private static int parseJsonInt(String block, String key) {
            try {
                String pattern = "\"" + key + "\":";
                int start = block.indexOf(pattern);
                if (start == -1) return 0;
                start += pattern.length();
                int end = block.indexOf(",", start);
                if (end == -1) end = block.indexOf("}", start);
                return Integer.parseInt(block.substring(start, end).trim());
            } catch (Exception e) {
                return 0;
            }
        }

        private static String parseJsonStr(String block, String key) {
            try {
                String pattern = "\"" + key + "\": \"";
                int start = block.indexOf(pattern);
                if (start == -1) return "";
                start += pattern.length();
                int end = block.indexOf("\"", start);
                return block.substring(start, end);
            } catch (Exception e) {
                return "";
            }
        }

        public static synchronized boolean addStudent(Student s) {
            for (Student existing : students) {
                if (existing.rollNo.equalsIgnoreCase(s.rollNo)) {
                    return false;
                }
            }
            s.id = studentIdSeq.getAndIncrement();
            students.add(s);
            save();
            return true;
        }

        public static synchronized List<Student> getStudents() {
            return new ArrayList<>(students);
        }

        public static synchronized Student getStudentByRollNo(String rollNo) {
            for (Student s : students) {
                if (s.rollNo.equalsIgnoreCase(rollNo)) return s;
            }
            return null;
        }

        public static synchronized Student getStudentByBarcode(String bc) {
            for (Student s : students) {
                if (s.barcode.equalsIgnoreCase(bc)) return s;
            }
            return null;
        }

        public static synchronized Student getStudentById(int id) {
            for (Student s : students) {
                if (s.id == id) return s;
            }
            return null;
        }

        public static synchronized void deleteStudent(int id) {
            students.removeIf(s -> s.id == id);
            attendanceLogs.removeIf(a -> a.studentId == id);
            save();
        }

        public static synchronized boolean markAttendance(int studentId, String date, String time, String status) {
            for (Attendance a : attendanceLogs) {
                if (a.studentId == studentId && a.attendanceDate.equals(date)) {
                    return false; // Already marked
                }
            }
            Attendance a = new Attendance();
            a.id = attendanceIdSeq.getAndIncrement();
            a.studentId = studentId;
            a.attendanceDate = date;
            a.attendanceTime = time;
            a.status = status;
            attendanceLogs.add(a);
            save();
            return true;
        }

        public static synchronized List<Attendance> getAttendanceLogs() {
            return new ArrayList<>(attendanceLogs);
        }
    }

    // ================================================================
    //  SESSION MANAGEMENT
    // ================================================================
    static class Session {
        boolean admin = false;
        Integer studentId = null;
    }
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static Session getSession(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        String sessionId = null;
        if (cookies != null) {
            for (String cHeader : cookies) {
                for (String cookie : cHeader.split(";")) {
                    String[] parts = cookie.trim().split("=", 2);
                    if (parts.length == 2 && "session_id".equals(parts[0])) {
                        sessionId = parts[1];
                        break;
                    }
                }
            }
        }
        if (sessionId != null && SESSIONS.containsKey(sessionId)) {
            return SESSIONS.get(sessionId);
        }
        String newId = UUID.randomUUID().toString();
        Session s = new Session();
        SESSIONS.put(newId, s);
        exchange.getResponseHeaders().add("Set-Cookie", "session_id=" + newId + "; Path=/; HttpOnly");
        return s;
    }

    // ================================================================
    //  BARCODE HELPERS (Code128 SVG)
    // ================================================================
    private static final String[] CODE128_PATTERNS = {
        "212222","222122","222221","121223","121322","131222","122213","122312","132212","221213",
        "221312","231212","112232","122132","122231","113222","123122","123221","223211","221132",
        "221231","213212","223112","312131","311222","321122","321221","312212","322112","322211",
        "212123","212321","232121","111323","131123","131321","112313","132113","132311","211313",
        "231113","231311","112133","112331","132131","113123","113321","133121","313121","211331",
        "231131","213113","213311","213131","311123","311321","313112","331121","312113","312311",
        "332111","314111","221411","431111","111224","111422","121124","121421","141122","141221",
        "112214","112412","122114","122411","142112","142211","241211","221114","411112","421111",
        "212114","214112","412112","111114","111411","114111","114411","411114","411411","113141",
        "114131","311141","411131","211412","211214","211412","2331112"
    };

    public static String generateBarcodeId() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rand = new Random();
        while (true) {
            StringBuilder sb = new StringBuilder("STU-");
            for (int i = 0; i < 8; i++) {
                sb.append(chars.charAt(rand.nextInt(chars.length())));
            }
            String code = sb.toString();
            if (useSqlite) {
                try (Connection conn = getDb();
                     PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM students WHERE barcode=?")) {
                    pstmt.setString(1, code);
                    ResultSet rs = pstmt.executeQuery();
                    if (!rs.next()) return code;
                } catch (SQLException e) {
                    return code;
                }
            } else {
                if (FileStorage.getStudentByBarcode(code) == null) return code;
            }
        }
    }

    public static String getBarcodeSvg(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        try {
            List<String> patterns = new ArrayList<>();
            patterns.add(CODE128_PATTERNS[104]); // Start B
            int checksum = 104;
            for (int i = 0; i < code.length(); i++) {
                char ch = code.charAt(i);
                int idx = ch - 32;
                if (idx < 0 || idx > 94) idx = 0;
                patterns.add(CODE128_PATTERNS[idx]);
                checksum += (i + 1) * idx;
            }
            checksum %= 103;
            patterns.add(CODE128_PATTERNS[checksum]);
            patterns.add(CODE128_PATTERNS[106]); // Stop

            StringBuilder barsSvg = new StringBuilder();
            double moduleWidth = 2.0;
            double height = 60.0;
            double quietZone = 20.0;
            double currentX = quietZone;

            for (String pat : patterns) {
                for (int j = 0; j < pat.length(); j++) {
                    int w = pat.charAt(j) - '0';
                    double barWidth = w * moduleWidth;
                    if (j % 2 == 0) {
                        barsSvg.append(String.format(Locale.US,
                            "<rect x=\"%.1f\" y=\"10\" width=\"%.1f\" height=\"%.1f\" fill=\"black\"/>",
                            currentX, barWidth, height));
                    }
                    currentX += barWidth;
                }
            }
            currentX += quietZone;
            double totalWidth = currentX;
            double totalHeight = height + 35.0;

            String svg = String.format(Locale.US,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.1f\" height=\"%.1f\" viewBox=\"0 0 %.1f %.1f\">" +
                "<rect width=\"100%%\" height=\"100%%\" fill=\"white\"/>" +
                "%s" +
                "<text x=\"%.1f\" y=\"%.1f\" font-family=\"monospace\" font-size=\"14\" font-weight=\"bold\" text-anchor=\"middle\" fill=\"black\">%s</text>" +
                "</svg>",
                totalWidth, totalHeight, totalWidth, totalHeight,
                barsSvg.toString(),
                totalWidth / 2.0, height + 25.0, escapeHtml(code));

            String b64 = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
            return "data:image/svg+xml;base64," + b64;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    //  DATA MODELS
    // ================================================================
    static class Student {
        int id;
        String name, dob, studentClass, rollNo, section, parentPhone, alternatePhone, barcode;
    }

    static class Attendance {
        int id, studentId;
        String attendanceDate, attendanceTime, status, name, rollNo, studentClass, section;
    }

    static class Report {
        String name, rollNo, studentClass, section;
        int totalDays, present, absent;
        double pct;
    }

    // ================================================================
    //  CSS & TEMPLATES
    // ================================================================
    private static final String CSS = """
<style>
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Outfit:wght@400;500;600;700;800&display=swap');

:root{
 --bg-deep:#0f0b1e;--bg-mid:#1a1145;
 --bg-card:rgba(255,255,255,.04);--bg-card-h:rgba(255,255,255,.08);
 --glass:rgba(255,255,255,.08);--glass-h:rgba(99,102,241,.3);
 --accent:#6366f1;--accent-l:#818cf8;--cyan:#06b6d4;
 --emerald:#10b981;--rose:#f43f5e;--amber:#f59e0b;
 --txt:#f1f5f9;--txt-d:#94a3b8;--txt-m:#64748b;
}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Inter',sans-serif;background:linear-gradient(135deg,var(--bg-deep),var(--bg-mid),#0d1b2a);color:var(--txt);min-height:100vh}
body::before{content:'';position:fixed;inset:0;background:radial-gradient(circle at 20% 50%,rgba(99,102,241,.08),transparent 50%),radial-gradient(circle at 80% 20%,rgba(6,182,212,.06),transparent 50%),radial-gradient(circle at 40% 80%,rgba(16,185,129,.05),transparent 50%);pointer-events:none;z-index:0}

/* NAVBAR */
.navbar{background:rgba(15,11,30,.88);backdrop-filter:blur(20px) saturate(180%);-webkit-backdrop-filter:blur(20px) saturate(180%);border-bottom:1px solid var(--glass);padding:0 32px;height:64px;display:flex;align-items:center;gap:6px;position:sticky;top:0;z-index:1000}
.nav-brand{display:flex;align-items:center;gap:10px;margin-right:auto;font-family:'Outfit',sans-serif;font-weight:700;font-size:20px;color:#fff;text-decoration:none}
.nav-link{color:var(--txt-d);text-decoration:none;padding:8px 14px;border-radius:8px;font-weight:500;font-size:13px;transition:.2s}
.nav-link:hover{color:#fff;background:rgba(99,102,241,.15)}

/* CONTAINER */
.container{max-width:1300px;margin:0 auto;padding:32px;position:relative;z-index:1}

/* GLASS CARD */
.glass{background:var(--bg-card);backdrop-filter:blur(16px) saturate(180%);-webkit-backdrop-filter:blur(16px) saturate(180%);border:1px solid var(--glass);border-radius:16px;padding:28px;transition:.3s cubic-bezier(.4,0,.2,1)}
.glass:hover{background:var(--bg-card-h);border-color:var(--glass-h);transform:translateY(-2px);box-shadow:0 8px 32px rgba(99,102,241,.12)}
.glass-static{background:var(--bg-card);backdrop-filter:blur(16px);border:1px solid var(--glass);border-radius:16px;padding:28px}

/* STAT CARDS */
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:20px;margin:24px 0}
.stat{background:var(--bg-card);backdrop-filter:blur(16px);border:1px solid var(--glass);border-radius:16px;padding:24px;position:relative;overflow:hidden;transition:.3s}
.stat:hover{transform:translateY(-3px);box-shadow:0 8px 30px rgba(99,102,241,.15);border-color:var(--glass-h)}
.stat::after{content:'';position:absolute;top:0;left:0;right:0;height:3px;background:linear-gradient(90deg,var(--accent),var(--cyan));opacity:0;transition:.3s}
.stat:hover::after{opacity:1}
.stat .icon{font-size:28px;margin-bottom:10px}
.stat .val{font-family:'Outfit',sans-serif;font-size:34px;font-weight:700;background:linear-gradient(135deg,var(--accent),var(--cyan));-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text}
.stat .lbl{color:var(--txt-d);font-size:13px;margin-top:4px}

/* INPUTS */
label{display:block;font-size:12px;font-weight:600;color:var(--txt-d);margin-bottom:5px;text-transform:uppercase;letter-spacing:.5px}
input,select,textarea{width:100%;padding:12px 16px;margin-bottom:14px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.1);border-radius:10px;color:var(--txt);font-size:14px;font-family:'Inter',sans-serif;transition:.3s;outline:none}
input:focus,select:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(99,102,241,.15);background:rgba(255,255,255,.08)}
input::placeholder{color:var(--txt-m)}
select option{background:#1a1145;color:var(--txt)}

/* BUTTONS */
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;padding:12px 24px;border:none;border-radius:10px;font-size:14px;font-weight:600;font-family:'Inter',sans-serif;cursor:pointer;transition:.3s cubic-bezier(.4,0,.2,1);width:100%;color:#fff;text-decoration:none}
.btn-primary{background:linear-gradient(135deg,#6366f1,#4f46e5);box-shadow:0 4px 15px rgba(99,102,241,.3)}
.btn-primary:hover{transform:translateY(-2px);box-shadow:0 6px 25px rgba(99,102,241,.4)}
.btn-success{background:linear-gradient(135deg,#10b981,#059669);box-shadow:0 4px 15px rgba(16,185,129,.3)}
.btn-success:hover{transform:translateY(-2px);box-shadow:0 6px 25px rgba(16,185,129,.4)}
.btn-danger{background:linear-gradient(135deg,#f43f5e,#e11d48);box-shadow:0 4px 15px rgba(244,63,94,.3)}
.btn-danger:hover{transform:translateY(-2px);box-shadow:0 6px 25px rgba(244,63,94,.4)}
.btn-dark{background:linear-gradient(135deg,#334155,#1e293b);box-shadow:0 4px 15px rgba(30,41,59,.3)}
.btn-dark:hover{transform:translateY(-2px);box-shadow:0 6px 25px rgba(30,41,59,.5)}
.btn-outline{background:transparent;border:1px solid var(--glass);color:var(--txt-d)}
.btn-outline:hover{background:rgba(255,255,255,.05);border-color:var(--accent);color:var(--txt)}
.btn-sm{padding:8px 16px;font-size:12px;width:auto}

/* TABLE */
.table-wrap{overflow-x:auto;border-radius:12px;border:1px solid var(--glass)}
table{width:100%;border-collapse:collapse}
th{background:rgba(99,102,241,.12);color:var(--accent-l);padding:14px 16px;text-align:left;font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.5px}
td{padding:13px 16px;border-bottom:1px solid rgba(255,255,255,.04);color:var(--txt-d);font-size:14px}
tr:hover td{background:rgba(255,255,255,.02)}

/* ALERTS */
.alert{padding:14px 20px;border-radius:12px;margin:16px 0;font-size:14px;display:flex;align-items:center;gap:10px}
.alert-info{background:rgba(99,102,241,.1);border:1px solid rgba(99,102,241,.2);color:var(--accent-l)}
.alert-success{background:rgba(16,185,129,.1);border:1px solid rgba(16,185,129,.2);color:var(--emerald)}
.alert-danger{background:rgba(244,63,94,.1);border:1px solid rgba(244,63,94,.2);color:var(--rose)}
.alert-warning{background:rgba(245,158,11,.1);border:1px solid rgba(245,158,11,.2);color:var(--amber)}

/* LOGIN */
.login-wrap{display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px;position:relative;z-index:1}
.login-card{width:440px;max-width:100%;background:var(--bg-card);backdrop-filter:blur(24px) saturate(180%);-webkit-backdrop-filter:blur(24px) saturate(180%);border:1px solid var(--glass);border-radius:20px;padding:44px;text-align:center}
.login-card .l-icon{font-size:56px;margin-bottom:8px}
.login-card h1{font-family:'Outfit',sans-serif;font-size:28px;font-weight:700;margin-bottom:4px}
.login-card .sub{color:var(--txt-m);font-size:14px;margin-bottom:20px}
.divider{border:none;height:1px;background:var(--glass);margin:20px 0}
.cred-box{background:rgba(99,102,241,.08);border:1px solid rgba(99,102,241,.15);border-radius:10px;padding:14px;margin-top:16px;text-align:left;font-size:13px;color:var(--txt-d)}
.cred-box b{color:var(--accent-l)}

/* FORM GRID */
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.fg{text-align:left}
.full{grid-column:1/-1}

/* PROGRESS */
.prog-track{width:100%;height:10px;background:rgba(255,255,255,.06);border-radius:20px;overflow:hidden;margin:8px 0}
.prog-fill{height:100%;border-radius:20px;background:linear-gradient(90deg,var(--accent),var(--cyan));transition:width 1s ease}

/* CHART */
.chart-box{position:relative;height:280px;margin:16px 0}

/* BADGE */
.badge{display:inline-block;padding:4px 12px;border-radius:20px;font-size:12px;font-weight:600}
.badge-ok{background:rgba(16,185,129,.15);color:var(--emerald)}
.badge-low{background:rgba(244,63,94,.15);color:var(--rose)}
.badge-warn{background:rgba(245,158,11,.15);color:var(--amber)}

/* BARCODE CARD */
.bc-card{background:#fff;border-radius:16px;padding:40px;text-align:center;color:#1a1145;max-width:500px;margin:0 auto}
.bc-card h2{font-family:'Outfit',sans-serif;color:#1a1145;margin-bottom:4px}
.bc-card p{color:#64748b;margin:4px 0}
.bc-card img{max-width:100%;height:auto;margin:12px 0}
.bc-text{font-family:'Courier New',monospace;font-size:16px;font-weight:bold;letter-spacing:3px;padding:10px;background:#f1f5f9;border-radius:8px;color:#1a1145;margin:8px 0;display:inline-block}

/* GRID */
.g2{display:grid;grid-template-columns:1fr 1fr;gap:20px}
.g3{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}

/* HEADER */
.ph h1{font-family:'Outfit',sans-serif;font-size:30px;font-weight:700}
.ph p{color:var(--txt-m);margin-top:4px;font-size:14px}
.ph{margin-bottom:24px}

/* FILTER BAR */
.fbar{display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap;margin-bottom:20px}
.fbar .fg{flex:1;min-width:140px}
.fbar .btn{width:auto;min-width:110px;margin-bottom:14px}

/* BACK LINK */
.back{color:var(--txt-m);text-decoration:none;font-size:14px;display:inline-flex;align-items:center;gap:6px;margin-top:16px;transition:.2s}
.back:hover{color:var(--accent-l)}

/* ANIMATION */
@keyframes fadeUp{from{opacity:0;transform:translateY(20px)}to{opacity:1;transform:translateY(0)}}
.fi{animation:fadeUp .5s ease forwards}
.fi1{animation:fadeUp .5s ease .1s forwards;opacity:0}
.fi2{animation:fadeUp .5s ease .2s forwards;opacity:0}
.fi3{animation:fadeUp .5s ease .3s forwards;opacity:0}
.fi4{animation:fadeUp .5s ease .4s forwards;opacity:0}

/* RESPONSIVE */
@media(max-width:768px){
 .navbar{padding:12px 16px;height:auto;flex-wrap:wrap}
 .nav-brand{margin-right:0;width:100%;margin-bottom:8px}
 .container{padding:16px}
 .stats{grid-template-columns:1fr 1fr}
 .form-grid,.g2,.g3{grid-template-columns:1fr}
 .full{grid-column:auto}
 .fbar{flex-direction:column}
 .fbar .btn{width:100%}
}
@media(max-width:480px){.stats{grid-template-columns:1fr}}

/* PRINT */
@media print{
 .navbar,.no-print{display:none!important}
 body{background:#fff!important;color:#000!important}
 body::before{display:none!important}
 .bc-card{box-shadow:none;border:2px solid #333}
}
</style>
""";

    private static final String HEAD = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>Smart Attendance System</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=Outfit:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
""" + CSS + """
</head>
<body>
""";

    private static final String FOOT = "</body></html>";

    private static final String ADMIN_NAV = """
<nav class="navbar">
<a href="/admin_dashboard" class="nav-brand">🎓 Smart Attendance</a>
<a href="/admin_dashboard" class="nav-link">📊 Dashboard</a>
<a href="/add_student" class="nav-link">➕ Add Student</a>
<a href="/students" class="nav-link">👥 Students</a>
<a href="/barcode_attendance" class="nav-link">📷 Scan</a>
<a href="/reports" class="nav-link">📋 Reports</a>
<a href="/logout" class="nav-link">🚪 Logout</a>
</nav>
""";

    private static final String STUDENT_NAV = """
<nav class="navbar">
<a href="/student_dashboard" class="nav-brand">🎓 Student Portal</a>
<a href="/student_dashboard" class="nav-link">📊 Dashboard</a>
<a href="/my_barcode" class="nav-link">🔖 My Barcode</a>
<a href="/logout" class="nav-link">🚪 Logout</a>
</nav>
""";

    // ================================================================
    //  HTTP HANDLERS
    // ================================================================
    static class MainHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Session session = getSession(exchange);

            try {
                if (path.equals("/")) {
                    if (session.admin) { redirect(exchange, "/admin_dashboard"); return; }
                    if (session.studentId != null) { redirect(exchange, "/student_dashboard"); return; }
                    renderHome(exchange);
                } else if (path.equals("/select_login") && method.equals("POST")) {
                    Map<String, String> form = parseFormData(exchange);
                    String role = form.getOrDefault("role", "admin");
                    if ("admin".equals(role)) redirect(exchange, "/admin_login");
                    else redirect(exchange, "/student_login");
                } else if (path.equals("/admin_login")) {
                    handleAdminLogin(exchange, method, session);
                } else if (path.equals("/student_login")) {
                    handleStudentLogin(exchange, method, session);
                } else if (path.equals("/admin_dashboard")) {
                    if (!session.admin) { redirect(exchange, "/"); return; }
                    renderAdminDashboard(exchange);
                } else if (path.equals("/add_student")) {
                    if (!session.admin) { redirect(exchange, "/"); return; }
                    handleAddStudent(exchange, method);
                } else if (path.equals("/students")) {
                    if (!session.admin) { redirect(exchange, "/"); return; }
                    renderStudentsList(exchange);
                } else if (path.startsWith("/delete_student/")) {
                    if (!session.admin) { redirect(exchange, "/"); return; }
                    handleDeleteStudent(exchange, path.substring("/delete_student/".length()));
                } else if (path.startsWith("/student_barcode/")) {
                    if (!session.admin) { redirect(exchange, "/"); return; }
                    renderStudentBarcode(exchange, path.substring("/student_barcode/".length()));
                } else if (path.equals("/barcode_attendance")) {
                    if (!session.admin) { redirect(exchange, "/"); return; }
                    handleBarcodeAttendance(exchange, method);
                } else if (path.equals("/reports")) {
                    if (!session.admin) { redirect(exchange, "/"); return; }
                    renderReports(exchange);
                } else if (path.equals("/export_csv")) {
                    if (!session.admin) { redirect(exchange, "/"); return; }
                    handleExportCsv(exchange);
                } else if (path.equals("/student_dashboard")) {
                    if (session.studentId == null) { redirect(exchange, "/"); return; }
                    renderStudentDashboard(exchange, session);
                } else if (path.equals("/my_barcode")) {
                    if (session.studentId == null) { redirect(exchange, "/"); return; }
                    renderMyBarcode(exchange, session);
                } else if (path.equals("/logout")) {
                    session.admin = false;
                    session.studentId = null;
                    redirect(exchange, "/");
                } else {
                    sendResponse(exchange, 404, "<h1>404 Not Found</h1>");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "<h1>500 Internal Server Error</h1><p>" + escapeHtml(e.getMessage()) + "</p>");
            }
        }
    }

    // --- HOME ---
    private static void renderHome(HttpExchange exchange) throws IOException {
        String html = HEAD + """
<div class="login-wrap">
<div class="login-card fi">
<div class="l-icon">🎓</div>
<h1>Smart Attendance</h1>
<p class="sub">Advanced Student Attendance Management System</p>
<hr class="divider">
<h3 style="margin-bottom:16px;color:var(--txt-d)">Select Login Type</h3>
<form action="/select_login" method="POST">
<button name="role" value="admin" class="btn btn-primary" style="margin-bottom:10px">👨‍💼 Admin Login</button>
<button name="role" value="student" class="btn btn-success">🎓 Student Login</button>
</form>
</div>
</div>
""" + FOOT;
        sendResponse(exchange, 200, html);
    }

    // --- ADMIN LOGIN ---
    private static void handleAdminLogin(HttpExchange exchange, String method, Session session) throws IOException {
        String error = null;
        if (method.equals("POST")) {
            Map<String, String> form = parseFormData(exchange);
            String username = form.getOrDefault("username", "");
            String password = form.getOrDefault("password", "");
            if ("admin".equals(username) && "admin123".equals(password)) {
                session.admin = true;
                session.studentId = null;
                redirect(exchange, "/admin_dashboard");
                return;
            }
            error = "❌ Invalid admin login ID or password.";
        }
        String errorHtml = error != null ? "<div class=\"alert alert-danger\">" + escapeHtml(error) + "</div>" : "";
        String html = HEAD + """
<div class="login-wrap">
<div class="login-card fi">
<div class="l-icon">👨‍💼</div>
<h1>Admin Login</h1>
<p class="sub">Enter your admin credentials</p>
<hr class="divider">
""" + errorHtml + """
<form method="POST" style="text-align:left">
<div class="fg">
<label for="username">Login ID</label>
<input id="username" type="text" name="username" placeholder="Enter admin login ID" autocomplete="username" required>
</div>
<div class="fg">
<label for="password">Password</label>
<input id="password" type="password" name="password" placeholder="Enter password" autocomplete="current-password" required>
</div>
<button type="submit" class="btn btn-primary">🔐 Sign In</button>
</form>

<div class="cred-box">
<b>Default Credentials</b><br><br>
Login ID: <b>admin</b><br>
Password: <b>admin123</b>
</div>

<a href="/" class="back">← Back to Home</a>
</div>
</div>
""" + FOOT;
        sendResponse(exchange, 200, html);
    }

    // --- STUDENT LOGIN ---
    private static void handleStudentLogin(HttpExchange exchange, String method, Session session) throws IOException {
        String error = null;
        if (method.equals("POST")) {
            Map<String, String> form = parseFormData(exchange);
            String rollNo = form.getOrDefault("roll_no", "").trim();
            String password = form.getOrDefault("password", "");
            if ("student123".equals(password)) {
                Student s = null;
                if (useSqlite) {
                    try (Connection conn = getDb();
                         PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM students WHERE roll_no=?")) {
                        pstmt.setString(1, rollNo);
                        ResultSet rs = pstmt.executeQuery();
                        if (rs.next()) {
                            s = new Student();
                            s.id = rs.getInt("id");
                        }
                    } catch (SQLException e) {
                        error = "❌ Database error: " + e.getMessage();
                    }
                } else {
                    s = FileStorage.getStudentByRollNo(rollNo);
                }

                if (s != null) {
                    session.admin = false;
                    session.studentId = s.id;
                    redirect(exchange, "/student_dashboard");
                    return;
                } else if (error == null) {
                    error = "❌ Roll number not found. Please check and try again.";
                }
            } else {
                error = "❌ Invalid password.";
            }
        }
        String errorHtml = error != null ? "<div class=\"alert alert-danger\">" + escapeHtml(error) + "</div>" : "";
        String html = HEAD + """
<div class="login-wrap">
<div class="login-card fi">
<div class="l-icon">🎓</div>
<h1>Student Login</h1>
<p class="sub">Login with your Roll Number</p>
<hr class="divider">
""" + errorHtml + """
<form method="POST" style="text-align:left">
<div class="fg">
<label for="roll_no">Login ID (Roll Number)</label>
<input id="roll_no" type="text" name="roll_no" placeholder="Enter your roll number" autocomplete="username" required>
</div>
<div class="fg">
<label for="password">Password</label>
<input id="password" type="password" name="password" placeholder="Enter password" autocomplete="current-password" required>
</div>
<button type="submit" class="btn btn-success">🔐 Sign In</button>
</form>

<div class="cred-box">
<b>How to Login</b><br><br>
Login ID: <b>Your Roll Number</b><br>
Password: <b>student123</b>
</div>

<a href="/" class="back">← Back to Home</a>
</div>
</div>
""" + FOOT;
        sendResponse(exchange, 200, html);
    }

    // --- ADMIN DASHBOARD ---
    private static void renderAdminDashboard(HttpExchange exchange) throws IOException {
        int totalStudents = 0, presentToday = 0, absentToday = 0;
        double todayPct = 0.0;
        String today = LocalDate.now().toString();

        List<String> trendLabels = new ArrayList<>();
        List<Integer> trendData = new ArrayList<>();
        List<String> classLabels = new ArrayList<>();
        List<Integer> classData = new ArrayList<>();
        List<Attendance> recent = new ArrayList<>();
        List<Report> lowAtt = new ArrayList<>();
        int totalDays = 0;

        if (useSqlite) {
            try (Connection conn = getDb()) {
                // Total students
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM students")) {
                    if (rs.next()) totalStudents = rs.getInt(1);
                }
                // Present today
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT COUNT(DISTINCT student_id) FROM attendance WHERE attendance_date=? AND status='Present'")) {
                    pstmt.setString(1, today);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) presentToday = rs.getInt(1);
                }
                absentToday = Math.max(totalStudents - presentToday, 0);
                todayPct = totalStudents > 0 ? Math.round((presentToday * 100.0 / totalStudents) * 10.0) / 10.0 : 0.0;

                // 7-day trend
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd");
                for (int i = 6; i >= 0; i--) {
                    LocalDate d = LocalDate.now().minusDays(i);
                    trendLabels.add(d.format(fmt));
                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "SELECT COUNT(DISTINCT student_id) FROM attendance WHERE attendance_date=? AND status='Present'")) {
                        pstmt.setString(1, d.toString());
                        ResultSet rs = pstmt.executeQuery();
                        trendData.add(rs.next() ? rs.getInt(1) : 0);
                    }
                }

                // Class distribution
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT student_class, COUNT(*) as c FROM students GROUP BY student_class")) {
                    while (rs.next()) {
                        classLabels.add(rs.getString("student_class"));
                        classData.add(rs.getInt("c"));
                    }
                }

                // Recent scans
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("""
                         SELECT s.name, s.roll_no, a.attendance_time
                         FROM attendance a JOIN students s ON a.student_id=s.id
                         ORDER BY a.id DESC LIMIT 8
                     """)) {
                    while (rs.next()) {
                        Attendance a = new Attendance();
                        a.name = rs.getString("name");
                        a.rollNo = rs.getString("roll_no");
                        a.attendanceTime = rs.getString("attendance_time");
                        recent.add(a);
                    }
                }

                // Total class days
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT attendance_date) FROM attendance")) {
                    if (rs.next()) totalDays = rs.getInt(1);
                }

                // Low attendance
                if (totalDays > 0) {
                    List<Student> allStudents = new ArrayList<>();
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT * FROM students")) {
                        while (rs.next()) {
                            Student s = new Student();
                            s.id = rs.getInt("id");
                            s.name = rs.getString("name");
                            s.rollNo = rs.getString("roll_no");
                            allStudents.add(s);
                        }
                    }
                    for (Student st : allStudents) {
                        try (PreparedStatement pstmt = conn.prepareStatement(
                                "SELECT COUNT(*) FROM attendance WHERE student_id=? AND status='Present'")) {
                            pstmt.setInt(1, st.id);
                            ResultSet rs = pstmt.executeQuery();
                            int pr = rs.next() ? rs.getInt(1) : 0;
                            double pct = Math.round((pr * 100.0 / totalDays) * 10.0) / 10.0;
                            if (pct < 75.0) {
                                Report r = new Report();
                                r.name = st.name;
                                r.rollNo = st.rollNo;
                                r.pct = pct;
                                lowAtt.add(r);
                            }
                        }
                    }
                    lowAtt.sort(Comparator.comparingDouble(a -> a.pct));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            // JSON File Storage
            List<Student> allStudents = FileStorage.getStudents();
            totalStudents = allStudents.size();
            List<Attendance> allLogs = FileStorage.getAttendanceLogs();

            Set<Integer> presentSet = new HashSet<>();
            Set<String> uniqueDates = new HashSet<>();
            Map<String, Integer> classCounts = new HashMap<>();

            for (Student s : allStudents) {
                classCounts.put(s.studentClass, classCounts.getOrDefault(s.studentClass, 0) + 1);
            }
            classLabels.addAll(classCounts.keySet());
            for (String cl : classLabels) classData.add(classCounts.get(cl));

            for (Attendance a : allLogs) {
                uniqueDates.add(a.attendanceDate);
                if (today.equals(a.attendanceDate) && "Present".equalsIgnoreCase(a.status)) {
                    presentSet.add(a.studentId);
                }
            }
            presentToday = presentSet.size();
            absentToday = Math.max(totalStudents - presentToday, 0);
            todayPct = totalStudents > 0 ? Math.round((presentToday * 100.0 / totalStudents) * 10.0) / 10.0 : 0.0;
            totalDays = uniqueDates.size();

            // 7-day trend
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd");
            for (int i = 6; i >= 0; i--) {
                LocalDate d = LocalDate.now().minusDays(i);
                trendLabels.add(d.format(fmt));
                String dStr = d.toString();
                Set<Integer> pSet = new HashSet<>();
                for (Attendance a : allLogs) {
                    if (dStr.equals(a.attendanceDate) && "Present".equalsIgnoreCase(a.status)) {
                        pSet.add(a.studentId);
                    }
                }
                trendData.add(pSet.size());
            }

            // Recent scans
            List<Attendance> sortedLogs = new ArrayList<>(allLogs);
            sortedLogs.sort((a, b) -> Integer.compare(b.id, a.id));
            for (int i = 0; i < Math.min(8, sortedLogs.size()); i++) {
                Attendance a = sortedLogs.get(i);
                Student s = FileStorage.getStudentById(a.studentId);
                if (s != null) {
                    Attendance r = new Attendance();
                    r.name = s.name;
                    r.rollNo = s.rollNo;
                    r.attendanceTime = a.attendanceTime;
                    recent.add(r);
                }
            }

            // Low attendance
            if (totalDays > 0) {
                for (Student st : allStudents) {
                    long pr = allLogs.stream().filter(a -> a.studentId == st.id && "Present".equalsIgnoreCase(a.status)).count();
                    double pct = Math.round((pr * 100.0 / totalDays) * 10.0) / 10.0;
                    if (pct < 75.0) {
                        Report r = new Report();
                        r.name = st.name;
                        r.rollNo = st.rollNo;
                        r.pct = pct;
                        lowAtt.add(r);
                    }
                }
                lowAtt.sort(Comparator.comparingDouble(a -> a.pct));
            }
        }

        StringBuilder recentRows = new StringBuilder();
        if (!recent.isEmpty()) {
            for (Attendance r : recent) {
                recentRows.append(String.format("<tr><td>%s</td><td>%s</td><td>%s</td></tr>",
                        escapeHtml(r.name), escapeHtml(r.rollNo), escapeHtml(r.attendanceTime)));
            }
        }

        StringBuilder lowRows = new StringBuilder();
        if (!lowAtt.isEmpty()) {
            for (Report s : lowAtt) {
                lowRows.append(String.format("<tr><td>%s</td><td>%s</td><td><span class=\"badge badge-low\">%.1f%%</span></td></tr>",
                        escapeHtml(s.name), escapeHtml(s.rollNo), s.pct));
            }
        }

        String recentHtml = !recent.isEmpty() ? """
<div class="table-wrap">
<table>
<tr><th>Student</th><th>Roll No</th><th>Time</th></tr>
""" + recentRows.toString() + "</table></div>" : "<p style=\"color:var(--txt-m)\">No attendance scans yet.</p>";

        String lowHtml;
        if (!lowAtt.isEmpty()) {
            lowHtml = "<div class=\"table-wrap\"><table><tr><th>Student</th><th>Roll No</th><th>%</th></tr>" + lowRows.toString() + "</table></div>";
        } else if (totalDays > 0) {
            lowHtml = "<p style=\"color:var(--txt-m)\">✅ All students have 75%+ attendance.</p>";
        } else {
            lowHtml = "<p style=\"color:var(--txt-m)\">No attendance data yet.</p>";
        }

        String html = HEAD + ADMIN_NAV + String.format(Locale.US, """
<div class="container">

<div class="ph fi"><h1>📊 Admin Dashboard</h1><p>Overview of attendance system</p></div>

<!-- STATS -->
<div class="stats">
<div class="stat fi1">
<div class="icon">👥</div>
<div class="val">%d</div>
<div class="lbl">Total Students</div>
</div>
<div class="stat fi2">
<div class="icon">✅</div>
<div class="val">%d</div>
<div class="lbl">Present Today</div>
</div>
<div class="stat fi3">
<div class="icon">❌</div>
<div class="val">%d</div>
<div class="lbl">Absent Today</div>
</div>
<div class="stat fi4">
<div class="icon">📈</div>
<div class="val">%.1f%%</div>
<div class="lbl">Today's Attendance</div>
</div>
</div>

<!-- CHARTS -->
<div class="g2" style="margin-bottom:20px">
<div class="glass-static fi3">
<h3 style="margin-bottom:4px;font-family:'Outfit',sans-serif">📈 Attendance Trend (Last 7 Days)</h3>
<p style="color:var(--txt-m);font-size:13px;margin-bottom:8px">Daily present student count</p>
<div class="chart-box"><canvas id="trendChart"></canvas></div>
</div>
<div class="glass-static fi4">
<h3 style="margin-bottom:4px;font-family:'Outfit',sans-serif">🎓 Class Distribution</h3>
<p style="color:var(--txt-m);font-size:13px;margin-bottom:8px">Students per class</p>
<div class="chart-box"><canvas id="classChart"></canvas></div>
</div>
</div>

<!-- BOTTOM ROW -->
<div class="g2">

<div class="glass-static fi3">
<h3 style="margin-bottom:12px;font-family:'Outfit',sans-serif">🕐 Recent Scans</h3>
%s
</div>

<div class="glass-static fi4">
<h3 style="margin-bottom:12px;font-family:'Outfit',sans-serif">⚠️ Low Attendance Alerts</h3>
%s
</div>

</div>

</div>

<script>
// Trend Chart
new Chart(document.getElementById('trendChart'),{
 type:'line',
 data:{labels:%s,datasets:[{
  label:'Present',data:%s,
  borderColor:'#6366f1',backgroundColor:'rgba(99,102,241,.1)',
  fill:true,tension:.4,pointBackgroundColor:'#6366f1',
  pointBorderColor:'#fff',pointBorderWidth:2,pointRadius:5
 }]},
 options:{responsive:true,maintainAspectRatio:false,
  plugins:{legend:{display:false}},
  scales:{
   x:{ticks:{color:'#94a3b8'},grid:{color:'rgba(255,255,255,.04)'}},
   y:{beginAtZero:true,ticks:{color:'#94a3b8',stepSize:1},grid:{color:'rgba(255,255,255,.04)'}}
  }
 }
});
// Class Chart
var cl=%s, cd=%s;
new Chart(document.getElementById('classChart'),{
 type:'doughnut',
 data:{labels:cl,datasets:[{data:cd,
  backgroundColor:['#6366f1','#06b6d4','#10b981','#f59e0b','#f43f5e','#8b5cf6','#14b8a6','#f97316','#ec4899','#3b82f6'],
  borderWidth:0,hoverOffset:8}]},
 options:{responsive:true,maintainAspectRatio:false,
  plugins:{legend:{position:'bottom',labels:{color:'#94a3b8',padding:12,font:{size:12}}}}
 }
});
</script>
""", totalStudents, presentToday, absentToday, todayPct,
recentHtml, lowHtml,
toJsonArray(trendLabels), toJsonArray(trendData),
toJsonArray(classLabels), toJsonArray(classData)) + FOOT;

        sendResponse(exchange, 200, html);
    }

    // --- ADD STUDENT ---
    private static void handleAddStudent(HttpExchange exchange, String method) throws IOException {
        String error = null;
        if (method.equals("POST")) {
            Map<String, String> form = parseFormData(exchange);
            String name = form.getOrDefault("name", "").trim();
            String dob = form.getOrDefault("dob", "");
            String studentClass = form.getOrDefault("student_class", "").trim();
            String rollNo = form.getOrDefault("roll_no", "").trim();
            String section = form.getOrDefault("section", "");
            String parentPhone = form.getOrDefault("parent_phone", "").trim();
            String alternatePhone = form.getOrDefault("alternate_phone", "").trim();
            String bc = generateBarcodeId();

            if (name.isEmpty() || rollNo.isEmpty()) {
                error = "❌ Please fill in all required student details.";
            } else if (useSqlite) {
                try (Connection conn = getDb();
                     PreparedStatement pstmt = conn.prepareStatement("""
                         INSERT INTO students
                         (name,dob,student_class,roll_no,section,parent_phone,alternate_phone,barcode)
                         VALUES (?,?,?,?,?,?,?,?)
                     """)) {
                    pstmt.setString(1, name);
                    pstmt.setString(2, dob);
                    pstmt.setString(3, studentClass);
                    pstmt.setString(4, rollNo);
                    pstmt.setString(5, section);
                    pstmt.setString(6, parentPhone);
                    pstmt.setString(7, alternatePhone);
                    pstmt.setString(8, bc);
                    pstmt.executeUpdate();
                    redirect(exchange, "/student_barcode/" + rollNo);
                    return;
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    if (msg.contains("UNIQUE") || msg.contains("roll_no")) {
                        error = "❌ Roll number '" + escapeHtml(rollNo) + "' already exists. Please use a unique roll number.";
                    } else {
                        error = "❌ Database error: " + escapeHtml(msg);
                    }
                }
            } else {
                Student s = new Student();
                s.name = name;
                s.dob = dob;
                s.studentClass = studentClass;
                s.rollNo = rollNo;
                s.section = section;
                s.parentPhone = parentPhone;
                s.alternatePhone = alternatePhone;
                s.barcode = bc;
                boolean ok = FileStorage.addStudent(s);
                if (ok) {
                    redirect(exchange, "/student_barcode/" + rollNo);
                    return;
                } else {
                    error = "❌ Roll number '" + escapeHtml(rollNo) + "' already exists. Please use a unique roll number.";
                }
            }
        }
        String errorHtml = error != null ? "<div class=\"alert alert-danger\">" + escapeHtml(error) + "</div>" : "";
        String html = HEAD + ADMIN_NAV + """
<div class="container">
<div class="ph fi"><h1>➕ Register New Student</h1><p>Fill in student details to generate barcode ID</p></div>
""" + errorHtml + """
<div class="glass-static fi1">
<form method="POST">
<div class="form-grid">

<div class="fg">
<label>Student Full Name</label>
<input type="text" name="name" placeholder="Enter full name" required>
</div>

<div class="fg">
<label>Date of Birth</label>
<input type="date" name="dob" required>
</div>

<div class="fg">
<label>Class / Course</label>
<input type="text" name="student_class" placeholder="e.g. BE CSE, 10th Std" required>
</div>

<div class="fg">
<label>Roll Number</label>
<input type="text" name="roll_no" placeholder="Enter unique roll number" required>
</div>

<div class="fg">
<label>Section</label>
<select name="section" required>
<option value="">Select Section</option>
<option>A</option><option>B</option><option>C</option><option>D</option>
</select>
</div>

<div class="fg">
<label>Parent Phone</label>
<input type="tel" name="parent_phone" placeholder="Parent phone number" required>
</div>

<div class="fg">
<label>Alternate Phone</label>
<input type="tel" name="alternate_phone" placeholder="Alternate phone number" required>
</div>

</div>

<button type="submit" class="btn btn-primary" style="margin-top:12px">🔖 Register & Generate Barcode</button>
</form>
</div>
</div>
""" + FOOT;
        sendResponse(exchange, 200, html);
    }

    // --- STUDENTS LIST ---
    private static void renderStudentsList(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        String msg = query.get("msg");

        List<Student> students = new ArrayList<>();
        if (useSqlite) {
            try (Connection conn = getDb();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM students ORDER BY id DESC")) {
                while (rs.next()) {
                    Student s = new Student();
                    s.id = rs.getInt("id");
                    s.name = rs.getString("name");
                    s.dob = rs.getString("dob");
                    s.studentClass = rs.getString("student_class");
                    s.rollNo = rs.getString("roll_no");
                    s.section = rs.getString("section");
                    s.parentPhone = rs.getString("parent_phone");
                    s.alternatePhone = rs.getString("alternate_phone");
                    s.barcode = rs.getString("barcode");
                    students.add(s);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            students = FileStorage.getStudents();
            students.sort((a, b) -> Integer.compare(b.id, a.id));
        }

        StringBuilder rows = new StringBuilder();
        int idx = 1;
        for (Student s : students) {
            rows.append(String.format("""
<tr>
<td>%d</td>
<td><b style="color:var(--txt)">%s</b></td>
<td>%s</td>
<td>%s</td>
<td>%s</td>
<td>%s</td>
<td>%s</td>
<td><a href="/student_barcode/%s" style="color:var(--accent-l);text-decoration:none">%s</a></td>
<td>
<a href="/student_barcode/%s" class="btn btn-dark btn-sm" style="margin-bottom:4px">🔖 Barcode</a>
<a href="/delete_student/%d" class="btn btn-danger btn-sm" onclick="return confirm('Delete %s?')">🗑 Delete</a>
</td>
</tr>
""", idx++, escapeHtml(s.name), escapeHtml(s.dob), escapeHtml(s.studentClass),
     escapeHtml(s.rollNo), escapeHtml(s.section), escapeHtml(s.parentPhone),
     escapeHtml(s.rollNo), escapeHtml(s.barcode), escapeHtml(s.rollNo),
     s.id, escapeHtml(s.name)));
        }

        String msgHtml = msg != null ? "<div class=\"alert alert-success fi1\">" + escapeHtml(msg) + "</div>" : "";
        String html = HEAD + ADMIN_NAV + String.format("""
<div class="container">
<div class="ph fi"><h1>👥 All Students</h1><p>%d students registered</p></div>
%s
<div class="glass-static fi1">
<div class="table-wrap">
<table>
<tr>
<th>#</th><th>Name</th><th>DOB</th><th>Class</th><th>Roll No</th>
<th>Section</th><th>Parent Phone</th><th>Barcode</th><th>Actions</th>
</tr>
%s
</table>
</div>
</div>
</div>
""", students.size(), msgHtml, rows.toString()) + FOOT;

        sendResponse(exchange, 200, html);
    }

    // --- DELETE STUDENT ---
    private static void handleDeleteStudent(HttpExchange exchange, String sidStr) throws IOException {
        try {
            int sid = Integer.parseInt(sidStr);
            if (useSqlite) {
                try (Connection conn = getDb()) {
                    try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM attendance WHERE student_id=?")) {
                        pstmt.setInt(1, sid);
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM students WHERE id=?")) {
                        pstmt.setInt(1, sid);
                        pstmt.executeUpdate();
                    }
                }
            } else {
                FileStorage.deleteStudent(sid);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        redirect(exchange, "/students?msg=Student+deleted+successfully");
    }

    // --- STUDENT BARCODE VIEW ---
    private static void renderStudentBarcode(HttpExchange exchange, String rollNo) throws IOException {
        Student student = null;
        if (useSqlite) {
            try (Connection conn = getDb();
                 PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM students WHERE roll_no=?")) {
                pstmt.setString(1, rollNo);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    student = new Student();
                    student.id = rs.getInt("id");
                    student.name = rs.getString("name");
                    student.rollNo = rs.getString("roll_no");
                    student.studentClass = rs.getString("student_class");
                    student.section = rs.getString("section");
                    student.barcode = rs.getString("barcode");
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            student = FileStorage.getStudentByRollNo(rollNo);
        }

        if (student == null) {
            redirect(exchange, "/students");
            return;
        }

        String barcodeUri = getBarcodeSvg(student.barcode);
        String barcodeImgHtml = barcodeUri != null ?
                String.format("<img src=\"%s\" alt=\"Barcode for %s\">", barcodeUri, escapeHtml(student.barcode)) :
                String.format("<div style=\"font-family:monospace;font-size:24px;letter-spacing:3px;padding:20px;background:#f8fafc;border-radius:8px;margin:16px 0;border:2px dashed #94a3b8\">%s</div>", escapeHtml(student.barcode));

        String html = HEAD + ADMIN_NAV + String.format("""
<div class="container">
<div class="ph fi"><h1>🔖 Student Barcode</h1><p>Printable ID card with scannable barcode</p></div>

<div class="bc-card fi1">
<h2>Student ID Card</h2>
<hr style="border:none;height:1px;background:#e2e8f0;margin:16px 0">
<h3 style="font-size:22px;margin:8px 0">%s</h3>
<p><b>Roll No:</b> %s</p>
<p><b>Class:</b> %s &nbsp;|&nbsp; <b>Section:</b> %s</p>
<hr style="border:none;height:1px;background:#e2e8f0;margin:16px 0">
%s
<div class="bc-text">%s</div>
<p style="font-size:12px;color:#94a3b8;margin-top:8px">Scan this barcode for attendance</p>
</div>

<div style="text-align:center;margin-top:20px" class="no-print">
<button onclick="window.print()" class="btn btn-dark btn-sm" style="width:auto">🖨️ Print ID Card</button>
&nbsp;
<a href="/students" class="btn btn-outline btn-sm" style="width:auto">← Back to Students</a>
</div>
</div>
""", escapeHtml(student.name), escapeHtml(student.rollNo), escapeHtml(student.studentClass),
     escapeHtml(student.section), barcodeImgHtml, escapeHtml(student.barcode)) + FOOT;

        sendResponse(exchange, 200, html);
    }

    // --- BARCODE ATTENDANCE ---
    private static void handleBarcodeAttendance(HttpExchange exchange, String method) throws IOException {
        String message = null;
        String msgType = "info";

        if (method.equals("POST")) {
            Map<String, String> form = parseFormData(exchange);
            String bc = form.getOrDefault("barcode", "").trim();
            Student student = null;
            if (useSqlite) {
                try (Connection conn = getDb()) {
                    try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM students WHERE barcode=?")) {
                        pstmt.setString(1, bc);
                        ResultSet rs = pstmt.executeQuery();
                        if (rs.next()) {
                            student = new Student();
                            student.id = rs.getInt("id");
                            student.name = rs.getString("name");
                            student.rollNo = rs.getString("roll_no");
                        }
                    }
                    if (student == null) {
                        message = "❌ Barcode not found in the system. Please check and try again.";
                        msgType = "danger";
} else {
                        String today = LocalDate.now().toString();
                        String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                        boolean exists = false;
                        try (PreparedStatement pstmt = conn.prepareStatement(
                                "SELECT id FROM attendance WHERE student_id=? AND attendance_date=?")) {
                            pstmt.setInt(1, student.id);
                            pstmt.setString(2, today);
                            ResultSet rs = pstmt.executeQuery();
                            if (rs.next()) exists = true;
                        }
                        if (exists) {
                            message = String.format("⚠️ Attendance already marked for %s (%s) today.", student.name, student.rollNo);
                            msgType = "warning";
                        } else {
                            try (PreparedStatement pstmt = conn.prepareStatement(
                                    "INSERT INTO attendance (student_id, attendance_date, attendance_time, status) VALUES (?,?,?,'Present')")) {
                                pstmt.setInt(1, student.id);
                                pstmt.setString(2, today);
                                pstmt.setString(3, nowTime);
                                pstmt.executeUpdate();
                            }
                            message = String.format("✅ Attendance marked for %s (%s) at %s", student.name, student.rollNo, nowTime);
                            msgType = "success";
                        }
                    }
                } catch (SQLException e) {
                    message = "❌ Database error: " + e.getMessage();
                    msgType = "danger";
                }
            } else {
                student = FileStorage.getStudentByBarcode(bc);
                if (student == null) {
                    message = "❌ Barcode not found in the system. Please check and try again.";
                    msgType = "danger";
                } else {
                    String today = LocalDate.now().toString();
                    String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    boolean ok = FileStorage.markAttendance(student.id, today, nowTime, "Present");
                    if (ok) {
                        message = String.format("✅ Attendance marked for %s (%s) at %s", student.name, student.rollNo, nowTime);
                        msgType = "success";
                    } else {
                        message = String.format("⚠️ Attendance already marked for %s (%s) today.", student.name, student.rollNo);
                        msgType = "warning";
                    }
                }
            }
        }

        String today = LocalDate.now().toString();
        List<Attendance> todayLog = new ArrayList<>();

        if (useSqlite) {
            try (Connection conn = getDb();
                 PreparedStatement pstmt = conn.prepareStatement("""
                     SELECT s.name, s.roll_no, s.student_class, s.section, a.attendance_time
                     FROM attendance a JOIN students s ON a.student_id=s.id
                     WHERE a.attendance_date=? ORDER BY a.attendance_time DESC
                 """)) {
                pstmt.setString(1, today);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    Attendance a = new Attendance();
                    a.name = rs.getString("name");
                    a.rollNo = rs.getString("roll_no");
                    a.studentClass = rs.getString("student_class");
                    a.section = rs.getString("section");
                    a.attendanceTime = rs.getString("attendance_time");
                    todayLog.add(a);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            List<Attendance> allLogs = FileStorage.getAttendanceLogs();
            for (Attendance a : allLogs) {
                if (today.equals(a.attendanceDate)) {
                    Student s = FileStorage.getStudentById(a.studentId);
                    if (s != null) {
                        Attendance item = new Attendance();
                        item.name = s.name;
                        item.rollNo = s.rollNo;
                        item.studentClass = s.studentClass;
                        item.section = s.section;
                        item.attendanceTime = a.attendanceTime;
                        todayLog.add(item);
                    }
                }
            }
            todayLog.sort((a, b) -> b.attendanceTime.compareTo(a.attendanceTime));
        }

        StringBuilder logRows = new StringBuilder();
        int idx = 1;
        for (Attendance r : todayLog) {
            logRows.append(String.format("""
<tr>
<td>%d</td>
<td><b style="color:var(--txt)">%s</b></td>
<td>%s</td>
<td>%s</td>
<td>%s</td>
</tr>
""", idx++, escapeHtml(r.name), escapeHtml(r.rollNo), escapeHtml(r.studentClass), escapeHtml(r.attendanceTime)));
        }

        String msgHtml = message != null ?
                String.format("<div class=\"alert alert-%s\">%s</div>", msgType, escapeHtml(message)) : "";

        String logTableHtml = !todayLog.isEmpty() ? String.format("""
<div class="table-wrap" style="max-height:400px;overflow-y:auto">
<table>
<tr><th>#</th><th>Name</th><th>Roll No</th><th>Class</th><th>Time</th></tr>
%s
</table>
</div>
""", logRows.toString()) : "<p style=\"color:var(--txt-m);padding:20px 0\">No scans recorded today yet.</p>";

        String html = HEAD + ADMIN_NAV + String.format("""
<div class="container">
<div class="ph fi"><h1>📷 Barcode Attendance</h1><p>Scan student barcodes to mark attendance</p></div>

<div class="g2" style="margin-bottom:20px">

<!-- SCANNER -->
<div class="glass-static fi1">
<h3 style="font-family:'Outfit',sans-serif;margin-bottom:12px">🔍 Scan Barcode</h3>
<p style="color:var(--txt-m);font-size:13px;margin-bottom:16px">Place cursor in the box and scan the student's ID card barcode, or type the code manually.</p>

%s

<form method="POST" id="scanForm">
<label>Barcode Input</label>
<input id="barcodeInput" type="text" name="barcode" placeholder="Scan or type barcode here..." autofocus required autocomplete="off">
<button type="submit" class="btn btn-success">✅ Mark Attendance</button>
</form>

<div class="alert alert-info" style="margin-top:16px">
<div>
<b>How it works:</b><br>
1. Student shows ID card<br>
2. Barcode scanner reads the code<br>
3. System automatically marks attendance<br>
4. Duplicate scans are prevented
</div>
</div>
</div>

<!-- TODAY'S LOG -->
<div class="glass-static fi2">
<h3 style="font-family:'Outfit',sans-serif;margin-bottom:4px">📋 Today's Scan Log</h3>
<p style="color:var(--txt-m);font-size:13px;margin-bottom:12px">%s — %d students scanned</p>
%s
</div>

</div>
</div>

<script>
window.addEventListener('load', function() {
    var inp = document.getElementById('barcodeInput');
    if (inp) inp.focus();
});
document.addEventListener('click', function() {
    var inp = document.getElementById('barcodeInput');
    if (inp) inp.focus();
});
</script>
""", msgHtml, today, todayLog.size(), logTableHtml) + FOOT;

        sendResponse(exchange, 200, html);
    }

    // --- REPORTS ---
    private static void renderReports(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        String dateFrom = query.getOrDefault("date_from", "");
        String dateTo = query.getOrDefault("date_to", "");
        String classFilter = query.getOrDefault("class_filter", "");

        List<String> classes = new ArrayList<>();
        int totalDays = 0;
        List<Report> reports = new ArrayList<>();

        if (useSqlite) {
            try (Connection conn = getDb()) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT DISTINCT student_class FROM students ORDER BY student_class")) {
                    while (rs.next()) classes.add(rs.getString("student_class"));
                }

                if (!dateFrom.isEmpty() && !dateTo.isEmpty()) {
                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "SELECT COUNT(DISTINCT attendance_date) FROM attendance WHERE attendance_date BETWEEN ? AND ?")) {
                        pstmt.setString(1, dateFrom);
                        pstmt.setString(2, dateTo);
                        ResultSet rs = pstmt.executeQuery();
                        if (rs.next()) totalDays = rs.getInt(1);
                    }
                } else {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT attendance_date) FROM attendance")) {
                        if (rs.next()) totalDays = rs.getInt(1);
                    }
                }

                List<Student> students = new ArrayList<>();
                if (!classFilter.isEmpty()) {
                    try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM students WHERE student_class=? ORDER BY name")) {
                        pstmt.setString(1, classFilter);
                        ResultSet rs = pstmt.executeQuery();
                        while (rs.next()) {
                            Student s = new Student();
                            s.id = rs.getInt("id");
                            s.name = rs.getString("name");
                            s.rollNo = rs.getString("roll_no");
                            s.studentClass = rs.getString("student_class");
                            s.section = rs.getString("section");
                            students.add(s);
                        }
                    }
                } else {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT * FROM students ORDER BY name")) {
                        while (rs.next()) {
                            Student s = new Student();
                            s.id = rs.getInt("id");
                            s.name = rs.getString("name");
                            s.rollNo = rs.getString("roll_no");
                            s.studentClass = rs.getString("student_class");
                            s.section = rs.getString("section");
                            students.add(s);
                        }
                    }
                }

                for (Student s : students) {
                    int present = 0;
                    String q = "SELECT COUNT(*) FROM attendance WHERE student_id=? AND status='Present'";
                    if (!dateFrom.isEmpty() && !dateTo.isEmpty()) {
                        q += " AND attendance_date BETWEEN ? AND ?";
                        try (PreparedStatement pstmt = conn.prepareStatement(q)) {
                            pstmt.setInt(1, s.id);
                            pstmt.setString(2, dateFrom);
                            pstmt.setString(3, dateTo);
                            ResultSet rs = pstmt.executeQuery();
                            if (rs.next()) present = rs.getInt(1);
                        }
                    } else {
                        try (PreparedStatement pstmt = conn.prepareStatement(q)) {
                            pstmt.setInt(1, s.id);
                            ResultSet rs = pstmt.executeQuery();
                            if (rs.next()) present = rs.getInt(1);
                        }
                    }
                    int absent = Math.max(totalDays - present, 0);
                    double pct = totalDays > 0 ? Math.round((present * 100.0 / totalDays) * 10.0) / 10.0 : 0.0;
                    Report r = new Report();
                    r.name = s.name;
                    r.rollNo = s.rollNo;
                    r.studentClass = s.studentClass;
                    r.section = s.section;
                    r.totalDays = totalDays;
                    r.present = present;
                    r.absent = absent;
                    r.pct = pct;
                    reports.add(r);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            List<Student> allStudents = FileStorage.getStudents();
            List<Attendance> allLogs = FileStorage.getAttendanceLogs();

            Set<String> classSet = new TreeSet<>();
            Set<String> dateSet = new HashSet<>();

            for (Student s : allStudents) classSet.add(s.studentClass);
            classes.addAll(classSet);

            for (Attendance a : allLogs) {
                if (!dateFrom.isEmpty() && !dateTo.isEmpty()) {
                    if (a.attendanceDate.compareTo(dateFrom) >= 0 && a.attendanceDate.compareTo(dateTo) <= 0) {
                        dateSet.add(a.attendanceDate);
                    }
                } else {
                    dateSet.add(a.attendanceDate);
                }
            }
            totalDays = dateSet.size();

            for (Student s : allStudents) {
                if (!classFilter.isEmpty() && !classFilter.equalsIgnoreCase(s.studentClass)) continue;
                int present = 0;
                for (Attendance a : allLogs) {
                    if (a.studentId == s.id && "Present".equalsIgnoreCase(a.status)) {
                        if (!dateFrom.isEmpty() && !dateTo.isEmpty()) {
                            if (a.attendanceDate.compareTo(dateFrom) >= 0 && a.attendanceDate.compareTo(dateTo) <= 0) {
                                present++;
                            }
                        } else {
                            present++;
                        }
                    }
                }
                int absent = Math.max(totalDays - present, 0);
                double pct = totalDays > 0 ? Math.round((present * 100.0 / totalDays) * 10.0) / 10.0 : 0.0;
                Report r = new Report();
                r.name = s.name;
                r.rollNo = s.rollNo;
                r.studentClass = s.studentClass;
                r.section = s.section;
                r.totalDays = totalDays;
                r.present = present;
                r.absent = absent;
                r.pct = pct;
                reports.add(r);
            }
        }

        reports.sort(Comparator.comparingDouble(a -> a.pct));
        double avgPct = reports.isEmpty() ? 0.0 : Math.round((reports.stream().mapToDouble(a -> a.pct).sum() / reports.size()) * 10.0) / 10.0;

        StringBuilder classOptions = new StringBuilder("<option value=\"\">All Classes</option>");
        for (String c : classes) {
            String sel = c.equals(classFilter) ? "selected" : "";
            classOptions.append(String.format("<option value=\"%s\" %s>%s</option>", escapeHtml(c), sel, escapeHtml(c)));
        }

        StringBuilder tableRows = new StringBuilder();
        int idx = 1;
        for (Report r : reports) {
            String badgeHtml;
            if (r.pct >= 75.0) badgeHtml = "<span class=\"badge badge-ok\">✓ Good</span>";
            else if (r.pct >= 50.0) badgeHtml = "<span class=\"badge badge-warn\">⚠ Low</span>";
            else badgeHtml = "<span class=\"badge badge-low\">✗ Critical</span>";

            tableRows.append(String.format(Locale.US, """
<tr>
<td>%d</td>
<td><b style="color:var(--txt)">%s</b></td>
<td>%s</td>
<td>%s</td>
<td>%s</td>
<td>%d</td>
<td style="color:var(--emerald)">%d</td>
<td style="color:var(--rose)">%d</td>
<td>
<div style="display:flex;align-items:center;gap:8px">
<div class="prog-track" style="flex:1;min-width:60px">
<div class="prog-fill" style="width:%.1f%%"></div>
</div>
<b style="color:var(--txt);min-width:45px">%.1f%%</b>
</div>
</td>
<td>%s</td>
</tr>
""", idx++, escapeHtml(r.name), escapeHtml(r.rollNo), escapeHtml(r.studentClass),
     escapeHtml(r.section), r.totalDays, r.present, r.absent, r.pct, r.pct, badgeHtml));
        }

        String html = HEAD + ADMIN_NAV + String.format(Locale.US, """
<div class="container">
<div class="ph fi"><h1>📋 Attendance Reports</h1><p>Filter, analyze and export attendance data</p></div>

<!-- SUMMARY -->
<div class="stats fi1">
<div class="stat">
<div class="icon">📅</div>
<div class="val">%d</div>
<div class="lbl">Total Class Days</div>
</div>
<div class="stat">
<div class="icon">👥</div>
<div class="val">%d</div>
<div class="lbl">Students</div>
</div>
<div class="stat">
<div class="icon">📊</div>
<div class="val">%.1f%%</div>
<div class="lbl">Average Attendance</div>
</div>
</div>

<!-- FILTERS -->
<div class="glass-static fi2" style="margin-bottom:20px">
<h3 style="font-family:'Outfit',sans-serif;margin-bottom:12px">🔍 Filters</h3>
<form method="GET" class="fbar">
<div class="fg">
<label>From Date</label>
<input type="date" name="date_from" value="%s">
</div>
<div class="fg">
<label>To Date</label>
<input type="date" name="date_to" value="%s">
</div>
<div class="fg">
<label>Class</label>
<select name="class_filter">
%s
</select>
</div>
<button type="submit" class="btn btn-primary btn-sm">🔍 Apply</button>
<a href="/export_csv?date_from=%s&date_to=%s&class_filter=%s" class="btn btn-dark btn-sm">📥 Export CSV</a>
</form>
</div>

<!-- TABLE -->
<div class="glass-static fi3">
<div class="table-wrap">
<table>
<tr>
<th>#</th><th>Student</th><th>Roll No</th><th>Class</th><th>Section</th>
<th>Total Days</th><th>Present</th><th>Absent</th><th>Percentage</th><th>Status</th>
</tr>
%s
</table>
</div>
</div>

</div>
""", totalDays, reports.size(), avgPct,
     escapeHtml(dateFrom), escapeHtml(dateTo), classOptions.toString(),
     escapeHtml(dateFrom), escapeHtml(dateTo), escapeHtml(classFilter),
     tableRows.toString()) + FOOT;

        sendResponse(exchange, 200, html);
    }

    // --- EXPORT CSV ---
    private static void handleExportCsv(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
        String dateFrom = query.getOrDefault("date_from", "");
        String dateTo = query.getOrDefault("date_to", "");
        String classFilter = query.getOrDefault("class_filter", "");

        int totalDays = 0;
        List<Report> reports = new ArrayList<>();

        if (useSqlite) {
            try (Connection conn = getDb()) {
                if (!dateFrom.isEmpty() && !dateTo.isEmpty()) {
                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "SELECT COUNT(DISTINCT attendance_date) FROM attendance WHERE attendance_date BETWEEN ? AND ?")) {
                        pstmt.setString(1, dateFrom);
                        pstmt.setString(2, dateTo);
                        ResultSet rs = pstmt.executeQuery();
                        if (rs.next()) totalDays = rs.getInt(1);
                    }
                } else {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT attendance_date) FROM attendance")) {
                        if (rs.next()) totalDays = rs.getInt(1);
                    }
                }

                List<Student> students = new ArrayList<>();
                if (!classFilter.isEmpty()) {
                    try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM students WHERE student_class=? ORDER BY name")) {
                        pstmt.setString(1, classFilter);
                        ResultSet rs = pstmt.executeQuery();
                        while (rs.next()) {
                            Student s = new Student();
                            s.id = rs.getInt("id");
                            s.name = rs.getString("name");
                            s.rollNo = rs.getString("roll_no");
                            s.studentClass = rs.getString("student_class");
                            s.section = rs.getString("section");
                            students.add(s);
                        }
                    }
                } else {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT * FROM students ORDER BY name")) {
                        while (rs.next()) {
                            Student s = new Student();
                            s.id = rs.getInt("id");
                            s.name = rs.getString("name");
                            s.rollNo = rs.getString("roll_no");
                            s.studentClass = rs.getString("student_class");
                            s.section = rs.getString("section");
                            students.add(s);
                        }
                    }
                }

                for (Student s : students) {
                    int present = 0;
                    String q = "SELECT COUNT(*) FROM attendance WHERE student_id=? AND status='Present'";
                    if (!dateFrom.isEmpty() && !dateTo.isEmpty()) {
                        q += " AND attendance_date BETWEEN ? AND ?";
                        try (PreparedStatement pstmt = conn.prepareStatement(q)) {
                            pstmt.setInt(1, s.id);
                            pstmt.setString(2, dateFrom);
                            pstmt.setString(3, dateTo);
                            ResultSet rs = pstmt.executeQuery();
                            if (rs.next()) present = rs.getInt(1);
                        }
                    } else {
                        try (PreparedStatement pstmt = conn.prepareStatement(q)) {
                            pstmt.setInt(1, s.id);
                            ResultSet rs = pstmt.executeQuery();
                            if (rs.next()) present = rs.getInt(1);
                        }
                    }
                    int absent = Math.max(totalDays - present, 0);
                    double pct = totalDays > 0 ? Math.round((present * 100.0 / totalDays) * 10.0) / 10.0 : 0.0;
                    Report r = new Report();
                    r.name = s.name;
                    r.rollNo = s.rollNo;
                    r.studentClass = s.studentClass;
                    r.section = s.section;
                    r.totalDays = totalDays;
                    r.present = present;
                    r.absent = absent;
                    r.pct = pct;
                    reports.add(r);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            List<Student> allStudents = FileStorage.getStudents();
            List<Attendance> allLogs = FileStorage.getAttendanceLogs();

            Set<String> dateSet = new HashSet<>();
            for (Attendance a : allLogs) {
                if (!dateFrom.isEmpty() && !dateTo.isEmpty()) {
                    if (a.attendanceDate.compareTo(dateFrom) >= 0 && a.attendanceDate.compareTo(dateTo) <= 0) {
                        dateSet.add(a.attendanceDate);
                    }
                } else {
                    dateSet.add(a.attendanceDate);
                }
            }
            totalDays = dateSet.size();

            for (Student s : allStudents) {
                if (!classFilter.isEmpty() && !classFilter.equalsIgnoreCase(s.studentClass)) continue;
                int present = 0;
                for (Attendance a : allLogs) {
                    if (a.studentId == s.id && "Present".equalsIgnoreCase(a.status)) {
                        if (!dateFrom.isEmpty() && !dateTo.isEmpty()) {
                            if (a.attendanceDate.compareTo(dateFrom) >= 0 && a.attendanceDate.compareTo(dateTo) <= 0) {
                                present++;
                            }
                        } else {
                            present++;
                        }
                    }
                }
                int absent = Math.max(totalDays - present, 0);
                double pct = totalDays > 0 ? Math.round((present * 100.0 / totalDays) * 10.0) / 10.0 : 0.0;
                Report r = new Report();
                r.name = s.name;
                r.rollNo = s.rollNo;
                r.studentClass = s.studentClass;
                r.section = s.section;
                r.totalDays = totalDays;
                r.present = present;
                r.absent = absent;
                r.pct = pct;
                reports.add(r);
            }
        }

        StringBuilder csv = new StringBuilder();
        csv.append("Name,Roll No,Class,Section,Total Days,Present,Absent,Percentage\n");
        for (Report r : reports) {
            csv.append(String.format(Locale.US, "\"%s\",\"%s\",\"%s\",\"%s\",%d,%d,%d,%.1f\n",
                    r.name.replace("\"", "\"\""),
                    r.rollNo.replace("\"", "\"\""),
                    r.studentClass.replace("\"", "\"\""),
                    r.section.replace("\"", "\"\""),
                    r.totalDays, r.present, r.absent, r.pct));
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=attendance_report.csv");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // --- STUDENT DASHBOARD ---
    private static void renderStudentDashboard(HttpExchange exchange, Session session) throws IOException {
        Student student = null;
        int totalDays = 0, present = 0, absent = 0;
        double pct = 0.0;
        List<String> chartLabels = new ArrayList<>();
        List<Integer> chartData = new ArrayList<>();
        List<Attendance> history = new ArrayList<>();

        if (useSqlite) {
            try (Connection conn = getDb()) {
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM students WHERE id=?")) {
                    pstmt.setInt(1, session.studentId);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        student = new Student();
                        student.id = rs.getInt("id");
                        student.name = rs.getString("name");
                        student.dob = rs.getString("dob");
                        student.studentClass = rs.getString("student_class");
                        student.rollNo = rs.getString("roll_no");
                        student.section = rs.getString("section");
                        student.barcode = rs.getString("barcode");
                    }
                }
                if (student == null) {
                    session.studentId = null;
                    redirect(exchange, "/");
                    return;
                }

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT attendance_date) FROM attendance")) {
                    if (rs.next()) totalDays = rs.getInt(1);
                }

                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM attendance WHERE student_id=? AND status='Present'")) {
                    pstmt.setInt(1, student.id);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) present = rs.getInt(1);
                }
                absent = Math.max(totalDays - present, 0);
                pct = totalDays > 0 ? Math.round((present * 100.0 / totalDays) * 10.0) / 10.0 : 0.0;

                // 30-day chart
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd");
                for (int i = 29; i >= 0; i--) {
                    LocalDate d = LocalDate.now().minusDays(i);
                    chartLabels.add(d.format(fmt));
                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "SELECT COUNT(*) FROM attendance WHERE student_id=? AND attendance_date=? AND status='Present'")) {
                        pstmt.setInt(1, student.id);
                        pstmt.setString(2, d.toString());
                        ResultSet rs = pstmt.executeQuery();
                        chartData.add(rs.next() && rs.getInt(1) > 0 ? 1 : 0);
                    }
                }

                // History
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT * FROM attendance WHERE student_id=? ORDER BY attendance_date DESC LIMIT 20")) {
                    pstmt.setInt(1, student.id);
                    ResultSet rs = pstmt.executeQuery();
                    while (rs.next()) {
                        Attendance a = new Attendance();
                        a.attendanceDate = rs.getString("attendance_date");
                        a.attendanceTime = rs.getString("attendance_time");
                        a.status = rs.getString("status");
                        history.add(a);
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            student = FileStorage.getStudentById(session.studentId);
            if (student == null) {
                session.studentId = null;
                redirect(exchange, "/");
                return;
            }

            List<Attendance> allLogs = FileStorage.getAttendanceLogs();
            Set<String> dates = new HashSet<>();
            for (Attendance a : allLogs) dates.add(a.attendanceDate);
            totalDays = dates.size();

            for (Attendance a : allLogs) {
                if (a.studentId == student.id && "Present".equalsIgnoreCase(a.status)) {
                    present++;
                }
            }
            absent = Math.max(totalDays - present, 0);
            pct = totalDays > 0 ? Math.round((present * 100.0 / totalDays) * 10.0) / 10.0 : 0.0;

            // 30-day chart
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd");
            for (int i = 29; i >= 0; i--) {
                LocalDate d = LocalDate.now().minusDays(i);
                chartLabels.add(d.format(fmt));
                String dStr = d.toString();
                boolean pr = false;
                for (Attendance a : allLogs) {
                    if (a.studentId == student.id && dStr.equals(a.attendanceDate) && "Present".equalsIgnoreCase(a.status)) {
                        pr = true;
                        break;
                    }
                }
                chartData.add(pr ? 1 : 0);
            }

            // History
            List<Attendance> myLogs = new ArrayList<>();
            for (Attendance a : allLogs) {
                if (a.studentId == student.id) myLogs.add(a);
            }
            myLogs.sort((a, b) -> b.attendanceDate.compareTo(a.attendanceDate));
            for (int i = 0; i < Math.min(20, myLogs.size()); i++) {
                history.add(myLogs.get(i));
            }
        }

        String alertBox;
        if (pct >= 75.0) alertBox = "<div class=\"alert alert-success\" style=\"margin-top:12px\">✅ Good attendance! Keep it up.</div>";
        else if (pct >= 50.0) alertBox = "<div class=\"alert alert-warning\" style=\"margin-top:12px\">⚠️ Attendance below 75%. Improve soon.</div>";
        else alertBox = "<div class=\"alert alert-danger\" style=\"margin-top:12px\">❌ Critical! Attendance below 50%.</div>";

        StringBuilder historyRows = new StringBuilder();
        int idx = 1;
        for (Attendance h : history) {
            historyRows.append(String.format("""
<tr>
<td>%d</td>
<td>%s</td>
<td>%s</td>
<td><span class="badge badge-ok">✓ %s</span></td>
</tr>
""", idx++, escapeHtml(h.attendanceDate), escapeHtml(h.attendanceTime), escapeHtml(h.status)));
        }

        String historyHtml = !history.isEmpty() ? String.format("""
<div class="table-wrap">
<table>
<tr><th>#</th><th>Date</th><th>Time</th><th>Status</th></tr>
%s
</table>
</div>
""", historyRows.toString()) : "<p style=\"color:var(--txt-m)\">No attendance records yet.</p>";

        String html = HEAD + STUDENT_NAV + String.format(Locale.US, """
<div class="container">

<div class="ph fi"><h1>Welcome, %s 👋</h1><p>Your attendance overview</p></div>

<!-- STATS -->
<div class="stats">
<div class="stat fi1">
<div class="icon">📅</div>
<div class="val">%d</div>
<div class="lbl">Total Class Days</div>
</div>
<div class="stat fi2">
<div class="icon">✅</div>
<div class="val">%d</div>
<div class="lbl">Days Present</div>
</div>
<div class="stat fi3">
<div class="icon">❌</div>
<div class="val">%d</div>
<div class="lbl">Days Absent</div>
</div>
<div class="stat fi4">
<div class="icon">📈</div>
<div class="val">%.1f%%</div>
<div class="lbl">Attendance</div>
</div>
</div>

<div class="g2" style="margin-bottom:20px">

<!-- CHART -->
<div class="glass-static fi2">
<h3 style="font-family:'Outfit',sans-serif;margin-bottom:4px">📊 Last 30 Days</h3>
<p style="color:var(--txt-m);font-size:13px;margin-bottom:8px">Your daily attendance (1=present, 0=absent)</p>
<div class="chart-box"><canvas id="stuChart"></canvas></div>
</div>

<!-- STUDENT INFO -->
<div class="glass-static fi3">
<h3 style="font-family:'Outfit',sans-serif;margin-bottom:16px">📋 Your Details</h3>
<table style="width:100%%">
<tr><td style="color:var(--txt-m);padding:8px 0">Name</td><td style="padding:8px 0"><b>%s</b></td></tr>
<tr><td style="color:var(--txt-m);padding:8px 0">Roll No</td><td style="padding:8px 0"><b>%s</b></td></tr>
<tr><td style="color:var(--txt-m);padding:8px 0">Class</td><td style="padding:8px 0">%s</td></tr>
<tr><td style="color:var(--txt-m);padding:8px 0">Section</td><td style="padding:8px 0">%s</td></tr>
<tr><td style="color:var(--txt-m);padding:8px 0">DOB</td><td style="padding:8px 0">%s</td></tr>
<tr><td style="color:var(--txt-m);padding:8px 0">Barcode</td><td style="padding:8px 0;font-family:monospace;color:var(--accent-l)">%s</td></tr>
</table>

<div style="margin-top:16px">
<h4 style="margin-bottom:6px">Attendance Status</h4>
<div class="prog-track" style="height:14px">
<div class="prog-fill" style="width:%.1f%%"></div>
</div>
<div style="display:flex;justify-between;margin-top:6px;font-size:13px">
<span style="color:var(--txt-m)">0%%</span>
<span style="color:var(--txt)"><b>%.1f%%</b></span>
<span style="color:var(--txt-m)">100%%</span>
</div>
%s
</div>

</div>
</div>

<!-- HISTORY -->
<div class="glass-static fi4">
<h3 style="font-family:'Outfit',sans-serif;margin-bottom:12px">🕐 Recent Attendance History</h3>
%s
</div>

</div>

<script>
new Chart(document.getElementById('stuChart'),{
 type:'bar',
 data:{labels:%s,datasets:[{
  label:'Attendance',data:%s,
  backgroundColor:%s.map(v=>v?'rgba(16,185,129,.6)':'rgba(244,63,94,.3)'),
  borderColor:%s.map(v=>v?'#10b981':'#f43f5e'),
  borderWidth:1,borderRadius:4
 }]},
 options:{responsive:true,maintainAspectRatio:false,
  plugins:{legend:{display:false}},
  scales:{
   x:{ticks:{color:'#94a3b8',maxRotation:45,font:{size:10}},grid:{display:false}},
   y:{beginAtZero:true,max:1,ticks:{color:'#94a3b8',stepSize:1,callback:function(v){return v?'Present':'Absent'}},grid:{color:'rgba(255,255,255,.04)'}}
  }
 }
});
</script>
""", escapeHtml(student.name), totalDays, present, absent, pct,
     escapeHtml(student.name), escapeHtml(student.rollNo), escapeHtml(student.studentClass),
     escapeHtml(student.section), escapeHtml(student.dob), escapeHtml(student.barcode),
     pct, pct, alertBox, historyHtml,
     toJsonArray(chartLabels), toJsonArray(chartData), toJsonArray(chartData), toJsonArray(chartData)) + FOOT;

        sendResponse(exchange, 200, html);
    }

    // --- MY BARCODE ---
    private static void renderMyBarcode(HttpExchange exchange, Session session) throws IOException {
        Student student = null;
        if (useSqlite) {
            try (Connection conn = getDb();
                 PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM students WHERE id=?")) {
                pstmt.setInt(1, session.studentId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    student = new Student();
                    student.name = rs.getString("name");
                    student.rollNo = rs.getString("roll_no");
                    student.studentClass = rs.getString("student_class");
                    student.section = rs.getString("section");
                    student.barcode = rs.getString("barcode");
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            student = FileStorage.getStudentById(session.studentId);
        }

        if (student == null) {
            session.studentId = null;
            redirect(exchange, "/");
            return;
        }

        String barcodeUri = getBarcodeSvg(student.barcode);
        String barcodeImgHtml = barcodeUri != null ?
                String.format("<img src=\"%s\" alt=\"Barcode\">", barcodeUri) :
                String.format("<div style=\"font-family:monospace;font-size:24px;letter-spacing:3px;padding:20px;background:#f8fafc;border-radius:8px;margin:16px 0;border:2px dashed #94a3b8\">%s</div>", escapeHtml(student.barcode));

        String html = HEAD + STUDENT_NAV + String.format("""
<div class="container">
<div class="ph fi"><h1>🔖 My Barcode</h1><p>Your personal attendance barcode</p></div>

<div class="bc-card fi1">
<h2>Student ID Card</h2>
<hr style="border:none;height:1px;background:#e2e8f0;margin:16px 0">
<h3 style="font-size:22px;margin:8px 0">%s</h3>
<p><b>Roll No:</b> %s</p>
<p><b>Class:</b> %s &nbsp;|&nbsp; <b>Section:</b> %s</p>
<hr style="border:none;height:1px;background:#e2e8f0;margin:16px 0">
%s
<div class="bc-text">%s</div>
<p style="font-size:12px;color:#94a3b8;margin-top:8px">Show this barcode for attendance scanning</p>
</div>

<div style="text-align:center;margin-top:20px" class="no-print">
<button onclick="window.print()" class="btn btn-dark btn-sm" style="width:auto">🖨️ Print</button>
</div>
</div>
""", escapeHtml(student.name), escapeHtml(student.rollNo), escapeHtml(student.studentClass),
     escapeHtml(student.section), barcodeImgHtml, escapeHtml(student.barcode)) + FOOT;

        sendResponse(exchange, 200, html);
    }

    // ================================================================
    //  HTTP & UTILITY HELPERS
    // ================================================================
    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            } else if (pair.length == 1) {
                map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), "");
            }
        }
        return map;
    }

    private static Map<String, String> parseFormData(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        return parseQueryParams(body);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String responseHtml) throws IOException {
        byte[] bytes = responseHtml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.getResponseBody().close();
        } else {
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(new byte[0]);
        }
    }

    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static String toJsonArray(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Object obj = list.get(i);
            if (obj instanceof Number || obj instanceof Boolean) {
                sb.append(obj);
            } else {
                sb.append("\"").append(escapeHtml(obj.toString()).replace("\"", "\\\"")).append("\"");
            }
            if (i < list.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    // ================================================================
    //  MAIN ENTRY POINT
    // ================================================================
    public static void main(String[] args) throws IOException {
        initDatabase();

        HttpServer server = null;
        int[] portsToTry = {5000, 5001, 8080, 8081};
        for (int p : portsToTry) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
                PORT = p;
                break;
            } catch (IOException ignored) {}
        }

        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PORT = server.getAddress().getPort();
        }

        server.createContext("/", new MainHandler());
        server.setExecutor(null);

        System.out.println();
        System.out.println("=======================================================");
        System.out.println("   SMART ATTENDANCE SYSTEM — All-in-One Java Edition");
        System.out.println("=======================================================");
        System.out.println();
        System.out.println("  🌐 Website  : http://127.0.0.1:" + PORT);
        System.out.println("  💾 Engine   : " + (useSqlite ? "SQLite DB (smart_attendance.db)" : "Built-in JSON (smart_attendance_data.json)"));
        System.out.println();
        System.out.println("  👨‍💼 ADMIN LOGIN");
        System.out.println("     Login ID : admin");
        System.out.println("     Password : admin123");
        System.out.println();
        System.out.println("  🎓 STUDENT LOGIN");
        System.out.println("     Login ID : (Your Roll Number)");
        System.out.println("     Password : student123");
        System.out.println();
        System.out.println("=======================================================");
        System.out.println();

        server.start();
    }
}