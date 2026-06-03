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

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final ValidationService validationService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public UserController(UserService userService,
                          ValidationService validationService,
                          NotificationService notificationService,
                          AuditLogService auditLogService) {
        this.userService = userService;
        this.validationService = validationService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@RequestBody UserRequest req) {
        User saved = userService.create(req);
        return UserResponse.from(saved);
    }

    @PostMapping("/with-notification")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createWithNotification(@RequestBody UserRequest req) {
        validationService.validate(req);          // 분기 1
        User saved = userService.create(req);     // 분기 2
        notificationService.notify(saved);        // 분기 3
        auditLogService.logCreation(saved);       // 분기 4
        return UserResponse.from(saved);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        try {
            return userService.findById(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
