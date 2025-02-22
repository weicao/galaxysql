package com.alibaba.polardbx.executor.ddl.job.task.tablegroup;

import com.alibaba.fastjson.annotation.JSONCreator;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.executor.ddl.job.task.BaseDdlTask;
import com.alibaba.polardbx.executor.ddl.job.task.util.TaskName;
import com.alibaba.polardbx.executor.utils.failpoint.FailPoint;
import com.alibaba.polardbx.gms.metadb.table.TableInfoManager;
import com.alibaba.polardbx.gms.partition.TablePartitionAccessor;
import com.alibaba.polardbx.gms.tablegroup.PartitionGroupAccessor;
import com.alibaba.polardbx.gms.tablegroup.PartitionGroupRecord;
import com.alibaba.polardbx.gms.tablegroup.TableGroupAccessor;
import com.alibaba.polardbx.gms.tablegroup.TableGroupConfig;
import com.alibaba.polardbx.gms.tablegroup.TableGroupRecord;
import com.alibaba.polardbx.gms.util.GroupInfoUtil;
import com.alibaba.polardbx.gms.util.TableGroupNameUtil;
import com.alibaba.polardbx.optimizer.OptimizerContext;
import com.alibaba.polardbx.optimizer.config.table.SchemaManager;
import com.alibaba.polardbx.optimizer.config.table.TableMeta;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.partition.PartitionInfo;
import com.alibaba.polardbx.optimizer.partition.PartitionSpec;
import com.alibaba.polardbx.optimizer.partition.PartitionTableType;
import com.alibaba.polardbx.optimizer.tablegroup.TableGroupInfoManager;
import lombok.Getter;
import org.apache.commons.lang.StringUtils;

import java.sql.Connection;
import java.util.List;

@Getter
@TaskName(name = "AlterTableSetTableGroupChangeMetaOnlyTask")
public class AlterTableSetTableGroupChangeMetaOnlyTask extends BaseDdlTask {

    protected String curTableGroup;
    protected String targetTableGroup;
    protected String logicalTable;
    protected boolean tableGroupExists;
    protected boolean reCreatePartitionGroups;

    @JSONCreator
    public AlterTableSetTableGroupChangeMetaOnlyTask(String schemaName, String logicalTable, String curTableGroup,
                                                     String targetTableGroup, boolean reCreatePartitionGroups,
                                                     boolean tableGroupExists) {
        super(schemaName);
        this.logicalTable = logicalTable;
        this.curTableGroup = curTableGroup;
        this.targetTableGroup = targetTableGroup;
        this.reCreatePartitionGroups = reCreatePartitionGroups;
        this.tableGroupExists = tableGroupExists;
    }

    public void executeImpl(Connection metaDbConnection, ExecutionContext executionContext) {
        changeMeta(metaDbConnection, executionContext);
        FailPoint.injectRandomExceptionFromHint(executionContext);
        FailPoint.injectRandomSuspendFromHint(executionContext);
    }

    public void rollbackImpl(Connection metaDbConnection, ExecutionContext executionContext) {
        restoreMeta(metaDbConnection, executionContext);
        FailPoint.injectRandomExceptionFromHint(executionContext);
        FailPoint.injectRandomSuspendFromHint(executionContext);
    }

    @Override
    protected void duringTransaction(Connection metaDbConnection, ExecutionContext executionContext) {
        executeImpl(metaDbConnection, executionContext);
    }

    @Override
    protected void duringRollbackTransaction(Connection metaDbConnection, ExecutionContext executionContext) {
        rollbackImpl(metaDbConnection, executionContext);
    }

    @Override
    protected void onExecutionSuccess(ExecutionContext executionContext) {
    }

    @Override
    protected void onRollbackSuccess(ExecutionContext executionContext) {
    }

    protected void updateTableVersion(Connection metaDbConnection, String schemaName, String logicalTableName, ExecutionContext executionContext) {
        try {
            SchemaManager schemaManager = executionContext.getSchemaManager(schemaName);
            TableMeta tableMeta = schemaManager.getTable(logicalTableName);
            if (tableMeta.isGsi()) {
                //all the gsi table version change will be behavior by primary table
                assert
                    tableMeta.getGsiTableMetaBean() != null && tableMeta.getGsiTableMetaBean().gsiMetaBean != null;
                logicalTableName = tableMeta.getGsiTableMetaBean().gsiMetaBean.tableName;
            }
            TableInfoManager.updateTableVersion(schemaName, logicalTableName, metaDbConnection);
        } catch (Exception e) {
            throw GeneralUtil.nestedException(e);
        }
    }

