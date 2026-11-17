/*
 * (C) Copyright 2026 Hyland (http://hyland.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Thibaud Arguillere (with the help of opencode/Copilot - Claude Opus 5.5)
 */
package nuxeo.labs.webdav.directedit;

import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createFile;
import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createWorkspace;
import static nuxeo.labs.webdav.directedit.WebDavDirectEditTestUtils.createWorkspaceRoot;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.Lock;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.EventContextImpl;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import nuxeo.labs.webdav.directedit.lock.OfficeEditLockStore;
import nuxeo.labs.webdav.directedit.lock.OfficeEditLockStore.OfficeEditLock;
import nuxeo.labs.webdav.directedit.lock.OfficeEditLockStore.Status;
import nuxeo.labs.webdav.directedit.lock.OfficeEditLockTracker;

/**
 * Covers locking on click and the release of the locks Office left behind, without a servlet container.
 * <p>
 * Office's own {@code LOCK} and {@code UNLOCK} are covered over real HTTP in {@link TestOfficeEditEndpoint}.
 * <p>
 * The scheduler is live in tests and fires the release job every minute, so these tests assert on the outcome, never
 * on how many locks a given call released: the job may have got there first.
 *
 * @since 2025.3
 */
@RunWith(FeaturesRunner.class)
@Features(WebDavDirectEditFeature.class)
@Deploy("nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core:test-base-url.xml")
public class TestOfficeEditLockOnOpen {

    protected static final String DISABLED = "nuxeo.labs.webdavdirectedit.nuxeo-labs-webdav-directedit-core:"
            + "test-lock-on-open-disabled.xml";

    protected static final String WORD_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    protected static final String ADMINISTRATOR = "Administrator";

    /** Holds a lock in the way. */
    protected static final String JDOE = "jdoe";

    /** A plain user with write access, who is not an administrator and so is subject to the lock policy. */
    protected static final String BOB = "bob";

    @Inject
    protected CoreSession session;

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected TransactionalFeature transactionalFeature;

    @Inject
    protected WebDavDirectEditService service;

    protected final OfficeEditLockStore store = new OfficeEditLockStore();

    protected final OfficeEditLockTracker tracker = new OfficeEditLockTracker();

    protected DocumentModel doc;

    @Before
    public void setUp() {
        createWorkspaceRoot(session);
        DocumentModel workspace = createWorkspace(session, "my-workspace");
        ACP acp = workspace.getACP();
        ACL acl = acp.getOrCreateACL();
        acl.add(new ACE(JDOE, SecurityConstants.READ_WRITE, true));
        acl.add(new ACE(BOB, SecurityConstants.READ_WRITE, true));
        session.setACP(workspace.getRef(), acp, true);
        doc = createFile(session, workspace, "report", "Report.docx", WORD_MIME_TYPE, "content");
        session.save();
        transactionalFeature.nextTransaction();
    }

    /* ==================== Click ==================== */

    @Test
    public void shouldLockTheDocumentOnClick() {
        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();

        assertTrue(info.available());
        Lock lock = session.getLockInfo(doc.getRef());
        assertNotNull("the document must be locked as soon as the user clicks", lock);
        assertEquals(ADMINISTRATOR, lock.getOwner());

        OfficeEditLock record = store.get(doc.getId()).orElseThrow();
        assertEquals(Status.WAITING, record.status());
        assertTrue(record.isSameLock(lock));
        assertTrue("the click lock must have a deadline", record.deadlineMillis() > System.currentTimeMillis());
    }

    @Test
    public void shouldKeepTheSameLockOnASecondClick() {
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        Lock first = session.getLockInfo(doc.getRef());

        DirectEditInfo again = service.getDirectEditInfo(session.getDocument(doc.getRef()), null, null);
        transactionalFeature.nextTransaction();

        assertTrue(again.available());
        assertEquals(first.getCreated(), session.getLockInfo(doc.getRef()).getCreated());
        assertTrue(store.get(doc.getId()).orElseThrow().isSameLock(first));
    }

