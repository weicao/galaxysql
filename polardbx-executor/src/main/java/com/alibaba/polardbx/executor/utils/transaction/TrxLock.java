package com.alibaba.polardbx.executor.utils.transaction;

/**
 * transaction lock, or DDL lock
 *
 * @author wuzhe
 */
public class TrxLock {
    // Each lock has a unique lock id
    private final String lockId;
    private final String mode;
    private final String type;
    private final String physicalTable;
    private final String index;
    private final Integer space;
    private final Integer page;
    private final Integer rec;
    private final String data;

    public TrxLock(String lockId, String mode, String type, String physicalTable, String index,
                   Integer space, Integer page, Integer rec, String data) {
        this.lockId = lockId;
        this.mode = mode;
        this.type = type;
        this.physicalTable = physicalTable;
        this.index = index;
        this.space = space;
        this.page = page;
        this.rec = rec;
        this.data = data;
    }

    // For MDL
    public TrxLock(String lockId, String type, String physicalTable) {
        this.lockId = lockId;
        this.mode = null;
        this.type = type;
        this.physicalTable = physicalTable;
        this.index = null;
        this.space = null;
        this.page = null;
        this.rec = null;
        this.data = null;
    }

    public String getMode() {
        return mode;
    }

    public String getType() {
        return type;
    }

    public String getPhysicalTable() {
        return physicalTable;
    }

    public String getIndex() {
        return index;
    }

    public Integer getSpace() {
        return space;
    }

    public Integer getPage() {
        return page;
    }

    public Integer getRec() {
        return rec;
    }

    public String getData() {
        return data;
    }

    @Override
    public int hashCode() {
        // Make the lock id case-insensitive although it only contains numbers
        return lockId.toLowerCase().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TrxLock)) {
            return false;
        }
        return lockId.equalsIgnoreCase(((TrxLock) o).lockId);
    }
}
