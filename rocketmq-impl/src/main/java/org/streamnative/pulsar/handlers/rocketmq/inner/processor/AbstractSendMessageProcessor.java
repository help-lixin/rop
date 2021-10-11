/**
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

package org.streamnative.pulsar.handlers.rocketmq.inner.processor;

import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.broker.mqtrace.SendMessageContext;
import org.apache.rocketmq.broker.mqtrace.SendMessageHook;
import org.apache.rocketmq.broker.topic.TopicValidator;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.TopicFilterType;
import org.apache.rocketmq.common.constant.DBMsgConstants;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.help.FAQUrl;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.protocol.NamespaceUtil;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.SendMessageRequestHeader;
import org.apache.rocketmq.common.protocol.header.SendMessageRequestHeaderV2;
import org.apache.rocketmq.common.protocol.header.SendMessageResponseHeader;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.common.sysflag.TopicSysFlag;
import org.apache.rocketmq.common.utils.ChannelUtil;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.protocol.LanguageCode;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.streamnative.pulsar.handlers.rocketmq.RocketMQServiceConfiguration;
import org.streamnative.pulsar.handlers.rocketmq.inner.RocketMQBrokerController;
import org.streamnative.pulsar.handlers.rocketmq.inner.RopClientChannelCnx;
import org.streamnative.pulsar.handlers.rocketmq.inner.namesvr.TopicConfigManager;
import org.streamnative.pulsar.handlers.rocketmq.inner.pulsar.PulsarMessageStore;
import org.streamnative.pulsar.handlers.rocketmq.utils.RocketMQTopic;

/**
 * Abstract send message processor.
 */
@Slf4j
public abstract class AbstractSendMessageProcessor implements NettyRequestProcessor {

    protected static final int DLQ_NUMS_PER_GROUP = 1;
    protected final RocketMQBrokerController brokerController;
    protected final Random random = new Random(System.currentTimeMillis());
    private List<SendMessageHook> sendMessageHookList;

    public AbstractSendMessageProcessor(final RocketMQBrokerController brokerController) {
        this.brokerController = brokerController;
    }

    protected PulsarMessageStore getServerCnxMsgStore(ChannelHandlerContext ctx, String groupName) {

        RopClientChannelCnx channelCnx = (RopClientChannelCnx) this.brokerController.getProducerManager()
                .findChlInfo(groupName, ctx.channel());
        if (channelCnx == null) {
            synchronized (ctx) {
                channelCnx = (RopClientChannelCnx) this.brokerController.getProducerManager()
                        .findChlInfo(groupName, ctx.channel());
                if (channelCnx == null) {
                    String clientId = ctx.channel().remoteAddress().toString() + "@" + System.currentTimeMillis();
                    channelCnx = new RopClientChannelCnx(this.brokerController, ctx, clientId, LanguageCode.JAVA, 0);
                    this.brokerController.getProducerManager().registerProducer(groupName, channelCnx);
                }
            }
        }
        return channelCnx.getServerCnx();
    }

