/* eslint-disable */
// @ts-ignore
import request from '@/request';

import * as API from './types';

/** 此处后端没有提供注释 GET /chat/gen/code */
export function chatGenCodeUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatGenCodeUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  return request<API.ServerSentEventString[]>('/chat/gen/code', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
