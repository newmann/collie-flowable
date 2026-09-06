package wf.flowable

import groovy.transform.CompileStatic
import org.flowable.common.engine.api.delegate.Expression
import org.flowable.engine.delegate.DelegateExecution
import org.flowable.engine.delegate.JavaDelegate
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Service Task bridge: call a Moqui service by name (CF-10 / CF-11 / CF-12). */
@CompileStatic
class MoquiServiceDelegate implements JavaDelegate {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiServiceDelegate.class)
    protected final ExecutionContextFactory ecf
    protected Expression serviceName

    MoquiServiceDelegate(ExecutionContextFactory ecf) { this.ecf = ecf }

    @Override
    void execute(DelegateExecution execution) {
        ExecutionContext ec = Moqui.getExecutionContext()
        if (ec == null) throw new IllegalStateException("No ExecutionContext on Service Task thread (CF-04)")
        String name = serviceName != null ? serviceName.getValue(execution)?.toString() : null
        if (name == null || name.isEmpty()) {
            name = execution.getVariable("serviceName")?.toString()
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Service Task missing serviceName")
        }

        Map<String, Object> params = new LinkedHashMap<>()
        Map<String, Object> vars = execution.getVariables()
        if (vars != null) params.putAll(vars)
        String businessKey = execution.getProcessInstanceBusinessKey()
        if (businessKey) params.put("businessKey", businessKey)

        logger.info("MoquiServiceDelegate calling ${name} businessKey=${businessKey}")
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        Map<String, Object> result
        try {
            result = ec.service.sync().name(name).parameters(params).call()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        if (ec.message.hasError()) {
            throw new RuntimeException("Moqui service ${name} reported errors: ${ec.message.errorsString}")
        }
        if (result != null) {
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                execution.setVariable(entry.key, entry.value)
            }
        }
    }
}
