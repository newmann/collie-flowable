package wf.flowable

import groovy.transform.CompileStatic
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType
import org.flowable.engine.ProcessEngine
import org.flowable.engine.ProcessEngineConfiguration
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.DataSource

@CompileStatic
class FlowableToolFactory implements ToolFactory<ProcessEngine> {
    protected final static Logger logger = LoggerFactory.getLogger(FlowableToolFactory.class)
    final static String TOOL_NAME = "Flowable"

    protected ExecutionContextFactory ecf = null
    protected ProcessEngine processEngine = null
    protected MoquiServiceDelegate serviceDelegate = null

    FlowableToolFactory() { }

    @Override String getName() { return TOOL_NAME }

    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
        DataSource dataSource = ecf.entity.getDatasourceFactory("transactional")?.getDataSource()
        if (dataSource == null) {
            throw new IllegalStateException("No JDBC DataSource for group transactional; cannot start Flowable")
        }
        dropLegacyLinkUniqueIndex(dataSource)

        boolean jobExecutorEnabled = !"false".equalsIgnoreCase(SystemBinding.getPropOrEnv("flowable_job_executor_enabled"))
        serviceDelegate = new MoquiServiceDelegate(ecf)

        ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                .createStandaloneProcessEngineConfiguration()
        config.setDataSource(dataSource)
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
        config.setAsyncExecutorActivate(jobExecutorEnabled)
        config.setEngineName("MoquiFlowable")
        config.setDisableIdmEngine(true)
        // Share Moqui's JTA enlistment; do not JDBC-commit an already-enlisted connection (CF-51).
        config.setTransactionsExternallyManaged(true)
        Map<Object, Object> beans = new HashMap<>()
        beans.put("moquiServiceDelegate", serviceDelegate)
        config.setBeans(beans)
        config.setCustomPreCommandInterceptors([new MoquiJobCommandInterceptor(ecf)])

        processEngine = config.buildProcessEngine()
        processEngine.runtimeService.addEventListener(new MoquiAssignmentListener(),
                FlowableEngineEventType.TASK_CREATED)
        processEngine.runtimeService.addEventListener(new MoquiTaskNotificationListener(),
                FlowableEngineEventType.TASK_CREATED)
        processEngine.runtimeService.addEventListener(new MoquiCallActivityLinkListener(),
                FlowableEngineEventType.PROCESS_STARTED)
        processEngine.runtimeService.addEventListener(new MoquiProcessEndListener(),
                FlowableEngineEventType.PROCESS_COMPLETED, FlowableEngineEventType.PROCESS_CANCELLED)

        logger.info("Flowable ProcessEngine started (jobExecutor=${jobExecutorEnabled})")
        WorkflowSupport.scanAndDeploySeedBpmn(ecf, processEngine)
    }

    @Override
    ProcessEngine getInstance(Object... parameters) {
        if (processEngine == null) throw new IllegalStateException("Flowable ProcessEngine is not initialized")
        return processEngine
    }

    @Override
    void destroy() {
        if (processEngine != null) {
            try {
                processEngine.close()
                logger.info("Flowable ProcessEngine closed")
            } catch (Throwable t) {
                logger.error("Error closing Flowable ProcessEngine", t)
            }
            processEngine = null
        }
    }

    /** Old unique (entityName, pkValue, key) blocked Ended history + restart (CF-24). */
    protected static void dropLegacyLinkUniqueIndex(DataSource dataSource) {
        java.sql.Connection con = null
        java.sql.Statement st = null
        try {
            con = dataSource.getConnection()
            st = con.createStatement()
            String[] sqls = [
                    "ALTER TABLE WF_PROCESS_LINK DROP CONSTRAINT IF EXISTS WF_LINK_KEY",
                    "DROP INDEX IF EXISTS WF_LINK_KEY",
                    "ALTER TABLE wf_process_link DROP CONSTRAINT IF EXISTS wf_link_key",
                    "DROP INDEX IF EXISTS wf_link_key"
            ]
            for (String sql : sqls) {
                try { st.execute(sql) } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            logger.warn("Could not drop legacy WF_LINK_KEY unique index: ${t.message}")
        } finally {
            if (st != null) try { st.close() } catch (Throwable ignored) { }
            if (con != null) try { con.close() } catch (Throwable ignored) { }
        }
    }
}
