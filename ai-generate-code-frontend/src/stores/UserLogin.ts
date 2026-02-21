import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { type LoginUserVO, userGetLoginUsingGet } from '@/api'

//UserLoginStore 的本质：是 defineStore() 返回的一个普通函数（工厂函数）
/**
 * 用户登录信息存储
 */
export const UserLoginStore =
  defineStore('UserLogin', () => {


    const userLogin = ref<LoginUserVO>({
      userName: '未登录',
    })

    /**
     * 可以把它理解成一个 “承诺”：异步操作一开始，就创建一个 Promise 对
     * 它承诺会在未来某个时间告诉你操作是成功了还是失败了，并把结果交给你
     */
    //获取登录用户的信息
    async function fetchLoginUser(): Promise<void> {
      const res = await userGetLoginUsingGet({})
      if ((res.data.code === 0 && res.data.data) || (res.data.code === 20000 && res.data.data)) {
        userLogin.value = res.data.data
      }
    }

    //直接设置登录用户的信息
    function setLoginUser(loginUser: any): void {
      userLogin.value = loginUser
    }

    return { userLogin, fetchLoginUser, setLoginUser }
  })
