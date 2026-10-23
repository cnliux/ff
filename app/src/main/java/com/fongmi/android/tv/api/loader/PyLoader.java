package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final ReentrantReadWriteLock lock;
    private final Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
        lock = new ReentrantReadWriteLock();
        loader = new Loader();
    }

    public void clear() {
        // 与 JsLoader 对齐：clear 前限时排空在途 proxy/init，防止对正在使用的
        // Python 对象调用 destroy（CPython 有 GIL 不是原生 UAF，但会抛异常、状态错乱）。
        boolean acquired = false;
        try {
            acquired = lock.writeLock().tryLock(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (acquired) {
                spiders.values().forEach(Spider::destroy);
            } else {
                SpiderDebug.log("pyloader", "clear skipped destroy, in-flight calls exceed 10s");
            }
            spiders.clear();
            recent = null;
        } finally {
            if (acquired) lock.writeLock().unlock();
        }
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        lock.readLock().lock();
        try {
            Spider cached = spiders.get(key);
            if (cached != null) return cached;
            // 不用 computeIfAbsent：Chaquopy 的 spider() 模块导入 + init() 可能触发网络与递归读同一 map，
            // computeIfAbsent 会在 lambda 期间持有该 bin 的锁，跨 key 阻塞甚至抛 IllegalStateException。
            // 与 JsLoader 一致的 check-then-act：重复构造的失败方直接丢弃，不入 map 也不常驻。
            Spider spider = createSpider(key, api, ext);
            if (spider instanceof SpiderNull) return spider;
            Spider existing = spiders.putIfAbsent(key, spider);
            if (existing != null) {
                // 竞争失败方已执行过 init()，丢弃前先销毁，避免 Python 侧对象悬而不决。
                try {
                    spider.destroy();
                } catch (Throwable e) {
                    SpiderDebug.log(e);
                }
                return existing;
            }
            return spider;
        } finally {
            lock.readLock().unlock();
        }
    }

    private Spider createSpider(String key, String api, String ext) {
        try {
            Spider spider = loader.spider(api);
            spider.siteKey = key;
            spider.init(App.get(), ext);
            return spider;
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return new SpiderNull();
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        lock.readLock().lock();
        try {
            Spider spider = spiders.get(recent);
            return spider != null ? spider.proxy(params) : null;
        } finally {
            lock.readLock().unlock();
        }
    }
}
