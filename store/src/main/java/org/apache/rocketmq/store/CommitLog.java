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
 */
package org.apache.rocketmq.store;

import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.config.FlushDiskType;
import org.apache.rocketmq.store.ha.HAService;
import org.apache.rocketmq.store.schedule.ScheduleMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Store all metadata downtime for recovery, data protection reliability
 */
public class CommitLog {

    private static final Logger log = LoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    /**
     * MAGIC_CODE - MESSAGE
     * Message's MAGIC CODE daa320a7
     * 标记某一段为消息，即：[msgId, MESSAGE_MAGIC_CODE, 消息]
     */
    public final static int MESSAGE_MAGIC_CODE = 0xAABBCCDD ^ 1880681586 + 8;
    /**
     * MAGIC_CODE - BLANK
     * End of file empty MAGIC CODE cbd43194
     * 标记某一段为空白，即：[msgId, BLANK_MAGIC_CODE, 空白]
     * 当CommitLog无法容纳消息时，使用该类型结尾
     */
    private final static int BLANK_MAGIC_CODE = 0xBBCCDDEE ^ 1880681586 + 8;
    /**
     * 映射文件队列
     */
    private final MappedFileQueue mappedFileQueue;
    /**
     * 消息存储
     */
    private final DefaultMessageStore defaultMessageStore;
    /**
     * flush commitLog 线程服务
     */
    private final FlushCommitLogService flushCommitLogService;
    /**
     * If TransientStorePool enabled, we must flush message to FileChannel at fixed periods
     * commit commitLog 线程服务
     */
    private final FlushCommitLogService commitLogService;
    /**
     * 写入消息到Buffer Callback
     */
    private final AppendMessageCallback appendMessageCallback;
    /**
     * topic消息队列 与 offset 的Map
     */
    private HashMap<String/* topic-queue_id */, Long/* offset */> topicQueueTable = new HashMap<>(1024);
    /**
     * TODO
     */
    private volatile long confirmOffset = -1L;
    /**
     * 当前获取lock时间。
     * 如果当前解锁，则为0
     */
    private volatile long beginTimeInLock = 0;
    /**
     * true: Can lock, false : in lock.
     * 添加消息 螺旋锁（通过while循环实现）
     */
    private AtomicBoolean putMessageSpinLock = new AtomicBoolean(true);
    /**
     * 添加消息重入锁
     */
    private ReentrantLock putMessageNormalLock = new ReentrantLock(); // Non fair Sync

    public CommitLog(final DefaultMessageStore defaultMessageStore) {
        this.mappedFileQueue = new MappedFileQueue(defaultMessageStore.getMessageStoreConfig().getStorePathCommitLog(),
            defaultMessageStore.getMessageStoreConfig().getMapedFileSizeCommitLog(), defaultMessageStore.getAllocateMappedFileService());
        this.defaultMessageStore = defaultMessageStore;

        if (FlushDiskType.SYNC_FLUSH == defaultMessageStore.getMessageStoreConfig().getFlushDiskType()) {
            this.flushCommitLogService = new GroupCommitService();
        } else {
            this.flushCommitLogService = new FlushRealTimeService();
        }

        this.commitLogService = new CommitRealTimeService();

        this.appendMessageCallback = new DefaultAppendMessageCallback(defaultMessageStore.getMessageStoreConfig().getMaxMessageSize());
    }

    public boolean load() {
        boolean result = this.mappedFileQueue.load();
        log.info("load commit log " + (result ? "OK" : "Failed"));
        return result;
    }

    public void start() {
        this.flushCommitLogService.start();

        if (defaultMessageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
            this.commitLogService.start();
        }
    }

    public void shutdown() {
        if (defaultMessageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
            this.commitLogService.shutdown();
        }

        this.flushCommitLogService.shutdown();
    }

    public long flush() {
        this.mappedFileQueue.commit(0);
        this.mappedFileQueue.flush(0);
        return this.mappedFileQueue.getFlushedWhere();
    }

    public long getMaxOffset() {
        return this.mappedFileQueue.getMaxOffset();
    }

    public long remainHowManyDataToCommit() {
        return this.mappedFileQueue.remainHowManyDataToCommit();
    }

    public long remainHowManyDataToFlush() {
        return this.mappedFileQueue.remainHowManyDataToFlush();
    }

    public int deleteExpiredFile(final long expiredTime,
                                 final int deleteFilesInterval,
                                 final long intervalForcibly,
                                 final boolean cleanImmediately
    ) {
        return this.mappedFileQueue.deleteExpiredFileByTime(expiredTime, deleteFilesInterval, intervalForcibly, cleanImmediately);
    }

    /**
     * Read CommitLog data, use data replication
     *
     * @param offset 物理offset
     * @return 获取映射Buffer结果
     */
    public SelectMappedBufferResult getData(final long offset) {
        return this.getData(offset, offset == 0);
    }

    /**
     * Read CommitLog data, use data replication
     * 读取已经Commit但是还未被Reput的数据区段,使用数据副本形式,引用MappedFile的字节数组中的一段
     *
     * @param offset                物理offset
     * @param returnFirstOnNotFound 当未找到时，是否返回第一个commitLog
     * @return 获取映射Buffer结果
     */
    public SelectMappedBufferResult getData(final long offset, final boolean returnFirstOnNotFound) {
        int mappedFileSize = this.defaultMessageStore.getMessageStoreConfig().getMapedFileSizeCommitLog();
        MappedFile mappedFile = this.mappedFileQueue.findMappedFileByOffset(offset, returnFirstOnNotFound);
        if (mappedFile != null) {
            int pos = (int)(offset % mappedFileSize);
            SelectMappedBufferResult result = mappedFile.selectMappedBuffer(pos);
            return result;
        }

        return null;
    }

