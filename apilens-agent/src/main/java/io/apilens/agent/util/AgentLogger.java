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
package io.apilens.agent.util;

/**
 * Stderr-only logger used throughout the agent.
 *
 * <p>The agent runs inside the host application's JVM and must not pull in SLF4J
 * or any other framework that could clash with the host's logging setup. Stderr
 * is the simplest path that always works.
 *
 * <p>Output prefix {@code [ApiLens]} keeps agent lines distinguishable in the
 * host's noisy stderr.
 */
public final class AgentLogger {

    private static final String PREFIX = "[ApiLens]";

    private final boolean debug;

    public AgentLogger(boolean debug) {
        this.debug = debug;
    }

    public void info(String message) {
        System.err.println(PREFIX + " " + message);
    }

    public void warn(String message) {
        System.err.println(PREFIX + "[WARN] " + message);
    }

    public void error(String message, Throwable t) {
        System.err.println(PREFIX + "[ERROR] " + message
                + (t == null ? "" : ": " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        if (debug && t != null) {
            t.printStackTrace(System.err);
        }
    }

    public void debug(String message) {
        if (debug) {
            System.err.println(PREFIX + "[DEBUG] " + message);
        }
    }

    public boolean isDebug() {
        return debug;
    }
}
