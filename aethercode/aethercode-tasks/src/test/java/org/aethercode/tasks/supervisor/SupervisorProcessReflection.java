package org.aethercode.tasks.supervisor;

/**
 * prior round (T-310..T-314): test-only accessor to open a second
 * read-only view of the supervisor's store. We can't share the
 * live store across threads (writes are serialised through a
 * single JDBC connection) so tests open a parallel handle on
 * the same DB file for assertions.
 */
final class SupervisorProcessReflection {
    private SupervisorProcessReflection() {}

    static SupervisorStore openStore(SupervisorProcess p) throws java.sql.SQLException, java.io.IOException {
        return SupervisorStore.open(p.dbPath());
    }
}