    /**
     * When the normal exit, data recovery, all memory data have been flush
     */
    public void recoverNormally() {
        boolean checkCRCOnRecover = this.defaultMessageStore.getMessageStoreConfig().isCheckCRCOnRecover();
        final List<MappedFile> mappedFiles = this.mappedFileQueue.getMappedFiles();
        if (!mappedFiles.isEmpty()) {
            // Began to recover from the last third file
            int index = mappedFiles.size() - 3;
            if (index < 0) { index = 0; }

            MappedFile mappedFile = mappedFiles.get(index);
            ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
            long processOffset = mappedFile.getFileFromOffset();
            long mappedFileOffset = 0;
            while (true) {
                DispatchRequest dispatchRequest = this.checkMessageAndReturnSize(byteBuffer, checkCRCOnRecover);
                int size = dispatchRequest.getMsgSize();
                // Normal data
                if (dispatchRequest.isSuccess() && size > 0) {
                    mappedFileOffset += size;
                }
                // Come the end of the file, switch to the next file Since the
                // return 0 representatives met last hole,
                // this can not be included in truncate offset
                else if (dispatchRequest.isSuccess() && size == 0) {
                    index++;
                    if (index >= mappedFiles.size()) {
                        // Current branch can not happen
                        log.info("recover last 3 physics file over, last maped file " + mappedFile.getFileName());
                        break;
                    } else {
                        mappedFile = mappedFiles.get(index);
                        byteBuffer = mappedFile.sliceByteBuffer();
                        processOffset = mappedFile.getFileFromOffset();
                        mappedFileOffset = 0;
                        log.info("recover next physics file, " + mappedFile.getFileName());
                    }
                }
                // Intermediate file read error
                else if (!dispatchRequest.isSuccess()) {
                    log.info("recover physics file end, " + mappedFile.getFileName());
                    break;
                }
            }

            processOffset += mappedFileOffset;
            this.mappedFileQueue.setFlushedWhere(processOffset);
            this.mappedFileQueue.setCommittedWhere(processOffset);
            this.mappedFileQueue.truncateDirtyFiles(processOffset);
        }
    }

    public DispatchRequest checkMessageAndReturnSize(java.nio.ByteBuffer byteBuffer, final boolean checkCRC) {
        return this.checkMessageAndReturnSize(byteBuffer, checkCRC, true);
    }

    private void doNothingForDeadCode(final Object obj) {
        if (obj != null) {
            if (log.isDebugEnabled()) {
                log.debug(String.valueOf(obj.hashCode()));
            }
        }
    }

    /**
     * check the message and returns the message size
     *
     * @return 0 Come the end of the file // >0 Normal messages // -1 Message checksum failure
     */
    // TODO 待读
    public DispatchRequest checkMessageAndReturnSize(ByteBuffer byteBuffer, final boolean checkCRC, final boolean readBody) {
        try {
            // 1 TOTAL SIZE
            int totalSize = byteBuffer.getInt();

            // 2 MAGIC CODE
            int magicCode = byteBuffer.getInt();
            switch (magicCode) {
                case MESSAGE_MAGIC_CODE:
                    break;
                case BLANK_MAGIC_CODE:
                    return new DispatchRequest(0, true /* success */);
                default:
                    log.warn("found a illegal magic code 0x" + Integer.toHexString(magicCode));
                    return new DispatchRequest(-1, false /* success */);
            }

            byte[] bytesContent = new byte[totalSize];

            // 3 BODYCRC
            int bodyCRC = byteBuffer.getInt();

            // 4 QUEUEID
            int queueId = byteBuffer.getInt();

            // 5 FLAG
            int flag = byteBuffer.getInt();

            // 6 QUEUE_OFFSET
            long queueOffset = byteBuffer.getLong();

            // 7 PHYSICAL_OFFSET
            long physicOffset = byteBuffer.getLong();

            // 8 SYS_FLAG
            int sysFlag = byteBuffer.getInt();

            // 9 BORN_TIMESTAMP
            long bornTimeStamp = byteBuffer.getLong();

            // 10
            ByteBuffer byteBuffer1 = byteBuffer.get(bytesContent, 0, 8);

            // 11 STORE_TIMESTAMP
            long storeTimestamp = byteBuffer.getLong();

            // 12
            ByteBuffer byteBuffer2 = byteBuffer.get(bytesContent, 0, 8);

            // 13 RECONSUME_TIMES
            int reconsumeTimes = byteBuffer.getInt();

            // 14 Prepared Transaction Offset
            long preparedTransactionOffset = byteBuffer.getLong();

            // 15 BODY
            int bodyLen = byteBuffer.getInt();
            if (bodyLen > 0) {
                if (readBody) {
                    byteBuffer.get(bytesContent, 0, bodyLen);

                    if (checkCRC) {
                        int crc = UtilAll.crc32(bytesContent, 0, bodyLen);
                        if (crc != bodyCRC) {
                            log.warn("CRC check failed. bodyCRC={}, currentCRC={}", crc, bodyCRC);
                            return new DispatchRequest(-1, false/* success */);
                        }
                    }
                } else {
                    byteBuffer.position(byteBuffer.position() + bodyLen);
                }
            }

            // 16 TOPIC
            byte topicLen = byteBuffer.get();
            byteBuffer.get(bytesContent, 0, topicLen);
            String topic = new String(bytesContent, 0, topicLen, MessageDecoder.CHARSET_UTF8);

            long tagsCode = 0;
            String keys = "";
            @SuppressWarnings("SpellCheckingInspection")
            String uniqKey = null;

            // 17 properties
            short propertiesLength = byteBuffer.getShort();
            if (propertiesLength > 0) {
                byteBuffer.get(bytesContent, 0, propertiesLength);
                String properties = new String(bytesContent, 0, propertiesLength, MessageDecoder.CHARSET_UTF8);
                Map<String, String> propertiesMap = MessageDecoder.string2messageProperties(properties);

                keys = propertiesMap.get(MessageConst.PROPERTY_KEYS);

                uniqKey = propertiesMap.get(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX);

                String tags = propertiesMap.get(MessageConst.PROPERTY_TAGS);
                if (tags != null && tags.length() > 0) {
                    tagsCode = MessageExtBrokerInner.tagsString2tagsCode(MessageExt.parseTopicFilterType(sysFlag), tags);
                }

                // Timing message processing
                {
                    String t = propertiesMap.get(MessageConst.PROPERTY_DELAY_TIME_LEVEL);
                    if (ScheduleMessageService.SCHEDULE_TOPIC.equals(topic) && t != null) {
                        int delayLevel = Integer.parseInt(t);

                        if (delayLevel > this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel()) {
                            delayLevel = this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel();
                        }

                        if (delayLevel > 0) {
                            //当是延时消息时，ConsumeQueue里的tagsCode存储延迟投递时间
                            tagsCode = this.defaultMessageStore.getScheduleMessageService().computeDeliverTimestamp(delayLevel,
                                storeTimestamp);
                        }
                    }
                }
            }

            int readLength = calMsgLength(bodyLen, topicLen, propertiesLength);
            if (totalSize != readLength) {
                doNothingForDeadCode(reconsumeTimes);
                doNothingForDeadCode(flag);
                doNothingForDeadCode(bornTimeStamp);
                doNothingForDeadCode(byteBuffer1);
                doNothingForDeadCode(byteBuffer2);
                log.error(
                    "[BUG]read total count not equals msg total size. totalSize={}, readTotalCount={}, bodyLen={}, topicLen={}, propertiesLength={}",
                    totalSize, readLength, bodyLen, topicLen, propertiesLength);
                return new DispatchRequest(totalSize, false/* success */);
            }

            return new DispatchRequest(//
                topic, // 1
                queueId, // 2
                physicOffset, // 3
                totalSize, // 4
                tagsCode, // 5
                storeTimestamp, // 6
                queueOffset, // 7
                keys, // 8
                uniqKey, //9
                sysFlag, // 9
                preparedTransactionOffset// 10
            );
        } catch (Exception e) {
        }

        return new DispatchRequest(-1, false /* success */);
    }

