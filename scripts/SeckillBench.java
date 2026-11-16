import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 秒杀接口压测工具（Java 17 单文件运行，无需编译）。
 * <p>
 * 三种模式：
 * <pre>
 *   售罄拒绝：java scripts/SeckillBench.java reject  &lt;skuId&gt; &lt;total&gt; &lt;concurrency&gt; &lt;token&gt;     [baseUrl]
 *   成功下单：java scripts/SeckillBench.java success &lt;skuId&gt; &lt;total&gt; &lt;concurrency&gt; &lt;tokenFile&gt; [baseUrl]
 *   批量注册：java scripts/SeckillBench.java seed    &lt;count&gt; &lt;tokenFile&gt;                   [baseUrl]
 * </pre>
 * 成功下单模式要求「一人一单」：tokenFile 每行一个 access token，每个用户只打一条请求，
 * 因此 token 数必须不少于 total；脚本会先为每个用户换取动态路径。
 * <p>
 * 前置条件（application-dev.yml / 压测 profile）：
 * 1. mall.access-limit.enabled=false（关闭滑动窗口限流）；
 * 2. mall.seckill.captcha-enabled=false（关闭验证码，脚本无人识码）；
 * 3. 秒杀商品已预热且处于秒杀时间窗口内，path TTL 需覆盖整个压测过程。
 * <p>
 * 输出为 ASCII 键值对，便于脚本解析并写入压测报告。
 */
public class SeckillBench {

