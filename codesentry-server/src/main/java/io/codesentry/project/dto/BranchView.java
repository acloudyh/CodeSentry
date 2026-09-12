package io.codesentry.project.dto;

public class BranchView {

    private final String name;
    private final String commit;
    private final boolean remote;

    public BranchView(String name, String commit, boolean remote) {
        this.name = name;
        this.commit = commit;
        this.remote = remote;
    }

    public String getName() {
        return name;
    }

    public String getCommit() {
        return commit;
    }

    public boolean isRemote() {
        return remote;
    }
}