    /**
     * 计算消息长度
     *
     * @param bodyLength       内容长度
     * @param topicLength      主题长度
     * @param propertiesLength 拓展属性长度
     * @return 消息长度
     */
    private int calMsgLength(int bodyLength, int topicLength, int propertiesLength) {
        final int msgLen = 4 // 1 TOTALSIZE
            + 4 // 2 MAGICCODE
            + 4 // 3 BODYCRC
            + 4 // 4 QUEUEID
            + 4 // 5 FLAG
            + 8 // 6 QUEUEOFFSET
            + 8 // 7 PHYSICALOFFSET
            + 4 // 8 SYSFLAG
            + 8 // 9 BORNTIMESTAMP
            + 8 // 10 BORNHOST
            + 8 // 11 STORETIMESTAMP
            + 8 // 12 STOREHOSTADDRESS
            + 4 // 13 RECONSUMETIMES
            + 8 // 14 Prepared Transaction Offset
            + 4 + (bodyLength > 0 ? bodyLength : 0) // 14 BODY
            + 1 + topicLength // 15 TOPIC
            + 2 + (propertiesLength > 0 ? propertiesLength : 0) // 16
            // propertiesLength
            + 0;
        return msgLen;
    }

    public long getConfirmOffset() {
        return this.confirmOffset;
    }

    public void setConfirmOffset(long phyOffset) {
        this.confirmOffset = phyOffset;
    }

    public void recoverAbnormally() {
        // recover by the minimum time stamp
        boolean checkCRCOnRecover = this.defaultMessageStore.getMessageStoreConfig().isCheckCRCOnRecover();
        final List<MappedFile> mappedFiles = this.mappedFileQueue.getMappedFiles();
        if (!mappedFiles.isEmpty()) {
            // Looking beginning to recover from which file
            int index = mappedFiles.size() - 1;
            MappedFile mappedFile = null;
            for (; index >= 0; index--) {
                mappedFile = mappedFiles.get(index);
                if (this.isMappedFileMatchedRecover(mappedFile)) {
                    log.info("recover from this maped file " + mappedFile.getFileName());
                    break;
                }
            }

            if (index < 0) {
                index = 0;
                mappedFile = mappedFiles.get(index);
            }

            ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
            long processOffset = mappedFile.getFileFromOffset();
            long mappedFileOffset = 0;
            while (true) {
                DispatchRequest dispatchRequest = this.checkMessageAndReturnSize(byteBuffer, checkCRCOnRecover);
                int size = dispatchRequest.getMsgSize();

                // Normal data
                if (size > 0) {
                    mappedFileOffset += size;

                    if (this.defaultMessageStore.getMessageStoreConfig().isDuplicationEnable()) {
                        if (dispatchRequest.getCommitLogOffset() < this.defaultMessageStore.getConfirmOffset()) {
                            this.defaultMessageStore.doDispatch(dispatchRequest);
                        }
                    } else {
                        this.defaultMessageStore.doDispatch(dispatchRequest);
                    }
                }
                // Intermediate file read error
                else if (size == -1) {
                    log.info("recover physics file end, " + mappedFile.getFileName());
                    break;
                }
                // Come the end of the file, switch to the next file
                // Since the return 0 representatives met last hole, this can
                // not be included in truncate offset
                else if (size == 0) {
                    index++;
                    if (index >= mappedFiles.size()) {
                        // The current branch under normal circumstances should
                        // not happen
                        log.info("recover physics file over, last maped file " + mappedFile.getFileName());
                        break;
                    } else {
                        mappedFile = mappedFiles.get(index);
                        byteBuffer = mappedFile.sliceByteBuffer();
                        processOffset = mappedFile.getFileFromOffset();
                        mappedFileOffset = 0;
                        log.info("recover next physics file, " + mappedFile.getFileName());
                    }
                }
            }

            processOffset += mappedFileOffset;
            this.mappedFileQueue.setFlushedWhere(processOffset);
            this.mappedFileQueue.setCommittedWhere(processOffset);
            this.mappedFileQueue.truncateDirtyFiles(processOffset);

            // Clear ConsumeQueue redundant data
            this.defaultMessageStore.truncateDirtyLogicFiles(processOffset);
        }
        // Commitlog case files are deleted
        else {
            this.mappedFileQueue.setFlushedWhere(0);
            this.mappedFileQueue.setCommittedWhere(0);
            this.defaultMessageStore.destroyLogics();
        }
    }

    private boolean isMappedFileMatchedRecover(final MappedFile mappedFile) {
        ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();

        int magicCode = byteBuffer.getInt(MessageDecoder.MESSAGE_MAGIC_CODE_POSTION);
        if (magicCode != MESSAGE_MAGIC_CODE) {
            return false;
        }

        long storeTimestamp = byteBuffer.getLong(MessageDecoder.MESSAGE_STORE_TIMESTAMP_POSTION);
        if (0 == storeTimestamp) {
            return false;
        }

        if (this.defaultMessageStore.getMessageStoreConfig().isMessageIndexEnable()//
            && this.defaultMessageStore.getMessageStoreConfig().isMessageIndexSafe()) {
            if (storeTimestamp <= this.defaultMessageStore.getStoreCheckpoint().getMinTimestampIndex()) {
                log.info("find check timestamp, {} {}", //
                    storeTimestamp, //
                    UtilAll.timeMillisToHumanString(storeTimestamp));
                return true;
            }
        } else {
            if (storeTimestamp <= this.defaultMessageStore.getStoreCheckpoint().getMinTimestamp()) {
                log.info("find check timestamp, {} {}", //
                    storeTimestamp, //
                    UtilAll.timeMillisToHumanString(storeTimestamp));
                return true;
            }
        }

        return false;
    }

    private void notifyMessageArriving() {

    }

    public boolean resetOffset(long offset) {
        return this.mappedFileQueue.resetOffset(offset);
    }

