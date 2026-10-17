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
        <h2>从购票到电子票核销</h2>
        <p>
          目前支持活动与票档展示、购票订单、模拟支付、超时关闭及候补。支付成功后自动生成独立电子票，用户可查看二维码，管理员可核销入场。
        </p>
        <p class="about-note">
          标记为“演示”的活动为虚构示例。订单会实际预占库存，电子票及核销记录真实保存；模拟支付不产生实际扣款，演示票不对应真实演出。
        </p>
      </section>
      <section class="about-card">
        <span class="section-kicker">入场提示</span>
        <h2>一张电子票，一次入场</h2>
        <ol>
          <li>按场次出示独立二维码，已核销或已作废的票券不能再次入场。</li>
        </ol>
      </section>
    </div>
    <section class="architecture-note">
      <h2>关于平台架构</h2>
      <p>
        前端采用 Vue 3 与 TypeScript，后端采用 Java 21 与 Spring Boot。MySQL
        保存交易、库存与电子票事实，RabbitMQ
        配合事务事件触发候补调度，数据库扫描提供补偿。电子票使用服务端签名凭证，核销由数据库条件更新控制。
      </p>
    </section>
    <RouterLink class="primary-link" to="/"
      >返回发现活动 <span aria-hidden="true">↗</span></RouterLink
    >
  </div>
</template>
