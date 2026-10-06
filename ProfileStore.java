import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class ProfileStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PROFILE_LIST = new TypeToken<ArrayList<LauncherProfile>>() { }.getType();
    private static final Path FILE = configDirectory().resolve("profiles.json");

    private ProfileStore() {
    }

    public static List<LauncherProfile> load() throws IOException {
        if (!Files.isRegularFile(FILE)) return new ArrayList<>();
        List<LauncherProfile> profiles = GSON.fromJson(Files.readString(FILE), PROFILE_LIST);
        return profiles == null ? new ArrayList<>() : profiles;
    }

    public static void save(List<LauncherProfile> profiles) throws IOException {
        Files.createDirectories(FILE.getParent());
        Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(profiles, PROFILE_LIST));
        Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path configDirectory() {
        String home = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Path.of(appData, "MinecraftLauncher");
        return Path.of(home, ".minecraftlauncher");
    }
}