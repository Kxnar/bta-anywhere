# World and process recovery

BTA Anywhere writes `bta-anywhere/recovery.json` before starting the managed process and updates it with the supervisor/server PIDs, start times, executables, control file, world paths, backup, port, and log. A journal is evidence that cleanup may be incomplete; it is not evidence that its PIDs are still the same processes.

On the next launch, the mod validates every recorded path and matches process identity before enabling an action. It never kills a process based on PID alone and never restores a backup automatically.

Only one BTA Anywhere client can use a game directory at a time. If a second
client reports that the directory is in use, close the first client or launch
with a separate game profile, then restart the second client. Do not delete
`bta-anywhere/client.lock`; the file is intentionally retained, and Windows
releases its lock when the first client exits or crashes. After a crash, the
new client must still follow the recovery journal before opening a Live save.

The ordinary single-player world selector checks both the current in-process Live handoff and the recovery journal before opening a save. It blocks the original Live world during backup and launch even before the journal exists; existing alternate save paths are compared by file identity, and differently cased names are blocked. The hosting screen shows the in-process state and stop control; a journal block opens the recovery screen. Other worlds remain available. A valid Showcase journal leaves the original world available because the managed server uses a separate copy. If the journal is malformed or an update is incomplete, the active world cannot be identified safely, so all single-player opens and new-world creation are blocked. The appropriate screen opens on the next client tick. Do not bypass the guard by opening the save through another tool or mod.

Startup records launch intent in its first published journal, immediately after
the backup or Showcase copy and before mod mirroring or supervisor start. A
journal without complete supervisor/server identity, an interrupted
`recovery.json.tmp` update, malformed JSON, or a live PID with a different
start time or executable blocks both **Open Original** and **Restore Backup**.
The recovery screen explains that process and file inspection is needed. An
absent recorded PID is not proof that no server owns a save. This also applies
to older journals created before launch intent was recorded.

If supervisor startup fails after a process was started but before the client
receives its verified handle, the hosting screen's Stop action retains the
launch-intent journal. It cannot prove which process owns the save, so a later
Stop attempt must not clear the journal merely because the client has no
in-memory server handle. Follow the manual safety procedure below.

## Recovery actions

- **Reconnect** is available only when the exact supervisor and server are alive and the supervisor reports ready. It reconnects to `127.0.0.1`; relay and automatic direct mapping may need to be established again.
- **Graceful Stop** authenticates to the matching supervisor, sends the BTA `stop` command, waits, and cleans a showcase copy only after a clean exit.
- If control data or either process identity is missing or mismatched, stop fails and leaves the process and journal for manual inspection. A failed authenticated STOP is not followed by an unauthenticated client-side force kill. The supervisor may force-stop its own verified child after a valid authenticated STOP times out; that result is unclean and retains the journal.
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

For an incomplete identity or interrupted journal update, check both `recovery.json` and `recovery.json.tmp` and verify that neither the managed supervisor nor its server is alive before reopening the original save outside BTA Anywhere. If a PID now belongs to a different process, leave that process alone. The recovery UI deliberately cannot clear this ambiguity for you.

Never unzip a backup over an open world. Never copy a live save into the managed server. Avoid manually editing `level.dat`; BTA 8.0.1 already persists the player's UUID data in the format its dedicated server reads.