    private static final Pattern PATH_PATTERN = Pattern.compile("\"data\":\"([a-f0-9]{32})\"");
    private static final String BODY =
            "{\"receiverName\":\"压测\",\"receiverPhone\":\"13800000000\",\"receiverAddress\":\"压测地址\"}";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(1);
        }
        String mode = args[0];
        switch (mode) {
            case "reject" -> runReject(args);
            case "success" -> runSuccess(args);
            case "seed" -> runSeed(args);
            case "raw" -> runRaw(args);
            default -> {
                // 兼容旧用法：第一个参数是 skuId
                runReject(legacyArgs(args));
            }
        }
    }

    // ============================== 售罄拒绝 ==============================

    private static void runReject(String[] args) throws Exception {
        if (args.length < 5) {
            usage();
            System.exit(1);
        }
        long skuId = Long.parseLong(args[1]);
        int total = Integer.parseInt(args[2]);
        int concurrency = Integer.parseInt(args[3]);
        String token = args[4];
        String base = args.length > 5 ? args[5] : "http://localhost:8080";

        HttpClient client = newClient();
        String path = fetchPath(client, base, skuId, token);
        System.out.println("path=" + path);

        HttpRequest request = executeRequest(base, skuId, path, token);
        List<HttpRequest> requests = new ArrayList<>(1);
        requests.add(request);
        benchmark("reject", total, concurrency, requests);
    }

    // ============================== 裸目标压测 ==============================

    /**
     * 对任意 URL 打流量，用于分离「应用耗时」与「宿主机端口转发 / Nginx」开销：
     * raw http://localhost:8090/not-found 5000 100
     */
    private static void runRaw(String[] args) throws Exception {
        if (args.length < 4) {
            usage();
            System.exit(1);
        }
        String url = args[1];
        int total = Integer.parseInt(args[2]);
        int concurrency = Integer.parseInt(args[3]);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        benchmark("raw", total, concurrency, List.of(request));
    }

    // ============================== 成功下单 ==============================

    private static void runSuccess(String[] args) throws Exception {
        if (args.length < 5) {
            usage();
            System.exit(1);
        }
        long skuId = Long.parseLong(args[1]);
        int total = Integer.parseInt(args[2]);
        int concurrency = Integer.parseInt(args[3]);
        List<String> tokens = Files.readAllLines(Path.of(args[4]), StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        String base = args.length > 5 ? args[5] : "http://localhost:8080";

        if (tokens.isEmpty()) {
            System.out.println("tokenFile 为空: " + args[4]);
            System.exit(1);
        }
        int limit = Math.min(total, tokens.size());
        if (limit < total) {
            System.out.println("warn: tokens(" + tokens.size() + ") < total(" + total
                    + ")，按一人一单只压 " + limit + " 条请求");
        }

        HttpClient client = newClient();
        // 并发换取每个用户的动态路径
        String[] paths = new String[limit];
        ExecutorService preparePool = Executors.newFixedThreadPool(Math.min(32, limit));
        CountDownLatch prepareLatch = new CountDownLatch(limit);
        for (int i = 0; i < limit; i++) {
            final int idx = i;
            preparePool.submit(() -> {
                try {
                    paths[idx] = fetchPath(client, base, skuId, tokens.get(idx));
                } catch (Exception e) {
                    paths[idx] = null;
                } finally {
                    prepareLatch.countDown();
                }
            });
        }
        prepareLatch.await();
        preparePool.shutdown();

        List<HttpRequest> requests = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            if (paths[i] != null) {
                requests.add(executeRequest(base, skuId, paths[i], tokens.get(i)));
            }
        }
        if (requests.isEmpty()) {
            System.out.println("所有用户换取动态路径失败，请确认已关闭验证码/限流且商品在秒杀时间窗口内");
            System.exit(1);
        }
        System.out.println("prepared=" + requests.size());
        benchmark("success", requests.size(), concurrency, requests);
    }

    // ============================== 批量注册 ==============================

    private static void runSeed(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            System.exit(1);
        }
        int count = Integer.parseInt(args[1]);
        Path tokenFile = Path.of(args[2]);
        String base = args.length > 3 ? args[3] : "http://localhost:8080";

        HttpClient client = newClient();
        String[] tokens = new String[count];
        String suffix = Long.toString(System.currentTimeMillis(), 36);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger failed = new AtomicInteger();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            final String username = "p5_" + suffix + "_" + i;
            final int idx = i;
            pool.submit(() -> {
                try {
                    String body = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"p5\"}";
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(base + "/api/auth/register"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(resp.body());
                    if (m.find()) {
                        tokens[idx] = m.group(1);
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        List<String> ok = Arrays.stream(tokens).filter(t -> t != null).toList();
        Files.write(tokenFile, ok, StandardCharsets.UTF_8);
        System.out.printf("seed total=%d ok=%d failed=%d elapsedMs=%d tokenFile=%s%n",
                count, ok.size(), failed.get(), elapsedMs, tokenFile.toAbsolutePath());
    }

    // ============================== 压测核心 ==============================

    private static void benchmark(String mode, int total, int concurrency,
                                  List<HttpRequest> requests) throws Exception {
        HttpClient client = newClient();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(total);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        long[] latencyNanos = new long[total];
        AtomicLong seq = new AtomicLong();
        long start = System.nanoTime();

        for (int i = 0; i < total; i++) {
            final HttpRequest request = requests.get(i % requests.size());
            pool.submit(() -> {
                int idx = (int) seq.getAndIncrement();
                long begin = System.nanoTime();
                try {
                    HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200 && resp.body().contains("\"code\":200")) {
                        ok.incrementAndGet();
                    } else if (resp.statusCode() == 429 || resp.body().contains("\"code\":429")) {
                        limited.incrementAndGet();
                    } else if (resp.body().contains("\"code\":10000")) {
                        rejected.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latencyNanos[idx] = System.nanoTime() - begin;
                    latch.countDown();
                }
            });
        }
        latch.await();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        Arrays.sort(latencyNanos);
        System.out.println("========== result ==========");
        System.out.printf("mode=%s total=%d concurrency=%d elapsedMs=%d qps=%.0f%n",
                mode, total, concurrency, elapsedMs, total * 1000.0 / Math.max(elapsedMs, 1));
        System.out.printf("ok=%d rejected=%d limited=%d other=%d errors=%d%n",
                ok.get(), rejected.get(), limited.get(), other.get(), errors.get());
        System.out.printf("latencyMs p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                percentileMs(latencyNanos, 0.50),
                percentileMs(latencyNanos, 0.95),
                percentileMs(latencyNanos, 0.99),
                latencyNanos[latencyNanos.length - 1] / 1_000_000.0);
    }

    private static double percentileMs(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sortedNanos.length) - 1;
        return sortedNanos[Math.max(Math.min(idx, sortedNanos.length - 1), 0)] / 1_000_000.0;
    }

    // ============================== 辅助方法 ==============================

    private static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    private static String fetchPath(HttpClient client, String base, long skuId, String token)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/seckill/" + skuId + "/path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        Matcher matcher = PATH_PATTERN.matcher(resp.body());
        if (!matcher.find()) {
            throw new IllegalStateException("换取动态路径失败: " + resp.body());
        }
        return matcher.group(1);
    }

    private static HttpRequest executeRequest(String base, long skuId, String path, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/seckill/" + skuId + "/" + path + "/execute"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(BODY, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 兼容旧调用：java SeckillBench.java &lt;skuId&gt; &lt;total&gt; &lt;concurrency&gt; &lt;token&gt; [baseUrl]
     */
    private static String[] legacyArgs(String[] args) {
        String[] converted = new String[args.length + 1];
        converted[0] = "reject";
        System.arraycopy(args, 0, converted, 1, args.length);
        return converted;
    }

    private static void usage() {
        System.out.println("usage:");
        System.out.println("  reject  <skuId> <total> <concurrency> <token>     [baseUrl]");
        System.out.println("  success <skuId> <total> <concurrency> <tokenFile> [baseUrl]");
        System.out.println("  seed    <count> <tokenFile>                       [baseUrl]");
        System.out.println("  raw     <url> <total> <concurrency>");
    }
}
