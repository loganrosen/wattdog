package io.github.loganrosen.wattdog.privileged;

interface IWattdogShell {
    // Reserved by Shizuku for UserService teardown.
    void destroy() = 16777114;

    // Streams `dumpsys <args>` output. Args must match an allowlisted DumpTarget.
    ParcelFileDescriptor dumpsys(in String[] args) = 1;
}
