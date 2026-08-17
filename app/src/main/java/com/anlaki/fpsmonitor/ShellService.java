package com.anlaki.fpsmonitor;

import android.content.Context;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

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
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RemoteException("Command timed out");
            }
            String result = output.toString(StandardCharsets.UTF_8.name());
            if (process.exitValue() != 0) throw new RemoteException(result);
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
