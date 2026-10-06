import java.util.UUID;

public class LauncherProfile {
    String id = UUID.randomUUID().toString();
    String name;
    String version;
    String username;
    String gameDirectory;
    String javaPath = "";
    String jvmArguments = "";
    String loader = "vanilla";
    String loaderVersion = "";
    int maxMemoryMb = 4096;

    public LauncherProfile() {
    }

    public LauncherProfile(String name, String version, String username, String gameDirectory) {
        this.name = name;
        this.version = version;
        this.username = username;
        this.gameDirectory = gameDirectory;
    }

    @Override
    public String toString() {
        return name;
    }
}