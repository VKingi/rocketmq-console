package com.alibaba.rocketmq.service;

import static com.alibaba.rocketmq.common.Tool.str;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.cli.Option;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.rocketmq.common.Table;
import com.alibaba.rocketmq.common.TopicConfig;
import com.alibaba.rocketmq.common.UtilAll;
import com.alibaba.rocketmq.common.admin.TopicOffset;
import com.alibaba.rocketmq.common.admin.TopicStatsTable;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.common.protocol.body.TopicList;
import com.alibaba.rocketmq.common.protocol.route.TopicRouteData;
import com.alibaba.rocketmq.tools.admin.DefaultMQAdminExt;
import com.alibaba.rocketmq.tools.command.CommandUtil;
import com.alibaba.rocketmq.tools.command.topic.DeleteTopicSubCommand;
import com.alibaba.rocketmq.tools.command.topic.TopicListSubCommand;
import com.alibaba.rocketmq.tools.command.topic.TopicRouteSubCommand;
import com.alibaba.rocketmq.tools.command.topic.TopicStatsSubCommand;
import com.alibaba.rocketmq.tools.command.topic.UpdateTopicSubCommand;
import com.alibaba.rocketmq.validate.CmdTrace;
import com.google.common.collect.Maps;

import static com.alibaba.rocketmq.common.Contants.KEY_MSG;
import static com.alibaba.rocketmq.common.Contants.KEY_RES;

/**
 * 
 * @author yankai913@gmail.com
 * @date 2014-2-11
 */
@Service
public class TopicService extends AbstractService {

    static final Logger logger = LoggerFactory.getLogger(TopicService.class);