    protected SendMessageContext buildMsgContext(ChannelHandlerContext ctx,
            SendMessageRequestHeader requestHeader) {
        if (!this.hasSendMessageHook()) {
            return null;
        }
        String namespace = NamespaceUtil.getNamespaceFromResource(requestHeader.getTopic());
        SendMessageContext mqtraceContext;
        mqtraceContext = new SendMessageContext();
        mqtraceContext.setProducerGroup(requestHeader.getProducerGroup());
        mqtraceContext.setNamespace(namespace);
        mqtraceContext.setTopic(requestHeader.getTopic());
        mqtraceContext.setMsgProps(requestHeader.getProperties());
        mqtraceContext.setBornHost(RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
        mqtraceContext.setBornTimeStamp(requestHeader.getBornTimestamp());

        Map<String, String> properties = MessageDecoder.string2messageProperties(requestHeader.getProperties());
        String uniqueKey = properties.get(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX);
        requestHeader.setProperties(MessageDecoder.messageProperties2String(properties));

        if (uniqueKey == null) {
            uniqueKey = "";
        }
        mqtraceContext.setMsgUniqueKey(uniqueKey);
        return mqtraceContext;
    }

    public boolean hasSendMessageHook() {
        return sendMessageHookList != null && !this.sendMessageHookList.isEmpty();
    }

    protected MessageExtBrokerInner buildInnerMsg(final ChannelHandlerContext ctx,
            final SendMessageRequestHeader requestHeader, final byte[] body, TopicConfig topicConfig) {
        int queueIdInt = requestHeader.getQueueId();
        if (queueIdInt < 0) {
            queueIdInt = Math.abs(this.random.nextInt() % 99999999) % topicConfig.getWriteQueueNums();
        }
        int sysFlag = requestHeader.getSysFlag();

        if (TopicFilterType.MULTI_TAG == topicConfig.getTopicFilterType()) {
            sysFlag |= MessageSysFlag.MULTI_TAGS_FLAG;
        }

        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setTopic(requestHeader.getTopic());
        msgInner.setBody(body);
        msgInner.setFlag(requestHeader.getFlag());
        MessageAccessor.setProperties(msgInner,
                MessageDecoder.string2messageProperties(requestHeader.getProperties()));
        msgInner.setPropertiesString(requestHeader.getProperties());
        msgInner.setTagsCode(MessageExtBrokerInner.tagsString2tagsCode(topicConfig.getTopicFilterType(),
                msgInner.getTags()));

        msgInner.setQueueId(queueIdInt);
        msgInner.setSysFlag(sysFlag);
        msgInner.setBornTimestamp(requestHeader.getBornTimestamp());
        msgInner.setBornHost(ctx.channel().remoteAddress());
        msgInner.setStoreHost(ctx.channel().localAddress());
        msgInner.setReconsumeTimes(requestHeader.getReconsumeTimes() == null ? 0 : requestHeader
                .getReconsumeTimes());
        return msgInner;
    }

    protected RemotingCommand msgContentCheck(final ChannelHandlerContext ctx,
            final SendMessageRequestHeader requestHeader, RemotingCommand request,
            final RemotingCommand response) {
        if (requestHeader.getTopic().length() > Byte.MAX_VALUE) {
            log.warn("putMessage message topic length too long {}", requestHeader.getTopic().length());
            response.setCode(ResponseCode.MESSAGE_ILLEGAL);
            return response;
        }
        if (requestHeader.getProperties() != null && requestHeader.getProperties().length() > Short.MAX_VALUE) {
            log.warn("putMessage message properties length too long {}", requestHeader.getProperties().length());
            response.setCode(ResponseCode.MESSAGE_ILLEGAL);
            return response;
        }
        if (request.getBody().length > DBMsgConstants.MAX_BODY_SIZE) {
            log.warn(" topic {}  msg body size {}  from {}", requestHeader.getTopic(),
                    request.getBody().length, ChannelUtil.getRemoteIp(ctx.channel()));
            response.setRemark("msg body must be less 64KB");
            response.setCode(ResponseCode.MESSAGE_ILLEGAL);
            return response;
        }
        return response;
    }

    public static void msgCheck(final RocketMQServiceConfiguration config,
            final TopicConfigManager topicManager,
            final ChannelHandlerContext ctx,
            final SendMessageRequestHeader requestHeader, final RemotingCommand response) {
        if (!PermName.isWriteable(config.getBrokerPermission())
                && topicManager.isOrderTopic(requestHeader.getTopic())) {
            response.setCode(ResponseCode.NO_PERMISSION);
            response.setRemark("the broker[" //+ this.brokerController.getBrokerConfig().getBrokerIP1()
                    + "] sending message is forbidden");
            return;
        }

        if (!TopicValidator.validateTopic(requestHeader.getTopic(), response)) {
            return;
        }
        TopicConfig topicConfig = topicManager
                .selectTopicConfig(RocketMQTopic.getPulsarOrigNoDomainTopic(requestHeader.getTopic()));
        if (null == topicConfig) {
            int topicSysFlag = 0;
            if (requestHeader.isUnitMode()) {
                if (requestHeader.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    topicSysFlag = TopicSysFlag.buildSysFlag(false, true);
                } else {
                    topicSysFlag = TopicSysFlag.buildSysFlag(true, false);
                }
            }

            log.warn("the topic {} not exist, producer: {}", requestHeader.getTopic(), ctx.channel().remoteAddress());
            topicConfig = topicManager.createTopicInSendMessageMethod(
                    requestHeader.getTopic(),
                    requestHeader.getDefaultTopic(),
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                    requestHeader.getDefaultTopicQueueNums(), topicSysFlag);

            if (null == topicConfig) {
                if (requestHeader.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    topicConfig = topicManager.createTopicInSendMessageBackMethod(
                            requestHeader.getTopic(), 1, PermName.PERM_WRITE | PermName.PERM_READ,
                            topicSysFlag);
                }
            }

            if (null == topicConfig) {
                response.setCode(ResponseCode.TOPIC_NOT_EXIST);
                response.setRemark("topic[" + requestHeader.getTopic() + "] not exist, apply first please!"
                        + FAQUrl.suggestTodo(FAQUrl.APPLY_TOPIC_URL));
                return;
            }
        }

        int queueIdInt = requestHeader.getQueueId();
        int idValid = Math.max(topicConfig.getWriteQueueNums(), topicConfig.getReadQueueNums());
        if (queueIdInt >= idValid) {
            String errorInfo = String.format("request queueId[%d] is illegal, %s Producer: %s",
                    queueIdInt,
                    topicConfig.toString(),
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()));

            log.warn(errorInfo);
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark(errorInfo);

        }
    }

