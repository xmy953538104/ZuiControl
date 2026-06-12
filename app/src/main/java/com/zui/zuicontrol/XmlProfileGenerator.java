package com.zui.zuicontrol;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public final class XmlProfileGenerator {
    private static final int[] LITTLE = {
            364800, 460800, 556800, 672000, 787200, 902400, 1017600, 1132800,
            1248000, 1344000, 1459200, 1574400, 1689600, 1804800, 1920000,
            2035200, 2150400, 2265600
    };
    private static final int[] BIG = {
            499200, 614400, 729600, 844800, 960000, 1075200, 1190400, 1286400,
            1401600, 1497600, 1612800, 1708800, 1824000, 1920000, 2035200,
            2131200, 2188800, 2246400, 2323200, 2380800, 2438400, 2515200,
            2572800, 2630400, 2707200, 2764800, 2841600, 2899200, 2956800,
            3014400, 3072000, 3148800
    };
    private static final int[] TITAN = {
            499200, 614400, 729600, 844800, 960000, 1075200, 1190400, 1286400,
            1401600, 1497600, 1612800, 1708800, 1824000, 1920000, 2035200,
            2131200, 2188800, 2246400, 2323200, 2380800, 2438400, 2515200,
            2572800, 2630400, 2707200, 2764800, 2841600, 2899200, 2956800
    };
    private static final int[] MEGA = {
            480000, 576000, 672000, 787200, 902400, 1017600, 1132800, 1248000,
            1363200, 1478400, 1593600, 1708800, 1824000, 1939200, 2035200,
            2112000, 2169600, 2246400, 2304000, 2380800, 2438400, 2496000,
            2553600, 2630400, 2688000, 2745600, 2803200, 2880000, 2937600,
            2995200, 3052800, 3110400, 3187200, 3244800, 3302400
    };
    private static final int[] GPU_ASC = {
            231000, 310000, 366000, 422000, 500000, 578000,
            629000, 680000, 720000, 770000, 834000, 903000
    };
    private static final int[] GPU_DESC = {
            903000, 834000, 770000, 720000, 680000, 629000,
            578000, 500000, 422000, 366000, 310000, 231000
    };

    private XmlProfileGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "usage: default_game default_perf profiles output_game output_perf");
        }
        List<Profile> profiles = readProfiles(new File(args[2]));
        generate(
                new File(args[0]),
                new File(args[1]),
                profiles,
                new File(args[3]),
                new File(args[4])
        );
    }

    private static void generate(
            File defaultGame,
            File defaultPerf,
            List<Profile> profiles,
            File outputGame,
            File outputPerf
    ) throws Exception {
        if (!defaultGame.isFile() || !defaultPerf.isFile()) {
            throw new IllegalStateException("baked baseline XML missing");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setIgnoringComments(false);
        Document game = factory.newDocumentBuilder().parse(defaultGame);
        Document perf = factory.newDocumentBuilder().parse(defaultPerf);

        Element defaultApp = findApp(game, "default");
        if (defaultApp == null) {
            throw new IllegalStateException("default App missing in game_policy.xml");
        }
        Map<String, Element> types = gameLimitTypes(perf);
        for (String required : Arrays.asList(
                "LittleCore", "BigCore", "TitanCore", "MegaCore", "GPU")) {
            if (!types.containsKey(required)) {
                throw new IllegalStateException("GameLimitConfig type missing: " + required);
            }
        }

        List<String> summaries = new ArrayList<>();
        for (Profile profile : profiles) {
            LevelValue little = cpuLevel(LITTLE, profile.littleMax, profile.littleMin);
            LevelValue big = cpuLevel(BIG, profile.bigMax, profile.bigMin);
            LevelValue titan = cpuLevel(TITAN, profile.titanMax, profile.titanMin);
            LevelValue mega = cpuLevel(MEGA, profile.megaMax, profile.megaMin);
            LevelValue gpu = gpuLevel(profile.gpuMax, profile.gpuMin);

            upsertFreq(perf, types.get("LittleCore"), little);
            upsertFreq(perf, types.get("BigCore"), big);
            upsertFreq(perf, types.get("TitanCore"), titan);
            upsertFreq(perf, types.get("MegaCore"), mega);
            upsertFreq(perf, types.get("GPU"), gpu);

            Element app = findApp(game, profile.pkg);
            if (app == null) {
                app = (Element) defaultApp.cloneNode(true);
                app.setAttribute("name", profile.pkg);
                app.setAttribute("pkg", profile.pkg);
                defaultApp.getParentNode().appendChild(app);
            }
            Element limit = findAttribute(app, "LimitConfig");
            if (limit == null) {
                throw new IllegalStateException("LimitConfig missing for " + profile.pkg);
            }
            String[] modes = normalize(limit.getTextContent()).split(" ");
            if (modes.length != 3) {
                throw new IllegalStateException("LimitConfig mode count invalid for " + profile.pkg);
            }
            String ids = little.level + "_" + big.level + "_" + titan.level + "_"
                    + mega.level + "_" + gpu.level;
            modes[profile.modeIndex] = replaceActiveLevels(modes[profile.modeIndex], ids);
            limit.setTextContent(String.join(" ", modes));

            summaries.add(String.format(
                    Locale.US,
                    "%s/%s L %.2f-%.2f B %.2f-%.2f T %.2f-%.2f M %.2f-%.2f GPU %.2f-%.2fGHz ids=%s",
                    profile.pkg,
                    profile.mode,
                    little.min / 1000000.0,
                    little.max / 1000000.0,
                    big.min / 1000000.0,
                    big.max / 1000000.0,
                    titan.min / 1000000.0,
                    titan.max / 1000000.0,
                    mega.min / 1000000.0,
                    mega.max / 1000000.0,
                    gpu.min / 1000000.0,
                    gpu.max / 1000000.0,
                    ids
            ));
        }

        writeDocument(game, outputGame);
        writeDocument(perf, outputPerf);
        System.out.println("profiles=" + profiles.size());
        System.out.println("baseline_game_sha256=" + sha256(defaultGame));
        System.out.println("baseline_performance_sha256=" + sha256(defaultPerf));
        System.out.println("output_game_sha256=" + sha256(outputGame));
        System.out.println("output_performance_sha256=" + sha256(outputPerf));
        for (String summary : summaries) {
            System.out.println(summary);
        }
    }

    private static List<Profile> readProfiles(File file) throws Exception {
        LinkedHashMap<String, Profile> result = new LinkedHashMap<>();
        if (!file.isFile()) {
            return new ArrayList<>();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (!validPackage(parts[0])) {
                    continue;
                }
                int modeIndex = modeIndex(parts[1]);
                Profile profile;
                if (parts.length == 6) {
                    int cpuMax = Integer.parseInt(parts[2]);
                    int cpuMin = Integer.parseInt(parts[3]);
                    int gpuMax = Integer.parseInt(parts[4]);
                    int gpuMin = Integer.parseInt(parts[5]);
                    profile = new Profile(parts[0], parts[1], modeIndex,
                            cpuMax, cpuMin, cpuMax, cpuMin, cpuMax, cpuMin, cpuMax, cpuMin,
                            gpuMax, gpuMin);
                } else if (parts.length == 12) {
                    profile = new Profile(parts[0], parts[1], modeIndex,
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4]),
                            Integer.parseInt(parts[5]),
                            Integer.parseInt(parts[6]),
                            Integer.parseInt(parts[7]),
                            Integer.parseInt(parts[8]),
                            Integer.parseInt(parts[9]),
                            Integer.parseInt(parts[10]),
                            Integer.parseInt(parts[11]));
                } else {
                    continue;
                }
                if (profile.valid()) {
                    result.put(profile.pkg + "|" + profile.mode, profile);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private static int modeIndex(String mode) {
        switch (mode) {
            case "balanced":
                return 0;
            case "powersave":
                return 1;
            case "savage":
                return 2;
            default:
                throw new IllegalArgumentException("invalid mode: " + mode);
        }
    }

    private static boolean validPackage(String value) {
        return value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static LevelValue cpuLevel(int[] available, int requestedMax, int requestedMin) {
        int max = floor(available, requestedMax);
        int min = ceil(available, requestedMin);
        if (min > max) {
            min = max;
        }
        int maxIndex = indexOf(available, max) + 1;
        int minIndex = indexOf(available, min) + 1;
        return new LevelValue(minIndex * 100 + maxIndex, max + "_" + min + "_-1", max, min);
    }

    private static LevelValue gpuLevel(int requestedMax, int requestedMin) {
        int max = floor(GPU_ASC, requestedMax);
        int min = ceil(GPU_ASC, requestedMin);
        if (min > max) {
            min = max;
        }
        int maxIndex = gpuIndex(max) + 1;
        int minIndex = gpuIndex(min) + 1;
        return new LevelValue(
                minIndex * 100 + maxIndex,
                (maxIndex - 1) + "_" + (minIndex - 1) + "_-1",
                max,
                min
        );
    }

    private static void upsertFreq(
            Document document,
            Element type,
            LevelValue level
    ) {
        NodeList children = type.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) {
                Element freq = (Element) node;
                if ("Freq".equals(freq.getTagName())
                        && Integer.toString(level.level).equals(freq.getAttribute("level"))) {
                    freq.setTextContent(level.value);
                    return;
                }
            }
        }
        Element freq = document.createElement("Freq");
        freq.setAttribute("level", Integer.toString(level.level));
        freq.setTextContent(level.value);
        type.appendChild(freq);
    }

    private static int floor(int[] values, int requested) {
        int selected = values[0];
        for (int value : values) {
            if (value <= requested) {
                selected = value;
            } else {
                break;
            }
        }
        return selected;
    }

    private static int ceil(int[] values, int requested) {
        for (int value : values) {
            if (value >= requested) {
                return value;
            }
        }
        return values[values.length - 1];
    }

    private static int indexOf(int[] values, int frequency) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == frequency) {
                return i;
            }
        }
        throw new IllegalArgumentException("unsupported frequency: " + frequency);
    }

    private static int gpuIndex(int frequency) {
        for (int i = 0; i < GPU_DESC.length; i++) {
            if (GPU_DESC[i] == frequency) {
                return i;
            }
        }
        throw new IllegalArgumentException("unsupported GPU frequency: " + frequency);
    }

    private static Element findApp(Document document, String pkg) {
        NodeList apps = document.getElementsByTagName("App");
        for (int i = 0; i < apps.getLength(); i++) {
            Element app = (Element) apps.item(i);
            if (pkg.equals(app.getAttribute("pkg"))) {
                return app;
            }
        }
        return null;
    }

    private static Element findAttribute(Element app, String name) {
        NodeList children = app.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) {
                Element element = (Element) node;
                if ("Attribute".equals(element.getTagName())
                        && name.equals(element.getAttribute("name"))) {
                    return element;
                }
            }
        }
        return null;
    }

    private static Map<String, Element> gameLimitTypes(Document document) {
        Map<String, Element> result = new LinkedHashMap<>();
        NodeList configs = document.getElementsByTagName("GameLimitConfig");
        if (configs.getLength() == 0) {
            return result;
        }
        NodeList children = configs.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) {
                Element element = (Element) node;
                if ("Type".equals(element.getTagName())) {
                    result.put(element.getAttribute("name"), element);
                }
            }
        }
        return result;
    }

    private static String replaceActiveLevels(String block, String ids) {
        String[] segments = block.split("\\|");
        int activeCount = Math.max(1, segments.length - 1);
        for (int i = 0; i < activeCount; i++) {
            int separator = segments[i].indexOf(':');
            String threshold = separator >= 0 ? segments[i].substring(0, separator) : "-1000";
            segments[i] = threshold + ":" + ids;
        }
        return String.join("|", segments);
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static void writeDocument(Document document, File output) throws Exception {
        File parent = output.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File temp = new File(output.getPath() + ".tmp");
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(temp));
        try {
            Files.move(
                    temp.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temp.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file.toPath())) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte item : digest.digest()) {
            builder.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return builder.toString();
    }

    private static final class LevelValue {
        final int level;
        final String value;
        final int max;
        final int min;

        LevelValue(int level, String value, int max, int min) {
            this.level = level;
            this.value = value;
            this.max = max;
            this.min = min;
        }
    }

    private static final class Profile {
        final String pkg;
        final String mode;
        final int modeIndex;
        final int littleMax;
        final int littleMin;
        final int bigMax;
        final int bigMin;
        final int titanMax;
        final int titanMin;
        final int megaMax;
        final int megaMin;
        final int gpuMax;
        final int gpuMin;

        Profile(
                String pkg,
                String mode,
                int modeIndex,
                int littleMax,
                int littleMin,
                int bigMax,
                int bigMin,
                int titanMax,
                int titanMin,
                int megaMax,
                int megaMin,
                int gpuMax,
                int gpuMin
        ) {
            this.pkg = pkg;
            this.mode = mode;
            this.modeIndex = modeIndex;
            this.littleMax = littleMax;
            this.littleMin = littleMin;
            this.bigMax = bigMax;
            this.bigMin = bigMin;
            this.titanMax = titanMax;
            this.titanMin = titanMin;
            this.megaMax = megaMax;
            this.megaMin = megaMin;
            this.gpuMax = gpuMax;
            this.gpuMin = gpuMin;
        }

        boolean valid() {
            return littleMin > 0 && littleMax >= littleMin
                    && bigMin > 0 && bigMax >= bigMin
                    && titanMin > 0 && titanMax >= titanMin
                    && megaMin > 0 && megaMax >= megaMin
                    && gpuMin > 0 && gpuMax >= gpuMin;
        }
    }
}
