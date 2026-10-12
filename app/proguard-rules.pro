# Shizuku instantiates the UserService reflectively inside the shell process.
-keep class io.github.loganrosen.wattdog.privileged.WattdogShellService {
    <init>();
    <init>(android.content.Context);
}
