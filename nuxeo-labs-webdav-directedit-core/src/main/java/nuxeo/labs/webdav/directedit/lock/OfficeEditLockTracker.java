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
package nuxeo.labs.webdav.directedit.lock;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.Lock;
import org.nuxeo.ecm.core.api.LockException;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.services.config.ConfigurationService;
import org.nuxeo.runtime.transaction.TransactionHelper;

import nuxeo.labs.webdav.directedit.lock.OfficeEditLockStore.OfficeEditLock;
import nuxeo.labs.webdav.directedit.lock.OfficeEditLockStore.Status;

/**
 * Locks a document when the user asks to open it in Office, and releases that lock when Office is done with it.
 * <p>
 * The lifecycle, when {@code nuxeo.labs.webdavDirectEdit.lockOnOpen.enabled} is true:
 * <ol>
 * <li><b>Click</b>: the document is locked for the user, and recorded as {@link Status#WAITING waiting} for Office,
 * with a short deadline.</li>
 * <li><b>Office {@code LOCK}</b>, or a save: the record becomes {@link Status#OPEN open}, with a long inactivity
 * deadline, pushed back by every request Office makes on the file. If Office takes the lock itself, as when the file
 * is reopened from its recent files, it is recorded the same way.</li>
 * <li><b>Office {@code UNLOCK}</b>: the lock is released at once. This is the only signal that the file was
 * closed.</li>
 * <li><b>Deadline passed</b>: the scheduled job releases the lock. Office never connected, or went away without
 * unlocking.</li>
 * </ol>
 * <b>A lock the plugin did not take is never released</b>, neither by Office's {@code UNLOCK} nor by the job. That is
 * the case of a lock the user set by hand before clicking, which they mean to keep.
 * <p>
 * Stateless apart from the {@link OfficeEditLockStore}, so the service and the servlet each hold their own instance.
 *
 * @since 2025.3
 */
public class OfficeEditLockTracker {

    private static final Logger log = LogManager.getLogger(OfficeEditLockTracker.class);

    public static final String ENABLED_PROP = "nuxeo.labs.webdavDirectEdit.lockOnOpen.enabled";

    /** How long the lock taken on click waits for Office to connect. */
    public static final String OPEN_TIMEOUT_PROP = "nuxeo.labs.webdavDirectEdit.lockOnOpen.openTimeoutMinutes";

    public static final int DEFAULT_OPEN_TIMEOUT_MINUTES = 5;

    /** How long a document open in Office stays locked with no request from Office. */
    public static final String INACTIVITY_TIMEOUT_PROP = "nuxeo.labs.webdavDirectEdit.lockOnOpen.inactivityTimeoutMinutes";

    public static final int DEFAULT_INACTIVITY_TIMEOUT_MINUTES = 65;

    /**
     * Floor of the inactivity timeout. Office is told its WebDAV lock lasts an hour, see
     * {@code DavConstants.LOCK_TIMEOUT_SECONDS}, so a client that refreshes it on time must never find it gone.
     */
    public static final int MIN_INACTIVITY_TIMEOUT_MINUTES = 61;

    /** Fired every minute by the scheduler, see {@code webdav-directedit-lock-contrib.xml}. */
    public static final String EXPIRATION_EVENT = "officeEditLockExpiration";

    /** Plain requests push the deadline back at most this often, so reads do not turn into a write each. */
    protected static final long REFRESH_INTERVAL_MILLIS = 60_000L;

    protected final OfficeEditLockStore store = new OfficeEditLockStore();

    /* ==================== Configuration ==================== */

    public boolean isEnabled() {
        ConfigurationService configurationService = Framework.getService(ConfigurationService.class);
        return configurationService == null || !configurationService.isBooleanFalse(ENABLED_PROP);
    }

    protected long openTimeoutMillis() {
        return minutes(OPEN_TIMEOUT_PROP, DEFAULT_OPEN_TIMEOUT_MINUTES, 1) * 60_000L;
    }

    protected long inactivityTimeoutMillis() {
        return minutes(INACTIVITY_TIMEOUT_PROP, DEFAULT_INACTIVITY_TIMEOUT_MINUTES, MIN_INACTIVITY_TIMEOUT_MINUTES)
                * 60_000L;
    }

