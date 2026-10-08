import io.github.kxnar.btaanywhere.mod.supervisor.SupervisorControlFile;
import java.nio.file.Path;

class StopValidationHost {
    public static void main(String[] args) throws Exception {
        var control = SupervisorControlFile.read(Path.of(args[0]));
        var supervisor = ProcessHandle.of(control.supervisorPid()).orElseThrow();
        var server = ProcessHandle.of(control.serverPid()).orElseThrow();
        if (!control.matchesSupervisor(supervisor) || !control.matchesServer(server)) {
            throw new IllegalStateException("Process identity mismatch; refusing STOP");
        }
        System.out.println(control.request("STOP", 45000));
        supervisor.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("supervisor_alive=" + supervisor.isAlive());
        System.out.println("server_alive=" + server.isAlive());
    }
}
