import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import { useAdminStore } from '../stores/admin'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/about', component: () => import('../views/AboutView.vue') },
    {
      path: '/activities/:id',
      component: () => import('../views/ActivityDetailView.vue'),
    },
    {
      path: '/admin/login',
      component: () => import('../views/AdminLoginView.vue'),
    },
    {
      path: '/admin/activities',
      component: () => import('../views/AdminActivitiesView.vue'),
      meta: { requiresAdmin: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  if (to.meta.requiresAdmin && !useAdminStore().authenticated)
    return { path: '/admin/login' }
})
export default router
