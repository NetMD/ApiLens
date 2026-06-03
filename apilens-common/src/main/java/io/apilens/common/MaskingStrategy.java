/*
 * Copyright 2026 ApiLens Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apilens.common;

/**
 * What to replace a matched value with.
 *
 * <ul>
 *   <li>{@link #FULL} — replace value entirely with {@code ***}.</li>
 *   <li>{@link #PARTIAL} — keep first quarter of chars, mask remainder with {@code *}.</li>
 *   <li>{@link #HASH} — replace with {@code [h:xxxxxxxx]} (8-hex SHA-256 prefix).</li>
 *   <li>{@link #LENGTH_ONLY} — replace with {@code [len=N]} preserving only the original length.</li>
 * </ul>
 */
public enum MaskingStrategy {
    FULL,
    PARTIAL,
    HASH,
    LENGTH_ONLY
}
