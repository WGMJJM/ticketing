<template>
  <div class="about-page">
    <span class="eyebrow"><span></span> 从一份热爱，到一场相遇</span>
    <h1>好现场，<br /><span>我们一起期待。</span></h1>
    <p class="about-intro">
      票务交易平台面向中国大陆用户，为演唱会、音乐节、舞台演出、展览和电竞赛事逐步建立清晰、可信赖的购票体验。
    </p>
    <div class="about-grid">
      <section class="about-card">
        <span class="section-kicker">当前阶段</span>
        <h2>购票、模拟支付与候补已就绪</h2>
        <p>
          目前支持用户注册登录、活动与票档展示、真实订单提交、模拟支付、超时关闭、主动取消与候补申请。候补分配成功后可通过对应订单支付，管理员可管理活动、场次和票档库存。
        </p>
        <p class="about-note">
          标记为“演示”的内容为虚构示例。订单会实际预占库存，模拟支付不会产生实际扣款，也不提供实际入场凭证。
        </p>
      </section>
      <section class="about-card">
        <span class="section-kicker">接下来</span>
        <h2>让热爱逐步抵达</h2>
        <ol>
          <li>电子票与入场核销</li>
        </ol>
      </section>
    </div>
    <section class="architecture-note">
      <h2>关于平台架构</h2>
      <p>
        前端采用 Vue 3 与 TypeScript，后端采用 Java 21 与 Spring Boot。MySQL
        保存交易与库存事实，RabbitMQ 配合事务事件触发候补调度，数据库扫描提供补偿。候补按同票档队列顺序处理，不保证分配成功。电子票及核销将在后续阶段实现。
      </p>
    </section>
    <RouterLink class="primary-link" to="/"
      >返回发现活动 <span aria-hidden="true">↗</span></RouterLink
    >
  </div>
</template>
