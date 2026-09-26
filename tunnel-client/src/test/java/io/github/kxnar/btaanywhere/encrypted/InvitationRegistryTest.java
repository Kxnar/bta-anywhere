package io.github.kxnar.btaanywhere.encrypted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class InvitationRegistryTest {
	private static final String HOST_SESSION_ID = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";
	private static final String PIN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void createsIndependentCapabilitiesAndEnforcesMaximumLifetimeAndCapacity() {
		TestClock clock = new TestClock();
		InvitationRegistry registry = registry(clock);
		List<BtaeInvitation> invitations = new ArrayList<>();
		for (int index = 0; index < InvitationRegistry.MAX_OUTSTANDING; index++) {
			invitations.add(registry.issue(Duration.ofMinutes(10)));
		}
		BtaeInvitation invitation = invitations.get(0);
		assertEquals(16, java.util.Base64.getUrlDecoder().decode(invitation.invitationId()).length);
		assertEquals(32, java.util.Base64.getUrlDecoder().decode(invitation.joinCapability()).length);
		assertEquals(32, java.util.Base64.getUrlDecoder().decode(invitation.statusCapability()).length);
		assertNotEquals(invitation.joinCapability(), invitation.statusCapability());
		assertThrows(IllegalStateException.class, () -> registry.issue(Duration.ofMinutes(1)));
		assertThrows(IllegalArgumentException.class, () -> registry.issue(Duration.ofMinutes(10).plusMillis(1)));
		assertTrue(registry.revoke(invitation.invitationId()));
		assertEquals(InvitationRegistry.MAX_OUTSTANDING - 1, registry.size());
		assertNotNull(registry.issue(Duration.ofMinutes(1)));
	}

	@Test
	void expiresFromMonotonicClockAndRevokesCapabilities() {
		TestClock clock = new TestClock();
		InvitationRegistry registry = registry(clock);
		BtaeInvitation expiring = registry.issue(Duration.ofSeconds(2));
		BtaeInvitation revoked = registry.issue(Duration.ofMinutes(1));

		clock.advance(Duration.ofSeconds(2));
		assertFalse(registry.redeemJoin(expiring.invitationId(), HOST_SESSION_ID, expiring.joinCapability()));
		assertEquals(1, registry.size());
		assertTrue(registry.revoke(revoked.invitationId()));
		assertFalse(registry.authorizeStatus(revoked.invitationId(), HOST_SESSION_ID, revoked.statusCapability()));
		assertFalse(registry.revoke(revoked.invitationId()));
	}

	@Test
	void enforcesCapabilityScopeAndSlidingStatusLimit() {
		TestClock clock = new TestClock();
		InvitationRegistry registry = registry(clock);
		BtaeInvitation invitation = registry.issue(Duration.ofMinutes(2));

		assertFalse(registry.redeemJoin(invitation.invitationId(), HOST_SESSION_ID, invitation.statusCapability()));
		assertFalse(registry.authorizeStatus(invitation.invitationId(), HOST_SESSION_ID, invitation.joinCapability()));
		assertFalse(registry.redeemJoin(invitation.invitationId(), "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ", invitation.joinCapability()));
		for (int index = 0; index < InvitationRegistry.MAX_STATUS_CHECKS_PER_MINUTE; index++) {
			assertTrue(registry.authorizeStatus(invitation.invitationId(), HOST_SESSION_ID, invitation.statusCapability()));
		}
		assertFalse(registry.authorizeStatus(invitation.invitationId(), HOST_SESSION_ID, invitation.statusCapability()));
		clock.advance(Duration.ofMinutes(1));
		assertTrue(registry.authorizeStatus(invitation.invitationId(), HOST_SESSION_ID, invitation.statusCapability()));

		assertTrue(registry.redeemJoin(invitation.invitationId(), HOST_SESSION_ID, invitation.joinCapability()));
		assertFalse(registry.redeemJoin(invitation.invitationId(), HOST_SESSION_ID, invitation.joinCapability()));
		assertTrue(registry.authorizeStatus(invitation.invitationId(), HOST_SESSION_ID, invitation.statusCapability()));
	}

	@Test
	void concurrentJoinRedemptionHasExactlyOneWinner() throws Exception {
		InvitationRegistry registry = registry(new TestClock());
		BtaeInvitation invitation = registry.issue(Duration.ofMinutes(1));
		int contenderCount = 24;
		ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
		CountDownLatch ready = new CountDownLatch(contenderCount);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Boolean>> attempts = new ArrayList<>();
			for (int index = 0; index < contenderCount; index++) {
				attempts.add(executor.submit(() -> {
					ready.countDown();
					assertTrue(start.await(5, TimeUnit.SECONDS));
					return registry.redeemJoin(invitation.invitationId(), HOST_SESSION_ID, invitation.joinCapability());
				}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			int winners = 0;
			for (Future<Boolean> attempt : attempts) {
				if (attempt.get(5, TimeUnit.SECONDS)) {
					winners++;
				}
			}
			assertEquals(1, winners);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void revokeAllInvalidatesEveryOutstandingInvitation() {
		InvitationRegistry registry = registry(new TestClock());
		BtaeInvitation first = registry.issue(Duration.ofMinutes(1));
		BtaeInvitation second = registry.issue(Duration.ofMinutes(1));
		registry.revokeAll();
		assertEquals(0, registry.size());
		assertFalse(registry.redeemJoin(first.invitationId(), HOST_SESSION_ID, first.joinCapability()));
		assertFalse(registry.authorizeStatus(second.invitationId(), HOST_SESSION_ID, second.statusCapability()));
	}

	private static InvitationRegistry registry(TestClock clock) {
		return new InvitationRegistry(HOST_SESSION_ID, PIN, "relay.example", 30_000, PIN,
			new SecureRandom(), clock.monotonicNanos::get, clock.wallClockMillis::get);
	}

	private static final class TestClock {
		private final AtomicLong monotonicNanos = new AtomicLong(1_000_000L);
		private final AtomicLong wallClockMillis = new AtomicLong(1_800_000_000_000L);

		private void advance(Duration duration) {
			monotonicNanos.addAndGet(duration.toNanos());
			wallClockMillis.addAndGet(duration.toMillis());
		}
	}
}
