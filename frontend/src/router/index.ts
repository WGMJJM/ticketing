import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import { useAuthStore } from '../stores/auth'

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
      component: () => import('../views/LoginView.vue'),
      props: { adminOnly: true },
    },
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/register', component: () => import('../views/RegisterView.vue') },
    {
      path: '/orders',
      component: () => import('../views/OrdersView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/orders/:id',
      component: () => import('../views/OrdersView.vue'),
      props: true,
      meta: { requiresAuth: true },
    },
    {
      path: '/profile',
      component: () => import('../views/ProfileView.vue'),
      meta: { requiresAuth: true },
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

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.restore()
  if ((to.meta.requiresAuth || to.meta.requiresAdmin) && !auth.authenticated)
    return {
      path: to.meta.requiresAdmin ? '/admin/login' : '/login',
      query: { redirect: to.path },
    }
  if (
    (to.meta.requiresAdmin || to.path === '/admin/login') &&
    auth.authenticated &&
    !auth.isAdmin
  )
    return { path: '/profile', query: { denied: '1' } }
})
export default router