    @Test
    public void shouldLeaveALockTheUserTookByHandUntracked() {
        Lock manual = session.setLock(doc.getRef());
        session.save();
        transactionalFeature.nextTransaction();

        DirectEditInfo info = service.getDirectEditInfo(session.getDocument(doc.getRef()), null, null);
        transactionalFeature.nextTransaction();

        // The owner can still open it, but the lock stays theirs: nothing is recorded, so nothing releases it.
        assertTrue(info.available());
        assertEquals(manual.getCreated(), session.getLockInfo(doc.getRef()).getCreated());
        assertTrue(store.get(doc.getId()).isEmpty());
        assertFalse(tracker.shouldReleaseOnOfficeUnlock(doc.getId(), session.getLockInfo(doc.getRef())));
    }

    @Test
    public void shouldReportALockHeldByAnotherUserRatherThanAMissingPermission() {
        lockAs(JDOE);

        // Bob has write access, but LockSecurityPolicy denies it while jdoe holds the lock.
        CoreSession bobSession = coreFeature.getCoreSession(BOB);
        DirectEditInfo info = service.getDirectEditInfo(bobSession.getDocument(doc.getRef()), null, null);

        assertFalse(info.available());
        assertEquals(UnavailabilityReason.LOCKED, info.reason());
        assertEquals(JDOE, info.lockOwner());
        assertEquals("Report.docx", info.fileName());
        assertNull(info.davUrl());
    }

    @Test
    public void shouldReportALockHeldByAnotherUserToAnAdministrator() {
        // Administrators bypass the lock policy entirely, so for them only the lock itself tells.
        lockAs(JDOE);

        DirectEditInfo info = service.getDirectEditInfo(session.getDocument(doc.getRef()), null, null);
        transactionalFeature.nextTransaction();

        assertEquals(UnavailabilityReason.LOCKED, info.reason());
        assertEquals(JDOE, info.lockOwner());
        assertEquals(JDOE, session.getLockInfo(doc.getRef()).getOwner());
        assertTrue(store.get(doc.getId()).isEmpty());
    }

    @Test
    @Deploy(DISABLED)
    public void shouldNotLockOnClickWhenDisabled() {
        assertFalse(tracker.isEnabled());

        DirectEditInfo info = service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();

        assertTrue(info.available());
        assertNull(session.getLockInfo(doc.getRef()));
        assertTrue(store.get(doc.getId()).isEmpty());
    }

    @Test
    @Deploy(DISABLED)
    public void shouldLetOfficeReleaseAnyLockWhenDisabled() {
        // The original behaviour: Office's UNLOCK releases whatever lock its user holds.
        Lock manual = session.setLock(doc.getRef());
        session.save();

        assertTrue(tracker.shouldReleaseOnOfficeUnlock(doc.getId(), manual));
    }

    @Test
    public void shouldLetOfficeReleaseOnlyTheLockThePluginTook() {
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        assertTrue(tracker.shouldReleaseOnOfficeUnlock(doc.getId(), session.getLockInfo(doc.getRef())));

        // Unlocked and locked again by hand: a different lock, even with the same owner.
        session.removeLock(doc.getRef());
        Lock manual = session.setLock(doc.getRef());
        session.save();
        backDateLockCreation();
        assertFalse(tracker.shouldReleaseOnOfficeUnlock(doc.getId(), manual));
    }

    /* ==================== Office takes over ==================== */

    @Test
    public void shouldExtendTheDeadlineOnlyOnceOfficeHasLocked() {
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        long waitingDeadline = store.get(doc.getId()).orElseThrow().deadlineMillis();

        // Reads alone do not mean the document is being edited.
        tracker.onActivity(doc.getId(), ADMINISTRATOR);
        assertEquals(waitingDeadline, store.get(doc.getId()).orElseThrow().deadlineMillis());
        assertTrue(tracker.isWaitingForOffice(doc.getId(), session.getLockInfo(doc.getRef())));

        tracker.onOfficeLock(doc.getId(), session.getLockInfo(doc.getRef()), false);
        OfficeEditLock open = store.get(doc.getId()).orElseThrow();
        assertEquals(Status.OPEN, open.status());
        assertTrue("an open document gets the long inactivity deadline",
                open.deadlineMillis() - System.currentTimeMillis() > 60 * 60_000L);
        assertFalse(tracker.isWaitingForOffice(doc.getId(), session.getLockInfo(doc.getRef())));
    }

