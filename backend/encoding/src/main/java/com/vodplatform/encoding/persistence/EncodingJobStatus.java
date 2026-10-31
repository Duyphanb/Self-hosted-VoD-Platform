package com.vodplatform.encoding.persistence;

/** Job lifecycle values from V1; jobs never have an UPLOADED state. */
public enum EncodingJobStatus {
    QUEUED, PROCESSING, READY, FAILED
}
