package com.colobu.rpcx.utils;

import com.colobu.rpcx.common.Config;
import com.colobu.rpcx.common.Pair;
import com.colobu.rpcx.rpc.URL;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Created by zhangzhiyong on 2018/7/4.
 */
public class ZkClient {

    private static final Logger logger = LoggerFactory.getLogger(ZkClient.class);

    private CuratorFramework client;


    private CopyOnWriteArrayList<PathChildrenCache> list = new CopyOnWriteArrayList<>();

    private AtomicBoolean stop = new AtomicBoolean(false);


    private ZkClient() {
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        String cs = Config.ins().get("rpcx.zk.connect.string");
        client =
                CuratorFrameworkFactory.builder()
                        .connectString(cs)
                        .sessionTimeoutMs(5000)
                        .connectionTimeoutMs(5000)
                        .retryPolicy(retryPolicy)
                        .build();
        client.start();
    }

    private static final class LazyHolder {
        private static final ZkClient ins = new ZkClient();
    }

    public static ZkClient ins() {
        return LazyHolder.ins;
    }


    public Set<Pair<String, String>> get(String basePath, String serviceName) {
        try {
            Set<String> set = client.getChildren().forPath(basePath + serviceName).stream().collect(Collectors.toSet());
            Set<Pair<String, String>> pairSet = set.stream().map(it -> {
                String data = "";
                try {
                    data = new String(client.getData().forPath(basePath + serviceName + "/" + it));
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return Pair.of(it, data);
            }).collect(Collectors.toSet());
            return pairSet;
        } catch (Exception e) {
            logger.error("get error:{}", e.getMessage());
        }
        return new HashSet<>();
    }


    ///youpin/services/Arith/tcp@0.0.0.0:8976"
    public void create(String basePath, Set<String> serviceNames, String addr) {
        serviceNames.forEach(u -> {
            URL url = URL.valueOf(u);
            //& 为了兼容  rpcx(golang)
//            String path = basePath + url.getPath() + "/tcp@" + addr + "&" + url.toParameterString();
            String path = basePath + url.getPath() + "/tcp@" + addr;
            try {
                if (client.checkExists().forPath(path) == null) {
                    client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(path, url.toParameterString().getBytes());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }


    //监控变化
    public void watch(LinkedBlockingQueue<PathStatus> queue, final String p) throws Exception {
        if (this.stop.get() == true) {
            return;
        }
        logger.info("---------->watch path:{}", p);
        PathChildrenCache cache = new PathChildrenCache(this.client, p, true);
        list.add(cache);
        cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
        cache.getListenable().addListener((cf, event) -> {
            switch (event.getType()) {
                case CHILD_ADDED:
                    String path = event.getData().getPath();
                    String param = new String(event.getData().getData());
                    String addr = path.split("@")[1];
                    logger.info("------->CHILD_ADDED :{}:{}", path, addr);
                    queue.offer(new PathStatus("CHILD_ADDED", Pair.of(addr, param), p));
                    break;
                case CHILD_UPDATED:
                    String updatePath = event.getData().getPath();
                    logger.info("------>CHILD_UPDATED :" + event.getData().getPath());
                    String updateParam = new String(event.getData().getData());
                    String updateAddr = updatePath.split("@")[1];
                    queue.offer(new PathStatus("CHILD_UPDATED", Pair.of(updateAddr, updateParam), p));
                    break;
                case CHILD_REMOVED:
                    String path2 = event.getData().getPath();
                    String addr2 = path2.split("@")[1];
                    logger.info("------>CHILD_REMOVED :{}:{}" + path2, addr2);
                    queue.offer(new PathStatus("CHILD_REMOVED", Pair.of(addr2, ""), p));
                    break;
                default:
                    break;
            }
        });
    }

    public void close() {
        this.stop.compareAndSet(false, true);
        this.list.forEach(it -> {
            try {
                it.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        this.client.close();
    }

}
