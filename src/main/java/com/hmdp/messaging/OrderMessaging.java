package com.hmdp.messaging;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.message.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import javax.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
@Component
public class OrderMessaging implements TransactionListener {
    @Resource private StringRedisTemplate redis;
    @Lazy @Resource private VoucherOrderServiceImpl orders;
    @Value("${rocketmq.nameserver}") private String nameserver;
    private TransactionMQProducer producer;
    private DefaultMQProducer delayed;
    @Resource private com.hmdp.service.OrderLifecycle lifecycle;
    private DefaultMQPushConsumer consumer;
    private final DefaultRedisScript<Long> reserve = new DefaultRedisScript<>();
    @PostConstruct public void start() throws Exception {
        reserve.setLocation(new ClassPathResource("seckill.lua")); reserve.setResultType(Long.class);
        producer = new TransactionMQProducer("chaoxian-order-producer");
        producer.setNamesrvAddr(nameserver); producer.setTransactionListener(this); producer.start();
        delayed = new DefaultMQProducer("chaoxian-close-producer");
        delayed.setNamesrvAddr(nameserver); delayed.start();
        consumer = new DefaultMQPushConsumer("chaoxian-order-consumer");
        consumer.setConsumeFromWhere(org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setNamesrvAddr(nameserver); consumer.subscribe("chaoxian-orders", "*");
        consumer.subscribe("chaoxian-close", "*");
        consumer.registerMessageListener((MessageListenerConcurrently)(messages, context) -> {
            try {
                for(MessageExt message: messages) {
                    VoucherOrder order = decode(message);
                    if ("chaoxian-close".equals(message.getTopic())) { lifecycle.close(order.getId()); continue; }
                    orders.createVoucherOrder(order);
                    Message close = new Message("chaoxian-close", message.getBody());
                    close.setKeys(order.getId().toString()); close.setDelayTimeLevel(14);
                    delayed.send(close);
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch(Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("订单消费失败，等待重试", e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
    }
    private VoucherOrder decode(Message message) {
        return JSONUtil.toBean(new String(message.getBody(), StandardCharsets.UTF_8), VoucherOrder.class);
    }
    public Result submit(VoucherOrder order) {
        try {
            Message message = new Message("chaoxian-orders", JSONUtil.toJsonStr(order).getBytes(StandardCharsets.UTF_8));
            message.setKeys(order.getId().toString());
            TransactionSendResult result = producer.sendMessageInTransaction(message, null);
            if(result.getLocalTransactionState()==LocalTransactionState.COMMIT_MESSAGE) return Result.ok(order.getId());
            return Result.fail("未获得购买资格或结果待确认，请查询订单");
        } catch(Exception e) { throw new IllegalStateException("提交结果未知，请按订单号查询: " + order.getId(), e); }
    }
    @Override public LocalTransactionState executeLocalTransaction(Message message, Object arg) {
        VoucherOrder order = decode(message);
        try {
            Long result = redis.execute(reserve, Collections.emptyList(), order.getVoucherId().toString(), order.getUserId().toString(), order.getId().toString());
            if(result == null) return LocalTransactionState.UNKNOW;
            return result == 0 ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.ROLLBACK_MESSAGE;
        } catch(Exception e) { return LocalTransactionState.UNKNOW; }
    }
    @Override public LocalTransactionState checkLocalTransaction(MessageExt message) {
        try {
            // SETNX fences a late local callback: aborted transactions cannot reserve stock.
            String key = "seckill:tx:" + decode(message).getId();
            redis.opsForValue().setIfAbsent(key, "ABORT");
            return "COMMIT".equals(redis.opsForValue().get(key)) ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.ROLLBACK_MESSAGE;
        } catch(Exception e) { return LocalTransactionState.UNKNOW; }
    }
    @PreDestroy public void stop() {
        if(consumer != null) consumer.shutdown();
        if(delayed != null) delayed.shutdown();
        if(producer != null) producer.shutdown();
    }
}
