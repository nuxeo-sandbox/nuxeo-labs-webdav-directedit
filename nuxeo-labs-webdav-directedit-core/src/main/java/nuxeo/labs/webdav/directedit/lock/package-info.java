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

/**
 * The lock-on-open lifecycle: the document is locked when the user clicks the button, and released when Office closes
 * the file, or when Office never comes or goes away silently.
 * <p>
 * Only locks this plugin took are ever released; a lock the user set by hand is left alone.
 *
 * @since 2025.3
 */
package nuxeo.labs.webdav.directedit.lock;
