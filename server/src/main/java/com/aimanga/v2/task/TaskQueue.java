package com.aimanga.v2.task;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 任务队列(Redis):主队列 aimanga:v2:task:queue(RBlockingQueue);
 * 每用户并发许可不足时经延迟队列重新入队(默认 2s),避免热轮询。
 * MySQL task 表仍是唯一事实;队列可丢可重建。
 */
@Component
@RequiredArgsConstructor
public class TaskQueue {

    public static final String QUEUE_KEY = "aimanga:v2:task:queue";
    /** 毒丸:通知 worker 线程退出(池缩容用) */
    public static final long POISON_PILL = -1L;

    private final RedissonClient redissonClient;

    private RBlockingQueue<Long> queue() {
        return redissonClient.getBlockingQueue(QUEUE_KEY);
    }

    /** 立即入队 */
    public void enqueue(long taskId) {
        queue().add(taskId);
    }

    /** 延迟重新入队(并发许可不足/调度退避) */
    public void enqueueDelayed(long taskId, long delayMs) {
        RDelayedQueue<Long> delayed = redissonClient.getDelayedQueue(queue());
        delayed.offer(taskId, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 阻塞获取(可被线程中断打断以支持池缩容) */
    public long take() throws InterruptedException {
        Long taskId = queue().take();
        return taskId == null ? POISON_PILL : taskId;
    }

    /** 投递毒丸(每个 worker 线程一颗) */
    public void poison(int count) {
        for (int i = 0; i < count; i++) {
            queue().add(POISON_PILL);
        }
    }

    /** 移除一颗毒丸;返回是否存在并移除(启动时清理上次停机残留) */
    public boolean removePoison() {
        return queue().remove(POISON_PILL);
    }

    /** 清除全部残留毒丸(启动恢复时调用) */
    public void purgePoison() {
        while (queue().remove(POISON_PILL)) {
            // 循环移除直到没有
        }
    }
}