    protected static int minutes(String property, int defaultValue, int minimum) {
        ConfigurationService configurationService = Framework.getService(ConfigurationService.class);
        int value = configurationService == null ? defaultValue
                : configurationService.getInteger(property, defaultValue);
        return value <= 0 ? defaultValue : Math.max(value, minimum);
    }

    /* ==================== Click ==================== */

    /**
     * Locks the document for the user who asked to open it, and records the lock as waiting for Office.
     * <p>
     * A lock the user already holds is left exactly as it is: if the plugin took it on an earlier click, Office gets a
     * fresh delay to connect; if the user took it by hand, it is theirs, and it is not recorded, so nothing will ever
     * release it on their behalf.
     *
     * @throws LockException when the document is locked by somebody else, including when they took the lock a moment
     *             before this call
     */
    public void lockOnOpen(CoreSession session, DocumentModel doc) {
        if (!isEnabled()) {
            return;
        }
        String userName = session.getPrincipal().getName();
        String docId = doc.getId();
        long now = System.currentTimeMillis();

        Lock existing = session.getLockInfo(doc.getRef());
        if (existing != null && !userName.equals(existing.getOwner())) {
            throw new LockException("Document already locked by " + existing.getOwner(),
                    HttpServletResponse.SC_CONFLICT);
        }
        if (existing != null) {
            String raw = store.getRaw(docId);
            Optional<OfficeEditLock> record = OfficeEditLockStore.parse(raw).filter(r -> r.isSameLock(existing));
            if (record.isPresent()) {
                if (record.get().isWaitingForOffice()) {
                    store.replace(docId, raw, record.get().with(Status.WAITING, now + openTimeoutMillis()));
                }
            } else {
                // A stale record would otherwise make this manual lock look like the plugin's own.
                store.remove(docId, raw);
                log.debug("Office edit: document {} was already locked by {}; this lock will not be released by"
                        + " the plugin", docId, userName);
            }
            return;
        }

        Lock lock = session.setLock(doc.getRef());
        session.save();
        store.put(docId, new OfficeEditLock(Status.WAITING, now + openTimeoutMillis(),
                OfficeEditLockStore.createdMillis(lock), userName));
        log.debug("Office edit: locked document {} for {} on open", docId, userName);
    }

    /* ==================== Office requests ==================== */

