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
        <h2>活动与场次已就绪</h2>
        <p>
          目前已建立前后端工程、活动列表筛选、活动详情、场次选择，以及活动和场次的运营管理。
        </p>
        <p class="about-note">
          当前只展示活动与场次。标记为“演示”的内容为虚构示例，尚未开放用户登录、下单或支付。
        </p>
      </section>
      <section class="about-card">
        <span class="section-kicker">接下来</span>
        <h2>让热爱逐步抵达</h2>
        <ol>
          <li>票档与开售状态查询</li>
          <li>用户注册登录与权限控制</li>
          <li>下单锁票、模拟支付与超时释放</li>
          <li>候补排队、电子票与入场核销</li>
        </ol>
      </section>
    </div>
    <section class="architecture-note">
      <h2>关于平台架构</h2>
      <p>
        前端采用 Vue 3 与 TypeScript，后端采用 Java 21 与 Spring Boot。MySQL
        保存交易与库存事实，Redis 和 RabbitMQ
        为后续缓存与可靠事件处理提供基础。所有票务业务将在后续阶段实现并逐项验证。
      </p>
    </section>
    <RouterLink class="primary-link" to="/"
      >返回发现活动 <span aria-hidden="true">↗</span></RouterLink
    >
  </div>
</template>
