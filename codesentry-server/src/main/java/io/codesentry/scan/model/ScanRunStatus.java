package io.codesentry.scan.model;

public enum ScanRunStatus {
  PENDING,
  PREPARING,
  RUNNING,
  COMPLETED,
  PARTIAL,
  FAILED,
  CANCELLED
}
