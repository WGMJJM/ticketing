package com.ticketing.admission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.auth.UserAccount;
import com.ticketing.auth.UserAccountMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.messaging.PurchaseCommands;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** HTTP入口只持久受理，不依赖交易实现、不锁SKU或创建订单。 */
@Service
public class PurchaseAdmission {
    private final PurchaseRequestMapper requests;
    private final UserAccountMapper users;
    private final PurchaseCommands commands;
    private final PurchaseProperties properties;
    public PurchaseAdmission(PurchaseRequestMapper requests,UserAccountMapper users,PurchaseCommands commands,PurchaseProperties properties) {
        this.requests=requests; this.users=users; this.commands=commands; this.properties=properties;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public PurchaseRequestView accept(long userId,String sku,int quantity,String key) {
        long skuId=skuId(sku);
        UserAccount user=users.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getId,userId).last("FOR UPDATE"));
        if(user==null || !"ACTIVE".equals(user.getStatus())) throw new BusinessException(HttpStatus.UNAUTHORIZED,"ACCOUNT_UNAVAILABLE","账号已停用或不存在，请重新登录");
        byte[] hash=hash(skuId,quantity);
        PurchaseRequest existing=requests.byKey(userId,key);
        if(existing!=null) {
            requireSame(existing,hash);
            return view(existing);
        }
        requests.insert(userId,skuId,quantity,key,hash,properties.queueSeconds());
        long id=requests.insertedId();
        // 无SKU外键写锁，避免受理持预算后等待交易库存；用户外键在用户锁内写入。
        if(requests.acquire(properties.capacity())!=1) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"PURCHASE_QUEUE_FULL","购票排队人数已达上限，请稍后重试");
        commands.append(id);
        return view(requests.find(id));
    }

    @Transactional(readOnly=true)
    public PurchaseRequestView detail(long userId,long id) {
        PurchaseRequest request=requests.find(id);
        if(request==null || request.userId()!=userId) throw new BusinessException(HttpStatus.NOT_FOUND,"PURCHASE_REQUEST_NOT_FOUND","购票请求不存在");
        return view(request);
    }
    @Transactional(readOnly=true)
    public List<PurchaseRequestView> active(long userId) { return requests.active(userId).stream().map(this::view).toList(); }
    /** 同一可信用户的只读恢复入口，不改变请求、容量或Outbox，也不放行新的写请求。 */
    @Transactional(readOnly=true)
    public PurchaseRequestView lookup(long userId, String key) {
        PurchaseRequest existing=requests.byKey(userId,key);
        if(existing==null) throw new BusinessException(HttpStatus.NOT_FOUND,"PURCHASE_REQUEST_NOT_FOUND","购票请求不存在");
        return view(existing);
    }
    /** 安全过滤器已校验可信用户，按用户和操作域只读查重；事务内仍做最终复核。 */
    @Transactional(readOnly=true)
    public PurchaseRequestView replay(long userId,long skuId,int quantity,String key) {
        PurchaseRequest existing=requests.byKey(userId,key);
        if(existing==null) return null;
        requireSame(existing,hash(skuId,quantity));
        return view(existing);
    }
    public long skuId(String sku) {
        try {
            long id=Long.parseLong(sku);
            if(id<=0) throw new NumberFormatException();
            return id;
        } catch(NumberFormatException exception) { throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_SKU_ID","票档编号超出允许范围"); }
    }
    private void requireSame(PurchaseRequest existing,byte[] hash) {
        if(!MessageDigest.isEqual(existing.requestHash(),hash)) throw new BusinessException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","同一幂等键不能用于不同票档或数量");
    }
    private PurchaseRequestView view(PurchaseRequest request) { return PurchaseRequestView.from(request,requests.now().toInstant(ZoneOffset.UTC)); }
    private byte[] hash(long sku,int quantity) {
        try { return MessageDigest.getInstance("SHA-256").digest((sku+":"+quantity).getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException("运行环境缺少SHA-256",exception); }
    }
}
