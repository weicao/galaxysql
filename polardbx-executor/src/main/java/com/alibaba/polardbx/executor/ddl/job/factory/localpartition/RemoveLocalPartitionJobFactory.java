package com.alibaba.polardbx.executor.ddl.job.factory.localpartition;

import com.alibaba.polardbx.common.exception.TddlNestableRuntimeException;
import com.alibaba.polardbx.druid.DbType;
import com.alibaba.polardbx.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLAlterTableStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.polardbx.executor.ddl.job.builder.LocalPartitionPhysicalSqlBuilder;
import com.alibaba.polardbx.executor.ddl.job.task.basic.RemoveLocalPartitionTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.TableSyncTask;
import com.alibaba.polardbx.executor.ddl.job.task.localpartition.LocalPartitionPhyDdlTask;
import com.alibaba.polardbx.executor.ddl.job.task.localpartition.LocalPartitionValidateTask;
import com.alibaba.polardbx.executor.ddl.newengine.job.DdlJobFactory;
import com.alibaba.polardbx.executor.ddl.newengine.job.DdlTask;
import com.alibaba.polardbx.executor.ddl.newengine.job.ExecutableDdlJob;
import com.alibaba.polardbx.optimizer.OptimizerContext;
import com.alibaba.polardbx.optimizer.config.table.GsiMetaManager;
import com.alibaba.polardbx.optimizer.config.table.TableMeta;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.ReorganizeLocalPartitionPreparedData;
import org.apache.calcite.rel.core.DDL;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlPhyDdlWrapper;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author guxu
 */
public class RemoveLocalPartitionJobFactory extends DdlJobFactory {

    private DDL ddl;
    private String schemaName;
    private String primaryTableName;
    private ExecutionContext executionContext;

    public RemoveLocalPartitionJobFactory(String schemaName,
                                          String primaryTableName,
                                          DDL ddl,
                                          ExecutionContext executionContext) {
        this.schemaName = schemaName;
        this.primaryTableName = primaryTableName;
        this.ddl = ddl;
        this.executionContext = executionContext;
    }

    @Override
    protected void validate() {

    }

    @Override
    protected ExecutableDdlJob doCreate() {
        final TableMeta primaryTableMeta =
            OptimizerContext.getContext(schemaName).getLatestSchemaManager().getTable(primaryTableName);
        if(primaryTableMeta.getLocalPartitionDefinitionInfo() == null){
            throw new TddlNestableRuntimeException(String.format(
                "table %s.%s is not a local partition table", schemaName, primaryTableName));
        }

        SQLAlterTableStatement alterTableStatement = new SQLAlterTableStatement();
        alterTableStatement.setTableSource(new SQLExprTableSource(new SQLIdentifierExpr("?")));
        alterTableStatement.setDbType(DbType.mysql);
        alterTableStatement.setRemovePatiting(true);
        final String phySql = alterTableStatement.toString();

        Map<String, GsiMetaManager.GsiIndexMetaBean> publishedGsi = primaryTableMeta.getGsiPublished();

        ExecutableDdlJob executableDdlJob = new ExecutableDdlJob();
        List<DdlTask> taskList = new ArrayList<>();
        taskList.add(genPhyDdlTask(schemaName, primaryTableName, phySql));
        if(publishedGsi != null){
            publishedGsi.forEach((gsiName, gsiIndexMetaBean) -> {
                taskList.add(genPhyDdlTask(schemaName, gsiName, phySql));
            });
        }
        taskList.add(new RemoveLocalPartitionTask(schemaName, primaryTableName));
        taskList.add(new TableSyncTask(schemaName, primaryTableName));
        executableDdlJob.addSequentialTasks(taskList);
        return executableDdlJob;
    }

    private LocalPartitionPhyDdlTask genPhyDdlTask(String schemaName, String tableName, String phySql){
        ddl.sqlNode = SqlPhyDdlWrapper.createForAllocateLocalPartition(new SqlIdentifier(tableName, SqlParserPos.ZERO), phySql);
        LocalPartitionPhysicalSqlBuilder builder = new LocalPartitionPhysicalSqlBuilder(
            ddl, new ReorganizeLocalPartitionPreparedData(schemaName, tableName), executionContext
        );
        builder.build();
        LocalPartitionPhyDdlTask phyDdlTask = new LocalPartitionPhyDdlTask(schemaName, builder.genPhysicalPlanData());
        return phyDdlTask;
    }

    @Override
    protected void excludeResources(Set<String> resources) {
        resources.add(concatWithDot(schemaName, primaryTableName));
    }

    @Override
    protected void sharedResources(Set<String> resources) {

    }
}