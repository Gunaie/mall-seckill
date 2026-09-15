import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 秒杀接口压测脚本（Java 17 单文件运行，无需编译）
 * <p>
 * 用法: java scripts/SeckillBench.java <seckillSkuId> <totalRequests> <concurrency> <accessToken> [baseUrl]
 * 示例: java scripts/SeckillBench.java 1 2000 200 <accessToken> http://localhost:8080
 * <p>
 * 阶段 6 起抢购链路为：验证码 -> 动态路径 -> 限流 -> 下单。压测前置条件（application-dev.yml）：
 * 1. mall.access-limit.enabled=false（关闭滑动窗口限流，否则全部被 429 拦截，无法测量业务漏斗）；
 * 2. mall.seckill.captcha-enabled=false（关闭验证码，脚本无人识码，直接用空 body 换取动态路径）；
 * 3. 秒杀商品已预热且处于秒杀时间窗口内，path TTL（默认 60s）需覆盖整个压测过程。
 * 脚本会先调用 /path 换取动态路径，再携带路径并发打 /{path}/execute。
 * <p>
 * 内存标记对比方法：
 * 1. mall.seckill.local-mark-enabled=false，抢空库存后运行（"已抢光"请求都要打一次 Redis Lua）；
 * 2. 置回 true 并重新预热上架，抢空后再运行（除首次外全部命中本地内存标记）；
 * 3. 对比两次输出的 QPS 差异。
 * <p>
 * 说明：固定 token 模拟单用户高并发，仅压测"售罄拒绝"路径吞吐；
 * 一人一单场景请使用不同账号分别抢购验证。
 */
public class SeckillBench {

    private static final Pattern PATH_PATTERN = Pattern.compile("\"data\":\"([a-f0-9]{32})\"");

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("用法: java scripts/SeckillBench.java <seckillSkuId> <totalRequests> <concurrency> <accessToken> [baseUrl]");
            System.exit(1);
        }
        long seckillSkuId = Long.parseLong(args[0]);
        int total = Integer.parseInt(args[1]);
        int concurrency = Integer.parseInt(args[2]);
        String token = args[3];
        String base = args.length > 4 ? args[4] : "http://localhost:8080";

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        // 预备：换取动态下单路径（压测配置下验证码已关闭，空 body 即可）
        HttpRequest pathRequest = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/seckill/" + seckillSkuId + "/path"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> pathResp = client.send(pathRequest, HttpResponse.BodyHandlers.ofString());
        Matcher matcher = PATH_PATTERN.matcher(pathResp.body());
        if (!matcher.find()) {
            System.out.println("换取动态路径失败，请确认已关闭验证码/限流且商品在秒杀时间窗口内：" + pathResp.body());
            System.exit(1);
        }
        String path = matcher.group(1);
        System.out.println("动态路径获取成功: " + path);

        String body = "{\"receiverName\":\"压测\",\"receiverPhone\":\"13800000000\",\"receiverAddress\":\"压测地址\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/seckill/" + seckillSkuId + "/" + path + "/execute"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(total);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        long start = System.nanoTime();

        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                try {
                    HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200 && resp.body().contains("\"code\":200")) {
                        ok.incrementAndGet();
                    } else if (resp.statusCode() == 429 || resp.body().contains("\"code\":429")) {
                        // 触发滑动窗口限流（正常压测应关闭限流，出现此项说明前置配置未生效）
                        limited.incrementAndGet();
                    } else if (resp.body().contains("\"code\":10000")) {
                        // 业务拒绝：已抢光 / 重复抢购 / 不在时间范围
                        rejected.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("========== 压测结果 ==========");
        System.out.printf("总请求=%d, 并发=%d, 耗时=%dms, QPS=%.0f%n",
                total, concurrency, elapsedMs, total * 1000.0 / Math.max(elapsedMs, 1));
        System.out.printf("排队中(code=200)=%d, 业务拒绝(code=10000)=%d, 限流(429)=%d, 其他=%d, 网络错误=%d%n",
                ok.get(), rejected.get(), limited.get(), other.get(), errors.get());
        pool.shutdown();
    }
}
