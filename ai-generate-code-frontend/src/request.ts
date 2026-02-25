import axios from 'axios'
import { message } from 'ant-design-vue'

/**
 * 统一处理后端返回的 Long（超过 JS 安全整数范围的 number），
 * 自动转成字符串，避免精度丢失。
 */
const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER

function transformLongToString(value: unknown): unknown {
  if (value === null || value === undefined) {
    return value
  }

  if (Array.isArray(value)) {
    return value.map((item) => transformLongToString(item))
  }

  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>
    Object.keys(obj).forEach((key) => {
      const v = obj[key]
      if (typeof v === 'number' && !Number.isSafeInteger(v) && Math.abs(v) > MAX_SAFE_INTEGER) {
        // 超出安全整数范围的 number 统一转为字符串
        obj[key] = String(v)
      } else if (typeof v === 'object' && v !== null) {
        obj[key] = transformLongToString(v)
      }
    })
    return obj
  }

  return value
}

// 创建 Axios 实例
const myAxios = axios.create({
  baseURL: 'http://localhost:8124/api',
  timeout: 600000, // 10分钟
  withCredentials: true,
})

// 全局请求拦截器
myAxios.interceptors.request.use(
  function (config) {
    // Do something before request is sent
    return config
  },
  function (error) {
    // Do something with request error
    return Promise.reject(error)
  },
)

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    const { data } = response

    // 先做 Long -> string 的统一转换
    if (data && typeof data === 'object') {
      transformLongToString(data)
    }

    // 未登录
    if (data.code === 40100) {
      // 不是获取用户信息的请求，并且用户目前不是已经在用户登录页面，则跳转到登录页面
      if (
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('请先登录')
        window.location.href = `/user/login?redirect=${window.location.href}`
      }
    }
    return response
  },
  function (error) {
    // Any status codes that falls outside the range of 2xx cause this function to trigger
    // Do something with response error
    return Promise.reject(error)
  },
)

export default myAxios
