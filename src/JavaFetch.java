import java.io.*;
import java.lang.management.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class JavaFetch {

    // === ANSI 转义 ===
    static final String RST = "\u001b[0m", BLD = "\u001b[1m", DIM = "\u001b[2m";
    static final String RED = "\u001b[31m", GRN = "\u001b[32m", YLW = "\u001b[33m";
    static final String BLU = "\u001b[34m", MAG = "\u001b[35m", CYN = "\u001b[36m", WHT = "\u001b[37m";

    static String rgb(int r, int g, int b) {
        return "\u001b[38;2;" + r + ";" + g + ";" + b + "m";
    }

    // === 系统枚举 ===
    enum OS { IOS, ANDROID, MAC, WINDOWS, LINUX, SOLARIS, AIX, FREEBSD, UNKNOWN }
    enum Distro {
        UBUNTU, DEBIAN, FEDORA, ARCH, CENTOS, RHEL, KALI, OPENSUSE,
        MANJARO, RASPBIAN, MINT, POP, GENTOO, ENDEAVOUROS, ALPINE, VOID, NIXOS, UNKNOWN
    }
    enum JBStatus { JAILBROKEN, ROOTHIDE, CLEAN }

    // === 颜色配置缓存 ===
    static final Map<String, int[]>   singleColors = new LinkedHashMap<>();
    static final Map<String, int[][]> gradColors   = new LinkedHashMap<>();
    // === Logo 缓存（仅从配置文件加载） ===
    static final Map<String, String[]> logos = new LinkedHashMap<>();

    // ==================== 入口 ====================
    public static void main(String[] args) {
        loadConfig(findConfigFile());
        OS os = detectOS();
        Distro distro = (os == OS.LINUX || os == OS.UNKNOWN) ? detectDistro() : null;
        String[] info = buildInfo(os, distro);
        String[] logo = getLogo(os, distro);
        String[] colors = getLogoColors(os, distro, logo.length);

        int maxW = 0;
        for (String line : logo) {
            int w = visualWidth(line);
            if (w > maxW) maxW = w;
        }
        int padTo = Math.max(maxW + 2, 2);
        int rows = Math.max(logo.length, info.length);

        System.out.println(DIM + "  ┌──────────────────────────────────────────────────┐" + RST);
        for (int i = 0; i < rows; i++) {
            String c = i < colors.length ? colors[i] : WHT;
            String left;
            if (i < logo.length) {
                int cw = visualWidth(logo[i]);
                int sp = Math.max(padTo - cw, 0);
                left = c + BLD + logo[i] + RST + " ".repeat(sp);
            } else {
                left = " ".repeat(padTo);
            }
            String right = i < info.length ? info[i] : "";
            System.out.println("  " + left + "  " + right);
        }
        System.out.println(DIM + "  └──────────────────────────────────────────────────┘" + RST);
    }

    // ==================== 配置文件 ====================
    static String findConfigFile() {
        String[] candidates = {
            "./javafetch.conf",
            new File(JavaFetch.class.getProtectionDomain().getCodeSource()
                .getLocation().getPath()).getParent() + "/javafetch.conf",
            System.getProperty("user.home") + "/.config/javafetch.conf"
        };
        for (String p : candidates) {
            try { if (new File(p).isFile()) return p; } catch (Exception ignored) {}
        }
        return null;
    }

    static void loadConfig(String path) {
        if (path == null) return;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line, section = "";
            List<String> buf = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                String t = line.stripTrailing();
                if (t.startsWith("#") || t.isBlank()) {
                    if (!section.isEmpty() && !section.equals("COLORS")) buf.add(t);
                    continue;
                }
                if (t.startsWith("[") && t.endsWith("]")) {
                    flushSection(section, buf);
                    section = t.substring(1, t.length() - 1).trim().toUpperCase();
                    buf.clear();
                } else if (!section.isEmpty()) {
                    buf.add(t);
                }
            }
            flushSection(section, buf);
        } catch (IOException ignored) {}
    }

    static void flushSection(String section, List<String> lines) {
        if (section.isEmpty()) return;
        if (section.equals("COLORS")) {
            for (String l : lines) {
                int eq = l.indexOf('=');
                if (eq < 0) continue;
                String key = l.substring(0, eq).trim().toUpperCase();
                String val = l.substring(eq + 1).trim();
                parseColor(key, val);
            }
        } else {
            List<String> cleaned = new ArrayList<>();
            for (String l : lines) {
                if (l.trim().equals("{EMPTY}")) cleaned.add("");
                else cleaned.add(l);
            }
            while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isEmpty())
                cleaned.remove(cleaned.size() - 1);
            if (!cleaned.isEmpty()) logos.put(section, cleaned.toArray(new String[0]));
        }
    }

    static void parseColor(String key, String val) {
        if (val.contains(">")) {
            String[] parts = val.split(">");
            if (parts.length == 2) {
                int[] c1 = parseRGB(parts[0].trim());
                int[] c2 = parseRGB(parts[1].trim());
                if (c1 != null && c2 != null) gradColors.put(key, new int[][]{c1, c2});
            }
        } else {
            int[] c = parseRGB(val);
            if (c != null) singleColors.put(key, c);
        }
    }

    static int[] parseRGB(String s) {
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try {
            return new int[]{
                Integer.parseInt(p[0].trim()),
                Integer.parseInt(p[1].trim()),
                Integer.parseInt(p[2].trim())
            };
        } catch (NumberFormatException e) { return null; }
    }

    // ==================== OS 检测 ====================
    static OS detectOS() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ENGLISH);
        String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ENGLISH);

        if (osName.contains("ios") || osName.contains("iphone") || osName.contains("ipad")) return OS.IOS;
        if (osName.contains("darwin") || osName.contains("mac")) return isRealMac() ? OS.MAC : OS.IOS;
        if (osName.contains("linux") && isIshEnv()) return OS.IOS;
        if (hasiOSFS()) return OS.IOS;
        if (vmName.contains("dalvik") || vendor.contains("android")) return OS.ANDROID;
        if (osName.contains("win")) return OS.WINDOWS;
        if (osName.contains("mac")) return OS.MAC;
        if (osName.contains("sunos") || osName.contains("solaris")) return OS.SOLARIS;
        if (osName.contains("aix")) return OS.AIX;
        if (osName.contains("freebsd")) return OS.FREEBSD;
        if (osName.contains("nix") || osName.contains("nux")) return OS.LINUX;
        return OS.UNKNOWN;
    }

    static boolean isRealMac() {
        if (exists("/System/Library/CoreServices/Finder.app")) return true;
        if (exists("/Applications/Safari.app")) return true;
        if (exists("/System/Library/CoreServices/SpringBoard.app")) return false;
        return !exists("/var/mobile");
    }

    static boolean isIshEnv() {
        if (exists("/proc/ish") || exists("/opt/ish")) return true;
        String ver = readFile("/proc/version");
        return ver.toLowerCase().contains("ish") || (!exists("/sys/class") && exists("/var/mobile"));
    }

    static boolean hasiOSFS() {
        return exists("/System/Library/CoreServices/SpringBoard.app")
            || exists("/var/mobile") || exists("/var/jb");
    }

    // ==================== Linux 发行版 ====================
    static Distro detectDistro() {
        String content = readFile("/etc/os-release");
        if (content.isEmpty()) content = readFile("/etc/lsb-release");
        String lower = content.toLowerCase(Locale.ENGLISH);
        if (lower.contains("ubuntu")) return Distro.UBUNTU;
        if (lower.contains("kali")) return Distro.KALI;
        if (lower.contains("debian")) return Distro.DEBIAN;
        if (lower.contains("arch")) return Distro.ARCH;
        if (lower.contains("manjaro")) return Distro.MANJARO;
        if (lower.contains("fedora")) return Distro.FEDORA;
        if (lower.contains("centos")) return Distro.CENTOS;
        if (lower.contains("rhel") || lower.contains("red hat")) return Distro.RHEL;
        if (lower.contains("opensuse") || lower.contains("suse")) return Distro.OPENSUSE;
        if (lower.contains("raspbian")) return Distro.RASPBIAN;
        if (lower.contains("mint")) return Distro.MINT;
        if (lower.contains("pop") && lower.contains("os")) return Distro.POP;
        if (lower.contains("gentoo")) return Distro.GENTOO;
        if (lower.contains("endeavouros")) return Distro.ENDEAVOUROS;
        if (lower.contains("alpine")) return Distro.ALPINE;
        if (lower.contains("void")) return Distro.VOID;
        if (lower.contains("nixos")) return Distro.NIXOS;
        return Distro.UNKNOWN;
    }

    // ==================== 信息面板 ====================
    static String[] buildInfo(OS os, Distro distro) {
        List<String> list = new ArrayList<>();
        String osLabel;
        if (os == OS.IOS) {
            osLabel = "iOS " + getiOSVersion() + " (" + getiOSModel() + ")";
        } else if (os == OS.LINUX && distro != null && distro != Distro.UNKNOWN) {
            osLabel = distro.toString() + " " + System.getProperty("os.version");
        } else {
            osLabel = System.getProperty("os.name") + " " + System.getProperty("os.version");
        }
        list.add(BLD + WHT + "OS:      " + RST + osLabel);
        list.add(BLD + WHT + "Host:    " + RST + getHostName());
        list.add(BLD + WHT + "Kernel:  " + RST + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        list.add(BLD + WHT + "User:    " + RST + System.getProperty("user.name"));
        list.add(BLD + WHT + "Java:    " + RST + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");

        // 系统物理内存（非 JVM 堆）
        long[] mem = getPhysicalMemory();
        if (mem[0] > 0) {
            long used = mem[0] - mem[1];
            list.add(BLD + WHT + "Memory:  " + RST + fmtMB(used) + " / " + fmtMB(mem[0]) + " (System)");
        }

        int cores = Runtime.getRuntime().availableProcessors();
        list.add(BLD + WHT + "CPU:     " + RST + cores + " cores");

        // 仅 iOS 显示越狱状态
        if (os == OS.IOS) {
            JBResult jb = checkJailbreak();
            StringBuilder jbLine = new StringBuilder();
            switch (jb.status) {
                case ROOTHIDE:
                    jbLine.append(RED).append(BLD).append("⚠ JAILBROKEN (RootHide)").append(RST);
                    break;
                case JAILBROKEN:
                    jbLine.append(RED).append(BLD).append("⚠ JAILBROKEN").append(RST);
                    break;
                default:
                    jbLine.append(GRN).append(BLD).append("✔ CLEAN").append(RST);
                    break;
            }
            jbLine.append(DIM).append(" [score:").append(jb.score).append("]").append(RST);
            list.add(BLD + WHT + "Jailbreak:" + RST + " " + jbLine.toString());
            if (!jb.evidence.isEmpty()) {
                // 暴露原因分行显示，每行前面留出适当空格
                list.add(DIM + "               " + String.join(", ", jb.evidence) + RST);
            }
        }
        return list.toArray(new String[0]);
    }

    static long[] getPhysicalMemory() {
        try {
            com.sun.management.OperatingSystemMXBean bean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return new long[]{bean.getTotalPhysicalMemorySize(), bean.getFreePhysicalMemorySize()};
        } catch (Exception e) {
            try {
                String meminfo = readFile("/proc/meminfo");
                long total = 0, avail = 0;
                for (String line : meminfo.split("\n")) {
                    if (line.startsWith("MemTotal:"))
                        total = Long.parseLong(line.replaceAll("[-9]", "")) * 1024;
                    if (line.startsWith("MemAvailable:"))
                        avail = Long.parseLong(line.replaceAll("[-9]", "")) * 1024;
                }
                return new long[]{total, avail};
            } catch (Exception ignored) {}
        }
        return new long[]{0, 0};
    }

    static String fmtMB(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    // ==================== iOS 信息 ====================
    static String getiOSVersion() {
        String plist = readFile("/System/Library/CoreServices/SystemVersion.plist");
        int s = plist.indexOf("<key>ProductVersion</key>");
        if (s < 0) return System.getProperty("os.version", "Unknown");
        int vs = plist.indexOf("<string>", s);
        int ve = plist.indexOf("</string>", vs);
        return (vs >= 0 && ve > vs) ? plist.substring(vs + 8, ve) : System.getProperty("os.version", "Unknown");
    }

    static String getiOSModel() {
        String m = execSysctl("hw.machine");
        return m.isEmpty() ? execSysctl("hw.product") : m;
    }

    // ==================== 越狱检测 ====================
    static class JBResult {
        final JBStatus status;
        final int score;
        final boolean rootHide;
        final List<String> evidence;
        JBResult(JBStatus s, int sc, boolean rh, List<String> ev) {
            this.status = s; this.score = sc; this.rootHide = rh; this.evidence = ev;
        }
    }

    static JBResult checkJailbreak() {
        int score = 0;
        List<String> ev = new ArrayList<>();
        boolean rootHide = false;
        String[] jbPaths = {
            "/Applications/Cydia.app", "/Applications/Sileo.app", "/Applications/Zebra.app",
            "/usr/bin/ssh", "/usr/sbin/sshd", "/bin/bash", "/etc/apt",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/.installed_unc0ver", "/.bootstrapped"
        };
        for (String p : jbPaths) {
            if (exists(p)) { score += 10; ev.add(p); }
        }
        if (exists("/var/jb")) { score += 25; rootHide = true; ev.add("/var/jb (RootHide)"); }
        if (exists("/var/mobile/.jbroot")) { score += 20; rootHide = true; ev.add("/var/mobile/.jbroot (RootHide)"); }
        if (isPortOpen(22)) { score += 15; ev.add("SSH:22"); }
        if (isPortOpen(44)) { score += 10; ev.add("Port:44"); }
        for (String proc : new String[]{"dropbear", "sshd", "cydia"}) {
            if (isProcessRunning(proc)) { score += 8; ev.add("proc:" + proc); }
        }
        if (canWrite("/")) { score += 5; ev.add("root rw"); }
        if (canWrite("/private/etc")) { score += 5; ev.add("etc rw"); }
        if (exists("/Library/MobileSubstrate/DynamicLibraries")) { score += 12; ev.add("tweak inj"); }
        if (exists("/dev/kmem")) { score += 20; ev.add("/dev/kmem"); }
        JBStatus status;
        if (score >= 30) status = rootHide ? JBStatus.ROOTHIDE : JBStatus.JAILBROKEN;
        else if (score >= 10) status = JBStatus.JAILBROKEN;
        else status = JBStatus.CLEAN;
        return new JBResult(status, score, rootHide, ev);
    }

    // ==================== Logo 获取（仅外部配置） ====================
    static String[] getLogo(OS os, Distro distro) {
        String key = logoKey(os, distro);
        if (logos.containsKey(key)) return logos.get(key);
        // 无配置时返回一个问号占位（保持面板对齐）
        return new String[]{"?    "};
    }

    static String logoKey(OS os, Distro distro) {
        if (os == OS.IOS) return "IOS";
        if (os == OS.MAC) return "MACOS";
        if (os == OS.WINDOWS) return "WINDOWS";
        if (os == OS.ANDROID) return "ANDROID";
        if (os == OS.LINUX && distro != null && distro != Distro.UNKNOWN) return distro.name();
        return "UNKNOWN";
    }

    // ==================== 颜色 ====================
    static String[] getLogoColors(OS os, Distro distro, int rows) {
        String key = logoKey(os, distro);
        if (gradColors.containsKey(key)) {
            int[][] g = gradColors.get(key);
            return gradient(rows, g[0][0], g[0][1], g[0][2], g[1][0], g[1][1], g[1][2]);
        }
        int[] c = singleColors.get(key);
        if (c == null) c = defaultColor(os, distro);
        String[] arr = new String[rows];
        Arrays.fill(arr, rgb(c[0], c[1], c[2]));
        return arr;
    }

    static int[] defaultColor(OS os, Distro distro) {
        // 给一个温和的默认颜色（无配置文件时）
        if (os == OS.IOS || os == OS.MAC) return new int[]{0, 200, 100};
        switch (os) {
            case WINDOWS: return new int[]{0, 120, 215};
            case ANDROID: return new int[]{120, 200, 60};
            default: return new int[]{255, 255, 0};
        }
    }

    static String[] gradient(int rows, int r0, int g0, int b0, int r1, int g1, int b1) {
        String[] colors = new String[rows];
        for (int i = 0; i < rows; i++) {
            float t = (rows == 1) ? 0.5f : (float) i / (rows - 1);
            int r = (int)(r0 + (r1 - r0) * t);
            int g = (int)(g0 + (g1 - g0) * t);
            int b = (int)(b0 + (b1 - b0) * t);
            colors[i] = rgb(r, g, b);
        }
        return colors;
    }

    // ==================== 工具 ====================
    static int visualWidth(String s) {
        int w = 0;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u001b') { esc = true; continue; }
            if (esc) { if (ch == 'm') esc = false; continue; }
            w++;
        }
        return w;
    }

    static boolean exists(String path) { return new File(path).exists(); }

    static String readFile(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException ignored) {}
        return sb.toString();
    }

    static String execSysctl(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sysctl", "-n", key});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line != null ? line.trim() : "";
            }
        } catch (Exception e) { return ""; }
    }

    static String getHostName() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    static boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) { return false; }
    }

    static boolean isProcessRunning(String name) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"pgrep", name});
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    static boolean canWrite(String path) {
        File f = new File(path, ".jf_" + System.nanoTime());
        try { if (f.createNewFile()) { f.delete(); return true; } }
        catch (IOException ignored) {}
        return false;
    }
}
