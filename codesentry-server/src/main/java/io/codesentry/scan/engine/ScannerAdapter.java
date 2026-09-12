package io.codesentry.scan.engine;

import java.util.List;

public interface ScannerAdapter {

    String name();

    boolean enabled();

    boolean available();

    List<NormalizedFinding> scan(ScanContext context);
}

