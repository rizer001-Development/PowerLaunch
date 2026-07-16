package com.powerlaunch;

/**
 * Standalone CLI entry point that does NOT require JavaFX.
 * This class can be used with: java -cp PowerLaunch.jar com.powerlaunch.CliEntryPoint cli --version ...
 * Or added as a separate Main-Class in the manifest for CLI mode.
 */
public class CliEntryPoint {
    public static void main(String[] args) {
        if (args.length > 0 && "cli".equalsIgnoreCase(args[0])) {
            String[] cliArgs = new String[args.length - 1];
            System.arraycopy(args, 1, cliArgs, 0, args.length - 1);
            CliLauncher.run(cliArgs);
        } else {
            System.out.println("PowerLaunch CLI — Use: java -jar PowerLaunch.jar cli --version <version> [options]");
            System.out.println("For GUI mode, use the main class: com.powerlaunch.Main");
        }
    }
}
