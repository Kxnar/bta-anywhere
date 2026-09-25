package io.github.kxnar.btaanywhere.mod.hosting;

public record RecoveryInspection(
	RecoveryJournal.Entry entry,
	boolean supervisorAlive,
	boolean serverAlive,
	boolean serverReady,
	boolean identityAmbiguous,
	String message
) {
	public boolean canReconnect() {
		return supervisorAlive && serverAlive && serverReady;
	}

	public boolean canGracefullyStop() {
		return supervisorAlive && serverAlive;
	}

	public boolean canOpenOriginal() {
		return entry.identityRecorded() && !entry.launchIntent() && !identityAmbiguous
			&& !supervisorAlive && !serverAlive;
	}

	public boolean canRestore() {
		return canOpenOriginal() && entry.worldMode() == WorldMode.LIVE && !entry.backupPath().isBlank();
	}
}
