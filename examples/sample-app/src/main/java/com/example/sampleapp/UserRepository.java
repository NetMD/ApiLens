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

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository — {@link io.apilens.agent.instrument.matcher.SpringMatchers#springRepository()}
 * 의 hasSuperType(JpaRepository) 매처가 이걸 잡는지 확인하는 대상.
 */
public interface UserRepository extends JpaRepository<User, Long> {
}
