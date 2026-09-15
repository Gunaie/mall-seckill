package com.mall.service.impl;

import com.mall.common.constant.CacheConstants;
import com.mall.common.exception.BusinessException;
import com.mall.config.SeckillProperties;
import com.mall.service.CaptchaService;
import com.mall.vo.CaptchaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 算术题图形验证码实现（纯 JDK AWT，无需第三方验证码组件）
 * <p>
 * 图片仅在内存中绘制（BufferedImage，headless 环境可用），输出 PNG base64；
 * 答案以令牌为 key 存入 Redis 并设短 TTL，校验用 Lua 保证「读取+删除」原子，验证码一次性消费。
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;

    private static final Random RANDOM = new Random();
    private static final int WIDTH = 130;
    private static final int HEIGHT = 40;
    private static final char[] OPERATORS = {'+', '-', '×'};

    /**
     * KEYS[1]=验证码 key, ARGV[1]=用户答案
     * 返回 1 正确 / -1 错误 / 0 不存在或已过期；无论对错读取后立即删除（一次性）
     */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "local answer = redis.call('get', KEYS[1]) " +
                "if answer == false then " +
                "    return 0 " +
                "end " +
                "redis.call('del', KEYS[1]) " +
                "if answer == ARGV[1] then return 1 else return -1 end");
        script.setResultType(Long.class);
        CONSUME_SCRIPT = script;
    }

    @Override
    public CaptchaVO generate() {
        // 1. 生成算术题（减法保证结果非负）
        int a = RANDOM.nextInt(9) + 1;
        int b = RANDOM.nextInt(9) + 1;
        char op = OPERATORS[RANDOM.nextInt(OPERATORS.length)];
        int answer;
        if (op == '+') {
            answer = a + b;
        } else if (op == '-') {
            if (a < b) {
                int t = a;
                a = b;
                b = t;
            }
            answer = a - b;
        } else {
            // 乘法控制在一位数相乘，答案简短
            answer = a * b;
        }
        String expression = a + " " + op + " " + b + " = ?";

        // 2. 令牌绑定答案写入 Redis
        String token = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(CacheConstants.SECKILL_CAPTCHA_KEY + token,
                String.valueOf(answer), seckillProperties.getCaptchaTtlSeconds(), TimeUnit.SECONDS);

        // 3. 绘制图片并转 base64 data URI
        String base64 = drawImage(expression);
        CaptchaVO vo = new CaptchaVO();
        vo.setCaptchaToken(token);
        vo.setImg("data:image/png;base64," + base64);
        return vo;
    }

    @Override
    public void verifyAndConsume(String captchaToken, String code) {
        if (captchaToken == null || captchaToken.isBlank() || code == null || code.isBlank()) {
            throw new BusinessException("请先完成验证码校验");
        }
        Long result = stringRedisTemplate.execute(CONSUME_SCRIPT,
                List.of(CacheConstants.SECKILL_CAPTCHA_KEY + captchaToken), code.trim());
        if (result == null || result == 0L) {
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (result == -1L) {
            throw new BusinessException("验证码错误");
        }
    }

    /**
     * 内存绘制算术题图片：白底 + 干扰线 + 噪点 + 彩色字符轻微旋转
     */
    private String drawImage(String expression) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 白色背景
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 干扰线
            for (int i = 0; i < 15; i++) {
                g.setColor(randomColor(160));
                int x1 = RANDOM.nextInt(WIDTH);
                int y1 = RANDOM.nextInt(HEIGHT);
                int x2 = RANDOM.nextInt(WIDTH);
                int y2 = RANDOM.nextInt(HEIGHT);
                g.drawLine(x1, y1, x2, y2);
            }
            // 噪点
            for (int i = 0; i < 40; i++) {
                g.setColor(randomColor(180));
                g.fillRect(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT), 1, 1);
            }

            // 字符逐个绘制，轻微旋转
            g.setFont(new Font("Arial", Font.BOLD, 24));
            char[] chars = expression.toCharArray();
            int x = 8;
            for (char c : chars) {
                g.setColor(randomColor(120));
                if (c != ' ') {
                    double theta = (RANDOM.nextDouble() - 0.5) * 0.5;
                    g.rotate(theta, x + 8.0, 28);
                    g.drawString(String.valueOf(c), x, 29);
                    g.rotate(-theta, x + 8.0, 28);
                    x += 15;
                } else {
                    x += 8;
                }
            }
            g.setColor(randomColor(120));
            g.drawRect(0, 0, WIDTH - 1, HEIGHT - 1);
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            log.error("验证码图片生成失败", e);
            throw new BusinessException("验证码生成失败，请重试");
        }
    }

    private Color randomColor(int bound) {
        return new Color(RANDOM.nextInt(bound), RANDOM.nextInt(bound), RANDOM.nextInt(bound));
    }
}