    @Test
    public void shouldNotLetAnotherUserKeepTheLockAlive() {
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        String before = store.getRaw(doc.getId());

        tracker.onSave(doc.getId(), JDOE);

        assertEquals(before, store.getRaw(doc.getId()));
    }

    /* ==================== Expiration ==================== */

    @Test
    public void shouldReleaseALockOfficeNeverOpened() {
        // The launch prompt was cancelled, or Office is not installed: no LOCK, no UNLOCK, ever.
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        expireRecord();

        tracker.releaseExpired();
        transactionalFeature.nextTransaction();

        assertNull(session.getLockInfo(doc.getRef()));
        assertTrue(store.get(doc.getId()).isEmpty());
    }

    @Test
    public void shouldReleaseALockOfficeLeftBehind() {
        // Office locked the document, then crashed: the UNLOCK never came.
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        tracker.onOfficeLock(doc.getId(), session.getLockInfo(doc.getRef()), false);
        expireRecord();

        tracker.releaseExpired();
        transactionalFeature.nextTransaction();

        assertNull(session.getLockInfo(doc.getRef()));
        assertTrue(store.get(doc.getId()).isEmpty());
    }

    @Test
    public void shouldKeepALockBeforeItsDeadline() {
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();

        tracker.releaseExpired();
        transactionalFeature.nextTransaction();

        assertNotNull(session.getLockInfo(doc.getRef()));
        assertTrue(store.get(doc.getId()).isPresent());
    }

    @Test
    public void shouldNotReleaseALockThatWasReplaced() {
        // The user unlocked the document by hand, then locked it again: that second lock is theirs.
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        backDateLockCreation();
        expireRecord();

        tracker.releaseExpired();
        transactionalFeature.nextTransaction();

        assertNotNull(session.getLockInfo(doc.getRef()));
        assertTrue("a record that no longer matches is dropped", store.get(doc.getId()).isEmpty());
    }

    @Test
    public void shouldNeverReleaseALockTheUserTookByHand() {
        session.setLock(doc.getRef());
        session.save();
        transactionalFeature.nextTransaction();
        service.getDirectEditInfo(session.getDocument(doc.getRef()), null, null);
        transactionalFeature.nextTransaction();

        tracker.releaseExpired();
        transactionalFeature.nextTransaction();

        assertNotNull(session.getLockInfo(doc.getRef()));
    }

    @Test
    public void shouldDropTheRecordOfADeletedDocument() {
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        expireRecord();
        session.removeDocument(doc.getRef());
        session.save();
        transactionalFeature.nextTransaction();

        tracker.releaseExpired();

        assertTrue(store.get(doc.getId()).isEmpty());
    }

    @Test
    public void shouldReleaseOnTheScheduledEvent() {
        // The wiring: scheduler event, async listener, release. Fired by hand rather than waiting for the cron.
        service.getDirectEditInfo(doc, null, null);
        transactionalFeature.nextTransaction();
        expireRecord();

        Framework.getService(EventService.class)
                 .fireEvent(new EventContextImpl().newEvent(OfficeEditLockTracker.EXPIRATION_EVENT));
        // Commits, which schedules the post-commit listener, and waits for it.
        transactionalFeature.nextTransaction();

        assertNull(session.getLockInfo(doc.getRef()));
        assertTrue(store.get(doc.getId()).isEmpty());
    }

    /* ==================== Helpers ==================== */

    protected void lockAs(String userName) {
        CoreSession other = coreFeature.getCoreSession(userName);
        other.setLock(new IdRef(doc.getId()));
        other.save();
        transactionalFeature.nextTransaction();
    }

    /** Moves the record's deadline into the past, which is the one thing a test cannot obtain by waiting. */
    protected void expireRecord() {
        OfficeEditLock record = store.get(doc.getId()).orElseThrow();
        store.put(doc.getId(), record.with(record.status(), System.currentTimeMillis() - 1000L));
    }

    /**
     * Makes the record describe a lock created an hour ago, so the lock in place no longer matches it. Unlocking and
     * locking again within one test would otherwise fall inside the one-second tolerance.
     */
    protected void backDateLockCreation() {
        OfficeEditLock record = store.get(doc.getId()).orElseThrow();
        store.put(doc.getId(), new OfficeEditLock(record.status(), record.deadlineMillis(),
                record.lockCreatedMillis() - 60 * 60_000L, record.owner()));
    }
}