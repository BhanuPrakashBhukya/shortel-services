package com.shortel.idgenerator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Snowflake-based 64-bit ID generator.
 * Layout: 41-bit timestamp | 10-bit machine ID | 12-bit sequence counter.
 * Capacity: 4096 IDs/ms per machine — easily handles 1M+ IDs/sec in cluster.
 */
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1700000000000L; // 2023-11-14 UTC custom epoch

    private static final long MACHINE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS   = 12L;

    private static final long MAX_MACHINE_ID = ~(-1L << MACHINE_ID_BITS); // 1023
    private static final long MAX_SEQUENCE   = ~(-1L << SEQUENCE_BITS);   // 4095

    private static final long MACHINE_SHIFT  = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS;

    private final long machineId;
    private long sequence     = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(@Value("${snowflake.machine-id:1}") long machineId) {
        if (machineId < 0 || machineId > MAX_MACHINE_ID) {
            throw new IllegalArgumentException(
                "Machine ID must be between 0 and " + MAX_MACHINE_ID + ", got: " + machineId);
        }
        this.machineId = machineId;
    }

    public synchronized long nextId() {
        long timestamp = now();

        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                "Clock moved backwards. Refusing to generate ID for " + (lastTimestamp - timestamp) + " ms");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted — spin until next millisecond
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
             | (machineId << MACHINE_SHIFT)
             | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long ts = now();
        while (ts <= lastTs) ts = now();
        return ts;
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