    /**
     * Records that Office locked the document.
     *
     * @param lock the Nuxeo lock now in place
     * @param tookLock whether this very {@code LOCK} request created the Nuxeo lock, as opposed to finding one
     */
    public void onOfficeLock(String docId, Lock lock, boolean tookLock) {
        if (!isEnabled() || lock == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + inactivityTimeoutMillis();
        if (tookLock) {
            // Reopened from Office's recent files, or locked again after Protected View: the plugin's lock too.
            store.put(docId,
                    new OfficeEditLock(Status.OPEN, deadline, OfficeEditLockStore.createdMillis(lock), lock.getOwner()));
            return;
        }
        String raw = store.getRaw(docId);
        Optional<OfficeEditLock> record = OfficeEditLockStore.parse(raw).filter(r -> r.isSameLock(lock));
        if (record.isPresent()) {
            store.replace(docId, raw, record.get().with(Status.OPEN, deadline));
        } else {
            store.remove(docId, raw);
            log.debug("Office edit: document {} is locked by hand by {}; Office's UNLOCK will keep it", docId,
                    lock.getOwner());
        }
    }

    /**
     * Whether Office's {@code UNLOCK} must release the Nuxeo lock.
     * <p>
     * With the feature off, always, which is the original behaviour. With it on, only a lock this plugin took.
     */
    public boolean shouldReleaseOnOfficeUnlock(String docId, Lock lock) {
        if (!isEnabled()) {
            return true;
        }
        return store.get(docId).filter(r -> r.isSameLock(lock)).isPresent();
    }

    /** Drops the record once the lock is gone. */
    public void forget(String docId) {
        store.remove(docId);
    }

    /**
     * A save: Office is plainly working on the file, whatever it sent before. Marks it open and pushes the deadline
     * back.
     */
    public void onSave(String docId, String userName) {
        touch(docId, userName, true);
    }

    /**
     * Any request on a document open in Office pushes its deadline back, at most once a minute.
     * <p>
     * Whether Office refreshes its WebDAV lock has not been measured. If it does not, this is what keeps an idle but
     * open document locked: Office keeps polling a file it has open. A crashed Office sends nothing, so the lock is
     * still released. A lock merely waiting for Office is not extended: reads alone do not mean it is being edited.
     */
    public void onActivity(String docId, String userName) {
        touch(docId, userName, false);
    }

    protected void touch(String docId, String userName, boolean save) {
        String raw = store.getRaw(docId);
        if (raw == null) {
            // The common case by far: one cheap read, nothing to write.
            return;
        }
        Optional<OfficeEditLock> parsed = OfficeEditLockStore.parse(raw);
        if (parsed.isEmpty() || !parsed.get().owner().equals(userName)) {
            // Somebody else reading the document must not keep the owner's lock alive.
            return;
        }
        OfficeEditLock record = parsed.get();
        if (record.isWaitingForOffice() && !save) {
            return;
        }
        long deadline = System.currentTimeMillis() + inactivityTimeoutMillis();
        if (!save && deadline - record.deadlineMillis() < REFRESH_INTERVAL_MILLIS) {
            return;
        }
        store.replace(docId, raw, record.with(Status.OPEN, deadline));
    }

    /**
     * Whether the document's lock is the one taken on click, Office not having locked it yet.
     * <p>
     * The endpoint then leaves it out of {@code lockdiscovery}. Until this feature, Office never saw a lock before
     * taking its own, and a lock it does not hold the token of may make it open the file read-only.
     */
    public boolean isWaitingForOffice(String docId, Lock lock) {
        return lock != null
                && store.get(docId).filter(r -> r.isWaitingForOffice() && r.isSameLock(lock)).isPresent();
    }

    /* ==================== Expiration ==================== */

    /**
     * Releases every lock this plugin took whose deadline has passed. Runs whatever the switch, so that records left
     * from before it was turned off are still honoured.
     *
     * @return how many locks were released
     */
    public int releaseExpired() {
        long now = System.currentTimeMillis();
        List<String> docIds;
        try (Stream<String> keys = store.docIds()) {
            docIds = keys.toList();
        }
        int released = 0;
        for (String docId : docIds) {
            if (releaseIfExpired(docId, now)) {
                released++;
            }
        }
        return released;
    }

    protected boolean releaseIfExpired(String docId, long now) {
        String raw = store.getRaw(docId);
        if (raw == null) {
            return false;
        }
        Optional<OfficeEditLock> parsed = OfficeEditLockStore.parse(raw);
        if (parsed.isEmpty()) {
            store.remove(docId, raw);
            return false;
        }
        OfficeEditLock record = parsed.get();
        if (!record.isExpired(now)) {
            return false;
        }
        // Claim before acting: another node, or a refresh from Office, may be on the same entry right now.
        if (!store.remove(docId, raw)) {
            return false;
        }
        // One transaction per entry: a failure inside a privileged session marks the transaction rollback-only, which
        // would otherwise cancel every release that follows. The entry is not put back on failure, so a document that
        // cannot be unlocked is reported once rather than every minute.
        String repositoryName = Framework.getService(RepositoryManager.class).getDefaultRepositoryName();
        Function<CoreSession, Boolean> releaser = session -> release(session, docId, record);
        try {
            boolean released = TransactionHelper.runInNewTransaction(
                    () -> CoreInstance.doPrivileged(repositoryName, releaser));
            if (released) {
                log.info("Office edit: released the lock on document {} held by {}, {}", docId, record.owner(),
                        record.isWaitingForOffice() ? "which Office never opened" : "after Office went silent");
            }
            return released;
        } catch (RuntimeException e) {
            log.warn("Office edit: could not release the lock on document {} held by {}", docId, record.owner(), e);
            return false;
        }
    }

    protected boolean release(CoreSession session, String docId, OfficeEditLock record) {
        var ref = new IdRef(docId);
        if (!session.exists(ref)) {
            log.debug("Office edit: document {} no longer exists, dropping its lock record", docId);
            return false;
        }
        Lock lock = session.getLockInfo(ref);
        if (!record.isSameLock(lock)) {
            // Unlocked, or unlocked and locked again, since: not the plugin's lock any more.
            log.debug("Office edit: document {} no longer holds the lock the plugin took, leaving it alone", docId);
            return false;
        }
        session.removeLock(ref);
        session.save();
        return true;
    }
}
