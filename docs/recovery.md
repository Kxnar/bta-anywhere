# World and process recovery

BTA Anywhere writes `bta-anywhere/recovery.json` before starting the managed process and updates it with the supervisor/server PIDs, start times, executables, control file, world paths, backup, port, and log. A journal is evidence that cleanup may be incomplete; it is not evidence that its PIDs are still the same processes.

On the next launch, the mod validates every recorded path and matches process identity before enabling an action. It never kills a process based on PID alone and never restores a backup automatically.

## Recovery actions

- **Reconnect** is available only when the exact supervisor and server are alive and the supervisor reports ready. It reconnects to `127.0.0.1`; relay and automatic direct mapping may need to be established again.
- **Graceful Stop** authenticates to the matching supervisor, sends the BTA `stop` command, waits, and cleans a showcase copy only after a clean exit.
- **Open Logs** opens the recorded log or log directory.
- **Restore Backup** is available only for a live-world session with a managed backup and no matching process alive. It requires a second confirmation.
- **Open Original (keep recovery files)** is available only when no matching process owns the save. It clears the active journal so BTA can open the original while retaining logs, backups, and crash artifacts for manual inspection.

## Backup restore behavior

A confirmed restore:

1. Revalidates that no matching managed process is alive.
2. Moves the current original save aside to a hidden `.pre-restore-<timestamp>` sibling.
3. Extracts the ZIP into a separate partial directory with traversal checks.
4. Moves the extracted save into place.
5. Clears the recovery journal only after success.

If extraction fails, the partial directory is removed and the moved-aside original is put back when possible. The pre-restore copy is deliberately retained after a successful restore. Inspect it before deleting anything.

## Manual safety procedure

If the recovery screen cannot validate the journal:

1. Do not open the original world yet.
2. Close BTA and copy the entire game directory's `bta-anywhere` folder somewhere safe.
3. Inspect the newest server log.
4. Use the OS process viewer to determine whether a Java process is still running the exact managed `fabric-server-launch.jar`. Do not kill an unrelated Java PID.
5. If uncertain, rebooting prevents a stale server process from retaining the save, but it does not repair save data.
6. Preserve the original save, newest backup, showcase copy, recovery journal, and log before reporting the problem.

Never unzip a backup over an open world. Never copy a live save into the managed server. Avoid manually editing `level.dat`; BTA 8.0.1 already persists the player's UUID data in the format its dedicated server reads.
