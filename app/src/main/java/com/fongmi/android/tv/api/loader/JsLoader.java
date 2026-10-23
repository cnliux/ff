package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JsLoader {

    // clear() 会在其他线程仍在 spider 内执行时释放 QuickJS runtime（原生 UAF 崩溃）；
    // 读写锁让 proxy/创建持读锁并发执行，clear 持写锁排空在途调用。clear 限时 10 秒：
    // 超时只摘除引用不销毁（罕见病态路径下宁可暂漏一个 runtime，也不做原生 use-after-free）。
    private static final long CLEAR_WAIT_SECONDS = 10;

    private final ConcurrentHashMap<String, Spider> spiders;
    private final ReentrantReadWriteLock lock;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        lock = new ReentrantReadWriteLock();
        loader = new Loader();
    }

    public void clear() {
        boolean acquired = false;
        try {
            acquired = lock.writeLock().tryLock(CLEAR_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (acquired) {
                spiders.values().forEach(Spider::destroy);
                Module.get().clear();
            } else {
                SpiderDebug.log("jsloader", "clear skipped destroy, in-flight calls exceed %ss", CLEAR_WAIT_SECONDS);
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

    public Spider getSpider(String key, String api, String ext, String jar) {
        lock.readLock().lock();
        try {
            Spider cached = spiders.get(key);
            if (cached != null) return cached;
            Spider spider = createSpider(key, api, ext, jar);
            // Same rule as JarLoader: an unavailable spider is usually a transient state
            // (the jar's remote-dependency confirmation had no Activity to show in), so
            // never memoize the null — a later request must be able to ask again.
            if (spider instanceof SpiderNull) return spider;
            Spider existing = spiders.putIfAbsent(key, spider);
            if (existing != null) {
                // 竞争失败方已经跑过 init()，持有独立的 QuickJS runtime；不销毁就是
                // 一个再也无人可达的原生 runtime 泄漏。SpiderNull 不缓存所以不会误杀。
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

    private Spider createSpider(String key, String api, String ext, String jar) {
        try {
            Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
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
