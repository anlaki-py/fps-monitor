package com.anlaki.fpsmonitor;

import android.content.Context;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ShellService extends IShellService.Stub {
    public ShellService() {}
    public ShellService(Context ignored) {}

    @Override
    public String run(String operation) throws RemoteException {
        String[] command;
        switch (operation) {
            case "enable":
                command = new String[]{"/system/bin/dumpsys", "SurfaceFlinger", "--timestats", "-clear", "-enable"};
                break;
            case "disable":
                command = new String[]{"/system/bin/dumpsys", "SurfaceFlinger", "--timestats", "-disable"};
                break;
            case "foregroundWindow":
                command = new String[]{"/system/bin/dumpsys", "window"};
                break;
            case "foregroundActivity":
                command = new String[]{"/system/bin/dumpsys", "activity", "activities"};
                break;
            case "sample":
                command = new String[]{"/system/bin/dumpsys", "SurfaceFlinger", "--timestats", "-dump", "-clear", "-maxlayers", "64"};
                break;
            default:
                throw new RemoteException("Unknown operation");
        }
        return execute(command);
    }

    private String execute(String[] command) throws RemoteException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream input = process.getInputStream();
            AtomicReference<IOException> readError = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try (InputStream stream = input) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                } catch (IOException error) {
                    readError.set(error);
                }
            }, "fps-command-output");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                try { input.close(); } catch (IOException ignored) {}
                reader.join(500);
                throw new RemoteException("Command timed out");
            }
            reader.join(1000);
            if (reader.isAlive()) {
                try { input.close(); } catch (IOException ignored) {}
                reader.join(500);
            }
            IOException outputError = readError.get();
            if (outputError != null) throw outputError;
            String result = output.toString(StandardCharsets.UTF_8.name());
            if (process.exitValue() != 0) {
                throw new RemoteException("Exit " + process.exitValue() + ": " + result.trim());
            }
            return result;
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteException(e.toString());
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
