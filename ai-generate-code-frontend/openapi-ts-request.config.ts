import { defineConfig } from 'openapi-ts-request'

// 根据后端文档自动生成前端接口代码, 生成到 src/api 目录下
export default defineConfig({
    schemaPath: 'http://localhost:8124/api/v3/api-docs', // 你的后端 swagger 地址
    serversPath: './src/api',                             // 生成到 src/api
    requestLibPath: "@/request",                          // 使用你封装好的 request.ts
})