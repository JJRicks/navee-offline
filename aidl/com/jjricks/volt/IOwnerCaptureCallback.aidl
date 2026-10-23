package com.jjricks.volt;
/** Fixed-purpose IPC; never receives arbitrary shell commands or log output. */
interface IOwnerCaptureCallback {
    boolean prepareCleanup(String token, String original);
    oneway void progress(String token, int phase);
    oneway void completed(String token, int outcome, boolean clean, String identity, int role);
}
