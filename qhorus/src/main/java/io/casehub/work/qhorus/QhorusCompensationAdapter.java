/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.work.qhorus;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageDispatcher;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.work.api.WorkItemStatusEvent;
import io.casehub.work.api.spi.WorkItemObserver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class QhorusCompensationAdapter implements WorkItemObserver {

  private static final Logger LOG = Logger.getLogger(QhorusCompensationAdapter.class);
  private static final String QHORUS_PREFIX = "qhorus:";

  @Inject MessageDispatcher messageDispatcher;

  @Override
  public void onStatusChange(WorkItemStatusEvent event) {
    if (event.originRef() == null || !event.originRef().startsWith(QHORUS_PREFIX)) {
      return;
    }
    if (!event.status().isTerminal()) {
      return;
    }

    try {
      String body = event.originRef().substring(QHORUS_PREFIX.length());
      String[] parts = body.split("/", 3);
      if (parts.length < 2) {
        LOG.warnf("Malformed Qhorus originRef: %s", event.originRef());
        return;
      }
      UUID channelId = UUID.fromString(parts[0]);
      String correlationId = parts.length > 2 ? parts[2] : null;

      MessageType speechAct = mapToSpeechAct(event);
      if (speechAct == null) {
        return;
      }

      String content = buildCompensationContent(event);

      messageDispatcher.dispatch(MessageDispatch.builder()
          .channelId(channelId)
          .sender("workitems-compensation")
          .type(speechAct)
          .correlationId(correlationId)
          .content(content)
          .actorType(ActorType.SYSTEM)
          .tenancyId(event.tenancyId())
          .build());

      LOG.infof("Compensation notification sent to Qhorus channel=%s correlationId=%s status=%s",
          channelId, correlationId, event.status());
    } catch (Exception e) {
      LOG.warnf(e, "Qhorus compensation notification failed for originRef=%s workItemId=%s",
          event.originRef(), event.workItemId());
    }
  }

  private static MessageType mapToSpeechAct(WorkItemStatusEvent event) {
    return switch (event.eventType()) {
      case COMPLETED -> MessageType.DONE;
      case REJECTED, FAULTED, ESCALATED -> MessageType.FAILURE;
      case CANCELLED, EXPIRED, OBSOLETE -> MessageType.DECLINE;
      default -> null;
    };
  }

  private static String buildCompensationContent(WorkItemStatusEvent event) {
    return "{\"workItemId\":\"" + event.workItemId()
        + "\",\"status\":\"" + event.status()
        + "\",\"compensation\":true"
        + ",\"outcome\":" + jsonString(event.outcome())
        + ",\"detail\":" + jsonString(event.detail()) + "}";
  }

  private static String jsonString(String value) {
    return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
  }
}
