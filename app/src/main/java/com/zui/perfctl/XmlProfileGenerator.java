package com.zui.perfctl;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
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
    private static final int CUSTOM_LEVEL_START = 9000;

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

        int level = CUSTOM_LEVEL_START;
        List<String> summaries = new ArrayList<>();
        for (Profile profile : profiles) {
            int currentLevel = level++;
            appendCpuLevel(perf, types.get("LittleCore"), currentLevel, LITTLE, profile);
            appendCpuLevel(perf, types.get("BigCore"), currentLevel, BIG, profile);
            appendCpuLevel(perf, types.get("TitanCore"), currentLevel, TITAN, profile);
            appendCpuLevel(perf, types.get("MegaCore"), currentLevel, MEGA, profile);
            int gpuMax = floor(GPU_ASC, profile.gpuMax);
            int gpuMin = ceil(GPU_ASC, profile.gpuMin);
            if (gpuMin > gpuMax) {
                gpuMin = gpuMax;
            }
            appendFreq(
                    perf,
                    types.get("GPU"),
                    currentLevel,
                    gpuIndex(gpuMax) + "_" + gpuIndex(gpuMin) + "_-1"
            );

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
            modes[profile.modeIndex] = replaceActiveLevels(modes[profile.modeIndex], currentLevel);
            limit.setTextContent(String.join(" ", modes));

            summaries.add(String.format(
                    Locale.US,
                    "%s/%s CPU %.3f-%.3fGHz GPU %d-%dMHz level=%d",
                    profile.pkg,
                    profile.mode,
                    profile.cpuMin / 1000000.0,
                    profile.cpuMax / 1000000.0,
                    gpuMin / 1000,
                    gpuMax / 1000,
                    currentLevel
            ));
        }

        writeDocument(game, outputGame);
        writeDocument(perf, outputPerf);
        System.out.println("profiles=" + profiles.size());
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
                if (parts.length != 6 || !validPackage(parts[0])) {
                    continue;
                }
                int modeIndex = modeIndex(parts[1]);
                int cpuMax = Integer.parseInt(parts[2]);
                int cpuMin = Integer.parseInt(parts[3]);
                int gpuMax = Integer.parseInt(parts[4]);
                int gpuMin = Integer.parseInt(parts[5]);
                if (cpuMin <= 0 || cpuMax < cpuMin || gpuMin <= 0 || gpuMax < gpuMin) {
                    continue;
                }
                Profile profile = new Profile(
                        parts[0], parts[1], modeIndex, cpuMax, cpuMin, gpuMax, gpuMin);
                result.put(parts[0] + "|" + parts[1], profile);
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

    private static void appendCpuLevel(
            Document document,
            Element type,
            int level,
            int[] available,
            Profile profile
    ) {
        int max = floor(available, profile.cpuMax);
        int min = ceil(available, profile.cpuMin);
        if (min > max) {
            min = max;
        }
        appendFreq(document, type, level, max + "_" + min + "_-1");
    }

    private static void appendFreq(
            Document document,
            Element type,
            int level,
            String value
    ) {
        Element freq = document.createElement("Freq");
        freq.setAttribute("level", Integer.toString(level));
        freq.setTextContent(value);
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

    private static String replaceActiveLevels(String block, int level) {
        String[] segments = block.split("\\|");
        int activeCount = Math.max(1, segments.length - 1);
        String ids = level + "_" + level + "_" + level + "_" + level + "_" + level;
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

    private static final class Profile {
        final String pkg;
        final String mode;
        final int modeIndex;
        final int cpuMax;
        final int cpuMin;
        final int gpuMax;
        final int gpuMin;

        Profile(
                String pkg,
                String mode,
                int modeIndex,
                int cpuMax,
                int cpuMin,
                int gpuMax,
                int gpuMin
        ) {
            this.pkg = pkg;
            this.mode = mode;
            this.modeIndex = modeIndex;
            this.cpuMax = cpuMax;
            this.cpuMin = cpuMin;
            this.gpuMax = gpuMax;
            this.gpuMin = gpuMin;
        }
    }
}
