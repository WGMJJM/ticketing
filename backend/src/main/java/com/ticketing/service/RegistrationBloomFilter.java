package com.ticketing.service;

import com.ticketing.config.RegistrationProperties;
import com.ticketing.mapper.UserAccountMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 单应用实例启动时分页回填；负判断仅优化查询，唯一性始终由MySQL裁决。 */
@Component
public class RegistrationBloomFilter implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationBloomFilter.class);
    private static final int PAGE_SIZE = 500;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("security/registration-bloom.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final UserAccountMapper accounts;
    private final RegistrationProperties properties;
    private final long bitSize;
    private final int hashCount;
    private final String geometry;
    private final List<String> keys;

    public RegistrationBloomFilter(StringRedisTemplate redis, UserAccountMapper accounts, RegistrationProperties properties) {
        this.redis = redis;
        this.accounts = accounts;
        this.properties = properties;
        bitSize = (long) Math.ceil(-properties.bloomExpectedAccounts() * Math.log(properties.bloomFalsePositiveRate())
                / (Math.log(2) * Math.log(2)));
        hashCount = Math.max(1, (int) Math.round((double) bitSize / properties.bloomExpectedAccounts() * Math.log(2)));
        geometry = bitSize + "-" + hashCount;
        String prefix = "ticketing:registration:v1:" + properties.namespace() + ":bloom:" + geometry + ":";
        keys = List.of(prefix + "ready", prefix + "username", prefix + "email");
    }

    @Override
    public void run(ApplicationArguments startupArguments) {
        if (!properties.bloomEnabled()) return;
        try {
            // 仅撤销就绪标记，不清空共享位图；并发提交的新账号可以继续追加位。
            redis.delete(keys.getFirst());
            long through = accounts.highestId();
            long cursor = 0;
            int loaded = 0;
            while (cursor < through) {
                var page = accounts.registrationPage(cursor, through, PAGE_SIZE);
                if (page.isEmpty()) break;
                List<String> args = new ArrayList<>(List.of("add", Integer.toString(hashCount), geometry));
                for (var account : page) {
                    if (++loaded > properties.bloomExpectedAccounts()) {
                        LOG.warn("注册布隆回填超过配置容量，保持MySQL预查询，请调整容量后重启");
                        return;
                    }
                    addOffsets(args, account.getUsername(), account.getEmail());
                    cursor = account.getId();
                }
                requireWritten(execute(args));
            }
            requireWritten(execute(List.of("ready", Long.toString(bitSize), geometry)));
            LOG.info("注册布隆历史回填完成，账号数 {}", loaded);
        } catch (RuntimeException exception) {
            LOG.warn("注册布隆初始化未完成，继续使用MySQL预查询，错误类型 {}", exception.getClass().getSimpleName());
        }
    }

    /** 未就绪、位图丢失、误判或Redis故障均走数据库确认，命中本身不构成拒绝依据。 */
    public boolean usernameMayExist(String username) { return mayExist(username, 2); }
    public boolean emailMayExist(String email) { return mayExist(email, 3); }

    private boolean mayExist(String value, int bitmap) {
        if (!properties.bloomEnabled()) return true;
        List<String> args = new ArrayList<>(List.of("contains", Integer.toString(bitmap), geometry));
        args.addAll(offsets(value));
        try {
            Long result = execute(args);
            return result == null || result != 0;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    /** 仅在账号事务已提交之后调用；失败不回滚账号，也不记录账号或邮箱原文。 */
    public void committed(String username, String email) {
        if (!properties.bloomEnabled()) return;
        try {
            write(username, email);
        } catch (RuntimeException exception) {
            LOG.warn("注册布隆提交后追加失败，MySQL唯一索引继续保障唯一性，错误类型 {}", exception.getClass().getSimpleName());
        }
    }

    private void write(String username, String email) {
        List<String> args = new ArrayList<>(List.of("add", Integer.toString(hashCount), geometry));
        addOffsets(args, username, email);
        requireWritten(execute(args));
    }

    private void addOffsets(List<String> args, String username, String email) {
        args.addAll(offsets(username));
        args.addAll(email == null ? java.util.Collections.nCopies(hashCount, "-1") : offsets(email));
    }

    private Long execute(List<String> args) { return redis.execute(SCRIPT, keys, args.toArray()); }

    private void requireWritten(Long result) {
        if (result == null || result != 1) throw new IllegalStateException("注册布隆写入未完成");
    }

    private List<String> offsets(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            long first = bytes.getLong();
            long step = bytes.getLong() | 1L;
            List<String> offsets = new ArrayList<>(hashCount);
            for (int index = 0; index < hashCount; index++) {
                offsets.add(Long.toString(Long.remainderUnsigned(first + index * step, bitSize)));
            }
            return offsets;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }
}
