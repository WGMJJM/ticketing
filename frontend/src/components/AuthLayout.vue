<template>
  <div class="auth-layout">
    <aside class="auth-aside" aria-label="平台欢迎语">
      <span class="auth-aside-kicker">每一份热爱，都值得在场</span>
      <h2>下一场精彩，<br />与你有关。</h2>
      <p>从期待到抵达，<br />把喜欢变成一段现场回忆。</p>
      <div class="auth-art-ticket" aria-hidden="true">
        <span>下一站 · 现场</span>
        <strong>为热爱<br />留个位置</strong>
        <span>期待与你相遇 <i>✦</i></span>
      </div>
      <span class="auth-aside-note">演唱会 / 音乐节 / 舞台演出 / 展览赛事</span>
    </aside>
    <div class="admin-login"><slot /></div>
  </div>
</template>