    public void changeMeta(Connection metaDbConnection, ExecutionContext executionContext) {
        PartitionInfo partitionInfo =
            executionContext.getSchemaManager(schemaName).getTable(logicalTable).getPartitionInfo();
        List<PartitionGroupRecord> partitionGroupRecords;
        Long tableGroupId;
        final TableGroupInfoManager tableGroupInfoManager =
            OptimizerContext.getContext(schemaName).getTableGroupInfoManager();
        TableGroupConfig targetTableGroupConfig = tableGroupInfoManager.getTableGroupConfigByName(targetTableGroup);
        if (StringUtils.isEmpty(targetTableGroup) || targetTableGroupConfig == null) {
            reCreatePartitionGroups = true;
            tableGroupExists = false;
            partitionGroupRecords = null;
            tableGroupId = -1L;
        } else {
            if (GeneralUtil.isEmpty(targetTableGroupConfig.getAllTables())) {
                // 1 create the partition group (when partition group is exists, delete if firstly),
                // 2 change the table_group reference
                reCreatePartitionGroups = true;
                tableGroupExists = true;
                tableGroupId = targetTableGroupConfig.getTableGroupRecord().id;
                partitionGroupRecords = null;
            } else {
                //the location of target partition group is identical to the table's, just change the groupId for this case
                reCreatePartitionGroups = false;
                tableGroupExists = true;
                tableGroupId = targetTableGroupConfig.getTableGroupRecord().id;
                partitionGroupRecords = targetTableGroupConfig.getPartitionGroupRecords();
            }
        }
        addNewPartitionGroupFromPartitionInfo(partitionInfo,
            partitionGroupRecords,
            tableGroupId, metaDbConnection);
        updateTableVersion(metaDbConnection, schemaName, logicalTable, executionContext);
    }

    private void restoreMeta(Connection metaDbConnection, ExecutionContext executionContext) {
        PartitionGroupAccessor partitionGroupAccessor = new PartitionGroupAccessor();
        TablePartitionAccessor tablePartitionAccessor = new TablePartitionAccessor();
        TableGroupAccessor tableGroupAccessor = new TableGroupAccessor();
        partitionGroupAccessor.setConnection(metaDbConnection);
        tablePartitionAccessor.setConnection(metaDbConnection);
        tableGroupAccessor.setConnection(metaDbConnection);

        PartitionInfo partitionInfo =
            executionContext.getSchemaManager(schemaName).getTable(logicalTable).getPartitionInfo();

        List<TableGroupRecord> targetTableGroupRecords =
            tableGroupAccessor.getTableGroupsBySchemaAndName(schemaName, targetTableGroup, false);

        List<TableGroupRecord> curTableGroupRecords =
            tableGroupAccessor.getTableGroupsBySchemaAndName(schemaName, curTableGroup, false);

        if (GeneralUtil.isNotEmpty(targetTableGroupRecords) && GeneralUtil.isNotEmpty(curTableGroupRecords)) {
            assert targetTableGroupRecords.size() == 1;
            assert curTableGroupRecords.size() == 1;
            TableGroupRecord tableGroupRecord = targetTableGroupRecords.get(0);
            if (!tableGroupExists) {
                tableGroupAccessor.deleteTableGroupsById(schemaName, tableGroupRecord.getId());
            }
            if (reCreatePartitionGroups) {
                partitionGroupAccessor.deletePartitionGroupsByTableGroupId(tableGroupRecord.getId(), false);
            }
            List<PartitionGroupRecord> partitionGroupRecords =
                partitionGroupAccessor.getPartitionGroupsByTableGroupId(curTableGroupRecords.get(0).getId(), false);

            assert partitionGroupRecords.size() == partitionInfo.getPartitionBy().getPartitions().size();
            boolean firstPart = true;
            for (PartitionSpec partitionSpec : partitionInfo.getPartitionBy().getPartitions()) {
                PartitionGroupRecord partitionGroupRecord = partitionGroupRecords.stream()
                    .filter(o -> o.partition_name.equalsIgnoreCase(partitionSpec.getName())).findFirst()
                    .orElse(null);
                tablePartitionAccessor.updateGroupIdById(partitionGroupRecord.id, partitionSpec.getId());
                if (firstPart) {
                    tablePartitionAccessor
                        .updateGroupIdById(partitionGroupRecord.tg_id, partitionSpec.getParentId());
                }
                firstPart = false;
            }
        }
        updateTableVersion(metaDbConnection, schemaName, logicalTable, executionContext);
    }

