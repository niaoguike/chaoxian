package com.hmdp.service;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import javax.annotation.Resource;
import java.util.*;
@Service
@EnableScheduling
public class OrderLifecycle {
    @Resource private JdbcTemplate db;
    @Resource private TransactionTemplate tx;
    @Resource private StringRedisTemplate redis;
    // Invoke only after a trusted payment provider has verified payment.
    public boolean paid(long orderId, long userId) {
        return db.update("UPDATE tb_voucher_order SET status=2,pay_time=NOW() WHERE id=? AND user_id=? AND status=1", orderId,userId)==1;
    }
    public boolean close(long orderId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int changed=db.update("UPDATE tb_voucher_order SET status=4 WHERE id=? AND status=1 AND create_time<=DATE_SUB(NOW(), INTERVAL 10 MINUTE)",orderId);
            if(changed==0) return false;
            Map<String,Object> order=db.queryForMap("SELECT user_id,voucher_id FROM tb_voucher_order WHERE id=?",orderId);
            db.update("UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?",order.get("voucher_id"));
            db.update("INSERT INTO order_compensation(order_id,user_id,voucher_id) VALUES(?,?,?)",orderId,order.get("user_id"),order.get("voucher_id"));
            return true;
        }));
    }
    @Scheduled(fixedDelay=5000) public void expireOrders() {
        for(Long id:db.queryForList("SELECT id FROM tb_voucher_order WHERE status=1 AND create_time<=DATE_SUB(NOW(), INTERVAL 10 MINUTE) LIMIT 100",Long.class)) close(id);
    }
    @Scheduled(fixedDelay=5000) public void compensate() {
        DefaultRedisScript<Long> script=new DefaultRedisScript<>(
            "if redis.call('exists',KEYS[1])==1 then return 0 end " +
            "redis.call('incrby',KEYS[2],1); redis.call('set',KEYS[1],'1'); return 1",Long.class);
        for(Map<String,Object> row: db.queryForList("SELECT order_id,user_id,voucher_id FROM order_compensation WHERE delivered=0 LIMIT 100")) {
            String id=row.get("order_id").toString(), voucher=row.get("voucher_id").toString();
            redis.execute(script,Arrays.asList("seckill:restored:"+id,"seckill:stock:"+voucher,"seckill:order:"+voucher),row.get("user_id").toString());
            db.update("UPDATE order_compensation SET delivered=1 WHERE order_id=?",row.get("order_id"));
        }
    }
}
