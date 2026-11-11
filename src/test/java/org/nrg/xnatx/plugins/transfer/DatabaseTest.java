package org.nrg.xnatx.plugins.transfer;

/**
 * JUnit category for tests that run against PostgreSQL in Docker (see {@code DatabaseTestConfig}). Gradle's
 * {@code test} task excludes this category, and {@code dbTest} runs only it.
 */
public interface DatabaseTest {
}
