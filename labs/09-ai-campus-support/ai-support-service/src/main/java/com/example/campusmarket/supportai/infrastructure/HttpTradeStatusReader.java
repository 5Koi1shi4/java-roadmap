package com.example.campusmarket.supportai.infrastructure;

import com.example.campusmarket.supportai.application.AnswerService;
import com.example.campusmarket.supportai.application.TradeStatusReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/** 调用交易服务单项摘要的 HTTP 客户端；永不记录 Bearer 或资源标识。 */
@Component
public final class HttpTradeStatusReader implements TradeStatusReader {
    private static final String SERVICE_NAME = "legacy-market-service";
    private static final String DEFAULT_SERVICE_URI = "lb://" + SERVICE_NAME;

    private final RestClient client;
    private final ObjectProvider<LoadBalancerClient> loadBalancers;
    private final URI serviceUri;

    @Autowired
    public HttpTradeStatusReader(RestClient.Builder builder,
                                 ObjectProvider<LoadBalancerClient> loadBalancers,
                                 @Value("${campus.market.legacy.base-url:" + DEFAULT_SERVICE_URI + "}")
                                 String serviceBaseUrl) {
        this(builder.build(), loadBalancers, URI.create(requireText(serviceBaseUrl, "交易服务地址")));
    }

    /** 供不启动 Spring 的单元测试注入受控 HTTP 客户端。 */
    public HttpTradeStatusReader(RestClient client, URI serviceBaseUri) {
        this(client, null, serviceBaseUri);
    }

    public HttpTradeStatusReader(RestClient client, String serviceBaseUrl) {
        this(client, URI.create(requireText(serviceBaseUrl, "交易服务地址")));
    }

    private HttpTradeStatusReader(RestClient client,
                                  ObjectProvider<LoadBalancerClient> loadBalancers,
                                  URI serviceUri) {
        this.client = Objects.requireNonNull(client, "HTTP 客户端不能为空");
        this.loadBalancers = loadBalancers;
        this.serviceUri = Objects.requireNonNull(serviceUri, "交易服务地址不能为空");
        if (serviceUri.getScheme() == null
            || (!"http".equalsIgnoreCase(serviceUri.getScheme())
                && !"https".equalsIgnoreCase(serviceUri.getScheme())
                && !"lb".equalsIgnoreCase(serviceUri.getScheme()))) {
            throw new IllegalArgumentException("交易服务地址协议无效");
        }
    }

    @Override
    public TradeStatusReader.StatusView read(String type, UUID id, String bearerToken) {
        requireType(type);
        Objects.requireNonNull(id, "资源 ID 不能为空");
        try {
            URI endpoint = resolveEndpoint(type, id);
            RestClient.RequestHeadersSpec<?> request = client.get().uri(endpoint);
            if (bearerToken != null && !bearerToken.isBlank()) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            }
            return request.retrieve().body(TradeStatusReader.StatusView.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 404 || status == 401 || status == 403) {
                throw new AnswerService.ResourceNotFoundException();
            }
            throw new AnswerService.DependencyUnavailableException("交易状态服务暂时不可用");
        } catch (AnswerService.ResourceNotFoundException
                 | AnswerService.DependencyUnavailableException exception) {
            throw exception;
        } catch (RestClientException | IllegalStateException exception) {
            throw new AnswerService.DependencyUnavailableException("交易状态服务暂时不可用");
        }
    }

    private URI resolveEndpoint(String type, UUID id) {
        if ("lb".equalsIgnoreCase(serviceUri.getScheme())) {
            LoadBalancerClient loadBalancer = loadBalancers == null
                ? null : loadBalancers.getIfAvailable();
            if (loadBalancer == null) {
                throw new AnswerService.DependencyUnavailableException("交易状态服务暂时不可用");
            }
            ServiceInstance instance = loadBalancer.choose(serviceUri.getHost());
            if (instance == null) {
                throw new AnswerService.DependencyUnavailableException("交易状态服务暂时不可用");
            }
            return URI.create(instance.getUri().toString() + "/api/support/"
                + type + "/" + id);
        }
        return URI.create(stripTrailingSlash(serviceUri.toString()) + "/api/support/"
            + type + "/" + id);
    }

    private static void requireType(String type) {
        if (!"orders".equals(type) && !"disputes".equals(type) && !"warranties".equals(type)) {
            throw new IllegalArgumentException("资源类型无效");
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > "http://".length()) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }
}
