#ifndef THREAD_SAFE_QUEUE_H
#define THREAD_SAFE_QUEUE_H

#include <queue>
#include <mutex>
#include <condition_variable>
#include <atomic>

template<typename T>
class ThreadSafeQueue {
public:
    ThreadSafeQueue() : aborted_(false), max_size_(200) {} // 默认上限 200 帧

    // 增加容量限制，防止非均匀交织文件撑爆内存
    void push(T value) {
        std::unique_lock<std::mutex> lock(mutex_);
        // 如果队列太满，让 Demuxer 歇会儿，实现“背压”控制
         cond_full_.wait(lock, [this] { return queue_.size() < max_size_ || aborted_; });

         if (aborted_) return;

        queue_.push(std::move(value));
        cond_empty_.notify_one();
    }

    bool wait_and_pop_timeout(T &value, int timeout_ms) {
        std::unique_lock<std::mutex> lock(mutex_);
        // 等待数据，或者被中断，或者超时
        bool data_available = cond_empty_.wait_for(lock, std::chrono::milliseconds(timeout_ms), [this] {
            return !queue_.empty() || aborted_;
        });

        if (aborted_ || !data_available || queue_.empty()) {
            return false; // 返回 false，说明可能是超时了
        }

        value = std::move(queue_.front());
        queue_.pop();
        cond_full_.notify_one();
        return true;
    }

    // 用于快速清理退出
    void abort() {
        std::lock_guard<std::mutex> lock(mutex_);
        aborted_ = true;
        cond_empty_.notify_all();
        cond_full_.notify_all();
    }

    // 重置状态，用于下一次播放循环
    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        aborted_ = false;
        std::queue<T>().swap(queue_); // 彻底清空内存
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return queue_.size();
    }

private:
    mutable std::mutex mutex_;
    std::queue<T> queue_;
    std::condition_variable cond_empty_; // 队列空了，消费者等数据
    std::condition_variable cond_full_;  // 队列满了，生产者等空间
    std::atomic<bool> aborted_;
    size_t max_size_;
};

#endif