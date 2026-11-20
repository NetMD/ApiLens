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
package io.apilens.server.web;

/**
 * Media type values shared by the REST controllers' {@code produces} and {@code consumes}
 * declarations.
 *
 * <p>Annotation attributes cannot take an array constant, so every mapping lists
 * {@code MediaType.APPLICATION_JSON_VALUE} and {@link #JSON_SUFFIX_VALUE} side by side.
 * Both are needed: as a {@code consumes} condition {@code application/*+json} does not
 * include plain {@code application/json}.
 *
 * <p>Holds constants only — no request handling lives here.
 */
public final class ApiMediaTypes {

    /** JSON structured-syntax suffix wildcard, e.g. matches {@code application/vnd.x+json}. */
    public static final String JSON_SUFFIX_VALUE = "application/*+json";

    private ApiMediaTypes() {
    }
}
