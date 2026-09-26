package io.github.kxnar.btaanywhere.mod.hosting;

import java.io.IOException;
import java.nio.file.Path;

/** Test-only injection seam. Production construction always uses {@link #NONE}. */
interface HostingFaults {
	HostingFaults NONE = point -> { };

	void hit(Point point);

	/** Test seam after process start and control verification, before the caller owns its handle. */
	default void afterSupervisorProcessStarted(Process process, Path controlFile) throws IOException {
	}

	enum Point {
		BEFORE_WORLD_SAVE,
		AFTER_WORLD_CLOSE,
		DURING_BACKUP_OR_COPY,
		BEFORE_BACKUP_OR_COPY_PUBLICATION,
		BEFORE_JOURNAL_PUBLICATION,
		AFTER_JOURNAL_PUBLICATION,
		BEFORE_SUPERVISOR_LAUNCH,
		AFTER_SUPERVISOR_LAUNCH,
		AFTER_SUPERVISOR_IDENTITY_RECORDED,
		BEFORE_READINESS,
		BEFORE_NETWORK_EXPOSURE,
		AFTER_NETWORK_EXPOSURE,
		DURING_GRACEFUL_STOP,
		BEFORE_SHOWCASE_CLEANUP,
		BEFORE_JOURNAL_CLEAR
	}
}
