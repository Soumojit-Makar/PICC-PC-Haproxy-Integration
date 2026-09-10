package com.nnp.haproxy.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class K8SService {
	
	@Value("${k8s.master.ip:127.0.0.1}")
	private String k8sMasterNodeIP;
	
	@Value("${k8s.server.port:6443}")
	private String k8sServicePort;
	
	@Value("${k8s.cluster.token:}")
	private String k8sClusterToken;
	
	//private CountDownLatch latch;
	
	
	@Qualifier("webClientK8SApi")
	@Autowired
	private WebClient webClientHaproxy;

    public boolean checkCompStatus(String env, String compName) {
        Map<String, String> podInf = webClientHaproxy.get()
                .uri("https://" + k8sMasterNodeIP + ":" + k8sServicePort + "/api/v1/namespaces/{namespace}/pods", env)
                .header("Authorization", "Bearer " + k8sClusterToken)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    Map<String, String> podInfo = new HashMap<>();
                    json.path("items").forEach(item -> {
                        String podName = item.path("metadata").path("name").asText("");
                        String appLabel = item.path("metadata").path("labels").path("app").asText("");
                        String k8sLabel = item.path("metadata").path("labels").path("app.kubernetes.io/name").asText("");
                        String status = item.path("status").path("phase").asText("");
                        if (!appLabel.isEmpty()) {
                            podInfo.put(appLabel.toLowerCase(), status);
                        }
                        if (!k8sLabel.isEmpty()) {
                            podInfo.put(k8sLabel.toLowerCase(), status);
                        }
                        if (!podName.isEmpty()) {
                            podInfo.put(podName.toLowerCase(), status);
                            int lastDashIndex = podName.lastIndexOf('-');
                            if (lastDashIndex > 0) {
                                String parentDep = podName.substring(0,
                                        podName.lastIndexOf('-', lastDashIndex - 1) > 0
                                                ? podName.lastIndexOf('-', lastDashIndex - 1)
                                                : lastDashIndex);
                                podInfo.putIfAbsent(parentDep.toLowerCase(), status);
                            }
                        }
                    });
                    return podInfo;
                }).block();

        if (podInf == null) {
            log.debug("The Pod Info is missing.");
            return false;
        }
        if (!"running".equalsIgnoreCase(podInf.get(compName.toLowerCase()))) {
            log.debug("The Pod is not Running.");
        }
        return "running".equalsIgnoreCase(podInf.get(compName.toLowerCase()));
    }
}
