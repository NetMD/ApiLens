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

import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository repo;

    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    public User create(UserRequest req) {
        User user = new User();
        user.setName(req.name());
        user.setPassword(req.password());
        user.setSsn(req.ssn());
        user.setEmail(req.email());
        return repo.save(user);
    }

    public UserResponse findById(Long id) {
        return repo.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + id));
    }
}
