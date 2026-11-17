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

import java.util.Calendar;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.core.api.Lock;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.kv.KeyValueStoreProvider;

/**
 * Records the document locks this plugin took itself, so it releases those and only those.
 * <p>
 * A Nuxeo lock carries nothing but an owner and a creation date: nothing tells a lock taken for an Office editing
 * session from one the user set by hand with the Lock button. This store is that missing mark. A document with no
 * entry here, or whose current lock does not match its entry, holds a lock the plugin must leave alone.
 * <p>
 * Keyed by document id, in the default repository, which is the only one the endpoint serves. The value is
 * {@code status:deadlineMillis:lockCreatedMillis:owner}, read with a limit of four fields because a user name may
 * contain the separator.
 * <p>
 * Every write is a compare-and-set against the value read just before, except the very first one. That is what makes
 * the release job safe on a cluster: a node claims an expired entry by removing it atomically, and a refresh racing
 * with it either lands first, and the entry is no longer expired, or fails, and the lock is released.
 *
 * @since 2025.3
 */
public class OfficeEditLockStore {

    /** Auto-created, namespaced from the default key/value store. No contribution is required. */
    public static final String STORE_NAME = "officeEditLock";

    public static final String SEPARATOR = ":";

    /**
     * How long an entry outlives its deadline before the store drops it on its own. Only a safety net: the release
     * job removes entries long before, but it is the job, so it can be stopped.
     */
    protected static final long GRACE_SECONDS = 24 * 3600L;

    /** Two locks are the same when their creation dates agree within this, whatever the storage precision. */
    protected static final long CREATED_TOLERANCE_MILLIS = 1000L;

    /**
     * Where a lock taken by this plugin stands.
     */
    public enum Status {

        /** Taken on click; Office has not locked the document yet. */
        WAITING("waiting"),

        /** Office has locked the document, or saved it: it is open in Office. */
        OPEN("open");

        protected final String id;

        Status(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static Optional<Status> fromId(String id) {
            for (Status status : values()) {
                if (status.id.equals(id)) {
                    return Optional.of(status);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * A lock taken by this plugin.
     *
     * @param status whether Office has taken over the lock yet
     * @param deadlineMillis past this, the release job releases the lock
     * @param lockCreatedMillis creation date of the Nuxeo lock, which identifies it together with its owner
     * @param owner the lock owner, who is also the user the document was opened for
     */
    public record OfficeEditLock(Status status, long deadlineMillis, long lockCreatedMillis, String owner) {

        public boolean isWaitingForOffice() {
            return status == Status.WAITING;
        }

        public boolean isExpired(long nowMillis) {
            return nowMillis >= deadlineMillis;
        }

        /**
         * Whether the given Nuxeo lock is the very one this record describes. A lock released and taken again, even
         * by the same user, is a different lock: its creation date differs by at least the time a human needs.
         */
        public boolean isSameLock(Lock lock) {
            return lock != null && owner.equals(lock.getOwner())
                    && Math.abs(createdMillis(lock) - lockCreatedMillis) <= CREATED_TOLERANCE_MILLIS;
        }

        public OfficeEditLock with(Status newStatus, long newDeadlineMillis) {
            return new OfficeEditLock(newStatus, newDeadlineMillis, lockCreatedMillis, owner);
        }
    }

    public static long createdMillis(Lock lock) {
        Calendar created = lock == null ? null : lock.getCreated();
        return created == null ? 0L : created.getTimeInMillis();
    }

    protected KeyValueStore store() {
        return Framework.getService(KeyValueService.class).getKeyValueStore(STORE_NAME);
    }

    public Optional<OfficeEditLock> get(String docId) {
        return parse(getRaw(docId));
    }

    /** The stored value as is, to be handed back to {@link #replace} or {@link #remove(String, String)}. */
    public String getRaw(String docId) {
        return StringUtils.isBlank(docId) ? null : store().getString(docId);
    }

    /** Unconditional write, used only when the plugin has just taken the lock itself. */
    public void put(String docId, OfficeEditLock lock) {
        store().put(docId, format(lock), ttlSeconds(lock));
    }

    /**
     * Writes the record only if the stored value is still the one read before.
     *
     * @return {@code false} when somebody else changed or removed it in the meantime
     */
    public boolean replace(String docId, String expectedRaw, OfficeEditLock lock) {
        return expectedRaw != null && store().compareAndSet(docId, expectedRaw, format(lock), ttlSeconds(lock));
    }

    /**
     * Removes the record only if the stored value is still the one read before. This is how the release job claims
     * an entry.
     *
     * @return {@code true} when this caller removed it
     */
    public boolean remove(String docId, String expectedRaw) {
        return expectedRaw != null && store().compareAndSet(docId, expectedRaw, null);
    }

    public void remove(String docId) {
        if (StringUtils.isNotBlank(docId)) {
            store().put(docId, (String) null);
        }
    }

    /** Every recorded document id. The caller must close the stream. */
    public Stream<String> docIds() {
        // Every store KeyValueService hands out is a provider; enumeration is only declared there.
        return ((KeyValueStoreProvider) store()).keyStream();
    }

    protected static long ttlSeconds(OfficeEditLock lock) {
        long remaining = (lock.deadlineMillis() - System.currentTimeMillis()) / 1000L;
        return Math.max(1L, remaining) + GRACE_SECONDS;
    }

    public static String format(OfficeEditLock lock) {
        return lock.status().getId() + SEPARATOR + lock.deadlineMillis() + SEPARATOR + lock.lockCreatedMillis()
                + SEPARATOR + lock.owner();
    }

    public static Optional<OfficeEditLock> parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Optional.empty();
        }
        String[] parts = raw.split(SEPARATOR, 4);
        if (parts.length != 4 || parts[3].isEmpty()) {
            return Optional.empty();
        }
        Optional<Status> status = Status.fromId(parts[0]);
        if (status.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new OfficeEditLock(status.get(), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                    parts[3]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
