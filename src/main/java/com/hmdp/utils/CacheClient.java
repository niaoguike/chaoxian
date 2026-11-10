package com.hmdp.utils;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.redisson.api.*;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Function;
import javax.annotation.PreDestroy;
@Component
public class CacheClient {
    private final StringRedisTemplate redis;
    private final RedissonClient locks;
    private final ExecutorService rebuild = new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128));
    public CacheClient(StringRedisTemplate redis, RedissonClient locks) { this.redis=redis; this.locks=locks; }
    public void set(String key,Object value,Long time,TimeUnit unit) {
        redis.opsForValue().set(key,JSONUtil.toJsonStr(value),unit.toSeconds(time)+ThreadLocalRandom.current().nextInt(30),TimeUnit.SECONDS);
    }
    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit) {
        RedisData data=new RedisData(); data.setData(value); data.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redis.opsForValue().set(key,JSONUtil.toJsonStr(data));
    }
    public <R,ID> R queryWithPassThrough(String prefix,ID id,Class<R> type,Function<ID,R> db,Long time,TimeUnit unit) {
        return queryWithMutex(prefix,id,type,db,time,unit);
    }
    public <R,ID> R queryWithMutex(String prefix,ID id,Class<R> type,Function<ID,R> db,Long time,TimeUnit unit) {
        String key=prefix+id, json=redis.opsForValue().get(key);
        if(json!=null) return StrUtil.isBlank(json)?null:JSONUtil.toBean(json,type);
        RLock lock=locks.getLock("lock:cache:"+key);
        boolean held=false;
        try {
            held=lock.tryLock(2,TimeUnit.SECONDS);
            if(!held) throw new IllegalStateException("缓存重建繁忙，请稍后重试");
            json=redis.opsForValue().get(key);
            if(json!=null) return StrUtil.isBlank(json)?null:JSONUtil.toBean(json,type);
            R value=db.apply(id);
            if(value==null) redis.opsForValue().set(key,"",2,TimeUnit.MINUTES); else set(key,value,time,unit);
            return value;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        finally { if(held && lock.isHeldByCurrentThread()) lock.unlock(); }
    }
    public <R,ID> R queryWithLogicalExpire(String prefix,ID id,Class<R> type,Function<ID,R> db,Long time,TimeUnit unit) {
        String key=prefix+id,json=redis.opsForValue().get(key);
        if(StrUtil.isBlank(json)) {
            RLock lock=locks.getLock("lock:cache:"+key); lock.lock();
            try {
                json=redis.opsForValue().get(key);
                if(StrUtil.isBlank(json)) { R value=db.apply(id); setWithLogicalExpire(key,value,time,unit); return value; }
            } finally { lock.unlock(); }
        }
        RedisData data=JSONUtil.toBean(json,RedisData.class);
        R value=data.getData()==null?null:JSONUtil.toBean(JSONUtil.toJsonStr(data.getData()),type);
        if(data.getExpireTime().isAfter(LocalDateTime.now())) return value;
        try { rebuild.submit(() -> {
            RLock lock=locks.getLock("lock:cache:"+key);
            if(!lock.tryLock()) return;
            try {
                RedisData latest=JSONUtil.toBean(redis.opsForValue().get(key),RedisData.class);
                if(!latest.getExpireTime().isAfter(LocalDateTime.now())) setWithLogicalExpire(key,db.apply(id),time,unit);
            } finally { lock.unlock(); }
        }); } catch(RejectedExecutionException ignored) { /* Serve stale data while the bounded queue is full. */ }
        return value;
    }
    @PreDestroy public void stop() { rebuild.shutdownNow(); }
}
