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

import java.time.LocalDateTime;

/**
 * Writes an audit log entry for every notable user mutation. Adds one more
 * DB INSERT span so the trace graph shows multiple downstream nodes.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repo;

    public AuditLogService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void logCreation(User user) {
        log.debug("writing audit log for user id={}", user.getId());
        AuditLog entry = new AuditLog();
        entry.setAction("USER_CREATED");
        entry.setTargetUserId(user.getId());
        entry.setTargetUserName(user.getName());
        entry.setCreatedAt(LocalDateTime.now());
        repo.save(entry);
    }
}
