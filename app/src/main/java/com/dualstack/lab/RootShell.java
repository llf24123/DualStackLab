package com.dualstack.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class RootShell {
    private static volatile String help;

    static ProcessBuilder command(String command) {
        return new ProcessBuilder(arguments(command, capabilities()));
    }

    static String shellCommand(String command) {
        return arguments(command, capabilities()).stream().map(FileStore::quote).collect(Collectors.joining(" "));
    }

    static List<String> arguments(String command, String help) {
        return help.contains("--mount-master") ? List.of("su", "--mount-master", "-c", command)
                : List.of("su", "-c", command);
    }

    static String capabilities() {
        if (help != null) return help;
        synchronized (RootShell.class) {
            if (help != null) return help;
            Process probe = null;
            String result = "";
            try {
                probe = new ProcessBuilder("su", "--help").redirectErrorStream(true).start();
                probe.getOutputStream().close();
                if (probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0)
                    result = new String(FileStore.readBytes(probe.getInputStream(), 32768), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // An unavailable or older su keeps its standard invocation.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (probe != null) {
                    if (probe.isAlive()) probe.destroyForcibly();
                    try { probe.getInputStream().close(); probe.getErrorStream().close(); }
                    catch (IOException ignored) { }
                }
            }
            help = result;
            return result;
        }
    }

    private RootShell() {}
}
