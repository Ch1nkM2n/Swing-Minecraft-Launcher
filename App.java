
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

public class App {
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,16}");

    private final Map<String, String> verifiedFileCache = new HashMap<>();
    private final JFrame frame = new JFrame("Minecraft Launcher");
    private final JComboBox<String> versions = new JComboBox<>();
    private final JComboBox<LauncherProfile> profiles = new JComboBox<>();
    private final JComboBox<String> loaderType = new JComboBox<>(new String[] {"vanilla", "fabric"});
    private final JComboBox<String> loaderVersions = new JComboBox<>();
    private final JTextField username = new JTextField("Player", 14);
    private final JTextField gameDirectory = new JTextField(defaultGameDirectory().toString(), 28);
    private final JTextField javaPath = new JTextField(28);
    private final JTextField jvmArguments = new JTextField(28);
    private final JSpinner maxMemory = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));
    private final JTextArea output = new JTextArea();
    private final JButton launchButton = new JButton("安装并启动");
    private final List<LauncherProfile> profileList = new ArrayList<>();
    private LauncherProfile activeProfile;
    private boolean changingProfile;
    private boolean loadingVersions;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new App().show());
    }

    private void show() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 520));
        frame.setLayout(new BorderLayout(8, 8));

        JPanel profilePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        profilePanel.add(new JLabel("游戏实例"));
        profiles.setPreferredSize(new Dimension(220, 26));
        profilePanel.add(profiles);
        JButton addProfileButton = new JButton("新建实例");
        JButton removeProfileButton = new JButton("删除实例");
        profilePanel.add(addProfileButton);
        profilePanel.add(removeProfileButton);
        frame.add(profilePanel, BorderLayout.NORTH);

        JPanel settings = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
        settings.add(new JLabel("Minecraft版本"));
        versions.setPreferredSize(new Dimension(145, 26));
        settings.add(versions);
        settings.add(new JLabel("加载器类型"));
        settings.add(loaderType);
        settings.add(new JLabel("Fabric版本"));
        settings.add(loaderVersions);
        settings.add(new JLabel("离线用户名"));
        settings.add(username);
        settings.add(new JLabel("游戏目录"));
        JPanel directoryField = new JPanel(new BorderLayout(4, 0));
        directoryField.add(gameDirectory, BorderLayout.CENTER);
        JButton browseButton = new JButton("游戏目录...");
        directoryField.add(browseButton, BorderLayout.EAST);
        settings.add(directoryField);
        settings.add(new JLabel("java可执行文件，留空使用启动时指定的的java"));
        JPanel javaField = new JPanel(new BorderLayout(4, 0));
        javaField.add(javaPath, BorderLayout.CENTER);
        JButton browseJavaButton = new JButton("选择");
        javaField.add(browseJavaButton, BorderLayout.EAST);
        settings.add(javaField);
        settings.add(new JLabel("最大内存 (MB)"));
        settings.add(maxMemory);
        settings.add(new JLabel("额外JVM参数"));
        settings.add(jvmArguments);

        browseButton.addActionListener(event -> chooseDirectory());
        browseJavaButton.addActionListener(event -> chooseJava());
        addProfileButton.addActionListener(event -> createProfile());
        removeProfileButton.addActionListener(event -> removeProfile());
        profiles.addActionListener(event -> changeProfile());
        loaderType.addActionListener(event -> {
            if (!changingProfile && "fabric".equals(loaderType.getSelectedItem())) loadFabricVersions();
        });
        versions.addActionListener(event -> {
            if (!changingProfile && !loadingVersions && "fabric".equals(loaderType.getSelectedItem())) loadFabricVersions();
        });

        output.setEditable(false);
        output.setLineWrap(true);
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(settings, BorderLayout.NORTH);
        center.add(new JScrollPane(output), BorderLayout.CENTER);
        frame.add(center, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshButton = new JButton("刷新版本");
        actions.add(refreshButton);
        actions.add(launchButton);
        frame.add(actions, BorderLayout.SOUTH);
        refreshButton.addActionListener(event -> loadVersions());
        launchButton.addActionListener(event -> installAndLaunch());

        loadProfiles();
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        loadVersions();
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(Path.of(gameDirectory.getText()).toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            gameDirectory.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseJava() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            javaPath.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void loadProfiles() {
        try {
            profileList.clear();
            profileList.addAll(ProfileStore.load());
            if (profileList.isEmpty()) {
                profileList.add(new LauncherProfile("默认实例", "", "Player",
                        defaultGameDirectory().resolve("instances").resolve("default").toString()));
            }
            changingProfile = true;
            profiles.removeAllItems();
            profileList.forEach(profiles::addItem);
            profiles.setSelectedIndex(0);
            changingProfile = false;
            activeProfile = (LauncherProfile) profiles.getSelectedItem();
            showProfile(activeProfile);
            saveProfiles();
        } catch (Exception exception) {
            log("读取实例配置失败: " + exception.getMessage());
        }
    }

    private void createProfile() {
        String name = JOptionPane.showInputDialog(frame, "实例名称", "新建实例", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        String normalizedName = name.trim();
        if (profileList.stream().anyMatch(profile -> profile.name.equalsIgnoreCase(normalizedName))) {
            showError("已经有同名实例了，倒是用不同的名称啊");
            return;
        }
        LauncherProfile profile = new LauncherProfile(normalizedName,
                versions.getSelectedItem() == null ? "" : versions.getSelectedItem().toString(),
                username.getText().trim(), defaultGameDirectory().resolve("instances")
                        .resolve(java.util.UUID.randomUUID().toString()).toString());
        profileList.add(profile);
        changingProfile = true;
        profiles.addItem(profile);
        profiles.setSelectedItem(profile);
        changingProfile = false;
        activeProfile = profile;
        showProfile(profile);
        saveProfiles();
    }

    private void removeProfile() {
        LauncherProfile selected = (LauncherProfile) profiles.getSelectedItem();
        if (selected == null) return;
        int answer = JOptionPane.showConfirmDialog(frame,
                "仅从启动器列表移除实例 不会删除文件 是否继续", "删除实例", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        profileList.remove(selected);
        if (profileList.isEmpty()) {
            profileList.add(new LauncherProfile("默认", "", "Player_Name",
                    defaultGameDirectory().resolve("instances").resolve("default").toString()));
        }
        changingProfile = true;
        profiles.removeAllItems();
        profileList.forEach(profiles::addItem);
        profiles.setSelectedIndex(0);
        changingProfile = false;
        activeProfile = (LauncherProfile) profiles.getSelectedItem();
        showProfile(activeProfile);
        saveProfiles();
    }

    private void changeProfile() {
        if (changingProfile) return;
        saveActiveProfileFields();
        activeProfile = (LauncherProfile) profiles.getSelectedItem();
        showProfile(activeProfile);
    }

    private void showProfile(LauncherProfile profile) {
        if (profile == null) return;
        changingProfile = true;
        if (profile.version != null && !profile.version.isBlank()) versions.setSelectedItem(profile.version);
        username.setText(profile.username == null ? "Player" : profile.username);
        gameDirectory.setText(profile.gameDirectory == null ? "" : profile.gameDirectory);
        javaPath.setText(profile.javaPath == null ? "" : profile.javaPath);
        jvmArguments.setText(profile.jvmArguments == null ? "" : profile.jvmArguments);
        maxMemory.setValue(Math.max(512, Math.min(65536, profile.maxMemoryMb)));
        loaderType.setSelectedItem(profile.loader == null ? "vanilla" : profile.loader);
        if (profile.loaderVersion != null && !profile.loaderVersion.isBlank()) {
            loaderVersions.setSelectedItem(profile.loaderVersion);
        }
        changingProfile = false;
        if ("fabric".equals(profile.loader)) loadFabricVersions();
    }

    private void saveActiveProfileFields() {
        if (activeProfile == null || changingProfile) return;
        Object selectedVersion = versions.getSelectedItem();
        activeProfile.version = selectedVersion == null ? "" : selectedVersion.toString();
        activeProfile.username = username.getText().trim();
        activeProfile.gameDirectory = gameDirectory.getText().trim();
        activeProfile.javaPath = javaPath.getText().trim();
        activeProfile.jvmArguments = jvmArguments.getText().trim();
        activeProfile.maxMemoryMb = (Integer) maxMemory.getValue();
        activeProfile.loader = (String) loaderType.getSelectedItem();
        Object selectedLoaderVersion = loaderVersions.getSelectedItem();
        activeProfile.loaderVersion = selectedLoaderVersion == null ? "" : selectedLoaderVersion.toString();
    }

    private void loadFabricVersions() {
        Object selectedGameVersion = versions.getSelectedItem();
        if (selectedGameVersion == null) return;
        String gameVersion = selectedGameVersion.toString();
        WORKER.submit(() -> {
            try {
                JsonArray entries = getJsonArray("https://meta.fabricmc.net/v2/versions/loader/" + gameVersion);
                List<String> stableVersions = new ArrayList<>();
                for (JsonElement entry : entries) {
                    JsonObject item = entry.getAsJsonObject();
                    if (item.getAsJsonObject("loader").get("stable").getAsBoolean()) {
                        stableVersions.add(item.getAsJsonObject("loader").get("version").getAsString());
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    String selected = activeProfile == null ? "" : activeProfile.loaderVersion;
                    loaderVersions.removeAllItems();
                    stableVersions.forEach(loaderVersions::addItem);
                    if (stableVersions.contains(selected)) loaderVersions.setSelectedItem(selected);
                    else if (loaderVersions.getItemCount() > 0) loaderVersions.setSelectedIndex(0);
                    if (activeProfile != null && "fabric".equals(activeProfile.loader)) {
                        activeProfile.loaderVersion = loaderVersions.getSelectedItem() == null
                                ? "" : loaderVersions.getSelectedItem().toString();
                        saveProfiles();
                    }
                    log("fabric稳定版已加载：" + stableVersions.size() + " 个。");
                });
            } catch (Exception exception) {
                log("读取fabric版本失败: " + exception.getMessage());
            }
        });
    }

    private void saveProfiles() {
        saveActiveProfileFields();
        try {
            ProfileStore.save(profileList);
        } catch (IOException exception) {
            log("保存实例配置失败: " + exception.getMessage());
        }
    }

    private void loadVersions() {
        setBusy(true);
        log("正在读取minecraft版本列表");
        WORKER.submit(() -> {
            try {
                JsonObject manifest = getJson(MANIFEST_URL);
                List<String> ids = new ArrayList<>();
                for (JsonElement element : manifest.getAsJsonArray("versions")) {
                    JsonObject version = element.getAsJsonObject();
                    if ("release".equals(version.get("type").getAsString())) {
                        ids.add(version.get("id").getAsString());
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    changingProfile = true;
                    loadingVersions = true;
                    versions.removeAllItems();
                    ids.forEach(versions::addItem);
                    if (activeProfile != null && ids.contains(activeProfile.version)) {
                        versions.setSelectedItem(activeProfile.version);
                    }
                    loadingVersions = false;
                    changingProfile = false;
                    saveActiveProfileFields();
                    saveProfiles();
                    log("已加载 " + ids.size() + " 个正式版");
                    if ("fabric".equals(loaderType.getSelectedItem())) loadFabricVersions();
                    setBusy(false);
                });
            } catch (Exception exception) {
                log("读取版本列表失败: " + exception.getMessage());
                setBusy(false);
            }
        });
    }

    private void installAndLaunch() {
        saveActiveProfileFields();
        saveProfiles();
        LauncherProfile profile = activeProfile;
        String versionId = (String) versions.getSelectedItem();
        String playerName = profile == null ? "" : profile.username;
        if (versionId == null) {
            showError("请先加载并选择一个minecraft版本");
            return;
        }
        if (!SAFE_NAME.matcher(playerName).matches()) {
            showError("用户名须为 1-16 位英文 // 包括数字或下划线");
            return;
        }
        if (profile == null || profile.gameDirectory == null || profile.gameDirectory.isBlank()) {
            showError("请为此实例设置游戏目录");
            return;
        }
        Path root = Path.of(profile.gameDirectory).toAbsolutePath().normalize();
        setBusy(true);
        log("开始准备实例: " + profile.name + "的Minecraft " + versionId + "...");
        WORKER.submit(() -> {
            try {
                launch(versionId, profile, root);
            } catch (Exception exception) {
                log("启动失败: " + exception.getMessage());
            } finally {
                setBusy(false);
            }
        });
    }

    private void launch(String versionId, LauncherProfile profile, Path root) throws Exception {
        String playerName = profile.username;
        Path sharedRoot = defaultGameDirectory();
        Files.createDirectories(root);
        Files.createDirectories(sharedRoot);
        loadDownloadCache(sharedRoot);
        JsonObject manifest = getJson(MANIFEST_URL);
        JsonObject selected = null;
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject item = element.getAsJsonObject();
            if (versionId.equals(item.get("id").getAsString())) {
                selected = item;
                break;
            }
        }
        if (selected == null) throw new IOException("版本已不在Ojang版本清单中");

        JsonObject baseMetadata = getJson(selected.get("url").getAsString());
        JsonObject metadata = baseMetadata;
        String launchVersionId = versionId;
        if ("fabric".equals(profile.loader)) {
            String fabricVersion = profile.loaderVersion;
            if (fabricVersion == null || fabricVersion.isBlank()) fabricVersion = latestStableFabricVersion(versionId);
            JsonObject fabricProfile = getJson("https://meta.fabricmc.net/v2/versions/loader/"
                + versionId + "/" + fabricVersion + "/profile/json");
            metadata = mergeFabricProfile(baseMetadata, fabricProfile);
            launchVersionId = fabricProfile.get("id").getAsString();
            log("正在准备fabric " + fabricVersion + "...");
        }

        Path baseVersionDirectory = sharedRoot.resolve("versions").resolve(versionId);
        Path clientJar = baseVersionDirectory.resolve(versionId + ".jar");
        Files.createDirectories(baseVersionDirectory);
        Files.writeString(baseVersionDirectory.resolve(versionId + ".json"), baseMetadata.toString());
        JsonObject client = baseMetadata.getAsJsonObject("downloads").getAsJsonObject("client");
        download(client.get("url").getAsString(), clientJar, stringValue(client, "sha1"));

        Path versionDirectory = sharedRoot.resolve("versions").resolve(launchVersionId);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(launchVersionId + ".json"), metadata.toString());
        Path nativesDirectory = sharedRoot.resolve("versions").resolve(launchVersionId + "-natives");
        Files.createDirectories(nativesDirectory);
        List<Path> classpath = installLibraries(metadata, sharedRoot, nativesDirectory);
        installAssets(metadata, sharedRoot);
        String assetIndexId = metadata.has("assetIndex")
            ? metadata.getAsJsonObject("assetIndex").get("id").getAsString()
            : stringValue(metadata, "assets");
        String loggingPath = installLoggingConfig(metadata, sharedRoot);
        saveDownloadCache(sharedRoot);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable(profile.javaPath));
        Map<String, String> substitutions = substitutions(launchVersionId, playerName, root,
            sharedRoot.resolve("assets"), nativesDirectory,
            clientJar, assetIndexId == null ? "legacy" : assetIndexId, loggingPath);
        addJvmArguments(command, metadata, substitutions, classpath, clientJar, profile);
        command.add(metadata.get("mainClass").getAsString());
        addGameArguments(command, metadata, substitutions);
        log("依赖好了 正在启动...");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) log(line);
            } catch (IOException exception) {
                log("读取游戏输出失败: " + exception.getMessage());
            }
        }, "minecraft-output");
        outputReader.setDaemon(true);
        outputReader.start();
        log("游戏启动了");
    }

    private String latestStableFabricVersion(String gameVersion) throws Exception {
        JsonArray entries = getJsonArray("https://meta.fabricmc.net/v2/versions/loader/" + gameVersion);
        for (JsonElement entry : entries) {
            JsonObject loader = entry.getAsJsonObject().getAsJsonObject("loader");
            if (loader.get("stable").getAsBoolean()) return loader.get("version").getAsString();
        }
        throw new IOException("该minecraft版本没有可用的fabric稳定版");
    }

    private JsonObject mergeFabricProfile(JsonObject baseMetadata, JsonObject fabricProfile) {
        JsonObject merged = baseMetadata.deepCopy();
        merged.addProperty("id", fabricProfile.get("id").getAsString());
        merged.addProperty("mainClass", fabricProfile.get("mainClass").getAsString());

        JsonArray libraries = merged.has("libraries") ? merged.getAsJsonArray("libraries") : new JsonArray();
        if (fabricProfile.has("libraries")) {
            for (JsonElement library : fabricProfile.getAsJsonArray("libraries")) libraries.add(library.deepCopy());
        }
        merged.add("libraries", libraries);

        JsonObject arguments = merged.has("arguments")
                ? merged.getAsJsonObject("arguments") : new JsonObject();
        JsonObject fabricArguments = fabricProfile.has("arguments")
                ? fabricProfile.getAsJsonObject("arguments") : new JsonObject();
        if (merged.has("minecraftArguments") && !arguments.has("game")) {
            JsonArray legacyArguments = new JsonArray();
            for (String value : merged.get("minecraftArguments").getAsString().split(" ")) {
                legacyArguments.add(value);
            }
            arguments.add("game", legacyArguments);
            merged.remove("minecraftArguments");
        }
        for (String kind : List.of("jvm", "game")) {
            JsonArray combined = arguments.has(kind) ? arguments.getAsJsonArray(kind) : new JsonArray();
            if (fabricArguments.has(kind)) {
                for (JsonElement value : fabricArguments.getAsJsonArray(kind)) combined.add(value.deepCopy());
            }
            if (combined.size() > 0) arguments.add(kind, combined);
        }
        merged.add("arguments", arguments);
        return merged;
    }

    private List<Path> installLibraries(JsonObject metadata, Path root, Path nativesDirectory) throws Exception {
        List<Path> classpath = new ArrayList<>();
        JsonArray libraries = metadata.getAsJsonArray("libraries");
        if (libraries == null) return classpath;
        for (JsonElement element : libraries) {
            JsonObject library = element.getAsJsonObject();
            if (!allowedByRules(library)) continue;
            JsonObject downloads = library.has("downloads") ? library.getAsJsonObject("downloads") : null;

                JsonObject artifact = downloads != null && downloads.has("artifact")
                    ? downloads.getAsJsonObject("artifact") : null;
                String artifactPath = artifact == null ? mavenPath(library) : artifact.get("path").getAsString();
                String artifactUrl = artifact == null ? mavenUrl(library, artifactPath)
                    : artifact.get("url").getAsString();
                String artifactSha1 = artifact == null ? stringValue(library, "sha1") : stringValue(artifact, "sha1");
                if (artifactPath != null && artifactUrl != null) {
                Path file = root.resolve("libraries").resolve(artifactPath).normalize();
                ensureInside(root.resolve("libraries"), file);
                download(artifactUrl, file, artifactSha1);
                classpath.add(file);
            }

            JsonObject natives = library.has("natives") ? library.getAsJsonObject("natives") : null;
                JsonObject classifiers = downloads != null && downloads.has("classifiers")
                    ? downloads.getAsJsonObject("classifiers") : null;
            if (natives != null && classifiers != null) {
                String classifier = stringValue(natives, operatingSystem());
                if (classifier != null) {
                    classifier = classifier.replace("${arch}", System.getProperty("os.arch").contains("64") ? "64" : "32");
                    if (classifiers.has(classifier)) {
                        JsonObject nativeFile = classifiers.getAsJsonObject(classifier);
                        Path file = root.resolve("libraries").resolve(nativeFile.get("path").getAsString()).normalize();
                        ensureInside(root.resolve("libraries"), file);
                        download(nativeFile.get("url").getAsString(), file, stringValue(nativeFile, "sha1"));
                        extractNatives(file, nativesDirectory);
                    }
                }
            }
        }
        return classpath;
    }

    private String mavenPath(JsonObject library) {
        if (!library.has("name")) return null;
        String[] coordinateAndExtension = library.get("name").getAsString().split("@", 2);
        String[] parts = coordinateAndExtension[0].split(":");
        if (parts.length < 3 || parts.length > 4) return null;
        String extension = coordinateAndExtension.length == 2 ? coordinateAndExtension[1] : "jar";
        String classifier = parts.length == 4 ? "-" + parts[3] : "";
        String fileName = parts[1] + "-" + parts[2] + classifier + "." + extension;
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + fileName;
    }

    private String mavenUrl(JsonObject library, String artifactPath) {
        if (artifactPath == null) return null;
        String repository = library.has("url") ? library.get("url").getAsString() : "https://libraries.minecraft.net/";
        return repository.endsWith("/") ? repository + artifactPath : repository + "/" + artifactPath;
    }

    private void installAssets(JsonObject metadata, Path root) throws Exception {
        if (!metadata.has("assetIndex")) return;
        JsonObject indexInfo = metadata.getAsJsonObject("assetIndex");
        String indexId = indexInfo.get("id").getAsString();
        Path indexFile = root.resolve("assets/indexes").resolve(indexId + ".json");
        download(indexInfo.get("url").getAsString(), indexFile, stringValue(indexInfo, "sha1"));
        JsonObject index = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
        JsonObject objects = index.getAsJsonObject("objects");
        int count = 0;
        int cached = 0;
        int downloaded = 0;
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = object.get("hash").getAsString();
            Path file = root.resolve("assets/objects").resolve(hash.substring(0, 2)).resolve(hash);
            boolean wasDownloaded = download("https://resources.download.minecraft.net/"
                    + hash.substring(0, 2) + "/" + hash, file, hash);
            if (wasDownloaded) downloaded++;
            else cached++;
            count++;
            if (count % 500 == 0) {
                log("资源进度 " + count + "/" + objects.size() + "// 缓存 " + cached + "，下载 " + downloaded + "");
            }
        }
        log("资源: 缓存命中 " + cached + " 项，新下载 " + downloaded + " 项");
    }

    private String installLoggingConfig(JsonObject metadata, Path root) throws Exception {
        if (!metadata.has("logging") || !metadata.getAsJsonObject("logging").has("client")) return "";
        JsonObject fileInfo = metadata.getAsJsonObject("logging").getAsJsonObject("client").getAsJsonObject("file");
        Path file = root.resolve("assets/log_configs").resolve(fileInfo.get("id").getAsString());
        download(fileInfo.get("url").getAsString(), file, stringValue(fileInfo, "sha1"));
        return file.toString();
    }

    private void addJvmArguments(List<String> command, JsonObject metadata, Map<String, String> values,
            List<Path> libraries, Path clientJar, LauncherProfile profile) throws Exception {
        String classpathValue = classpath(libraries, clientJar);
        Map<String, String> expandedValues = new HashMap<>(values);
        expandedValues.put("classpath", classpathValue);
        if (metadata.has("arguments") && metadata.getAsJsonObject("arguments").has("jvm")) {
            for (JsonElement argument : metadata.getAsJsonObject("arguments").getAsJsonArray("jvm")) {
                appendArgument(command, argument, expandedValues);
            }
            if (command.stream().noneMatch(value -> value.equals("-cp") || value.equals("-classpath"))) {
                command.add("-cp");
                command.add(classpathValue);
            }
        } else {
            command.add("-Djava.library.path=" + values.get("natives_directory"));
            command.add("-cp");
            command.add(classpathValue);
        }
        command.add("-Xmx" + profile.maxMemoryMb + "M");
        if (profile.jvmArguments != null && !profile.jvmArguments.isBlank()) {
            for (String argument : profile.jvmArguments.trim().split("\\s+")) command.add(argument);
        }
    }

    private void addGameArguments(List<String> command, JsonObject metadata, Map<String, String> values) {
        if (metadata.has("arguments") && metadata.getAsJsonObject("arguments").has("game")) {
            for (JsonElement argument : metadata.getAsJsonObject("arguments").getAsJsonArray("game")) {
                appendArgument(command, argument, values);
            }
        } else if (metadata.has("minecraftArguments")) {
            for (String argument : metadata.get("minecraftArguments").getAsString().split(" ")) {
                command.add(replaceValues(argument, values));
            }
        }
    }

    private void appendArgument(List<String> command, JsonElement argument, Map<String, String> values) {
        if (argument.isJsonPrimitive()) {
            command.add(replaceValues(argument.getAsString(), values));
        } else if (argument.isJsonObject()) {
            JsonObject conditional = argument.getAsJsonObject();
            if (allowedByRules(conditional)) {
                JsonElement value = conditional.get("value");
                if (value != null && value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        command.add(replaceValues(item.getAsString(), values));
                    }
                } else if (value != null && value.isJsonPrimitive()) {
                    command.add(replaceValues(value.getAsString(), values));
                }
            }
        }
    }

    private boolean allowedByRules(JsonObject item) {
        if (!item.has("rules")) return true;
        boolean allowed = false;
        for (JsonElement ruleElement : item.getAsJsonArray("rules")) {
            JsonObject rule = ruleElement.getAsJsonObject();
            if (!ruleMatches(rule)) continue;
            allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private boolean ruleMatches(JsonObject rule) {
        if (rule.has("os")) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !operatingSystem().equals(os.get("name").getAsString())) return false;
            if (os.has("arch") && !System.getProperty("os.arch").matches(os.get("arch").getAsString())) return false;
            if (os.has("version") && !Pattern.compile(os.get("version").getAsString()).matcher(System.getProperty("os.version")).find()) return false;
        }
        if (rule.has("features")) {
            for (Map.Entry<String, JsonElement> feature : rule.getAsJsonObject("features").entrySet()) {
                if (feature.getValue().getAsBoolean()) return false;
            }
        }
        return true;
    }

    private Map<String, String> substitutions(String versionId, String playerName, Path gameDirectory,
            Path assetsRoot, Path natives, Path clientJar, String assetIndexId, String loggingPath) {
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return Map.ofEntries(
                Map.entry("auth_player_name", playerName), Map.entry("version_name", versionId),
                Map.entry("game_directory", gameDirectory.toString()), Map.entry("assets_root", assetsRoot.toString()),
                Map.entry("assets_index_name", assetIndexId), Map.entry("auth_uuid", uuid),
                Map.entry("auth_access_token", "0"), Map.entry("user_type", "legacy"),
                Map.entry("version_type", "release"), Map.entry("natives_directory", natives.toString()),
                Map.entry("launcher_name", "MinecraftLauncher"), Map.entry("launcher_version", "1.0"),
                Map.entry("classpath", clientJar.toString()), Map.entry("clientid", ""), Map.entry("auth_xuid", ""),
                Map.entry("path", loggingPath));
    }

    private String replaceValues(String input, Map<String, String> values) {
        String result = input;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String classpath(List<Path> libraries, Path clientJar) {
        List<String> entries = new ArrayList<>();
        libraries.forEach(path -> entries.add(path.toString()));
        entries.add(clientJar.toString());
        return String.join(System.getProperty("path.separator"), entries);
    }

    private void extractNatives(Path archive, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                Path outputFile = destination.resolve(entry.getName()).normalize();
                ensureInside(destination, outputFile);
                Files.createDirectories(outputFile.getParent());
                Files.copy(zip, outputFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private JsonObject getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "MinecraftLauncher/1.0").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("HTTP " + response.statusCode() + " from " + url);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonArray getJsonArray(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "MinecraftLauncher/1.0").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("HTTP " + response.statusCode() + " from " + url);
        return JsonParser.parseString(response.body()).getAsJsonArray();
    }

    private boolean download(String url, Path destination, String expectedSha1) throws Exception {
        if (Files.isRegularFile(destination)) {
            if (expectedSha1 == null) return false;
            String cacheKey = destination.toAbsolutePath().normalize().toString();
            String signature = fileSignature(destination, expectedSha1);
            if (signature.equals(verifiedFileCache.get(cacheKey))) return false;
            if (expectedSha1.equalsIgnoreCase(sha1(destination))) {
                verifiedFileCache.put(cacheKey, signature);
                return false;
            }
        }
        Files.createDirectories(destination.getParent());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "MinecraftLauncher/1.0").GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
        }
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try (InputStream stream = response.body()) {
            Files.copy(stream, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        if (expectedSha1 != null && !expectedSha1.equalsIgnoreCase(sha1(temporary))) {
            Files.deleteIfExists(temporary);
            throw new IOException("SHA-1 校验失败: " + destination.getFileName());
        }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        if (expectedSha1 != null) {
            String cacheKey = destination.toAbsolutePath().normalize().toString();
            verifiedFileCache.put(cacheKey, fileSignature(destination, expectedSha1));
        }
        return true;
    }

    private String fileSignature(Path file, String sha1) throws IOException {
        return sha1.toLowerCase(Locale.ROOT) + ":" + Files.size(file) + ":"
                + Files.getLastModifiedTime(file).toMillis();
    }

    private void loadDownloadCache(Path root) throws IOException {
        verifiedFileCache.clear();
        Path cacheFile = root.resolve("launcher-cache.json");
        if (!Files.isRegularFile(cacheFile)) return;
        try {
            JsonObject cache = JsonParser.parseString(Files.readString(cacheFile)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : cache.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    verifiedFileCache.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (RuntimeException exception) {
            verifiedFileCache.clear();
        }
    }

    private void saveDownloadCache(Path root) throws IOException {
        JsonObject cache = new JsonObject();
        verifiedFileCache.forEach(cache::addProperty);
        Files.writeString(root.resolve("launcher-cache.json"), cache.toString());
    }

    private String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }

    private void ensureInside(Path root, Path file) throws IOException {
        if (!file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IOException("下载路径越界: " + file);
        }
    }

    private String operatingSystem() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private String javaExecutable(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath).toAbsolutePath().normalize();
            if (Files.isDirectory(configured)) {
                String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java";
                return configured.resolve("bin").resolve(executable).toString();
            }
            return configured.toString();
        }
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private String stringValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private void setBusy(boolean busy) {
        SwingUtilities.invokeLater(() -> {
            launchButton.setEnabled(!busy);
            versions.setEnabled(!busy);
        });
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            output.append(message + "\n");
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "Minecraft Launcher", JOptionPane.ERROR_MESSAGE);
    }

    private static Path defaultGameDirectory() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return Path.of(System.getenv().getOrDefault("APPDATA", home), ".minecraft");
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "minecraft");
        return Path.of(home, ".minecraft");
    }
}
