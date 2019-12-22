/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.lang.reflect.Method;

public class RabbitMQConsumerInterceptor implements InstanceMethodsAroundInterceptor {
    public static final String OPERATE_NAME_PREFIX = "RabbitMQ/";
    public static final String CONSUMER_OPERATE_NAME_SUFFIX = "/Consumer/Invoke/";

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes, MethodInterceptResult result) throws Throwable {
        ContextCarrier contextCarrier = new ContextCarrier();

        Connection connection = ((Channel)allArguments[0]).getConnection();
        String url =  connection.getAddress().toString().replace("/","") + ":" + connection.getPort();
        Message message = (Message) allArguments[1];
        MessageProperties msgProperties = message.getMessageProperties();
        String msgTopic = msgProperties.getReceivedRoutingKey();
        // show message content in trace
        String msgContent = new String(message.getBody());
        AbstractSpan activeSpan = ContextManager.createEntrySpan(OPERATE_NAME_PREFIX + "Topic/" + msgTopic +
                CONSUMER_OPERATE_NAME_SUFFIX + msgContent, null).start(System.currentTimeMillis());

        Tags.MQ_BROKER.set(activeSpan, url);
        Tags.MQ_TOPIC.set(activeSpan, msgProperties.getReceivedExchange());
        Tags.MQ_QUEUE.set(activeSpan, msgProperties.getReceivedRoutingKey());
        activeSpan.setComponent(ComponentsDefine.RABBITMQ_CONSUMER);
        SpanLayer.asMQ(activeSpan);
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            if (msgProperties.getHeaders() != null && msgProperties.getHeader(next.getHeadKey()) != null) {
                next.setHeadValue(msgProperties.getHeader(next.getHeadKey()).toString());
            }
        }
        ContextManager.extract(contextCarrier);


    }


    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes, Object ret) throws Throwable {
        ContextManager.stopSpan();
        return ret;

    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes, Throwable t) {
        ContextManager.activeSpan().errorOccurred().log(t);
    }
}
