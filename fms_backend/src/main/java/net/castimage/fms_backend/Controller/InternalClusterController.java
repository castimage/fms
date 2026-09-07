package net.castimage.fms_backend.Controller;

import net.castimage.fms_backend.Service.SystemInfoService;
import net.castimage.fms_backend.Model.vo.SystemInfoView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集群对等探测端点，GET /internal/cluster-info，返回本实例运行信息，
 * 供其他后端实例经 fms.cluster.peers 聚合展示。
 *
 * 与 /internal/health 同级，只读运行信息，不含密钥/口令/私密连接串，
 * 不经网关签名过滤器（urlPatterns=/api/*）与 ApiKeyFilter（/api/*），
 * 集群内容器网络可达即可探测，探测失败按不可达呈现，fail-open 于展示。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal")
public class InternalClusterController {

    private final SystemInfoService systemInfoService;


    /**
     * 本实例集群信息，纯本机采集（selfInfo 不触发对等探测，防递归），
     * 实例名取容器 HOSTNAME
     *
     * @return instance/javaVersion/springBootVersion/startedAt/uptimeSecs/redis
     */
    @GetMapping("/cluster-info")
    public Map<String, Object> clusterInfo() {
        String hostname = System.getenv("HOSTNAME");
        SystemInfoView.SelfInfo self = systemInfoService.selfInfo();

        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("mode", self.redis().mode());
        redis.put("reachable", self.redis().reachable());
        redis.put("version", self.redis().version());
        redis.put("keys", self.redis().keys());
        redis.put("usedMemoryBytes", self.redis().usedMemoryBytes());
        redis.put("usedMemoryPct", self.redis().usedMemoryPct());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instance", hostname == null ? "unknown" : hostname);
        m.put("javaVersion", self.javaVersion());
        m.put("springBootVersion", self.springBootVersion());
        m.put("startedAt", self.startedAt().toString());
        m.put("uptimeSecs", self.uptimeSecs());
        m.put("redis", redis);
        return m;
    }
}
