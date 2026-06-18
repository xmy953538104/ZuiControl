package com.zui.zuicontrol;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public final class XmlProfileGenerator {
    private static final int DEFAULT_REFRESH_HZ = 120;
    private static final int MIN_REFRESH_HZ = 60;

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
        if (args.length != 5 && args.length != 6) {
            throw new IllegalArgumentException(
                    "usage: default_game default_perf profiles [refresh_profiles] output_game output_perf");
        }
        List<Profile> profiles = readProfiles(new File(args[2]));
        RefreshRules refreshRules = args.length == 6
                ? readRefreshRules(new File(args[3]))
                : RefreshRules.empty();
        int outputOffset = args.length == 6 ? 4 : 3;
        generate(
                new File(args[0]),
                new File(args[1]),
                profiles,
                refreshRules,
                new File(args[outputOffset]),
                new File(args[outputOffset + 1])
        );
    }

    private static void generate(
            File defaultGame,
            File defaultPerf,
            List<Profile> profiles,
            RefreshRules refreshRules,
            File outputGame,
            File outputPerf
    ) throws Exception {
        if (!defaultGame.isFile() || !defaultPerf.isFile()) {
            throw new IllegalStateException("baked baseline XML missing");
        }
        DocumentBuilderFactory factory = newDocumentFactory();
        Document game = factory.newDocumentBuilder().parse(defaultGame);
        Document perf = factory.newDocumentBuilder().parse(defaultPerf);

        Element defaultApp = findApp(game, "default");
        if (defaultApp == null) {
            throw new IllegalStateException("default App missing in game_policy.xml");
        }
        normalizeAppRefreshAttributes(game);
        Map<String, Element> types = gameLimitTypes(perf);
        for (String required : Arrays.asList(
                "LittleCore", "BigCore", "TitanCore", "MegaCore", "GPU")) {
            if (!types.containsKey(required)) {
                throw new IllegalStateException("GameLimitConfig type missing: " + required);
            }
        }

        List<String> summaries = new ArrayList<>();
        Map<String, Boolean> packageHasIndependent = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            Boolean current = packageHasIndependent.get(profile.pkg);
            packageHasIndependent.put(profile.pkg,
                    (current != null && current.booleanValue()) || profile.independentPolicy());
        }
        for (Map.Entry<String, Boolean> entry : packageHasIndependent.entrySet()) {
            if (!entry.getValue().booleanValue() && removeApp(game, entry.getKey())) {
                summaries.add(entry.getKey() + " policy=default app_entry=removed");
            }
        }

        for (Profile profile : profiles) {
            List<String> stagedSegments = new ArrayList<>();
            List<String> stageSummaries = new ArrayList<>();
            String firstIds = null;
            for (Stage stage : profile.stages) {
                LevelValue little = cpuLevel(LITTLE, stage.littleMax, stage.littleMin);
                LevelValue big = cpuLevel(BIG, stage.bigMax, stage.bigMin);
                LevelValue titan = cpuLevel(TITAN, stage.titanMax, stage.titanMin);
                LevelValue mega = cpuLevel(MEGA, stage.megaMax, stage.megaMin);
                LevelValue gpu = gpuLevel(stage.gpuMax, stage.gpuMin);

                upsertFreq(perf, types.get("LittleCore"), little);
                upsertFreq(perf, types.get("BigCore"), big);
                upsertFreq(perf, types.get("TitanCore"), titan);
                upsertFreq(perf, types.get("MegaCore"), mega);
                upsertFreq(perf, types.get("GPU"), gpu);

                String ids = little.level + "_" + big.level + "_" + titan.level + "_"
                        + mega.level + "_" + gpu.level;
                if (firstIds == null) {
                    firstIds = ids;
                }
                stagedSegments.add(stage.thresholdLevel + ":" + ids);
                stageSummaries.add(String.format(
                        Locale.US,
                        "%s L %.2f-%.2f B %.2f-%.2f T %.2f-%.2f M %.2f-%.2f GPU %.2f-%.2fGHz ids=%s",
                        stage.label(),
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

            if (!profile.independentPolicy()) {
                summaries.add(profile.pkg + "/" + profile.mode + " policy=default " +
                        (profile.staged ? "thermal_stages=" : "legacy=") +
                        String.join("; ", stageSummaries));
                continue;
            }

            Element app = findApp(game, profile.pkg);
            if (app == null) {
                app = (Element) defaultApp.cloneNode(true);
                app.setAttribute("name", profile.pkg);
                app.setAttribute("pkg", profile.pkg);
                defaultApp.getParentNode().appendChild(app);
            }
            FrameValue frame = frameValue(profile, refreshRules);
            upsertAttribute(app, "RefreshRateConfig", Integer.toString(frame.normalHz));
            upsertAttribute(app, "PowerSaveRefreshRateConfig", Integer.toString(frame.powerSaveHz));

            Element limit = findAttribute(app, "LimitConfig");
            if (limit == null) {
                throw new IllegalStateException("LimitConfig missing for " + profile.pkg);
            }
            String[] modes = normalize(limit.getTextContent()).split(" ");
            if (modes.length != 3) {
                throw new IllegalStateException("LimitConfig mode count invalid for " + profile.pkg);
            }
            if (profile.staged) {
                modes[profile.modeIndex] = String.join("|", stagedSegments);
            } else {
                modes[profile.modeIndex] = replaceActiveLevels(modes[profile.modeIndex], firstIds);
            }
            limit.setTextContent(String.join(" ", modes));

            summaries.add(profile.pkg + "/" + profile.mode +
                    " policy=independent frame=" + profile.framePolicy +
                    " refresh=" + frame.normalHz +
                    " powersave=" + frame.powerSaveHz + " " +
                    (profile.staged ? "thermal_stages=" : "legacy=") +
                    String.join("; ", stageSummaries));
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
        int lineNumber = 0;
        int skipped = 0;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = stripBom(line.trim());
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].trim();
                }
                if (parts.length != 4 && parts.length != 6 && parts.length != 7
                        && parts.length != 12) {
                    skipped += skipProfile(lineNumber, "invalid_field_count");
                    continue;
                }
                if (!validPackage(parts[0])) {
                    skipped += skipProfile(lineNumber, "invalid_package");
                    continue;
                }
                int modeIndex = modeIndexOrMinus(parts[1]);
                if (modeIndex < 0) {
                    skipped += skipProfile(lineNumber, "invalid_mode");
                    continue;
                }
                Profile profile;
                if (parts.length == 6 && "v4".equals(parts[2])) {
                    String policy = parts[3];
                    String framePolicy = parts[4];
                    if (!validPolicy(policy)) {
                        skipped += skipProfile(lineNumber, "invalid_policy");
                        continue;
                    }
                    if (!validFramePolicy(framePolicy)) {
                        skipped += skipProfile(lineNumber, "invalid_frame_policy");
                        continue;
                    }
                    List<Stage> stages = parseStages(parts[5]);
                    if (stages.isEmpty()) {
                        skipped += skipProfile(lineNumber, "invalid_stage_payload");
                        continue;
                    }
                    profile = new Profile(parts[0], parts[1], modeIndex, stages, true,
                            policy, framePolicy);
                } else if (parts.length == 4 || parts.length == 7) {
                    boolean v3 = parts.length == 7;
                    if (v3 && !"v3".equals(parts[2])) {
                        skipped += skipProfile(lineNumber, "invalid_profile_version");
                        continue;
                    }
                    if (!v3 && !"v2".equals(parts[2])) {
                        skipped += skipProfile(lineNumber, "invalid_profile_version");
                        continue;
                    }
                    String policy = "independent";
                    String framePolicy = "default";
                    String stagePayload = parts[3];
                    if (v3) {
                        policy = parts[3];
                        if (!validPolicy(policy)) {
                            skipped += skipProfile(lineNumber, "invalid_policy");
                            continue;
                        }
                        int refreshHz = parseIntOrMinus(parts[4]);
                        int powerSaveRefreshHz = parseIntOrMinus(parts[5]);
                        if (!validRefreshHz(refreshHz) ||
                                !validPowerSaveRefreshHz(powerSaveRefreshHz)) {
                            skipped += skipProfile(lineNumber, "invalid_refresh");
                            continue;
                        }
                        framePolicy = legacyFramePolicy(policy, refreshHz, powerSaveRefreshHz);
                        stagePayload = parts[6];
                    }
                    List<Stage> stages = parseStages(stagePayload);
                    if (stages.isEmpty()) {
                        skipped += skipProfile(lineNumber, "invalid_stage_payload");
                        continue;
                    }
                    profile = new Profile(parts[0], parts[1], modeIndex, stages, true,
                            policy, framePolicy);
                } else {
                    int[] values = parseProfileValues(parts);
                    if (values == null) {
                        skipped += skipProfile(lineNumber, "invalid_number");
                        continue;
                    }
                    Stage stage;
                    if (parts.length == 6) {
                        stage = new Stage(-1000,
                                values[0], values[1], values[0], values[1],
                                values[0], values[1], values[0], values[1],
                                values[2], values[3]);
                    } else {
                        stage = new Stage(-1000,
                                values[0], values[1], values[2], values[3],
                                values[4], values[5], values[6], values[7],
                                values[8], values[9]);
                    }
                    profile = new Profile(parts[0], parts[1], modeIndex,
                            Arrays.asList(stage), false, "independent", "default");
                }
                if (!profile.valid()) {
                    skipped += skipProfile(lineNumber, "invalid_range");
                    continue;
                }
                result.put(profile.pkg + "|" + profile.mode, profile);
            }
        }
        if (skipped > 0) {
            System.out.println("skipped_profiles=" + skipped);
        }
        return new ArrayList<>(result.values());
    }

    private static int modeIndexOrMinus(String mode) {
        switch (mode) {
            case "balanced":
                return 0;
            case "powersave":
                return 1;
            case "savage":
                return 2;
            default:
                return -1;
        }
    }

    private static int[] parseProfileValues(String[] parts) {
        int[] values = new int[parts.length - 2];
        for (int i = 2; i < parts.length; i++) {
            try {
                values[i - 2] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return values;
    }

    private static int parseIntOrMinus(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean validPolicy(String value) {
        return "independent".equals(value) || "default".equals(value);
    }

    private static boolean validRefreshHz(int value) {
        return value == 0 || value == 60 || value == 90 || value == 120
                || value == 144 || value == 165;
    }

    private static boolean validPowerSaveRefreshHz(int value) {
        return validRefreshHz(value) || value == 30;
    }

    private static boolean validFramePolicy(String value) {
        return "default".equals(value) || "fixed60".equals(value)
                || "follow_display".equals(value);
    }

    private static String legacyFramePolicy(String policy, int refreshHz, int powerSaveRefreshHz) {
        if (!"independent".equals(policy)) {
            return "default";
        }
        if (refreshHz == 60 && powerSaveRefreshHz == 60) {
            return "fixed60";
        }
        if (refreshHz > 0 && refreshHz != DEFAULT_REFRESH_HZ) {
            return "follow_display";
        }
        return "default";
    }

    private static RefreshRules readRefreshRules(File file) throws Exception {
        RefreshRules rules = RefreshRules.empty();
        if (!file.isFile()) {
            return rules;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = stripBom(line.trim());
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("version=")) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length == 5 && "default".equals(parts[0])) {
                    int hz = parseIntOrMinus(parts[2]);
                    if (validRefreshHz(hz) && hz > 0) {
                        rules.defaultHz = hz;
                    }
                } else if (parts.length == 6 && "pkg".equals(parts[0])) {
                    String pkg = parts[2].trim();
                    int hz = parseIntOrMinus(parts[3]);
                    if (validPackage(pkg) && validRefreshHz(hz) && hz > 0) {
                        rules.packageHz.put(pkg, hz);
                    }
                }
            }
        }
        return rules;
    }

    private static List<Stage> parseStages(String payload) {
        List<Stage> result = new ArrayList<>();
        String[] segments = payload.split(";", -1);
        for (String segment : segments) {
            segment = segment.trim();
            if (segment.isEmpty()) {
                continue;
            }
            String[] parts = segment.split(",", -1);
            if (parts.length != 11) {
                return new ArrayList<>();
            }
            int[] values = new int[11];
            for (int i = 0; i < parts.length; i++) {
                try {
                    values[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException ignored) {
                    return new ArrayList<>();
                }
            }
            result.add(new Stage(
                    values[0],
                    values[1],
                    values[2],
                    values[3],
                    values[4],
                    values[5],
                    values[6],
                    values[7],
                    values[8],
                    values[9],
                    values[10]));
        }
        return result;
    }

    private static int skipProfile(int lineNumber, String reason) {
        System.out.println("skip_profile line=" + lineNumber + " reason=" + reason);
        return 1;
    }

    private static boolean validPackage(String value) {
        return value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static DocumentBuilderFactory newDocumentFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setIgnoringComments(false);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            factory.setExpandEntityReferences(false);
        } catch (UnsupportedOperationException ignored) {
        }
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    private static void trySetFeature(
            DocumentBuilderFactory factory,
            String feature,
            boolean value
    ) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Android framework XML parser support varies; unsupported hardening flags are skipped.
        }
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

    private static void upsertAttribute(Element app, String name, String value) {
        Element attribute = findAttribute(app, name);
        if (attribute == null) {
            attribute = app.getOwnerDocument().createElement("Attribute");
            attribute.setAttribute("name", name);
            app.appendChild(attribute);
        }
        attribute.setTextContent(value);
    }

    private static boolean removeApp(Document document, String pkg) {
        Element app = findApp(document, pkg);
        if (app == null || "default".equals(pkg)) {
            return false;
        }
        app.getParentNode().removeChild(app);
        return true;
    }

    private static void normalizeAppRefreshAttributes(Document document) {
        NodeList apps = document.getElementsByTagName("App");
        for (int i = 0; i < apps.getLength(); i++) {
            Element app = (Element) apps.item(i);
            if ("default".equals(app.getAttribute("pkg"))) {
                upsertAttribute(app, "RefreshRateConfig", Integer.toString(DEFAULT_REFRESH_HZ));
                upsertAttribute(app, "PowerSaveRefreshRateConfig", Integer.toString(MIN_REFRESH_HZ));
                continue;
            }
            Element refresh = findAttribute(app, "RefreshRateConfig");
            if (refresh != null && parseIntOrMinus(normalize(refresh.getTextContent())) < MIN_REFRESH_HZ) {
                refresh.setTextContent(Integer.toString(MIN_REFRESH_HZ));
            }
            Element powerSave = findAttribute(app, "PowerSaveRefreshRateConfig");
            if (powerSave == null ||
                    parseIntOrMinus(normalize(powerSave.getTextContent())) < MIN_REFRESH_HZ) {
                upsertAttribute(app, "PowerSaveRefreshRateConfig", Integer.toString(MIN_REFRESH_HZ));
            }
        }
    }

    private static FrameValue frameValue(Profile profile, RefreshRules refreshRules) {
        if ("fixed60".equals(profile.framePolicy)) {
            return new FrameValue(MIN_REFRESH_HZ, MIN_REFRESH_HZ);
        }
        if ("follow_display".equals(profile.framePolicy)) {
            int normalHz = refreshRules.refreshFor(profile.pkg);
            return new FrameValue(normalHz, MIN_REFRESH_HZ);
        }
        return new FrameValue(DEFAULT_REFRESH_HZ, MIN_REFRESH_HZ);
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

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1).trim() : value;
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

    private static final class FrameValue {
        final int normalHz;
        final int powerSaveHz;

        FrameValue(int normalHz, int powerSaveHz) {
            this.normalHz = normalHz;
            this.powerSaveHz = powerSaveHz;
        }
    }

    private static final class RefreshRules {
        int defaultHz = DEFAULT_REFRESH_HZ;
        final Map<String, Integer> packageHz = new LinkedHashMap<>();

        static RefreshRules empty() {
            return new RefreshRules();
        }

        int refreshFor(String pkg) {
            Integer value = packageHz.get(pkg);
            if (value != null && validRefreshHz(value) && value > 0) {
                return value.intValue();
            }
            return validRefreshHz(defaultHz) && defaultHz > 0 ? defaultHz : DEFAULT_REFRESH_HZ;
        }
    }

    private static final class Profile {
        final String pkg;
        final String mode;
        final int modeIndex;
        final List<Stage> stages;
        final boolean staged;
        final String policy;
        final String framePolicy;

        Profile(
                String pkg,
                String mode,
                int modeIndex,
                List<Stage> stages,
                boolean staged,
                String policy,
                String framePolicy
        ) {
            this.pkg = pkg;
            this.mode = mode;
            this.modeIndex = modeIndex;
            this.stages = stages;
            this.staged = staged;
            this.policy = policy;
            this.framePolicy = framePolicy;
        }

        boolean valid() {
            if (stages.isEmpty()) {
                return false;
            }
            if (!validPolicy(policy) || !validFramePolicy(framePolicy)) {
                return false;
            }
            if (stages.get(0).thresholdLevel != -1000) {
                return false;
            }
            int previousThermalLevel = 0;
            for (int i = 0; i < stages.size(); i++) {
                Stage stage = stages.get(i);
                if (!stage.valid()) {
                    return false;
                }
                if (i > 0) {
                    if (stage.thresholdLevel <= previousThermalLevel) {
                        return false;
                    }
                    previousThermalLevel = stage.thresholdLevel;
                }
            }
            return true;
        }

        boolean independentPolicy() {
            return "independent".equals(policy);
        }
    }

    private static final class Stage {
        final int thresholdLevel;
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

        Stage(
                int thresholdLevel,
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
            this.thresholdLevel = thresholdLevel;
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
            return (thresholdLevel == -1000 || thresholdLevel >= 1 && thresholdLevel <= 16)
                    && littleMin > 0 && littleMax >= littleMin
                    && bigMin > 0 && bigMax >= bigMin
                    && titanMin > 0 && titanMax >= titanMin
                    && megaMin > 0 && megaMax >= megaMin
                    && gpuMin > 0 && gpuMax >= gpuMin;
        }

        String label() {
            if (thresholdLevel == -1000) {
                return "default";
            }
            return (thresholdLevel + 34) + "C";
        }
    }
}