    private void addNewPartitionGroupFromPartitionInfo(PartitionInfo partitionInfo,
                                                       List<PartitionGroupRecord> partitionGroupRecords,
                                                       Long tableGroupId,
                                                       Connection connection) {
        PartitionGroupAccessor partitionGroupAccessor = new PartitionGroupAccessor();
        TablePartitionAccessor tablePartitionAccessor = new TablePartitionAccessor();
        TableGroupAccessor tableGroupAccessor = new TableGroupAccessor();
        partitionGroupAccessor.setConnection(connection);
        tablePartitionAccessor.setConnection(connection);
        tableGroupAccessor.setConnection(connection);
        boolean firstPart = true;
        if (reCreatePartitionGroups) {
            if (!tableGroupExists) {
                TableGroupRecord tableGroupRecord = new TableGroupRecord();
                tableGroupRecord.schema = partitionInfo.getTableSchema();
                tableGroupRecord.tg_name = String.valueOf(System.currentTimeMillis());
                tableGroupRecord.meta_version = 0L;
                if (partitionInfo.getTableType() == PartitionTableType.SINGLE_TABLE) {
                    if (partitionInfo.getTableGroupId() != TableGroupRecord.INVALID_TABLE_GROUP_ID) {
                        // Come here is alter table group id for a single table
                        tableGroupRecord.tg_type = TableGroupRecord.TG_TYPE_NON_DEFAULT_SINGLE_TBL_TG;
                    } else {
                        tableGroupRecord.tg_type = TableGroupRecord.TG_TYPE_DEFAULT_SINGLE_TBL_TG;
                    }
                } else if (partitionInfo.getTableType() == PartitionTableType.BROADCAST_TABLE) {
                    tableGroupRecord.tg_type = TableGroupRecord.TG_TYPE_BROADCAST_TBL_TG;
                } else {
                    tableGroupRecord.tg_type = TableGroupRecord.TG_TYPE_PARTITION_TBL_TG;
                }
                tableGroupId = tableGroupAccessor.addNewTableGroup(tableGroupRecord);
                int tgType = tableGroupRecord.tg_type;
                String finalTgName = TableGroupNameUtil.autoBuildTableGroupName(tableGroupId, tgType);
                List<TableGroupRecord> tableGroupRecords =
                    tableGroupAccessor
                        .getTableGroupsBySchemaAndName(partitionInfo.getTableSchema(), finalTgName, false);
                if (GeneralUtil.isNotEmpty(tableGroupRecords)) {
                    finalTgName = "tg" + tableGroupRecord.tg_name;
                }
                tableGroupAccessor.updateTableGroupName(tableGroupId, finalTgName);
                targetTableGroup = finalTgName;//will pass the new create targetTableGroup to the following tasks

            } else {
                int tableGroupType = TableGroupRecord.TG_TYPE_PARTITION_TBL_TG;
                if (partitionInfo.getTableType() == PartitionTableType.SINGLE_TABLE) {
                    if (partitionInfo.getTableGroupId() != TableGroupRecord.INVALID_TABLE_GROUP_ID) {
                        // Come here is alter table group id for a single table
                        tableGroupType = TableGroupRecord.TG_TYPE_NON_DEFAULT_SINGLE_TBL_TG;
                    } else {
                        tableGroupType = TableGroupRecord.TG_TYPE_DEFAULT_SINGLE_TBL_TG;
                    }
                } else if (partitionInfo.getTableType() == PartitionTableType.BROADCAST_TABLE) {
                    tableGroupType = TableGroupRecord.TG_TYPE_BROADCAST_TBL_TG;
                }
                tableGroupAccessor.updateTableGroupType(tableGroupId, tableGroupType);
                partitionGroupAccessor.deletePartitionGroupsByTableGroupId(tableGroupId, false);
            }
            for (PartitionSpec partitionSpec : partitionInfo.getPartitionBy().getPartitions()) {
                PartitionGroupRecord partitionGroupRecord = new PartitionGroupRecord();
                partitionGroupRecord.visible = 1;
                partitionGroupRecord.partition_name = partitionSpec.getName();
                partitionGroupRecord.tg_id = tableGroupId;
                partitionGroupRecord.phy_db =
                    GroupInfoUtil.buildPhysicalDbNameFromGroupName(partitionSpec.getLocation().getGroupKey());
                partitionGroupRecord.locality = "";
                partitionGroupRecord.pax_group_id = 0L;
                Long newPartitionGroupId =
                    partitionGroupAccessor.addNewPartitionGroup(partitionGroupRecord, false);
                tablePartitionAccessor.updateGroupIdById(newPartitionGroupId, partitionSpec.getId());
                if (firstPart) {
                    tablePartitionAccessor.updateGroupIdById(tableGroupId, partitionSpec.getParentId());
                }
                firstPart = false;
            }
        } else {
            assert partitionGroupRecords.size() == partitionInfo.getPartitionBy().getPartitions().size();
            for (PartitionSpec partitionSpec : partitionInfo.getPartitionBy().getPartitions()) {
                PartitionGroupRecord partitionGroupRecord = partitionGroupRecords.stream()
                    .filter(o -> o.partition_name.equalsIgnoreCase(partitionSpec.getName())).findFirst()
                    .orElse(null);
                tablePartitionAccessor.updateGroupIdById(partitionGroupRecord.id, partitionSpec.getId());
                if (firstPart) {
                    tablePartitionAccessor.updateGroupIdById(tableGroupId, partitionSpec.getParentId());
                }
                firstPart = false;
            }
        }
    }

}
