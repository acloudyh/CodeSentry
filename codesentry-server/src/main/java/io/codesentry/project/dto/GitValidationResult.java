package io.codesentry.project.dto;

import java.util.*;

public class GitValidationResult {
    public final boolean valid;
    public final String code;
    public final String message;
    public final String defaultBranch;
    public final List<String> branches;
    public GitValidationResult(boolean valid,String code,String message,String defaultBranch,List<String> branches) {
        this.valid=valid;this.code=code;this.message=message;this.defaultBranch=defaultBranch;this.branches=branches;
    }
    public static GitValidationResult failure(String code,String message) {return new GitValidationResult(false,code,message,null,List.of());}
}
