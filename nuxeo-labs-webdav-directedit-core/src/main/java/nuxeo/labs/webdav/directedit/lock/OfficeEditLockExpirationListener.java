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

import org.nuxeo.ecm.core.event.EventBundle;
import org.nuxeo.ecm.core.event.PostCommitEventListener;

/**
 * Releases the edit locks past their deadline, on the scheduler's every-minute tick.
 *
 * @since 2025.3
 */
public class OfficeEditLockExpirationListener implements PostCommitEventListener {

    @Override
    public void handleEvent(EventBundle events) {
        if (events.containsEventName(OfficeEditLockTracker.EXPIRATION_EVENT)) {
            new OfficeEditLockTracker().releaseExpired();
        }
    }
}
