package io.codesentry.scan.engine;

import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.WorkspaceMode;

import java.nio.file.Path;

public class ScanContext {

    private io.codesentry.scan.model.NetworkMode networkMode = io.codesentry.scan.model.NetworkMode.LOCAL;
    public io.codesentry.scan.model.NetworkMode getNetworkMode() { return networkMode; }
    public void setNetworkMode(io.codesentry.scan.model.NetworkMode value) { networkMode = value; }
    private final Long runId;
    private final ScanProject project;
    private final ScanMode scanMode;
    private final WorkspaceMode workspaceMode;
    private final Path workspace;
    private final Path repository;
    private final Path outputDirectory;
    private final String scanCommit;
    private final String mergeBaseCommit;
    private final String label;
    private final String ruleProfile;

    public ScanContext(Long runId, ScanProject project, ScanMode scanMode, WorkspaceMode workspaceMode,
                       Path workspace, Path repository, Path outputDirectory, String scanCommit,
                       String mergeBaseCommit, String label, String ruleProfile) {
        this.runId = runId;
        this.project = project;
        this.scanMode = scanMode;
        this.workspaceMode = workspaceMode;
        this.workspace = workspace;
        this.repository = repository;
        this.outputDirectory = outputDirectory;
        this.scanCommit = scanCommit;
        this.mergeBaseCommit = mergeBaseCommit;
        this.label = label;
        this.ruleProfile = ruleProfile;
    }

    public Long getRunId() {
        return runId;
    }

    public ScanProject getProject() {
        return project;
    }

    public ScanMode getScanMode() {
        return scanMode;
    }

    public WorkspaceMode getWorkspaceMode() {
        return workspaceMode;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public Path getRepository() {
        return repository;
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public String getScanCommit() {
        return scanCommit;
    }

    public String getMergeBaseCommit() {
        return mergeBaseCommit;
    }

    public String getLabel() {
        return label;
    }

    public String getRuleProfile() { return ruleProfile; }
}
