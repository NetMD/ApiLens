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
package com.example.sampleapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates incoming user request — emitted as a dedicated service span in ApiLens traces.
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    public void validate(UserRequest req) {
        log.debug("validating user request");
        if (req == null) {
            throw new IllegalArgumentException("request is null");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (req.email() == null || !req.email().contains("@")) {
            throw new IllegalArgumentException("email is invalid");
        }
        if (req.password() == null || req.password().length() < 6) {
            throw new IllegalArgumentException("password must be at least 6 characters");
        }
        if (req.ssn() == null || req.ssn().isBlank()) {
            throw new IllegalArgumentException("ssn is required");
        }
    }
}