    public long getBeginTimeInLock() {
        return beginTimeInLock;
    }

    /**
     * 添加消息，返回消息结果
     *
     * @param msg 消息
     * @return 结果
     */
    public PutMessageResult putMessage(final MessageExtBrokerInner msg) {
        // Set the storage time
        msg.setStoreTimestamp(System.currentTimeMillis());
        // Set the message body BODY CRC (consider the most appropriate setting
        // on the client)
        msg.setBodyCRC(UtilAll.crc32(msg.getBody()));
        // Back to Results
        AppendMessageResult result = null;

        StoreStatsService storeStatsService = this.defaultMessageStore.getStoreStatsService();

        String topic = msg.getTopic();
        int queueId = msg.getQueueId();

        // 延时消息处理,事务的 TRANSACTION_PREPARED_TYPE和TRANSACTION_ROLLBACK_TYPE 消息不支持延时投递
        final int tranType = MessageSysFlag.getTransactionValue(msg.getSysFlag());
        if (tranType == MessageSysFlag.TRANSACTION_NOT_TYPE || tranType == MessageSysFlag.TRANSACTION_COMMIT_TYPE) {
            // Delay Delivery
            if (msg.getDelayTimeLevel() > 0) {
                if (msg.getDelayTimeLevel() > this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel()) {
                    msg.setDelayTimeLevel(this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel());
                }

                // 存储消息时，延时消息进入 `Topic` 为 `SCHEDULE_TOPIC_XXXX` 。
                topic = ScheduleMessageService.SCHEDULE_TOPIC;

                //  消息队列编号 与 延迟级别  做固定映射 queueId = delayLevel - 1
                queueId = ScheduleMessageService.delayLevel2QueueId(msg.getDelayTimeLevel());

                // Backup real topic, queueId
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_TOPIC, msg.getTopic());  // %RETRY%+consumeGroup
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_QUEUE_ID, String.valueOf(msg.getQueueId()));  //0
                msg.setPropertiesString(MessageDecoder.messageProperties2String(msg.getProperties()));

                msg.setTopic(topic);
                msg.setQueueId(queueId);
            }
        }

        long eclipseTimeInLock = 0;

        // 获取写入映射文件
        MappedFile unlockMappedFile = null;
        MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();

        // 获取追加锁,限制同一时间只能有一个线程进行数据的Put工作
        lockForPutMessage(); //spin...
        try {
            long beginLockTimestamp = this.defaultMessageStore.getSystemClock().now();
            this.beginTimeInLock = beginLockTimestamp;

            // Here settings are stored timestamp, in order to ensure an orderly
            // global
            msg.setStoreTimestamp(beginLockTimestamp);

            // 当不存在映射文件或者文件已经空间已满，进行创建
            if (null == mappedFile || mappedFile.isFull()) {
                mappedFile = this.mappedFileQueue.getLastMappedFile(0); // Mark: NewFile may be cause noise
            }
            if (null == mappedFile) {
                log.error("create maped file1 error, topic: " + msg.getTopic() + " clientAddr: " + msg.getBornHostString());
                beginTimeInLock = 0;
                return new PutMessageResult(PutMessageStatus.CREATE_MAPEDFILE_FAILED, null);
            }

            // 将消息追加到MappedFile的MappedByteBuffer/writeBuffer中,更新其写入位置wrotePosition,但还没Commit及Flush
            result = mappedFile.appendMessage(msg, this.appendMessageCallback);
            switch (result.getStatus()) {
                case PUT_OK:
                    break;
                case END_OF_FILE: // 当文件剩余空间不足以插入当前消息时,创建新的MapperFile,进行插入
                    unlockMappedFile = mappedFile;
                    // Create a new file, re-write the message
                    mappedFile = this.mappedFileQueue.getLastMappedFile(0);
                    if (null == mappedFile) {
                        // XXX: warn and notify me
                        log.error("create maped file2 error, topic: " + msg.getTopic() + " clientAddr: " + msg.getBornHostString());
                        beginTimeInLock = 0;
                        return new PutMessageResult(PutMessageStatus.CREATE_MAPEDFILE_FAILED, result);
                    }
                    result = mappedFile.appendMessage(msg, this.appendMessageCallback);
                    break;
                case MESSAGE_SIZE_EXCEEDED:
                case PROPERTIES_SIZE_EXCEEDED:
                    beginTimeInLock = 0;
                    return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, result);
                case UNKNOWN_ERROR:
                    beginTimeInLock = 0;
                    return new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result);
                default:
                    beginTimeInLock = 0;
                    return new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result);
            }

            eclipseTimeInLock = this.defaultMessageStore.getSystemClock().now() - beginLockTimestamp;
            beginTimeInLock = 0;
        } finally {
            // 释放锁
            releasePutMessageLock();
        }

        if (eclipseTimeInLock > 500) {
            log.warn("[NOTIFYME]putMessage in lock cost time(ms)={}, bodyLength={} AppendMessageResult={}", eclipseTimeInLock, msg.getBody().length, result);
        }

        if (null != unlockMappedFile && this.defaultMessageStore.getMessageStoreConfig().isWarmMapedFileEnable()) {
            this.defaultMessageStore.unlockMappedFile(unlockMappedFile);
        }

        PutMessageResult putMessageResult = new PutMessageResult(PutMessageStatus.PUT_OK, result);

        // Statistics
        storeStatsService.getSinglePutMessageTopicTimesTotal(msg.getTopic()).incrementAndGet();
        storeStatsService.getSinglePutMessageTopicSizeTotal(topic).addAndGet(result.getWroteBytes());

        // 进行同步||异步 flush||commit
        GroupCommitRequest request = null;
        // Synchronization flush
        if (FlushDiskType.SYNC_FLUSH == this.defaultMessageStore.getMessageStoreConfig().getFlushDiskType()) {
            final GroupCommitService service = (GroupCommitService)this.flushCommitLogService;
            if (msg.isWaitStoreMsgOK()) {
                request = new GroupCommitRequest(result.getWroteOffset() + result.getWroteBytes());
                service.putRequest(request);
                boolean flushOK = request.waitForFlush(this.defaultMessageStore.getMessageStoreConfig().getSyncFlushTimeout());
                if (!flushOK) {
                    log.error("do groupcommit, wait for flush failed, topic: " + msg.getTopic() + " tags: " + msg.getTags()
                        + " client address: " + msg.getBornHostString());
                    putMessageResult.setPutMessageStatus(PutMessageStatus.FLUSH_DISK_TIMEOUT);
                }
            } else {
                service.wakeup();
            }
        }
        // Asynchronous flush
        else {
            if (!this.defaultMessageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
                flushCommitLogService.wakeup(); //异步刷盘,使用MappedByteBuffer,默认策略
            } else {
                commitLogService.wakeup();  //异步刷盘,使用字节缓冲区+FileChannel
            }
        }

        // Synchronous write double 如果是 SYNC_MASTER,马上将信息同步至SLAVE; 若ASYNC_MASTER,则每隔1S唤醒SLAVE同步请求
        if (BrokerRole.SYNC_MASTER == this.defaultMessageStore.getMessageStoreConfig().getBrokerRole()) {
            HAService service = this.defaultMessageStore.getHaService();
            if (msg.isWaitStoreMsgOK()) {
                // 推送到Slave的Offset是否小于这条消息的Offset,且Slave落后Master的进度在允许范围内(256MB)
                if (service.isSlaveOK(result.getWroteOffset() + result.getWroteBytes())) {
                    //如果是ASYNC_FLUSH
                    if (null == request) {
                        request = new GroupCommitRequest(result.getWroteOffset() + result.getWroteBytes());
                    }
                    service.putRequest(request);

                    // 唤醒WriteSocketService
                    service.getWaitNotifyObject().wakeupAll();

                    boolean flushOK = request.waitForFlush(this.defaultMessageStore.getMessageStoreConfig().getSyncFlushTimeout());
                    if (!flushOK) {
                        log.error("do sync transfer other node, wait return, but failed, topic: " + msg.getTopic() + " tags: "
                            + msg.getTags() + " client address: " + msg.getBornHostString());
                        putMessageResult.setPutMessageStatus(PutMessageStatus.FLUSH_SLAVE_TIMEOUT);
                    }
                }
                // Slave problem
                else {
                    // Tell the producer, slave not available
                    putMessageResult.setPutMessageStatus(PutMessageStatus.SLAVE_NOT_AVAILABLE);
                }
            }
        }

        return putMessageResult;
    }

    /**
     * According to receive certain message or offset storage time if an error
     * occurs, it returns -1
     */
    public long pickupStoreTimestamp(final long offset, final int size) {
        if (offset >= this.getMinOffset()) {
            SelectMappedBufferResult result = this.getMessage(offset, size);
            if (null != result) {
                try {
                    return result.getByteBuffer().getLong(MessageDecoder.MESSAGE_STORE_TIMESTAMP_POSTION);
                } finally {
                    result.release();
                }
            }
        }

        return -1;
    }

    public long getMinOffset() {
        MappedFile mappedFile = this.mappedFileQueue.getFirstMappedFile();
        if (mappedFile != null) {
            if (mappedFile.isAvailable()) {
                return mappedFile.getFileFromOffset();
            } else {
                return this.rollNextFile(mappedFile.getFileFromOffset());
            }
        }

        return -1;
    }

    /**
     * 根据从ConsumeQueue里获取到的消息位置及大小,从MappeFile中获取相应的数据
     *
     * @param offset 消息在CommitLog中的全局Offset
     * @param size   消息的字节长度
     * @return
     */
    public SelectMappedBufferResult getMessage(final long offset, final int size) {
        int mappedFileSize = this.defaultMessageStore.getMessageStoreConfig().getMapedFileSizeCommitLog();
        MappedFile mappedFile = this.mappedFileQueue.findMappedFileByOffset(offset, offset == 0);
        if (mappedFile != null) {
            int pos = (int)(offset % mappedFileSize);
            return mappedFile.selectMappedBuffer(pos, size);
        }
        return null;
    }

    /**
     * 跳到当前Offset对应的MappedFile的下一个MappedFile的起始位置
     * 比如当前Offset为1G+5000,
     * offset + mappedFileSize - offset % mappedFileSize = 1G+5000+1G-5000/1G =2G
     *
     * @param offset
     * @return
     */
    public long rollNextFile(final long offset) {
        int mappedFileSize = this.defaultMessageStore.getMessageStoreConfig().getMapedFileSizeCommitLog();
        return offset + mappedFileSize - offset % mappedFileSize;
    }

    public HashMap<String, Long> getTopicQueueTable() {
        return topicQueueTable;
    }

    public void setTopicQueueTable(HashMap<String, Long> topicQueueTable) {
        this.topicQueueTable = topicQueueTable;
    }

    public void destroy() {
        this.mappedFileQueue.destroy();
    }

    /**
     * commitLog添加数据
     * ！该方法主要在Master与Slave同步数据时调用
     *
     * @param startOffset 开始物理位置
     * @param data        数据
     * @return 是否成功
     */
    public boolean appendData(long startOffset, byte[] data) {
        lockForPutMessage(); //spin...
        try {
            MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile(startOffset);
            if (null == mappedFile) {
                log.error("appendData getLastMappedFile error  " + startOffset);
                return false;
            }

            return mappedFile.appendMessage(data);
        } finally {
            releasePutMessageLock();
        }
    }

    public boolean retryDeleteFirstFile(final long intervalForcibly) {
        return this.mappedFileQueue.retryDeleteFirstFile(intervalForcibly);
    }

    public void removeQueueFromTopicQueueTable(final String topic, final int queueId) {
        String key = topic + "-" + queueId;
        synchronized (this) {
            this.topicQueueTable.remove(key);
        }

        log.info("removeQueueFromTopicQueueTable OK Topic: {} QueueId: {}", topic, queueId);
    }

    public void checkSelf() {
        mappedFileQueue.checkSelf();
    }

    public long lockTimeMills() {
        long diff = 0;
        long begin = this.beginTimeInLock;
        if (begin > 0) {
            diff = this.defaultMessageStore.now() - begin;
        }

        if (diff < 0) {
            diff = 0;
        }

        return diff;
    }

    /**
     * Spin util acquired the lock.
     * 获取 putMessage 锁
     */
    private void lockForPutMessage() {
        if (this.defaultMessageStore.getMessageStoreConfig().isUseReentrantLockWhenPutMessage()) {
            putMessageNormalLock.lock();
        } else {
            boolean flag;
            do {  //通过AtomicBoolean实现自旋锁,自旋直到当前线程获得锁
                flag = this.putMessageSpinLock.compareAndSet(true, false);
            }
            while (!flag);
        }
    }

    /**
     * 释放 putMessage 锁
     */
    private void releasePutMessageLock() {
        if (this.defaultMessageStore.getMessageStoreConfig().isUseReentrantLockWhenPutMessage()) {
            putMessageNormalLock.unlock();
        } else {
            this.putMessageSpinLock.compareAndSet(false, true);
        }
    }

    public static class GroupCommitRequest {

        private final long nextOffset;
        private final CountDownLatch countDownLatch = new CountDownLatch(1);
        private volatile boolean flushOK = false;

        public GroupCommitRequest(long nextOffset) {
            this.nextOffset = nextOffset;
        }

        public long getNextOffset() {
            return nextOffset;
        }

        /**
         * 终止等待
         *
         * @param flushOK
         */
        public void wakeupCustomer(final boolean flushOK) {
            this.flushOK = flushOK;
            this.countDownLatch.countDown();
        }

        /**
         * 等待一段时间,直至{@link #wakeupCustomer}
         *
         * @param timeout
         * @return
         */
        public boolean waitForFlush(long timeout) {
            try {
                this.countDownLatch.await(timeout, TimeUnit.MILLISECONDS);
                return this.flushOK;
            } catch (InterruptedException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    /**
     * flush commitLog 线程服务
     */
    abstract class FlushCommitLogService extends ServiceThread {

        protected static final int RETRY_TIMES_OVER = 10;
    }

    /**
     * 实时 commit commitLog 线程服务
     */
    class CommitRealTimeService extends FlushCommitLogService {

        /**
         * 最后 commit 时间戳
         */
        private long lastCommitTimestamp = 0;

        @Override
        public String getServiceName() {
            return CommitRealTimeService.class.getSimpleName();
        }

        @Override
        public void run() {
            CommitLog.log.info(this.getServiceName() + " service started");
            while (!this.isStopped()) {
                int interval = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitIntervalCommitLog();
                int commitDataLeastPages = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitCommitLogLeastPages();
                int commitDataThoroughInterval = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitCommitLogThoroughInterval();

                long begin = System.currentTimeMillis();
                //当最近200毫秒内没有消息Commit时,此次消息触发Commit
                if (begin >= (this.lastCommitTimestamp + commitDataThoroughInterval)) {
                    this.lastCommitTimestamp = begin;
                    commitDataLeastPages = 0;
                }

                try {
                    //Commit需要缓冲区内至少含有4页数据，也就是16KB,或者是最近200毫秒内没有消息Commit
                    boolean result = CommitLog.this.mappedFileQueue.commit(commitDataLeastPages);
                    long end = System.currentTimeMillis();
                    if (!result) {   //代表着writeBuffer里的数据commit到了fileChannel中，可能是writeBuffer里数据超过16KB或者最近200毫秒内没有消息Commit
                        this.lastCommitTimestamp = end;
                        //now wake up flush thread.
                        flushCommitLogService.wakeup();
                    }

                    if (end - begin > 500) {
                        log.info("Commit data to file costs {} ms", end - begin);
                    }

                    // 等待执行
                    this.waitForRunning(interval);
                } catch (Throwable e) {
                    CommitLog.log.error(this.getServiceName() + " service has exception. ", e);
                }
            }
            //在循环退出也就是CommitLog Stop停止时，强制刷盘
            boolean result = false;
            for (int i = 0; i < RETRY_TIMES_OVER && !result; i++) {
                result = CommitLog.this.mappedFileQueue.commit(0);
                CommitLog.log.info(this.getServiceName() + " service shutdown, retry " + (i + 1) + " times " + (result ? "OK" : "Not OK"));
            }
            CommitLog.log.info(this.getServiceName() + " service end");
        }
    }

    /**
     * 实时 flush commitLog 线程服务
     */
    class FlushRealTimeService extends FlushCommitLogService {

        /**
         * 最后flush时间戳
         */
        private long lastFlushTimestamp = 0;
        /**
         * print计时器。
         * 满足print次数时，调用{@link #printFlushProgress()}
         */
        private long printTimes = 0;

        @Override
        public void run() {
            CommitLog.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                boolean flushCommitLogTimed = CommitLog.this.defaultMessageStore.getMessageStoreConfig().isFlushCommitLogTimed();
                int interval = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getFlushIntervalCommitLog();  // 500ms
                int flushPhysicQueueLeastPages = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getFlushCommitLogLeastPages();  // 4
                int flushPhysicQueueThoroughInterval = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getFlushCommitLogThoroughInterval(); //10S

                // Print flush progress
                boolean printFlushProgress = false;
                long currentTimeMillis = System.currentTimeMillis();
                // 当时间满足距离上次flush时间超过10秒时，即使写入的数量不足flushPhysicQueueLeastPages(4页，16KB)，也进行flush
                if (currentTimeMillis >= (this.lastFlushTimestamp + flushPhysicQueueThoroughInterval)) {
                    this.lastFlushTimestamp = currentTimeMillis;
                    flushPhysicQueueLeastPages = 0;
                    printFlushProgress = (printTimes++ % 10) == 0;
                }

                try {
                    // 刷盘是否设置了定时,默认否
                    if (flushCommitLogTimed) {
                        Thread.sleep(interval);
                    } else {  //当没有消息触发wakeup()时,每休眠500毫秒执行一次flush;  当commit消息后触发wakeup(),若正在休眠则直接终止休眠，若不在休眠则跳过下次休眠
                        this.waitForRunning(interval);
                    }

                    if (printFlushProgress) {
                        this.printFlushProgress();
                    }

                    // flush commitLog
                    long begin = System.currentTimeMillis();
                    //刷盘至少需要4页数据，也就是16KB
                    CommitLog.this.mappedFileQueue.flush(flushPhysicQueueLeastPages);
                    long storeTimestamp = CommitLog.this.mappedFileQueue.getStoreTimestamp();
                    if (storeTimestamp > 0) {
                        CommitLog.this.defaultMessageStore.getStoreCheckpoint().setPhysicMsgTimestamp(storeTimestamp);
                    }
                    long past = System.currentTimeMillis() - begin;
                    if (past > 500) {
                        log.info("Flush data to disk costs {} ms", past);
                    }
                } catch (Throwable e) {
                    CommitLog.log.warn(this.getServiceName() + " service has exception. ", e);
                    this.printFlushProgress();
                }
            }

            // Normal shutdown, to ensure that all the flush before exit
            boolean result = false;
            for (int i = 0; i < RETRY_TIMES_OVER && !result; i++) {
                result = CommitLog.this.mappedFileQueue.flush(0);
                CommitLog.log.info(this.getServiceName() + " service shutdown, retry " + (i + 1) + " times " + (result ? "OK" : "Not OK"));
            }

            this.printFlushProgress();

            CommitLog.log.info(this.getServiceName() + " service end");
        }

        @Override
        public String getServiceName() {
            return FlushRealTimeService.class.getSimpleName();
        }

        private void printFlushProgress() {
            // CommitLog.log.info("how much disk fall behind memory, "
            // + CommitLog.this.mappedFileQueue.howMuchFallBehind());
        }

        @Override
        @SuppressWarnings("SpellCheckingInspection")
        public long getJointime() {
            return 1000 * 60 * 5;
        }
    }

    /**
     * GroupCommit Service
     */
    class GroupCommitService extends FlushCommitLogService {

        /**
         * 写入请求队列
         */
        private volatile List<GroupCommitRequest> requestsWrite = new ArrayList<>();
        /**
         * 读取请求队列
         */
        private volatile List<GroupCommitRequest> requestsRead = new ArrayList<>();

        /**
         * 添加写入请求
         *
         * @param request 写入请求
         */
        public synchronized void putRequest(final GroupCommitRequest request) {
            // 添加写入请求
            synchronized (this.requestsWrite) {
                this.requestsWrite.add(request);
            }
            // 切换读写队列
            if (hasNotified.compareAndSet(false, true)) {
                waitPoint.countDown(); // notify
            }
        }

        /**
         * 切换读写队列
         */
        private void swapRequests() {
            List<GroupCommitRequest> tmp = this.requestsWrite;
            this.requestsWrite = this.requestsRead;
            this.requestsRead = tmp;
        }

        private void doCommit() {
            synchronized (this.requestsRead) {
                if (!this.requestsRead.isEmpty()) {
                    for (GroupCommitRequest req : this.requestsRead) {
                        // There may be a message in the next file, so a maximum of
                        // two times the flush (可能批量提交的messages，分布在两个MappedFile)
                        boolean flushOK = false;
                        for (int i = 0; i < 2 && !flushOK; i++) {
                            // 是否满足需要flush条件，即请求的offset超过flush的offset
                            flushOK = CommitLog.this.mappedFileQueue.getFlushedWhere() >= req.getNextOffset();
                            if (!flushOK) {
                                CommitLog.this.mappedFileQueue.flush(0);
                            }
                        }
                        // 唤醒等待请求
                        req.wakeupCustomer(flushOK);
                    }

                    long storeTimestamp = CommitLog.this.mappedFileQueue.getStoreTimestamp();
                    if (storeTimestamp > 0) {
                        CommitLog.this.defaultMessageStore.getStoreCheckpoint().setPhysicMsgTimestamp(storeTimestamp);
                    }

                    // 清理读取队列
                    this.requestsRead.clear();
                } else {
                    // Because of individual messages is set to not sync flush, it
                    // will come to this process 不合法的请求，比如message上未设置isWaitStoreMsgOK。
                    // 走到此处的逻辑，相当于发送一条消息，落盘一条消息，实际无批量提交的效果。
                    CommitLog.this.mappedFileQueue.flush(0);
                }
            }
        }

        @Override
        public void run() {
            CommitLog.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    this.waitForRunning(10);
                    this.doCommit();
                } catch (Exception e) {
                    CommitLog.log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            // Under normal circumstances shutdown, wait for the arrival of the
            // request, and then flush
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                CommitLog.log.warn("GroupCommitService Exception, ", e);
            }

            synchronized (this) {
                this.swapRequests();
            }

            this.doCommit();

            CommitLog.log.info(this.getServiceName() + " service end");
        }

        /**
         * 每次执行完，切换读写队列
         */
        @Override
        protected void onWaitEnd() {
            this.swapRequests();
        }

        @Override
        public String getServiceName() {
            return GroupCommitService.class.getSimpleName();
        }

        @Override
        public long getJointime() {
            return 1000 * 60 * 5;
        }
    }

    /**
     * 写入消息到Buffer默认实现
     */
    class DefaultAppendMessageCallback implements AppendMessageCallback {

        // File at the end of the minimum fixed length empty
        private static final int END_FILE_MIN_BLANK_LENGTH = 4 + 4;
        /**
         * 存储在内存中的消息编号字节Buffer
         */
        private final ByteBuffer msgIdMemory;
        /**
         * Store the message content
         * 存储在内存中的消息字节Buffer
         * 当消息传递到{@link #doAppend(long, ByteBuffer, int, MessageExtBrokerInner)}方法时，最终写到该参数
         */
        private final ByteBuffer msgStoreItemMemory;
        /**
         * The maximum length of the message
         * 消息最大长度
         */
        private final int maxMessageSize;
        /**
         * Build Message Key
         * {@link #topicQueueTable}的key
         * 计算方式：topic + "-" + queueId
         */
        private final StringBuilder keyBuilder = new StringBuilder();
        /**
         * host字节buffer
         * 用于重复计算host的字节内容
         */
        private final ByteBuffer hostHolder = ByteBuffer.allocate(8);

        DefaultAppendMessageCallback(final int size) {
            this.msgIdMemory = ByteBuffer.allocate(MessageDecoder.MSG_ID_LENGTH);
            this.msgStoreItemMemory = ByteBuffer.allocate(size + END_FILE_MIN_BLANK_LENGTH);
            this.maxMessageSize = size;
        }

        public ByteBuffer getMsgStoreItemMemory() {
            return msgStoreItemMemory;
        }

        @Override
        public AppendMessageResult doAppend(final long fileFromOffset, final ByteBuffer byteBuffer, final int maxBlank, final MessageExtBrokerInner msgInner) {
            // STORETIMESTAMP + STOREHOSTADDRESS + OFFSET <br>

            //物理写入偏移量,也就是当前文件里已经写入的数据位置
            long wroteOffset = fileFromOffset + byteBuffer.position();

            // 计算commitLog里的msgId
            this.resetByteBuffer(hostHolder, 8);
            //创建messageId,由 "ip+port+wroteOffset" 组成
            String msgId = MessageDecoder.createMessageId(this.msgIdMemory, msgInner.getStoreHostBytes(hostHolder), wroteOffset);

            // Record ConsumeQueue information 获取队列offset
            keyBuilder.setLength(0);
            keyBuilder.append(msgInner.getTopic());
            keyBuilder.append('-');
            keyBuilder.append(msgInner.getQueueId());
            //tpoic-queueId
            String key = keyBuilder.toString();
            //记录这条消息的消费信息在当前队列的序号,也就是第几条消息
            Long queueOffset = CommitLog.this.topicQueueTable.get(key);
            if (null == queueOffset) {
                queueOffset = 0L;
                CommitLog.this.topicQueueTable.put(key, queueOffset);
            }

            // Transaction messages that require special handling
            final int tranType = MessageSysFlag.getTransactionValue(msgInner.getSysFlag());
            switch (tranType) {
                // Prepared and Rollback message is not consumed, will not enter the
                // consumer queue
                case MessageSysFlag.TRANSACTION_PREPARED_TYPE:
                case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE:
                    queueOffset = 0L;
                    break;
                case MessageSysFlag.TRANSACTION_NOT_TYPE:
                case MessageSysFlag.TRANSACTION_COMMIT_TYPE:
                default:
                    break;
            }

            // 计算消息长度
            final byte[] propertiesData =
                msgInner.getPropertiesString() == null ? null : msgInner.getPropertiesString().getBytes(MessageDecoder.CHARSET_UTF8);
            final int propertiesLength = propertiesData == null ? 0 : propertiesData.length;
            if (propertiesLength > Short.MAX_VALUE) {
                log.warn("putMessage message properties length too long. length={}", propertiesData.length);
                return new AppendMessageResult(AppendMessageStatus.PROPERTIES_SIZE_EXCEEDED);
            }
            final byte[] topicData = msgInner.getTopic().getBytes(MessageDecoder.CHARSET_UTF8);
            final int topicLength = topicData.length;
            final int bodyLength = msgInner.getBody() == null ? 0 : msgInner.getBody().length;
            final int msgLen = calMsgLength(bodyLength, topicLength, propertiesLength);
            // Exceeds the maximum message
            if (msgLen > this.maxMessageSize) {
                CommitLog.log.warn("message size exceeded, msg total size: " + msgLen + ", msg body size: " + bodyLength
                    + ", maxMessageSize: " + this.maxMessageSize);
                return new AppendMessageResult(AppendMessageStatus.MESSAGE_SIZE_EXCEEDED);
            }

            // 如果文件已经接近满额,剩余空间容纳不下当前消息, maxBlank ：当前剩余空间
            if ((msgLen + END_FILE_MIN_BLANK_LENGTH) > maxBlank) {
                this.resetByteBuffer(this.msgStoreItemMemory, maxBlank);
                // 1 TOTAL_SIZE
                this.msgStoreItemMemory.putInt(maxBlank);
                // 2 MAGIC_CODE
                this.msgStoreItemMemory.putInt(CommitLog.BLANK_MAGIC_CODE);
                // 3 The remaining space may be any value
                //

                // Here the length of the specially set maxBlank
                final long beginTimeMills = CommitLog.this.defaultMessageStore.now();
                byteBuffer.put(this.msgStoreItemMemory.array(), 0, maxBlank);
                return new AppendMessageResult(AppendMessageStatus.END_OF_FILE, wroteOffset, maxBlank, msgId, msgInner.getStoreTimestamp(),
                    queueOffset, CommitLog.this.defaultMessageStore.now() - beginTimeMills);
            }

            // Initialization of storage space
            this.resetByteBuffer(msgStoreItemMemory, msgLen);
            // 1 TOTAL_SIZE
            this.msgStoreItemMemory.putInt(msgLen);
            // 2 MAGIC_CODE
            this.msgStoreItemMemory.putInt(CommitLog.MESSAGE_MAGIC_CODE);
            // 3 BODY_CRC
            this.msgStoreItemMemory.putInt(msgInner.getBodyCRC());
            // 4 QUEUE_ID
            this.msgStoreItemMemory.putInt(msgInner.getQueueId());
            // 5 FLAG
            this.msgStoreItemMemory.putInt(msgInner.getFlag());
            // 6 QUEUE_OFFSET
            this.msgStoreItemMemory.putLong(queueOffset);
            // 7 PHYSICAL_OFFSET
            this.msgStoreItemMemory.putLong(fileFromOffset + byteBuffer.position());
            // 8 SYS_FLAG
            this.msgStoreItemMemory.putInt(msgInner.getSysFlag());
            // 9 BORN_TIMESTAMP
            this.msgStoreItemMemory.putLong(msgInner.getBornTimestamp());
            // 10 BORN_HOST
            this.resetByteBuffer(hostHolder, 8);
            this.msgStoreItemMemory.put(msgInner.getBornHostBytes(hostHolder));
            // 11 STORE_TIMESTAMP
            this.msgStoreItemMemory.putLong(msgInner.getStoreTimestamp());
            // 12 STORE_HOST_ADDRESS
            this.resetByteBuffer(hostHolder, 8);
            this.msgStoreItemMemory.put(msgInner.getStoreHostBytes(hostHolder));
            //this.msgStoreItemMemory.put(msgInner.getStoreHostBytes());
            // 13 RECONSUME_TIMES
            this.msgStoreItemMemory.putInt(msgInner.getReconsumeTimes());
            // 14 Prepared Transaction Offset
            this.msgStoreItemMemory.putLong(msgInner.getPreparedTransactionOffset());
            // 15 BODY
            this.msgStoreItemMemory.putInt(bodyLength);
            if (bodyLength > 0) { this.msgStoreItemMemory.put(msgInner.getBody()); }
            // 16 TOPIC
            this.msgStoreItemMemory.put((byte)topicLength);
            this.msgStoreItemMemory.put(topicData);
            // 17 PROPERTIES
            this.msgStoreItemMemory.putShort((short)propertiesLength);
            if (propertiesLength > 0) { this.msgStoreItemMemory.put(propertiesData); }

            final long beginTimeMills = CommitLog.this.defaultMessageStore.now();
            // Write messages to the queue buffer
            byteBuffer.put(this.msgStoreItemMemory.array(), 0, msgLen);

            AppendMessageResult result = new AppendMessageResult(AppendMessageStatus.PUT_OK, wroteOffset, msgLen, msgId,
                msgInner.getStoreTimestamp(), queueOffset, CommitLog.this.defaultMessageStore.now() - beginTimeMills);

            switch (tranType) {
                //事务类型为PREPARED_TYPE和ROLLBACK_TYPE的消息不会进ConsumeQueue,所以这里不会增加queueOffset的值
                case MessageSysFlag.TRANSACTION_PREPARED_TYPE:
                case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE:
                    break;
                case MessageSysFlag.TRANSACTION_NOT_TYPE:
                case MessageSysFlag.TRANSACTION_COMMIT_TYPE:
                    // The next update ConsumeQueue information 更新队列的offset
                    CommitLog.this.topicQueueTable.put(key, ++queueOffset);
                    break;
                default:
                    break;
            }
            return result;
        }

        /**
         * 重置字节缓冲区
         *
         * @param byteBuffer 字节缓冲区
         * @param limit      长度
         */
        private void resetByteBuffer(final ByteBuffer byteBuffer, final int limit) {
            byteBuffer.flip();
            byteBuffer.limit(limit);
        }
    }
}
