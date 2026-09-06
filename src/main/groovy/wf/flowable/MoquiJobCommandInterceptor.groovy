package wf.flowable

import org.flowable.common.engine.impl.identity.Authentication
import org.flowable.common.engine.impl.interceptor.AbstractCommandInterceptor
import org.flowable.common.engine.impl.interceptor.Command
import org.flowable.common.engine.impl.interceptor.CommandConfig
import org.flowable.common.engine.impl.interceptor.CommandExecutor
import org.flowable.engine.ProcessEngine
import org.flowable.engine.history.HistoricProcessInstance
import org.flowable.engine.runtime.ProcessInstance
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.UserFacadeImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Ensures Flowable command threads have a Moqui ExecutionContext (CF-04). */
class MoquiJobCommandInterceptor extends AbstractCommandInterceptor {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiJobCommandInterceptor.class)
    protected final ExecutionContextFactory ecf

    MoquiJobCommandInterceptor(ExecutionContextFactory ecf) { this.ecf = ecf }

    @Override
    <T> T execute(CommandConfig config, Command<T> command, CommandExecutor commandExecutor) {
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) ecf
        boolean created = false
        if (ecfi.activeContext.get() == null) {
            ExecutionContext ec = ecfi.getExecutionContext()
            created = true
            loginJobUser(ec, command)
            if (!ec.transaction.isTransactionInPlace()) ec.transaction.begin(null)
            logger.debug("Created ExecutionContext for Flowable command on thread ${Thread.currentThread().name}")
        }
        try {
            return next.execute(config, command, commandExecutor)
        } catch (Throwable t) {
            if (created) {
                try { ecfi.getExecutionContext().transaction.rollback(true, "Flowable command failed", t) } catch (Throwable ignored) {}
            }
            throw t
        } finally {
            if (created) {
                ExecutionContext ec = ecfi.activeContext.get()
                if (ec != null && ec.transaction.isTransactionInPlace()) {
                    try { ec.transaction.commit() } catch (Throwable ignored) {}
                }
                ecfi.destroyActiveExecutionContext()
                logger.debug("Destroyed ExecutionContext after Flowable command on thread ${Thread.currentThread().name}")
            }
        }
    }

    static void loginJobUser(ExecutionContext ec, Command command) {
        String userId = resolveInitiatorUserId(ec, command)
        if (userId == null || userId.isEmpty() || userId == "_NA_") {
            ec.user.loginAnonymousIfNoUser()
            return
        }
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            def account = ec.entity.find("moqui.security.UserAccount")
                    .condition("userId", userId).useCache(true).one()
            if (account != null) {
                ((UserFacadeImpl) ec.user).internalLoginUser(account.getString("username"), false)
            } else {
                ec.user.loginAnonymousIfNoUser()
            }
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    protected static String resolveInitiatorUserId(ExecutionContext ec, Command command) {
        String userId = Authentication.getAuthenticatedUserId()
        if (userId && !userId.isEmpty() && userId != "_NA_") return userId
        String processInstanceId = extractProcessInstanceId(command)
        if (!processInstanceId) return null
        try {
            ProcessEngine pe = (ProcessEngine) ec.getTool("Flowable", ProcessEngine.class)
            ProcessInstance running = pe.runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult()
            if (running?.startUserId) return running.startUserId
            Object var = null
            try { var = pe.runtimeService.getVariable(processInstanceId, "initiatorUserId") } catch (Throwable ignored) {}
            if (var) return var.toString()
            HistoricProcessInstance historic = pe.historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult()
            if (historic?.startUserId) return historic.startUserId
            def histVar = pe.historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId).variableName("initiatorUserId").singleResult()
            if (histVar?.value) return histVar.value.toString()
        } catch (Throwable ignored) { }
        return null
    }

    protected static String extractProcessInstanceId(Command command) {
        if (command == null) return null
        Object value = invokeIfPresent(command, "getProcessInstanceId")
        if (value) return value.toString()
        Class cls = command.getClass()
        while (cls != null && cls != Object.class) {
            for (def field : cls.getDeclaredFields()) {
                try {
                    field.setAccessible(true)
                    Object fieldVal = field.get(command)
                    if (fieldVal == null) continue
                    String name = field.getName().toLowerCase()
                    if (name == "processinstanceid") return fieldVal.toString()
                    Object nested = invokeIfPresent(fieldVal, "getProcessInstanceId")
                    if (nested) return nested.toString()
                } catch (Throwable ignored) { }
            }
            cls = cls.getSuperclass()
        }
        return null
    }

    protected static Object invokeIfPresent(Object target, String methodName) {
        try {
            def method = target.getClass().getMethod(methodName)
            return method.invoke(target)
        } catch (Throwable ignored) {
            return null
        }
    }
}
