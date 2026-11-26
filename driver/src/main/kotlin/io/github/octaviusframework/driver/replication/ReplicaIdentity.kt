package io.github.octaviusframework.driver.replication

/** Which columns of a table identify a row in its updates and deletes: the table's `REPLICA IDENTITY`. */
enum class ReplicaIdentity {
    /** The primary key's. */
    DEFAULT,

    /** None. */
    NOTHING,

    /** Every column. */
    FULL,

    /** Those of the index `REPLICA IDENTITY USING INDEX` names. */
    INDEX
}