    public void registerSendMessageHook(List<SendMessageHook> sendMessageHookList) {
        this.sendMessageHookList = sendMessageHookList;
    }

    protected void doResponse(ChannelHandlerContext ctx, RemotingCommand request, final RemotingCommand response) {
        if (!request.isOnewayRPC()) {
            try {
                ctx.writeAndFlush(response);
            } catch (Throwable e) {
                log.error("SendMessageProcessor process request over, but response failed", e);
                log.error(request.toString());
                log.error(response.toString());
            }
        }
    }

    public void executeSendMessageHookBefore(final ChannelHandlerContext ctx, final RemotingCommand request,
            SendMessageContext context) {
        if (hasSendMessageHook()) {
            for (SendMessageHook hook : this.sendMessageHookList) {
                try {
                    final SendMessageRequestHeader requestHeader = parseRequestHeader(request);

                    String namespace = NamespaceUtil.getNamespaceFromResource(requestHeader.getTopic());
                    context.setNamespace(namespace);
                    context.setProducerGroup(requestHeader.getProducerGroup());
                    context.setTopic(requestHeader.getTopic());
                    context.setBodyLength(request.getBody().length);
                    context.setMsgProps(requestHeader.getProperties());
                    context.setBornHost(RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
                    // context.setBrokerAddr(this.brokerController.getBrokerAddr());
                    context.setQueueId(requestHeader.getQueueId());

                    hook.sendMessageBefore(context);
                    requestHeader.setProperties(context.getMsgProps());
                } catch (Throwable e) {
                    // Ignore
                }
            }
        }
    }

    public static SendMessageRequestHeader parseRequestHeader(RemotingCommand request)
            throws RemotingCommandException {

        SendMessageRequestHeaderV2 requestHeaderV2 = null;
        SendMessageRequestHeader requestHeader = null;
        if (request.getCode() == RequestCode.SEND_BATCH_MESSAGE || request.getCode() == RequestCode.SEND_MESSAGE_V2) {
            requestHeaderV2 =
                    (SendMessageRequestHeaderV2) request
                            .decodeCommandCustomHeader(SendMessageRequestHeaderV2.class);


        }
        if (null == requestHeaderV2) {
            requestHeader =
                    (SendMessageRequestHeader) request
                            .decodeCommandCustomHeader(SendMessageRequestHeader.class);
        } else {
            requestHeader = SendMessageRequestHeaderV2.createSendMessageRequestHeaderV1(requestHeaderV2);
        }
        return requestHeader;
    }

    public void executeSendMessageHookAfter(final RemotingCommand response, final SendMessageContext context) {
        if (hasSendMessageHook()) {
            for (SendMessageHook hook : this.sendMessageHookList) {
                try {
                    if (response != null) {
                        final SendMessageResponseHeader responseHeader =
                                (SendMessageResponseHeader) response.readCustomHeader();
                        context.setMsgId(responseHeader.getMsgId());
                        context.setQueueId(responseHeader.getQueueId());
                        context.setQueueOffset(responseHeader.getQueueOffset());
                        context.setCode(response.getCode());
                        context.setErrorMsg(response.getRemark());
                    }
                    hook.sendMessageAfter(context);
                } catch (Throwable e) {
                    // Ignore
                }
            }
        }
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }
}
