package io.codesentry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "codesentry")
public class CodeSentryProperties {

    private final Storage storage = new Storage();
    private final Git git = new Git();
    private final Scanning scanning = new Scanning();
    private final Scanners scanners = new Scanners();
    private final Ai ai = new Ai();
    private final Remediation remediation = new Remediation();
    public Remediation getRemediation() { return remediation; }
    public static class Remediation {
        private int timeoutSeconds=600, maxQueries=1000, maxResponseMegabytes=32;
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { timeoutSeconds=v; }
        public int getMaxQueries() { return maxQueries; }
        public void setMaxQueries(int v) { maxQueries=v; }
        public int getMaxResponseMegabytes() { return maxResponseMegabytes; }
        public void setMaxResponseMegabytes(int v) { maxResponseMegabytes=v; }
    }

    public Storage getStorage() {
        return storage;
    }

    public Git getGit() {
        return git;
    }

    public Scanning getScanning() {
        return scanning;
    }

    public Scanners getScanners() {
        return scanners;
    }

    public Ai getAi() {
        return ai;
    }

    public static class Storage {
        private String root = System.getProperty("user.home") + "/.codesentry";
        private String repositoriesDirectory = "repositories";
        private String snapshotsDirectory = "snapshots";
        private String reportsDirectory = "reports";

        public Path rootPath() {
            return Paths.get(root).toAbsolutePath().normalize();
        }

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public String getRepositoriesDirectory() {
            return repositoriesDirectory;
        }

        public void setRepositoriesDirectory(String repositoriesDirectory) {
            this.repositoriesDirectory = repositoriesDirectory;
        }

        public String getSnapshotsDirectory() {
            return snapshotsDirectory;
        }

        public void setSnapshotsDirectory(String snapshotsDirectory) {
            this.snapshotsDirectory = snapshotsDirectory;
        }

        public String getReportsDirectory() {
            return reportsDirectory;
        }

        public void setReportsDirectory(String reportsDirectory) {
            this.reportsDirectory = reportsDirectory;
        }
    }

    public static class Git {
        private String executable = "git";
        private long commandTimeoutSeconds = 120;

        public String getExecutable() {
            return executable;
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }

        public long getCommandTimeoutSeconds() {
            return commandTimeoutSeconds;
        }

        public void setCommandTimeoutSeconds(long commandTimeoutSeconds) {
            this.commandTimeoutSeconds = commandTimeoutSeconds;
        }
    }

    public static class Scanning {
        private int workerCount = 2;
        private long taskTimeoutMinutes = 30;
        private List<String> excludedGlobs = new ArrayList<>(Arrays.asList(
                "**/*.bmp", "**/*.cab", "**/*.doc", "**/*.docx", "**/*.eot",
                "**/*.gif", "**/*.ico", "**/*.jpeg", "**/*.jpg", "**/*.map",
                "**/*.mov", "**/*.mp3", "**/*.mp4", "**/*.otf", "**/*.pdf",
                "**/*.png", "**/*.sdoc", "**/*.svg", "**/*.swp", "**/*.swo",
                "**/*.tar", "**/*.ttf", "**/*.wav", "**/*.wmv", "**/*.woff",
                "**/*.woff2", "**/*.xls", "**/*.xlsx", "**/*.zip", "**/*.min.js",
                "**/jquery*.js", "**/bootstrap*.js", "**/echarts*.js", "**/amcharts/**"
        ));

        public int getWorkerCount() {
            return workerCount;
        }

        public void setWorkerCount(int workerCount) {
            this.workerCount = workerCount;
        }

        public long getTaskTimeoutMinutes() {
            return taskTimeoutMinutes;
        }

        public void setTaskTimeoutMinutes(long taskTimeoutMinutes) {
            this.taskTimeoutMinutes = taskTimeoutMinutes;
        }

        public List<String> getExcludedGlobs() {
            return excludedGlobs;
        }

        public void setExcludedGlobs(List<String> excludedGlobs) {
            this.excludedGlobs = excludedGlobs == null ? new ArrayList<>() : new ArrayList<>(excludedGlobs);
        }
    }

    public static class Scanners {
        private final Tool semgrep = new Tool("semgrep", true);
        private final Tool gitleaks = new Tool("gitleaks", true);
        private final Tool trivy = new Tool("trivy", true);
        private final Tool spotbugs = new Tool("spotbugs", true);
        private final Tool p3c = new Tool("java", true);

        public Tool getSemgrep() {
            return semgrep;
        }

        public Tool getGitleaks() {
            return gitleaks;
        }

        public Tool getTrivy() {
            return trivy;
        }

        public Tool getSpotbugs() {
            return spotbugs;
        }

        public Tool getP3c() {
            return p3c;
        }
    }

    public static class Tool {
        private String executable;
        private boolean enabled;
        private String config;
        private String include;
        private int timeoutSeconds = 180;
        private int maxHeapMegabytes = 768;
        private boolean offlineScan = true;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
        public int getMaxHeapMegabytes() { return maxHeapMegabytes; }
        public void setMaxHeapMegabytes(int value) { maxHeapMegabytes = value; }
        public boolean isOfflineScan() { return offlineScan; }
        public void setOfflineScan(boolean value) { offlineScan = value; }

        public Tool() {
        }

        public Tool(String executable, boolean enabled) {
            this.executable = executable;
            this.enabled = enabled;
        }

        public String getExecutable() {
            return executable;
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getConfig() {
            return config;
        }

        public void setConfig(String config) {
            this.config = config;
        }

        public String getInclude() {
            return include;
        }

        public void setInclude(String include) {
            this.include = include;
        }
    }

    public static class Ai {
        private String provider = "OPENAI_COMPATIBLE";
        private boolean enabled;
        private String baseUrl;
        private String model;
        private String apiKey;
        private String keychainService = "CodeSentry-DeepSeek";
        private String keychainAccount = System.getProperty("user.name");
        private String thinkingMode;
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 90;
        private int maxContextCharacters = 12000;
        private int maxConcurrency = 4;
        private int highConfidencePercent = 85;
        private int maxOutputTokens = 4096;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int value) { this.maxOutputTokens = value; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getKeychainService() { return keychainService; }
        public void setKeychainService(String keychainService) { this.keychainService = keychainService; }
        public String getKeychainAccount() { return keychainAccount; }
        public void setKeychainAccount(String keychainAccount) { this.keychainAccount = keychainAccount; }
        public String getThinkingMode() { return thinkingMode; }
        public void setThinkingMode(String thinkingMode) { this.thinkingMode = thinkingMode; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
        public int getMaxContextCharacters() { return maxContextCharacters; }
        public void setMaxContextCharacters(int maxContextCharacters) { this.maxContextCharacters = maxContextCharacters; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
        public int getHighConfidencePercent() { return highConfidencePercent; }
        public void setHighConfidencePercent(int highConfidencePercent) { this.highConfidencePercent = highConfidencePercent; }
    }
}
