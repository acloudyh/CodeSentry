package io.codesentry.dashboard.dto;

public class DashboardSummary {

    private final int projects;
    private final int scans;
    private final int runningScans;
    private final int newFindings;

    public DashboardSummary(int projects, int scans, int runningScans, int newFindings) {
        this.projects = projects;
        this.scans = scans;
        this.runningScans = runningScans;
        this.newFindings = newFindings;
    }

    public int getProjects() {
        return projects;
    }

    public int getScans() {
        return scans;
    }

    public int getRunningScans() {
        return runningScans;
    }

    public int getNewFindings() {
        return newFindings;
    }
}

