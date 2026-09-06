package wf.flowable

import groovy.transform.CompileStatic
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType
import org.flowable.common.engine.api.delegate.event.FlowableEvent
import org.flowable.common.engine.api.delegate.event.FlowableEventListener
import org.flowable.engine.delegate.event.FlowableProcessEngineEvent
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Mark WfProcessLink ended when the instance completes or is deleted. */
@CompileStatic
class MoquiProcessEndListener implements FlowableEventListener {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiProcessEndListener.class)

    @Override
    void onEvent(FlowableEvent event) {
        if (!(event instanceof FlowableProcessEngineEvent)) return
        String instanceId = ((FlowableProcessEngineEvent) event).processInstanceId
        if (!instanceId) return
        ExecutionContext ec = Moqui.getExecutionContext()
        if (ec == null) return
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            def links = ec.entity.find("wf.flowable.WfProcessLink")
                    .condition("processInstanceId", instanceId)
                    .condition("statusId", "WfLinkActive").list()
            for (def link : links) {
                link.set("statusId", "WfLinkEnded")
                link.update()
            }
        } catch (Throwable t) {
            logger.warn("Could not end WfProcessLink for ${instanceId}: ${t.message}")
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @Override boolean isFailOnException() { return false }
    @Override boolean isFireOnTransactionLifecycleEvent() { return false }
    @Override String getOnTransaction() { return null }
}