    @CmdTrace(cmdClazz = TopicListSubCommand.class)
    public Table list() {
        DefaultMQAdminExt defaultMQAdminExt = getDefaultMQAdminExt();
        try {
            defaultMQAdminExt.start();
            TopicList topicList = defaultMQAdminExt.fetchAllTopicList();
            int row = topicList.getTopicList().size();
            if (row > 0) {
                Table table = new Table(new String[] { "topic" }, row);
                for (String topicName : topicList.getTopicList()) {
                    Object[] tr = table.createTR();
                    tr[0] = topicName;
                    table.insertTR(tr);
                }
                return table;
            }
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        finally {
            shutdownDefaultMQAdminExt(defaultMQAdminExt);
        }
        return null;
    }


    @CmdTrace(cmdClazz = TopicStatsSubCommand.class)
    public Table stats(String topicName) {
        DefaultMQAdminExt defaultMQAdminExt = getDefaultMQAdminExt();
        try {
            defaultMQAdminExt.start();
            TopicStatsTable topicStatsTable = defaultMQAdminExt.examineTopicStats(topicName);

            List<MessageQueue> mqList = new LinkedList<MessageQueue>();
            mqList.addAll(topicStatsTable.getOffsetTable().keySet());
            Collections.sort(mqList);

            // System.out.printf("%-32s  %-4s  %-20s  %-20s    %s\n",//
            // "#Broker Name",//
            // "#QID",//
            // "#Min Offset",//
            // "#Max Offset",//
            // "#Last Updated" //
            // );
            String[] thead =
                    new String[] { "#Broker Name", "#QID", "#Min Offset", "#Max Offset", "#Last Updated" };
            Table table = new Table(thead, mqList.size());
            for (MessageQueue mq : mqList) {
                TopicOffset topicOffset = topicStatsTable.getOffsetTable().get(mq);

                String humanTimestamp = "";
                if (topicOffset.getLastUpdateTimestamp() > 0) {
                    humanTimestamp = UtilAll.timeMillisToHumanString2(topicOffset.getLastUpdateTimestamp());
                }

                Object[] tr = table.createTR();
                tr[0] = UtilAll.frontStringAtLeast(mq.getBrokerName(), 32);
                tr[1] = str(mq.getQueueId());
                tr[2] = str(topicOffset.getMinOffset());
                tr[3] = str(topicOffset.getMaxOffset());
                tr[4] = humanTimestamp;

                table.insertTR(tr);
                // System.out.printf("%-32s  %-4d  %-20d  %-20d    %s\n",//
                // UtilAll.frontStringAtLeast(mq.getBrokerName(), 32),//
                // mq.getQueueId(),//
                // topicOffset.getMinOffset(),//
                // topicOffset.getMaxOffset(),//
                // humanTimestamp //
                // );
            }
            return table;
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        finally {
            shutdownDefaultMQAdminExt(defaultMQAdminExt);
        }
        return null;
    }

    final static UpdateTopicSubCommand updateTopicSubCommand = new UpdateTopicSubCommand();


    public Collection<Option> getOptionsForUpdate() {
        return getOptions(updateTopicSubCommand);
    }


    @CmdTrace(cmdClazz = UpdateTopicSubCommand.class)
    public Map<String, Object> update(String topic, String readQueueNums, String writeQueueNums, String perm,
            String brokerAddr, String clusterName) {

        Map<String, Object> result = Maps.newHashMap();

        DefaultMQAdminExt defaultMQAdminExt = getDefaultMQAdminExt();

        try {
            TopicConfig topicConfig = new TopicConfig();
            topicConfig.setReadQueueNums(8);
            topicConfig.setWriteQueueNums(8);
            topicConfig.setTopicName(topic);

            if (StringUtils.isNotBlank(readQueueNums)) {
                topicConfig.setReadQueueNums(Integer.parseInt(readQueueNums));
            }

            if (StringUtils.isNotBlank(writeQueueNums)) {
                topicConfig.setWriteQueueNums(Integer.parseInt(writeQueueNums));
            }

            if (StringUtils.isNotBlank(perm)) {
                topicConfig.setPerm(translatePerm(perm));
            }

            if (StringUtils.isNotBlank(brokerAddr)) {
                defaultMQAdminExt.start();
                defaultMQAdminExt.createAndUpdateTopicConfig(brokerAddr, topicConfig);
                result.put(KEY_RES, true);
            }
            else if (StringUtils.isNotBlank(clusterName)) {

                defaultMQAdminExt.start();

                Set<String> masterSet =
                        CommandUtil.fetchMasterAddrByClusterName(defaultMQAdminExt, clusterName);
                for (String addr : masterSet) {
                    defaultMQAdminExt.createAndUpdateTopicConfig(addr, topicConfig);
                }
                result.put(KEY_RES, true);
            }
            else {
                throw new IllegalStateException("clusterName or brokerAddr can not be all blank!");
            }
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.put(KEY_RES, false);
            result.put(KEY_MSG, e.getMessage());
        }
        finally {
            shutdownDefaultMQAdminExt(defaultMQAdminExt);
        }
        return result;
    }

    final static DeleteTopicSubCommand deleteTopicSubCommand = new DeleteTopicSubCommand();


    public Collection<Option> getOptionsForDelete() {
        return getOptions(deleteTopicSubCommand);
    }


    @CmdTrace(cmdClazz = DeleteTopicSubCommand.class)
    public Map<String, Object> delete(String topicName, String clusterName) {
        Map<String, Object> result = Maps.newHashMap();
        DefaultMQAdminExt adminExt = getDefaultMQAdminExt();
        try {
            if (StringUtils.isNotBlank(clusterName)) {
                adminExt.start();
                Set<String> masterSet = CommandUtil.fetchMasterAddrByClusterName(adminExt, clusterName);
                adminExt.deleteTopicInBroker(masterSet, topicName);
                Set<String> nameServerSet = null;
                if (StringUtils.isNotBlank(configureInitializer.getNamesrvAddr())) {
                    String[] ns = configureInitializer.getNamesrvAddr().split(";");
                    nameServerSet = new HashSet<String>(Arrays.asList(ns));
                }
                adminExt.deleteTopicInNameServer(nameServerSet, topicName);
                result.put(KEY_RES, true);
            }
            else {
                throw new IllegalStateException("clusterName can be blank!");
            }
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.put(KEY_RES, false);
            result.put(KEY_MSG, e.getMessage());
        }
        finally {
            shutdownDefaultMQAdminExt(adminExt);
        }
        return result;
    }


    @CmdTrace(cmdClazz = TopicRouteSubCommand.class)
    public TopicRouteData route(String topicName) {
        DefaultMQAdminExt adminExt = getDefaultMQAdminExt();
        try {
            adminExt.start();
            TopicRouteData topicRouteData = adminExt.examineTopicRouteInfo(topicName);
            return topicRouteData;
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        finally {
            shutdownDefaultMQAdminExt(adminExt);
        }
        return null;
    }
    
}
