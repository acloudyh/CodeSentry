package io.codesentry.rule.dto;

import java.util.List;

public class RuleProfileView {
    private final String id;
    private final String name;
    private final String description;
    private final List<String> standards;
    private final List<String> scanners;
    private final boolean offlineReady;

    public RuleProfileView(String id, String name, String description, List<String> standards,
                           List<String> scanners, boolean offlineReady) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.standards = standards;
        this.scanners = scanners;
        this.offlineReady = offlineReady;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<String> getStandards() { return standards; }
    public List<String> getScanners() { return scanners; }
    public boolean isOfflineReady() { return offlineReady; }
}
